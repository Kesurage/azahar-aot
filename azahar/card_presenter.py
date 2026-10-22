#!/usr/bin/env python3
"""Swaps the two Portrait/Landscape background rows in SettingsFragmentPresenter.kt for the
thumbnail card. Run from the repo root."""
import sys

PATH = "src/android/app/src/main/java/org/citra/citra_emu/features/settings/ui/SettingsFragmentPresenter.kt"
IMPORT = "import org.citra.citra_emu.features.settings.model.view.EmulationBackgroundSetting\n"

s = open(PATH).read()

if "add(EmulationBackgroundSetting())" in s:
    print("Already done.")
    sys.exit(0)


def block_span(text, marker):
    """Span of the add( ... ) call that contains marker."""
    m = text.index(marker)
    start = text.rindex("add(", 0, m)
    # include leading indentation
    line_start = text.rfind("\n", 0, start) + 1
    depth = 0
    i = start + 3
    while True:
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    end = i + 1
    # swallow the newline and one following blank line
    if text[end:end + 1] == "\n":
        end += 1
    if text[end:end + 1] == "\n":
        end += 1
    return line_start, end


a0, a1 = block_span(s, "R.string.emulation_background_portrait,")
s = s[:a0] + "            add(EmulationBackgroundSetting())\n\n" + s[a1:]
b0, b1 = block_span(s, "R.string.emulation_background_landscape,")
s = s[:b0] + s[b1:]

if IMPORT not in s:
    marker = "import org.citra.citra_emu.features.settings.model.view."
    i = s.index(marker)
    s = s[:i] + IMPORT + s[i:]

open(PATH, "w").write(s)
print("Updated", PATH)
