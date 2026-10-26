#include "aot_library.h"

#include <algorithm>
#include <cstring>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace Aot {

namespace {

void* OpenLib(const std::string& path, std::string& error) {
#ifdef _WIN32
    void* h = reinterpret_cast<void*>(LoadLibraryA(path.c_str()));
    if (!h)
        error = "LoadLibrary failed";
    return h;
#else
    void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        const char* e = dlerror();
        error = e ? e : "dlopen failed";
    }
    return h;
#endif
}

void* Sym(void* h, const char* name) {
#ifdef _WIN32
    return reinterpret_cast<void*>(GetProcAddress(reinterpret_cast<HMODULE>(h), name));
#else
    return dlsym(h, name);
#endif
}

void CloseLib(void* h) {
#ifdef _WIN32
    FreeLibrary(reinterpret_cast<HMODULE>(h));
#else
    dlclose(h);
#endif
}

size_t SlotOf(uint32_t address) {
    // Top bits of address * golden ratio: ARM steps by 4 and Thumb by 2 with bit 0 set,
    // so plain low bits would leave half the slots unused.
    return (address * 0x9E3779B1u) >> 16;
}

} // namespace

uint64_t Fnv1a(const uint8_t* data, size_t size) {
    uint64_t h = 0xCBF29CE484222325ull;
    for (size_t i = 0; i < size; ++i) {
        h = (h ^ data[i]) * 0x100000001B3ull;
    }
    return h;
}

Library::~Library() {
    if (handle)
        CloseLib(handle);
}

std::unique_ptr<Library> Library::Open(const std::string& path, std::string& error) {
    void* h = OpenLib(path, error);
    if (!h)
        return nullptr;
    auto need = [&](const char* name) -> void* {
        void* p = Sym(h, name);
        if (!p)
            error = std::string("missing symbol ") + name;
        return p;
    };
    const auto* abi = static_cast<const uint32_t*>(need("recomp_abi"));
    const auto* ecount = static_cast<const uint32_t*>(abi ? need("recomp_entry_count") : nullptr);
    const auto* ents = static_cast<const Entry*>(ecount ? need("recomp_entries") : nullptr);
    const auto* mcount = static_cast<const uint32_t*>(ents ? need("recomp_module_count") : nullptr);
    const auto* mods = static_cast<const Module*>(mcount ? need("recomp_modules") : nullptr);
    if (!mods) {
        CloseLib(h);
        return nullptr;
    }
    if (*abi != RECOMP_ABI && *abi != RECOMP_ABI + 1) { // 5: built with RECOMP_INLINE_SVC
        error = "built for version " + std::to_string(*abi) + " of the interface, this host is " +
                std::to_string(RECOMP_ABI);
        CloseLib(h);
        return nullptr;
    }
    std::unique_ptr<Library> lib(new Library());
    lib->handle = h;
    lib->entries = ents;
    lib->entry_count = *ecount;
    lib->modules = mods;
    lib->module_count = *mcount;
    if (const auto* gen = static_cast<const uint32_t*>(Sym(h, "recomp_generation"))) {
        lib->generation = *gen;
    }
    // Generation 2 laid the origins table out differently; nothing but tests used it.
    if (lib->generation >= 3) {
        lib->origins = static_cast<const Origins*>(Sym(h, "recomp_origins"));
    }
    lib->cache.resize(kCacheSlots);
    lib->pending.resize(lib->module_count + 1, false);
    return lib;
}

std::string Library::Describe() const {
    return std::to_string(entry_count) + " entry points, " + std::to_string(module_count) +
           " modules, generation " + std::to_string(generation);
}

const Origins* Library::OriginsOf(size_t unit) const {
    if (!origins || unit > module_count)
        return nullptr;
    return origins + unit;
}

bool Library::OwnerIsStale(size_t unit, size_t entry) const {
    const Origins* o = OriginsOf(unit);
    if (!o || !o->owners)
        return false;
    const uint32_t owner = o->owners[entry];
    return owner != NO_ORIGIN && o->stale[owner] != 0;
}

Code Library::Find(const Entry* table, size_t count, size_t unit, uint32_t address) const {
    const Entry* end = table + count;
    const Entry* it = std::lower_bound(table, end, address, [](const Entry& e, uint32_t a) {
        return e.address < a;
    });
    if (it == end || it->address != address)
        return nullptr;
    if (OwnerIsStale(unit, static_cast<size_t>(it - table)))
        return nullptr;
    return it->code;
}

Code Library::Lookup(uint32_t address) {
    Slot& slot = cache[SlotOf(address)];
    if (slot.address == address)
        return slot.code;
    Code code = Find(entries, entry_count, 0, address);
    if (!code) {
        for (const Placed& p : placed) {
            if (address - p.base < p.size) {
                const Module& m = modules[p.index];
                code = Find(m.entries, m.count, p.index + 1, address - p.base);
                break;
            }
        }
    }
    slot.address = address;
    slot.code = code;
    return code;
}

void* Library::Symbol(const char* name) const {
    return handle ? Sym(handle, name) : nullptr;
}

bool Library::InExecutable(uint32_t address) const {
    const Origins* o = OriginsOf(0);
    if (!o || o->piece_count == 0)
        return false;
    return address >= o->pieces[0].start && address < o->pieces[o->piece_count - 1].end;
}

