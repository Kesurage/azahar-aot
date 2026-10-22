// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.model.view

import org.citra.citra_emu.R

/** A horizontally scrolling picker of overlay button themes with thumbnail previews. */
class OverlayThemeSetting : SettingsItem(null, R.string.overlay_theme, R.string.overlay_theme_description) {
    override val type = TYPE_OVERLAY_THEME
}
