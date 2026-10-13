"""Parse an Android btsnoop_hci.log capture down to RSMC frames.

Walks HCI ACL data packets -> L2CAP reassembly -> RFCOMM (TS 07.10 / Bluetooth
RFCOMM, including the credit-based-flow-control octet documented in README
section 7d) -> raw SPP byte stream per direction, then feeds that stream
through rsmc.py's Decoder same as any live capture, plus greps for the
"IAP x ..." session-bracket markers.

    adb pull /sdcard/btsnoop_hci.log .
    python parse_snoop.py btsnoop_hci.log

No Wireshark/tshark needed -- the btsnoop format is simple enough to read
directly (see the format notes inline below).
"""

import argparse
import struct
import sys

sys.path.insert(0, ".")

from bsp import split_address, unpack_header  # noqa: E402
from rsmc import Decoder  # noqa: E402

IAP_MARKERS = [b"IAP x OK", b"IAP x START", b"IAP x STOP"]

# --- btsnoop file format -----------------------------------------------
# Header: "btsnoop\0" (8) + version u32BE (4) + datalink_type u32BE (4)
# Record: orig_len u32BE, incl_len u32BE, flags u32BE, drops u32BE,
#         timestamp_usecs s64BE (usecs since 0000-01-01, offset by the
#         standard btsnoop epoch -- not needed for relative ordering),
#         then incl_len bytes of packet data.
# Android's datalink type is 1002 (HCI UART / H4): the packet data's first
# byte is the H4 type indicator (0x01 cmd, 0x02 ACL, 0x03 SCO, 0x04 event).
# flags bit 0: 0 = sent (host->controller), 1 = received (controller->host).

BTSNOOP_MAGIC = b"btsnoop\x00"


def read_records(path):
    with open(path, "rb") as fh:
        magic = fh.read(8)
        if magic != BTSNOOP_MAGIC:
            raise ValueError("not a btsnoop file (bad magic)")
        version, datalink = struct.unpack(">II", fh.read(8))
        while True:
            hdr = fh.read(24)
            if len(hdr) < 24:
                return
            orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIq", hdr)
            data = fh.read(incl_len)
            direction = "RX" if (flags & 1) else "TX"  # host's perspective
            yield direction, ts, data


def hexdump(data, indent="    "):
    out = []
    for off in range(0, len(data), 16):
        chunk = data[off:off + 16]
        hexpart = " ".join("%02x" % b for b in chunk)
        text = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        out.append("%s%04x  %-47s  %s" % (indent, off, hexpart, text))
    return "\n".join(out)


class L2CAPReassembler:
    """Reassembles fragmented ACL data into complete L2CAP PDUs, per direction."""

    def __init__(self):
        self.partial = None  # (cid, remaining_bytes_needed, buf)

    def feed_acl(self, handle_flags, payload):
        pb = (handle_flags >> 12) & 0x3
        if pb in (0, 2):  # start of a new L2CAP PDU
            if len(payload) < 4:
                self.partial = None
                return None
            l2len, cid = struct.unpack_from("<HH", payload, 0)
            body = payload[4:]
            if len(body) >= l2len:
                self.partial = None
                return cid, body[:l2len]
            self.partial = (cid, l2len, bytearray(body))
            return None
        else:  # continuation
            if self.partial is None:
                return None
            cid, l2len, buf = self.partial
            buf += payload
            if len(buf) >= l2len:
                self.partial = None
                return cid, bytes(buf[:l2len])
            self.partial = (cid, l2len, buf)
            return None


