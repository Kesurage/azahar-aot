#include <cassert>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>
#include "aot_library.h"

// Guest memory the test controls: exe code at 0x100000..0x100020, module at 0x400000.
static std::vector<uint8_t> exe(0x20), mod(0x100);
static size_t exe_reads = 0, mod_reads = 0;
static void Read(uint32_t a, uint8_t* out, size_t n) {
    if (a >= 0x400000) {
        assert(a - 0x400000 <= mod.size() && n <= mod.size() - (a - 0x400000));
        ++mod_reads;
        std::memcpy(out, &mod[a - 0x400000], n);
    } else {
        assert(a >= 0x100000 && a - 0x100000 <= exe.size() &&
               n <= exe.size() - (a - 0x100000));
        ++exe_reads;
        std::memcpy(out, &exe[a - 0x100000], n);
    }
}

int main(int argc, char** argv) {
    std::string err;
    auto lib = Aot::Library::Open(argv[1], err);
    if (!lib) { std::printf("open failed: %s\n", err.c_str()); return 1; }
    assert(lib->Generation() == 3 && lib->ChecksItself());
    assert(lib->EntryCount() == 3 && lib->ModuleCount() == 1);
    const auto check_range = [&](uint32_t address, uint64_t length) {
        lib->InvalidateRange(address, length);
        return lib->CheckPending(Read);
    };

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

    // Patching an already-loaded function must invalidate both ARM/Thumb lookup results
    // and direct generated calls that saved a function pointer before the patch.
    Code direct_a = lib->Lookup(0x100000);
    Code direct_mod = lib->Lookup(0x400020);
    exe[3] ^= 1;
    exe_reads = mod_reads = 0;
    assert(check_range(0x100003, 1) == 1);
    assert(exe_reads == 1 && mod_reads == 0);
    assert(!lib->Lookup(0x100000) && !lib->Lookup(0x100001));
    assert(lib->Lookup(0x100010) && lib->Lookup(0x400020));
    Context direct_ctx{};
    direct_ctx.r[15] = 0x100000;
    direct_ctx.r[0] = 99;
    direct_a(&direct_ctx);
    assert(direct_ctx.exit == EXIT_UNWIND && direct_ctx.r[0] == 99);

    // Restoring a second function must not reactivate the first while it remains patched.
    exe[0x13] ^= 1;
    assert(check_range(0x100013, 1) == 2);
    exe[0x13] ^= 1;
    assert(check_range(0x100013, 1) == 1);
    assert(!lib->Lookup(0x100000) && lib->Lookup(0x100010));
    exe[3] ^= 1;
    assert(check_range(0x100003, 1) == 0);
    direct_ctx.exit = EXIT_NONE;
    direct_a(&direct_ctx);
    assert(direct_ctx.exit == EXIT_NONE && direct_ctx.r[0] == 1);

    // Module-relative code uses the current placement. Data-only and empty ranges are
    // cheap; touching the final code byte counts, touching the exclusive end does not.
    exe_reads = mod_reads = 0;
    assert(check_range(0x400030, 0x20) == 0);
    assert(check_range(0x400020, 0) == 0);
    assert(check_range(0x0FFFFF, 1) == 0);
    assert(check_range(0x100020, 1) == 0);
    assert(exe_reads == 0 && mod_reads == 0);
    mod[0x2F] ^= 1;
    assert(check_range(0x40002F, 1) == 1);
    assert(exe_reads == 0 && mod_reads == 1 && !lib->Lookup(0x400020));
    direct_ctx = Context{};
    direct_ctx.r[15] = 0x400020;
    direct_ctx.r[0] = 99;
    direct_mod(&direct_ctx);
    assert(direct_ctx.exit == EXIT_UNWIND && direct_ctx.r[0] == 99);

    // A full cache flush rechecks loaded modules as well as the executable. Restored
    // original bytes become eligible for AOT again after the flush.
    exe[3] ^= 1;
    assert(lib->CheckAll(Read) == 2);
    assert(!lib->Lookup(0x100000) && !lib->Lookup(0x400020));
    exe[3] ^= 1;
    mod[0x2F] ^= 1;
    assert(lib->CheckAll(Read) == 0);
    assert(lib->Lookup(0x100000) && lib->Lookup(0x400020));

    // Guest addresses are 32-bit: range arithmetic must not overflow or miss the
    // low part of a wrapping invalidation, even for a full address-space flush.
    exe_reads = mod_reads = 0;
    assert(check_range(0xFFFFFFFFu, 1) == 0);
    assert(exe_reads == 0 && mod_reads == 0);
    exe[3] ^= 1;
    assert(check_range(0xFFFFFFF0u, 0x100020) == 1);
    assert(exe_reads == 1 && mod_reads == 0);
    exe[3] ^= 1;
    assert(check_range(0xFFFFFFFFu, std::numeric_limits<uint64_t>::max()) == 0);
    assert(lib->Lookup(0x100000));

    // Gateway block cheats invalidate BEFORE writing. No reads or generated code may run
    // while pending; revalidation at CPU dispatch must observe the eventual write.
    exe_reads = mod_reads = 0;
    lib->InvalidateRange(0x100003, 1);
    assert(exe_reads == 0 && mod_reads == 0);
    assert(!lib->Lookup(0x100000) && !lib->Lookup(0x100010));
    direct_ctx = Context{};
    direct_ctx.r[15] = 0x100000;
    direct_ctx.r[0] = 99;
    direct_a(&direct_ctx);
    assert(direct_ctx.exit == EXIT_UNWIND && direct_ctx.r[0] == 99);
    exe[3] ^= 1;
    assert(lib->CheckPending(Read) == 1);
    assert(exe_reads == 1 && mod_reads == 0);
    assert(!lib->Lookup(0x100000) && lib->Lookup(0x100010));
    assert(lib->CheckPending(Read) == 0 && exe_reads == 1); // no repeated hashing

    // Repeated invalidations coalesce. A pre-write restoration re-enables code only after
    // the bytes are restored, while a queued module change remains stale.
    lib->InvalidateRange(0x100003, 1);
    lib->InvalidateRange(0x100013, 1);
    lib->InvalidateRange(0x400025, 1);
    exe[3] ^= 1;
    mod[0x25] ^= 1;
    exe_reads = mod_reads = 0;
    assert(lib->CheckPending(Read) == 1);
    assert(exe_reads == 1 && mod_reads == 1);
    assert(lib->Lookup(0x100000) && !lib->Lookup(0x400020));
    lib->InvalidateAll();
    assert(!lib->Lookup(0x100000));
    mod[0x25] ^= 1;
    assert(lib->CheckPending(Read) == 0);
    assert(lib->Lookup(0x100000) && lib->Lookup(0x400020));

    // A module unloaded while its check is pending must never be read afterward.
    lib->InvalidateRange(0x400025, 1);
    assert(lib->Place("Mod", 0, Read));
    exe_reads = mod_reads = 0;
    assert(lib->CheckPending(Read) == 0 && exe_reads == 0 && mod_reads == 0);
    assert(!lib->Lookup(0x400020));
    exe_reads = mod_reads = 0;
    assert(check_range(0x400020, 0x10) == 0);
    assert(lib->CheckAll(Read) == 0);
    assert(exe_reads == 1 && mod_reads == 0); // unloaded memory is never read

    // calling the code works through the Context ABI
    Context ctx{};
    Code c = lib->Lookup(0x100010);
    ctx.r[15] = 0x100010;
    ctx.exit = EXIT_UNWIND; c(&ctx);
    assert(ctx.r[0] == 2 && ctx.exit == EXIT_NONE);
    std::puts("aot_library_test: all checks passed");
    return 0;
}
