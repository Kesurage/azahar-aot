import sys
def fnv(b):
    h=0xcbf29ce484222325
    for x in b: h=((h^x)*0x100000001b3)&(2**64-1)
    return h
p0=fnv(bytes(range(0,0x10))); p1=fnv(bytes(0x80+i for i in range(0x10))); m0=fnv(bytes(0x40+i for i in range(0x10)))
open(sys.argv[1],'w').write(f"#define EXE_P0_HASH 0x{p0:X}ull\n#define EXE_P1_HASH 0x{p1:X}ull\n#define MOD_P0_HASH 0x{m0:X}ull\n")