void Library::FlushCache() {
    std::fill(cache.begin(), cache.end(), Slot{});
}

size_t Library::Check(size_t unit, uint32_t base, const ReadFn& read) {
    const Origins* o = OriginsOf(unit);
    FlushCache();
    if (unit < pending.size())
        pending[unit] = false;
    if (!o || o->piece_count == 0)
        return 0;
    const Piece* pieces = o->pieces;
    const uint32_t first = pieces[0].start;
    const uint32_t last = pieces[o->piece_count - 1].end;
    std::vector<uint8_t> code(last - first);
    read(base + first, code.data(), code.size());

    // changed_before[i] = how many of the pieces before piece i changed
    std::vector<uint32_t> changed_before(o->piece_count + 1, 0);
    for (uint32_t i = 0; i < o->piece_count; ++i) {
        const Piece& p = pieces[i];
        const bool changed = Fnv1a(code.data() + (p.start - first), p.end - p.start) != p.hash;
        changed_before[i + 1] = changed_before[i] + (changed ? 1 : 0);
    }
    size_t stale = 0;
    for (uint32_t f = 0; f < o->count; ++f) {
        const Origin& fn = o->functions[f];
        bool changed = false;
        for (uint32_t r = fn.first; r < fn.first + fn.count && !changed; ++r) {
            const Run& run = o->runs[r];
            changed = changed_before[run.end] > changed_before[run.first];
        }
        o->stale[f] = changed ? 1 : 0;
        o->starts[f] = changed ? NO_START : fn.start;
        stale += changed ? 1 : 0;
    }
    return stale;
}

size_t Library::CheckAll(const ReadFn& read) {
    size_t stale = Check(0, 0, read);
    for (const Placed& p : placed)
        stale += Check(p.index + 1, p.base, read);
    return stale;
}

bool Library::OverlapsRange(size_t unit, uint32_t base, uint32_t address, uint64_t length) const {
    if (length == 0)
        return false;
    const Origins* o = OriginsOf(unit);
    if (!o || o->piece_count == 0)
        return false;
    constexpr uint64_t address_space = uint64_t{1} << 32;
    if (length >= address_space)
        return true;

    // Use wide endpoints so invalidating the last guest byte cannot wrap to an empty
    // range. Split a wrapping range instead of overflowing start + length.
    const uint64_t end = static_cast<uint64_t>(address) + length;
    const auto overlaps = [&](uint64_t first, uint64_t last) {
        if (first == last)
            return false;
        const Piece* pieces_end = o->pieces + o->piece_count;
        const Piece* piece = std::lower_bound(
            o->pieces, pieces_end, first, [base](const Piece& p, uint64_t at) {
                return static_cast<uint64_t>(base) + p.end <= at;
            });
        return piece != pieces_end && static_cast<uint64_t>(base) + piece->start < last;
    };
    return overlaps(address, std::min(end, address_space)) ||
           (end > address_space && overlaps(0, end - address_space));
}

void Library::InvalidateUnit(size_t unit) {
    const Origins* o = OriginsOf(unit);
    if (!o || o->piece_count == 0 || pending[unit])
        return;
    pending[unit] = true;
    checks_pending = true;
    // Invalidate before reading memory: some writers notify us before the actual write.
    // Saved direct function pointers bypass Lookup, so both generated guards must change.
    std::fill_n(o->stale, o->count, uint8_t{1});
    std::fill_n(o->starts, o->count, NO_START);
    FlushCache();
}

void Library::InvalidateRange(uint32_t address, uint64_t length) {
    if (OverlapsRange(0, 0, address, length))
        InvalidateUnit(0);
    for (const Placed& p : placed) {
        if (OverlapsRange(p.index + 1, p.base, address, length))
            InvalidateUnit(p.index + 1);
    }
}

void Library::InvalidateAll() {
    InvalidateUnit(0);
    for (const Placed& p : placed)
        InvalidateUnit(p.index + 1);
}

size_t Library::CheckPending(const ReadFn& read) {
    if (!checks_pending)
        return 0;
    checks_pending = false;
    // Check whole affected units: a function can span multiple pieces, and an older
    // patch in a different piece must remain stale when this range is restored.
    size_t stale = pending[0] ? Check(0, 0, read) : 0;
    for (const Placed& p : placed) {
        if (pending[p.index + 1])
            stale += Check(p.index + 1, p.base, read);
    }
    return stale;
}

bool Library::Place(const std::string& name, uint32_t base, const ReadFn& read, size_t* stale_out) {
    size_t index = module_count;
    for (size_t i = 0; i < module_count; ++i) {
        if (name == modules[i].name) {
            index = i;
            break;
        }
    }
    if (index == module_count)
        return false;
    const Module& m = modules[index];
    *m.base = base;
    placed.erase(std::remove_if(placed.begin(), placed.end(),
                                [&](const Placed& p) { return p.index == index; }),
                 placed.end());
    pending[index + 1] = false;
    if (base != 0)
        placed.push_back({base, m.size, index});
    FlushCache();
    if (base != 0) {
        const size_t stale = Check(index + 1, base, read);
        if (stale_out)
            *stale_out = stale;
    }
    return true;
}

} // namespace Aot