def rfcomm_frames(data):
    """Split a byte stream into RFCOMM frames: (dlci, control, credit_or_None, info)."""
    i = 0
    n = len(data)
    out = []
    while i < n:
        if i + 2 > n:
            break
        addr = data[i]
        control = data[i + 1]
        if not (addr & 0x01):  # EA bit must be 1 (single-octet address)
            break
        dlci = (addr >> 2) & 0x3F
        j = i + 2
        if j >= n:
            break
        len_byte = data[j]
        if len_byte & 0x01:  # single-octet length
            length = len_byte >> 1
            j += 1
        else:
            if j + 1 >= n:
                break
            length = (len_byte >> 1) | (data[j + 1] << 7)
            j += 2
        credit = None
        if control == 0xFF:  # UIH with P/F set -> credit octet present
            if j >= n:
                break
            credit = data[j]
            j += 1
        if j + length + 1 > n:  # +1 for FCS
            break
        info = data[j:j + length]
        fcs = data[j + length]
        out.append((dlci, control, credit, info))
        i = j + length + 1
    return out, data[i:]  # also return unconsumed tail (debugging aid)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("logfile")
    ap.add_argument("--dlci", type=int, default=None,
                     help="only show this DLCI's data channel (default: auto, skip DLCI 0)")
    ap.add_argument("--raw", action="store_true", help="hexdump every RFCOMM info field")
    args = ap.parse_args()

    reassemblers = {}  # (direction) -> L2CAPReassembler, keyed loosely since handle is stable per session
    streams = {}  # (direction, dlci) -> bytearray, accumulated SPP byte stream
    decoders = {}  # (direction, dlci) -> Decoder

    n_acl = 0
    n_l2cap = 0
    n_rfcomm = 0

    for direction, ts, data in read_records(args.logfile):
        if not data:
            continue
        h4_type = data[0]
        if h4_type != 0x02:  # only ACL data carries RFCOMM/SPP bytes
            continue
        if len(data) < 5:
            continue  # truncated record (e.g. capture cut off mid-write)
        n_acl += 1
        handle_flags, dlen = struct.unpack_from("<HH", data, 1)
        payload = data[5:5 + dlen]
        reasm = reassemblers.setdefault(direction, L2CAPReassembler())
        result = reasm.feed_acl(handle_flags, payload)
        if result is None:
            continue
        cid, l2payload = result
        n_l2cap += 1
        if cid == 1:  # L2CAP signaling channel, not RFCOMM data -- skip
            continue
        frames, tail = rfcomm_frames(l2payload)
        if not frames:
            continue
        n_rfcomm += len(frames)
        for dlci, control, credit, info in frames:
            key = (direction, dlci)
            if args.raw:
                print("[%s] DLCI=%d control=%#04x credit=%s info(%d)=\n%s"
                      % (direction, dlci, control,
                         credit if credit is not None else "-", len(info), hexdump(info)))
            if args.dlci is not None and dlci != args.dlci:
                continue
            if dlci == 0:
                continue  # RFCOMM mux control channel (PN/MSC/...), not app data
            streams.setdefault(key, bytearray())
            streams[key] += info
            for marker in IAP_MARKERS:
                if info == marker:
                    print("[%s] DLCI=%d IAP marker: %s" % (direction, dlci, marker.decode()))
            dec = decoders.setdefault(key, Decoder())
            for f in dec.feed(bytes(info)):
                print("[%s] DLCI=%d RSMC FRAME seq=%d ack=%d nak=%d payload=%d bytes"
                      % (direction, dlci, f.seq, f.ack, f.nak, len(f.payload)))
                if len(f.payload) >= 8:
                    h = unpack_header(f.payload)
                    dnode, dport = split_address(h["dst"])
                    snode, sport = split_address(h["src"])
                    print("    transport: type=%d flags=%d dst=%04x(node %d port %#x) "
                          "src=%04x(node %d port %#x) aux=%04x"
                          % (h["type"], h["flags"], h["dst"], dnode, dport,
                             h["src"], snode, sport, h["aux"]))
                    body = f.payload[8:]
                    if body:
                        print("    body (%d bytes) raw: %s" % (len(body), body.hex()))
                    if len(body) >= 8:
                        cmd_id, arg_len = struct.unpack_from("<II", body, 0)
                        arg_data = body[8:8 + arg_len]
                        print("    command: cmdID=%d (%#x) argLen=%d argData=%s"
                              % (cmd_id, cmd_id, arg_len, arg_data.hex()))

    print("\n%d ACL packets, %d complete L2CAP PDUs, %d RFCOMM frames parsed."
          % (n_acl, n_l2cap, n_rfcomm))
    for (direction, dlci), buf in streams.items():
        print("stream %s DLCI=%d: %d bytes total" % (direction, dlci, len(buf)))


if __name__ == "__main__":
    main()
