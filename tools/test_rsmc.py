"""Tests for the RSMC frame codec: header layout, CRCs, sync hunting, resync and
zero-length (header-only) frames.
"""

import random
import struct

from rsmc import HEADER_LEN, Decoder, build_frame, crc16

results = []


def check(name, cond):
    results.append((name, bool(cond)))
    print(("  PASS  " if cond else "  FAIL  ") + name)


# --- checksum -------------------------------------------------------------
check("crc16('123456789') == 0x29B1 (CCITT-FALSE, init 0xFFFF)",
      crc16(b"123456789") == 0x29B1)

# --- header shape --------------------------------------------------------
f = build_frame(seq=7, ack=3, payload=b"hello", nak=1)
check("sync bytes are 0x55 0x55", f[0] == 0x55 and f[1] == 0x55)
check("frame[2] is a constant zero", f[2] == 0)
check("seq/ack/nak occupy bytes 3/4/5", (f[3], f[4], f[5]) == (7, 3, 1))
check("length is u16 LE at offset 6", struct.unpack_from("<H", f, 6)[0] == 5)
check("total size is 12 + payload", len(f) == HEADER_LEN + 5)

chk = bytearray(f[:HEADER_LEN])
struct.pack_into("<H", chk, 10, 0)
check("header CRC covers all 12 bytes with its own field zeroed",
      crc16(bytes(chk)) == struct.unpack_from("<H", f, 10)[0])

# --- round trip -----------------------------------------------------------
got = Decoder(max_payload=1024).feed(f)
check("round-trips to a single frame",
      len(got) == 1 and got[0].payload == b"hello"
      and (got[0].seq, got[0].ack, got[0].nak) == (7, 3, 1))

got = Decoder().feed(build_frame(9, 9))
check("zero-length header-only ack decodes", len(got) == 1 and got[0].payload == b"")

# --- stream fragmentation -------------------------------------------------
d, acc = Decoder(), []
for byte in build_frame(1, 2, b"fragmented payload"):
    acc.extend(d.feed(bytes([byte])))
check("survives one-byte-at-a-time delivery",
      len(acc) == 1 and acc[0].payload == b"fragmented payload")

stream = b"\x00\xffnoise" + b"".join(
    build_frame(i, 0, bytes([i]) * (i + 1)) for i in range(5))
got = Decoder().feed(stream)
check("skips leading garbage and decodes 5 back-to-back frames",
      len(got) == 5 and [g.seq for g in got] == list(range(5)))

# --- transparency: no byte stuffing exists in the protocol ---------------
got = Decoder().feed(build_frame(4, 4, b"\x55\x55\x55\x55rest"))
check("payload containing 0x55 0x55 stays transparent",
      len(got) == 1 and got[0].payload == b"\x55\x55\x55\x55rest")

# A stray 0x55 immediately before a real sync shifts the window by one byte and
# costs that frame -- the amp's own receiver behaves identically, so this is a
# faithful property rather than a defect. Recovery must still happen.
d = Decoder()
got = d.feed(b"\x55" + build_frame(1, 0, b"lost") + build_frame(2, 0, b"kept"))
check("stray 0x55 before sync costs one frame but recovers",
      [g.payload for g in got] == [b"kept"] and d.bad_frames >= 1)

# --- rejection ------------------------------------------------------------
bad = bytearray(build_frame(1, 1, b"abcdefgh"))
bad[HEADER_LEN + 2] ^= 0xFF
d = Decoder()
check("corrupt payload rejected by payload CRC",
      d.feed(bytes(bad)) == [] and d.bad_frames == 1)

bad_hdr = bytearray(build_frame(1, 1, b"abcd"))
bad_hdr[4] ^= 0xFF
d = Decoder()
check("corrupt header rejected by header CRC",
      d.feed(bytes(bad_hdr)) == [] and d.bad_frames == 1)

h = bytearray(build_frame(1, 1, b""))
struct.pack_into("<H", h, 6, 9999)
struct.pack_into("<H", h, 10, 0)
struct.pack_into("<H", h, 10, crc16(bytes(h)))
d = Decoder(max_payload=256)
check("oversize length rejected (mirrors cmp against chan+0x4c)",
      d.feed(bytes(h)) == [] and d.bad_frames == 1)

got = Decoder().feed(bytes(bad) + build_frame(42, 7, b"after recovery"))
check("resynchronises after a corrupt frame",
      len(got) == 1 and got[0].seq == 42 and got[0].payload == b"after recovery")

# --- fuzz -----------------------------------------------------------------
random.seed(1)
frames = [build_frame(i & 0xFF, 0,
                      bytes(random.getrandbits(8) for _ in range(random.randint(0, 200))))
          for i in range(60)]
stream = b"".join(frames)
d, got, pos = Decoder(), [], 0
while pos < len(stream):
    step = random.randint(1, 77)
    got.extend(d.feed(stream[pos:pos + step]))
    pos += step
check("fuzz: 60 frames across random chunk boundaries",
      len(got) == 60 and d.bad_frames == 0)

failed = [n for n, ok in results if not ok]
print("\n%d/%d checks passed" % (len(results) - len(failed), len(results)))
if failed:
    print("FAILED: " + "; ".join(failed))
    raise SystemExit(1)
print("ALL CHECKS PASSED")
