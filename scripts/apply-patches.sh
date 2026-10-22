#!/usr/bin/env bash
# Applies patches/*.patch to azahar/ (idempotent: skips patches already applied).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/azahar"
git init -q 2>/dev/null || true   # git apply wants a work tree; harmless in a plain folder
for p in "$root"/patches/*.patch; do
    if git apply --reverse --check -p1 "$p" 2>/dev/null; then echo "already applied: $(basename "$p")"; continue; fi
    git apply -p1 "$p" && echo "applied: $(basename "$p")"
done
