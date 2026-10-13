"""RSMC (reliable serial message channel) frame codec for Line 6 AMPLIFi amps.

RSMC is the framing layer that rides on the amp's Bluetooth serial (RFCOMM) link.

Frame layout -- 12-byte header, little-endian, no byte stuffing:

    off  size  field
    0    2     0x5555        sync ("U" "U"); a receiver hunts for it byte by byte
    2    1     zero          constant 0x00
    3    1     seq           sender's sequence number
    4    1     ack           last in-sequence frame the sender received
    5    1     nak           receive-window edge; always equals ack + 8 in practice
    6    2     length        payload byte count, u16
    8    2     payload_crc   CRC-16/CCITT-FALSE over the payload
    10   2     header_crc    CRC-16/CCITT-FALSE over bytes 0..11 with this field zeroed
    12   ...   payload       `length` bytes

Both CRCs use init 0xFFFF, polynomial 0x1021, MSB-first.

Sequencing rules (getting these wrong makes a perfectly healthy amp look dead):

  * seq advances only when a PAYLOAD frame is sent. Heartbeats / pure acks are
    zero-length frames that reuse the last seq.
  * A receiver delivers a payload frame only if seq == last_delivered + 1.
    Anything else is acknowledged at the link level but silently dropped.
  * A zero-length frame is a valid header-only ack, and its payload_crc field is
    NOT meaningful (the amp sends 0x0000 there, not crc16(b"")), so it must not
    be validated.
"""

import struct

HEADER_LEN = 12
SYNC = 0xFFFF & 0x5555
CRC_INIT = 0xFFFF


def crc16(data, crc=CRC_INIT):
    """CRC-16/CCITT-FALSE (init 0xFFFF, poly 0x1021, MSB-first), bit-serial form."""
    for b in data:
        x = (b ^ (crc >> 8)) & 0xFFFF
        t = (x ^ (x >> 4)) & 0xFFFF
        crc = (((crc << 8) | t) ^ (t << 12) ^ (t << 5)) & 0xFFFF
    return crc


def build_frame(seq, ack, payload=b"", nak=0):
    """Serialise one RSMC frame."""
    header = bytearray(HEADER_LEN)
    struct.pack_into("<H", header, 0, SYNC)
    header[2] = 0
    header[3] = seq & 0xFF
    header[4] = ack & 0xFF
    header[5] = nak & 0xFF
    struct.pack_into("<H", header, 6, len(payload))
    struct.pack_into("<H", header, 8, crc16(payload))
    # header_crc is computed with its own field still zero, then written in place
    struct.pack_into("<H", header, 10, crc16(header))
    return bytes(header) + bytes(payload)


class Frame:
    __slots__ = ("seq", "ack", "nak", "payload")

    def __init__(self, seq, ack, nak, payload):
        self.seq, self.ack, self.nak, self.payload = seq, ack, nak, payload

    def __repr__(self):
        return "Frame(seq=%d, ack=%d, nak=%d, payload=%d bytes)" % (
            self.seq, self.ack, self.nak, len(self.payload))


class Decoder:
    """Incremental frame decoder.

    Feed it arbitrary chunks from the RFCOMM stream; it yields complete frames.
    States match the original: 0 hunting for sync, 1 collecting the header,
    2 collecting the payload.
    """

    STATE_SYNC, STATE_HEADER, STATE_PAYLOAD = 0, 1, 2

    def __init__(self, max_payload=None):
        self.max_payload = max_payload
        self.bad_frames = 0
        self._window = bytearray(2)
        self._reset()

    def _reset(self):
        self.state = self.STATE_SYNC
        self._header = bytearray()
        self._payload = bytearray()
        self._need = 0
        # The sliding sync window is cleared after every frame, good or bad, so a
        # payload ending in 0x55 cannot pair with the next frame's leading 0x55.
        self._window[0] = 0
        self._window[1] = 0

    def feed(self, data):
        out = []
        i, n = 0, len(data)
        while i < n:
            if self.state == self.STATE_SYNC:
                # sliding 2-byte window hunting for 0x55 0x55
                self._window[0] = self._window[1]
                self._window[1] = data[i]
                i += 1
                if self._window[0] == 0x55 and self._window[1] == 0x55:
                    self.state = self.STATE_HEADER
                    self._header = bytearray(b"\x55\x55")
                    self._need = HEADER_LEN - 2
            elif self.state == self.STATE_HEADER:
                take = min(self._need, n - i)
                self._header += data[i:i + take]
                i += take
                self._need -= take
                if self._need == 0:
                    out.extend(self._header_complete())
            else:
                take = min(self._need, n - i)
                self._payload += data[i:i + take]
                i += take
                self._need -= take
                if self._need == 0:
                    f = self._payload_complete()
                    if f is not None:
                        out.append(f)
        return out

    def _header_complete(self):
        hdr = self._header
        got = struct.unpack_from("<H", hdr, 10)[0]
        check = bytearray(hdr)
        struct.pack_into("<H", check, 10, 0)
        if crc16(bytes(check)) != got:
            self.bad_frames += 1
            self._reset()
            return []
        length = struct.unpack_from("<H", hdr, 6)[0]
        if self.max_payload is not None and length > self.max_payload:
            self.bad_frames += 1
            self._reset()
            return []
        if length == 0:
            frame = Frame(hdr[3], hdr[4], hdr[5], b"")
            self._reset()
            return [frame]
        self.state = self.STATE_PAYLOAD
        self._payload = bytearray()
        self._need = length
        return []

    def _payload_complete(self):
        hdr = self._header
        want = struct.unpack_from("<H", hdr, 8)[0]
        if crc16(bytes(self._payload)) != want:
            self.bad_frames += 1
            self._reset()
            return None
        frame = Frame(hdr[3], hdr[4], hdr[5], bytes(self._payload))
        self._reset()
        return frame
