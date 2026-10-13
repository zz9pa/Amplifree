"""BSP transport header codec: the 8-byte header carried at the start of every
message inside an RSMC frame payload.

Wire layout (two little-endian 32-bit words):

    word0  bits 0..15   dst      16-bit field. For stream data this is the
                                 segment's payload length + 8 (+12 for the
                                 connect segment); see msgstream notes.
           bits 16..25  unused   always zero
           bits 26..27  flags    always 2
           bits 28..31  type     1 = stream data, 2 = stream connect
    word1  bits 0..15   src      sender's port (0x2002 / 0x2003 from the app)
           bits 16..31  aux      service the message belongs to
                                 (0x03ED = editor, 0x03EF = librarian when sent
                                 by the app; the amp's replies carry the app's
                                 src port here instead)

A 16-bit address splits into (node << 8) | port. Only flags == 2 has ever been
observed; `aux` is carried faithfully rather than interpreted by this module.
"""

import struct

HEADER_LEN = 8
FLAGS_DEFAULT = 2


def make_address(node, port):
    """Compose a 16-bit BSP address: (node << 8) | port."""
    return ((node & 0xFF) << 8) | (port & 0xFF)


def split_address(addr):
    """Inverse of make_address: returns (node, port)."""
    return (addr >> 8) & 0xFF, addr & 0xFF


def pack_header(type_, dst, src, aux=0, flags=FLAGS_DEFAULT):
    """Bit-pack a transport header into its 8-byte wire form."""
    if not 0 <= type_ <= 0xF:
        raise ValueError("type must fit in 4 bits, got %r" % (type_,))
    word0 = ((type_ & 0xF) << 28) | ((flags & 0x3) << 26) | (dst & 0xFFFF)
    word1 = ((aux & 0xFFFF) << 16) | (src & 0xFFFF)
    return struct.pack("<II", word0, word1)


def unpack_header(data):
    """Recover the fields from an 8-byte wire header."""
    if len(data) < HEADER_LEN:
        raise ValueError("need %d bytes, got %d" % (HEADER_LEN, len(data)))
    word0, word1 = struct.unpack_from("<II", data, 0)
    return {
        "type": (word0 >> 28) & 0xF,
        "flags": (word0 >> 26) & 0x3,
        "dst": word0 & 0xFFFF,
        "src": word1 & 0xFFFF,
        "aux": (word1 >> 16) & 0xFFFF,
    }
