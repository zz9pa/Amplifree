"""Tests for the BSP transport header codec.

The strongest check is `reference_pack`, an independent implementation of the
documented bit layout that works on a plain 8-byte struct. Agreement between it
and bsp.pack_header over every type/flags combination guards the bit-packing.
"""

import struct

from bsp import (HEADER_LEN, make_address, pack_header, split_address,
                 unpack_header)

results = []


def check(name, cond):
    results.append((name, bool(cond)))
    print(("  PASS  " if cond else "  FAIL  ") + name)


def reference_pack(struct8):
    b = struct8
    eax = b[0] << 28
    ecx = struct.unpack_from("<H", b, 2)[0]
    eax = (eax | ecx) & 0xFFFFFFFF
    ecx = (b[1] & 3) << 26
    word0 = (eax | ecx) & 0xFFFFFFFF
    eax = struct.unpack_from("<H", b, 6)[0] << 16
    edx = struct.unpack_from("<H", b, 4)[0]
    word1 = (eax | edx) & 0xFFFFFFFF
    return struct.pack("<II", word0, word1)


# --- address composition: (node << 8) | port ------------------------------
check("make_address(0x12, 0x34) == 0x1234", make_address(0x12, 0x34) == 0x1234)
check("node shifts into the high byte", make_address(0xAB, 0x00) == 0xAB00)
check("split_address round-trips", split_address(make_address(7, 9)) == (7, 9))

# --- agreement with the independent reference implementation ---------------
mismatch = None
for type_ in range(16):
    for flags in range(4):
        for dst, src, aux in ((0, 0, 0), (0xFFFF, 0xFFFF, 0xFFFF),
                              (0x1234, 0x5678, 0x9ABC), (0xDE01, 0x02FE, 0x00FF)):
            s = bytearray(8)
            s[0] = type_
            s[1] = flags
            struct.pack_into("<H", s, 2, dst)
            struct.pack_into("<H", s, 4, src)
            struct.pack_into("<H", s, 6, aux)
            if pack_header(type_, dst, src, aux, flags) != reference_pack(bytes(s)):
                mismatch = (type_, flags, dst, src, aux)
                break
check("matches the reference implementation over all types and flags", mismatch is None)

# --- structure ------------------------------------------------------------
h = pack_header(type_=3, dst=make_address(1, 0x20), src=make_address(2, 0x31), aux=0xBEEF)
check("header is 8 bytes", len(h) == HEADER_LEN)
check("round-trips all five fields",
      unpack_header(h) == {"type": 3, "flags": 2, "dst": 0x0120,
                           "src": 0x0231, "aux": 0xBEEF})

w0 = struct.unpack_from("<I", pack_header(0xF, 0xFFFF, 0xFFFF, 0xFFFF, 3), 0)[0]
check("bits 16..25 of word0 stay zero at full field values",
      (w0 >> 16) & 0x3FF == 0)
check("flags defaults to the observed constant 0x02",
      unpack_header(pack_header(1, 0, 0))["flags"] == 2)

try:
    pack_header(0x10, 0, 0)
    check("type above 4 bits raises", False)
except ValueError:
    check("type above 4 bits raises", True)

try:
    unpack_header(b"\x00" * 7)
    check("short buffer raises", False)
except ValueError:
    check("short buffer raises", True)

failed = [n for n, ok in results if not ok]
print("\n%d/%d checks passed" % (len(results) - len(failed), len(results)))
if failed:
    print("FAILED: " + "; ".join(failed))
    raise SystemExit(1)
print("ALL CHECKS PASSED")
