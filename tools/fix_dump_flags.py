#!/usr/bin/env python3
"""Marks a dump as decrypted when its DATA is already plain but its flags still say "encrypted".

Some decryption tools leave the NCCH crypto-method byte set (and, for .cia, the TMD's
"encrypted" bit) after writing plaintext. Azahar copes, but 3dsrecomp, Zakuro and MH4U's
extract_ncch.py trust the flags and refuse the file ("the dump is encrypted").

The check is on the data, like extract_ncch.py's: the ExeFS header of really encrypted content
is scrambled, a plain one starts with a file name such as ".code". Only then are the flag bytes
set (NCCH flags[7] |= 0x04 "NoCrypto"; CIA content 0 type bit 0 cleared). No keys are touched,
nothing is decrypted, and a genuinely encrypted dump is reported and left alone.

usage: fix_dump_flags.py <game.3ds|.cia|.cxi> [...]      (edits the files in place)
"""
import struct
import sys

MU = 0x200


def align(n):
    return (n + 0x3F) & ~0x3F


def locate(f):
    """-> (ncch_offset, cia_flag_offset or None)"""
    f.seek(0)
    head = f.read(0x200)
    if head[0x100:0x104] == b"NCSD":
        return struct.unpack_from("<I", head, 0x120)[0] * MU, None
    if head[0x100:0x104] == b"NCCH":
        return 0, None
    if struct.unpack_from("<I", head, 0)[0] != 0x2020:
        raise ValueError("not a 3DS cartridge image, .cia or executable")
    cert, ticket, tmd_size = struct.unpack_from("<III", head, 8)
    f.seek(0x20)
    bitmap = f.read(0x2000)
    tmd_off = align(align(align(0x2020) + cert) + ticket)
    contents = align(tmd_off + tmd_size)
    f.seek(tmd_off)
    tmd = f.read(tmd_size)
    sig = {0: 0x240, 3: 0x240, 1: 0x140, 4: 0x140, 2: 0x80, 5: 0x80}[struct.unpack_from(">I", tmd, 0)[0] & 0xFFFF]
    count = struct.unpack_from(">H", tmd, sig + 0x9E)[0]
    off = contents
    for i in range(count):
        rec = sig + 0xC4 + 64 * 0x24 + i * 0x30
        index = struct.unpack_from(">H", tmd, rec + 4)[0]
        if not bitmap[index // 8] & (0x80 >> (index % 8)):
            continue
        if index == 0:
            return off, tmd_off + rec + 6
        off = align(off + struct.unpack_from(">Q", tmd, rec + 8)[0])
    raise ValueError("the .cia has no executable content")


def fix(path):
    with open(path, "r+b") as f:
        ncch, cia_type_at = locate(f)
        f.seek(ncch)
        hdr = f.read(0x200)
        if hdr[0x100:0x104] != b"NCCH":
            return f"{path}: no NCCH header at 0x{ncch:X} (content really is encrypted, or not a game/update)"
        flags = bytearray(hdr[0x188:0x190])
        exefs_off, exefs_size = struct.unpack_from("<II", hdr, 0x1A0)
        f.seek(ncch + exefs_off * MU)
        names = [f.read(16)[:8] for _ in range(10)] if exefs_size else []
        plain = any(n.startswith(b".code") for n in names)
        if not plain:
            return f"{path}: ExeFS header is not plain: the dump really is encrypted, decrypt it first"
        changed = []
        if not flags[7] & 0x04:
            flags[7] |= 0x04
            f.seek(ncch + 0x188)
            f.write(bytes(flags))
            changed.append(f"NCCH flags (crypto method 0x{flags[3]:02X}, NoCrypto now set)")
        if cia_type_at is not None:
            f.seek(cia_type_at)
            t = struct.unpack(">H", f.read(2))[0]
            if t & 1:
                f.seek(cia_type_at)
                f.write(struct.pack(">H", t & ~1))
                changed.append("CIA content 0 'encrypted' bit")
        return f"{path}: data is plain; " + (("fixed " + ", ".join(changed)) if changed else "flags were already right")


if __name__ == "__main__":
    bad = 0
    for p in sys.argv[1:]:
        try:
            msg = fix(p)
        except (OSError, ValueError, KeyError, struct.error) as e:
            msg = f"{p}: {e}"
        print(msg)
        bad += "really is encrypted" in msg or "is not plain" in msg
    sys.exit(1 if bad else 0)
