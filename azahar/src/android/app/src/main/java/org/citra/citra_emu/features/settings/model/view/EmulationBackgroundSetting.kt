// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.model.view

import org.citra.citra_emu.R

/** A card with a portrait and a landscape thumbnail to pick the emulation backgrounds. */
class EmulationBackgroundSetting :
    SettingsItem(null, R.string.emulation_background, R.string.emulation_background_description) {
    override val type = TYPE_EMULATION_BACKGROUND
}
