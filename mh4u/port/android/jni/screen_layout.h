#pragma once

#include <string_view>

#include "common/settings.h"

namespace Mh4uAndroid {

// Keep the requested layout in the app config. Hiding an overlay temporarily selects the
// core's single-screen layout; showing it restores CustomLayout, including after a restart.
// The core's single-screen layout respects swap_screen, so the main screen is unchanged.
inline Settings::LayoutOption ScreenLayoutOption(std::string_view screens, float overlay_height) {
    using Layout = Settings::LayoutOption;
    if (screens == "single") {
        return Layout::SingleScreen;
    }
    if (screens == "stacked") {
        return Layout::Default;
    }
    if (screens == "side") {
        return Layout::SideScreen;
    }
    if (screens == "large") {
        return Layout::LargeScreen;
    }
    if (screens == "hybrid") {
        return Layout::HybridScreen;
    }
    // "overlay", or "windows" (there is no second window on Android).
    return overlay_height > 0.0f ? Layout::CustomLayout : Layout::SingleScreen;
}

} // namespace Mh4uAndroid
