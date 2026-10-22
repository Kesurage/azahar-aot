// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.model.view

import org.citra.citra_emu.R

/** A live preview of the chat overlay with a few one-tap presets. */
class ChatOverlayPreviewSetting :
    SettingsItem(null, R.string.chat_preview_title, R.string.chat_preview_description) {
    override val type = TYPE_CHAT_PREVIEW
}
