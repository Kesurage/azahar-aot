// Host side of 3dsrecomp's interface (recomp.h, ABI 4): opens a recompiled-code library,
// finds code for an address, places CRO modules and marks functions whose code in memory
// no longer matches what they were compiled from. No dependency on Azahar, so it is
// unit-tested on its own (aot/tests). C++ port of recomp/abi/src/lib.rs (`Library`).
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

extern "C" {
#include "recomp.h"
}

namespace Aot {

class Library {
public:
    ~Library();

    /// dlopen()s a library made by `3dsrecomp build`. Returns null and sets `error` on failure
    /// (missing symbols, or an ABI version other than RECOMP_ABI).
    static std::unique_ptr<Library> Open(const std::string& path, std::string& error);

    /// Version of the code generator that made the library (0 if it predates the symbol).
    uint32_t Generation() const {
        return generation;
    }
    /// Whether the library can detect changed code (generation 3 and later).
    bool ChecksItself() const {
        return origins != nullptr;
    }
    std::string Describe() const;

    /// Code that can run from `address` (bit 0 set for Thumb), in the executable or in a
    /// placed module; null if there is none or the function's code changed since it was made.
    Code Lookup(uint32_t address);

    /// Reads guest memory: fills `out` with `size` bytes from `address`.
    using ReadFn = std::function<void(uint32_t address, uint8_t* out, size_t size)>;

    /// Hashes the code of unit (0 = executable, i + 1 = module i) as it is in memory and marks
    /// functions that changed as stale. Returns how many are stale.
    size_t Check(size_t unit, uint32_t base, const ReadFn& read);

    /// Tells the code of module `name` where the title loaded it (0 = unloaded) and checks it.
    /// Returns false if the library has no module of that name.
    bool Place(const std::string& name, uint32_t base, const ReadFn& read, size_t* stale = nullptr);

    size_t EntryCount() const {
        return entry_count;
    }
    size_t ModuleCount() const {
        return module_count;
    }

private:
    Library() = default;
    const Origins* OriginsOf(size_t unit) const;
    bool OwnerIsStale(size_t unit, size_t entry) const;
    Code Find(const Entry* entries, size_t count, size_t unit, uint32_t address) const;
    void FlushCache();

    void* handle = nullptr;
    const Entry* entries = nullptr;
    size_t entry_count = 0;
    const Module* modules = nullptr;
    size_t module_count = 0;
    const Origins* origins = nullptr;
    uint32_t generation = 0;

    struct Placed {
        uint32_t base, size;
        size_t index;
    };
    std::vector<Placed> placed;

    // Direct-mapped cache of recent lookups: a jump through a register would otherwise
    // binary-search every entry. Same scheme as Zakuro's.
    static constexpr size_t kCacheSlots = 65536;
    struct Slot {
        uint32_t address = 0xFFFFFFFFu;
        Code code = nullptr;
    };
    std::vector<Slot> cache;
};

/// 64-bit FNV-1a, which recomp.h uses for Piece::hash.
uint64_t Fnv1a(const uint8_t* data, size_t size);

} // namespace Aot
