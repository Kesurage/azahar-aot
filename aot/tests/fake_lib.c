/* A hand-written stand-in for a 3dsrecomp library (ABI 4, generation 3). */
#include "recomp.h"

static uint8_t exe_stale[2];
static uint32_t exe_starts[2] = {0x100000, 0x100010};
static uint8_t mod_stale[1];
static uint32_t mod_starts[1] = {0x20};
static uint32_t mod_base;

/* Match the generated entry guard: the common start skips the stale check, so
   invalidation must change BOTH the stale flag and the fast-path start. */
static void f_a(Context *ctx) {
    if (ctx->r[15] != exe_starts[0]) { STALE_CHECK(exe_stale[0]); }
    ctx->r[0] = 1; ctx->exit = EXIT_NONE;
}
static void f_b(Context *ctx) {
    if (ctx->r[15] != exe_starts[1]) { STALE_CHECK(exe_stale[1]); }
    ctx->r[0] = 2; ctx->exit = EXIT_NONE;
}
static void m_x(Context *ctx) {
    if (ctx->r[15] != mod_base + mod_starts[0]) { STALE_CHECK(mod_stale[0]); }
    ctx->r[0] = 3; ctx->exit = EXIT_NONE;
}

RECOMP_EXPORT const uint32_t recomp_abi = RECOMP_ABI;
RECOMP_EXPORT const uint32_t recomp_generation = 3;

/* exe: function A at 0x100000 (Thumb entry too, 0x100001), B at 0x100010 */
RECOMP_EXPORT const uint32_t recomp_entry_count = 3;
/* recomp_entries must be an array symbol (dlsym gives its address) */
RECOMP_EXPORT const Entry recomp_entries[3] = {
    {0x00100000, f_a}, {0x00100001, f_a}, {0x00100010, f_b},
};

static const Entry mod_entries[] = {{0x20, m_x}};
RECOMP_EXPORT const uint32_t recomp_module_count = 1;
RECOMP_EXPORT const Module recomp_modules[1] = {{"Mod", &mod_base, 0x100, 1, mod_entries}};

/* pieces: exe [0x100000,0x100010) hash set by test at runtime is not possible (const),
   so the hashes are filled by the generator script into hashes.h */
#include "hashes.h"
static const Piece exe_pieces[] = {
    {0x100000, 0x100010, EXE_P0_HASH}, {0x100010, 0x100020, EXE_P1_HASH}};
static const Run exe_runs[] = {{0, 1}, {1, 2}};
static const Origin exe_funcs[] = {{0, 1, 0x100000}, {1, 1, 0x100010}};
static const uint32_t exe_owners[] = {0, 0, 1};

static const Piece mod_pieces[] = {{0x20, 0x30, MOD_P0_HASH}};
static const Run mod_runs[] = {{0, 1}};
static const Origin mod_funcs[] = {{0, 1, 0x20}};
static const uint32_t mod_owners[] = {0};

RECOMP_EXPORT const Origins recomp_origins[2] = {
    {2, 2, exe_pieces, exe_funcs, exe_runs, exe_owners, exe_stale, exe_starts},
    {1, 1, mod_pieces, mod_funcs, mod_runs, mod_owners, mod_stale, mod_starts},
};
