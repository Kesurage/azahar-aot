#include <cassert>
#include <cstdio>
#include <cstring>
#include <vector>
#include "aot_library.h"

// Guest memory the test controls: exe code at 0x100000..0x100020, module at 0x400000.
static std::vector<uint8_t> exe(0x20), mod(0x100);
static void Read(uint32_t a, uint8_t* out, size_t n) {
    if (a >= 0x400000) std::memcpy(out, &mod[a - 0x400000], n);
    else std::memcpy(out, &exe[a - 0x100000], n);
}

int main(int argc, char** argv) {
    std::string err;
    auto lib = Aot::Library::Open(argv[1], err);
    if (!lib) { std::printf("open failed: %s\n", err.c_str()); return 1; }
    assert(lib->Generation() == 3 && lib->ChecksItself());
    assert(lib->EntryCount() == 3 && lib->ModuleCount() == 1);

    // lookup: ARM and Thumb entries, a miss, and the module before it is placed
    assert(lib->Lookup(0x100000) && lib->Lookup(0x100001) && lib->Lookup(0x100010));
    assert(!lib->Lookup(0x100004));
    assert(!lib->Lookup(0x400020));

    // unchanged memory: nothing stale
    for (size_t i = 0; i < 0x10; ++i) { exe[i] = i; exe[0x10 + i] = 0x80 + i; mod[0x20 + i] = 0x40 + i; }
    assert(lib->Check(0, 0, Read) == 0);

    // change a byte of function B only
    exe[0x13] ^= 1;
    assert(lib->Check(0, 0, Read) == 1);
    assert(lib->Lookup(0x100000) && !lib->Lookup(0x100010));
    exe[0x13] ^= 1;
    assert(lib->Check(0, 0, Read) == 0 && lib->Lookup(0x100010));

    // module: place, look up at base+offset, check, unplace
    size_t stale = 99;
    assert(!lib->Place("Nope", 0x400000, Read));
    assert(lib->Place("Mod", 0x400000, Read, &stale) && stale == 0);
    assert(lib->Lookup(0x400020));
    mod[0x25] ^= 0xFF;
    assert(lib->Place("Mod", 0x400000, Read, &stale) && stale == 1);
    assert(!lib->Lookup(0x400020));
    mod[0x25] ^= 0xFF;
    assert(lib->Place("Mod", 0x400000, Read, &stale) && stale == 0 && lib->Lookup(0x400020));
    assert(lib->Place("Mod", 0, Read));
    assert(!lib->Lookup(0x400020));

    // calling the code works through the Context ABI
    Context ctx{};
    Code c = lib->Lookup(0x100010);
    ctx.exit = EXIT_UNWIND; c(&ctx);
    assert(ctx.r[0] == 2 && ctx.exit == EXIT_NONE);
    std::puts("aot_library_test: all checks passed");
    return 0;
}
