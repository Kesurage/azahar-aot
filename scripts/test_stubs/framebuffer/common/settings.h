// Only the settings interface is stubbed. The tests compile the real patched layout,
// Rectangle, common types, and 3DS screen constants from the supplied Azahar source.
#pragma once

#include "common/common_types.h"

namespace Settings {

enum class LayoutOption : u32 {
    Default, SingleScreen, LargeScreen, SideScreen,
#ifndef ANDROID
    SeparateWindows,
#endif
    HybridScreen, CustomLayout
};
enum class PortraitLayoutOption : u32 {
    PortraitTopFullWidth, PortraitCustomLayout, PortraitOriginal
};
enum class SecondaryDisplayLayout : u32 {
    None, TopScreenOnly, BottomScreenOnly, SideBySide, OppositeScreenOnly, Original, Hybrid,
    LargeScreen
};
enum class SmallScreenPosition : u32 {
    TopRight, MiddleRight, BottomRight, TopLeft, MiddleLeft, BottomLeft, AboveLarge, BelowLarge
};
enum class StereoRenderOption : u32 {
    Off, SideBySide, SideBySideFull, Anaglyph, Interlaced, ReverseInterlaced, CardboardVR
};
enum class AspectRatio : u32 { Default, R16_9, R4_3, R21_9, R16_10, Stretch };

template <class T>
struct Setting {
    T value{};
    T GetValue() const { return value; }
    void SetValue(T next) { value = next; }
    Setting& operator=(T next) { value = next; return *this; }
    operator T() const { return value; }
};

struct Values {
    Setting<LayoutOption> layout_option;
    Setting<PortraitLayoutOption> portrait_layout_option;
    Setting<SecondaryDisplayLayout> secondary_display_layout;
    Setting<SmallScreenPosition> small_screen_position;
    Setting<StereoRenderOption> render_3d;
    Setting<AspectRatio> aspect_ratio;
    Setting<bool> swap_screen, upright_screen, use_integer_scaling;
    Setting<bool> screen_top_stretch, screen_bottom_stretch;
    Setting<u32> screen_top_leftright_padding, screen_top_topbottom_padding;
    Setting<u32> screen_bottom_leftright_padding, screen_bottom_topbottom_padding;
    Setting<u32> screen_gap;
    Setting<float> large_screen_proportion{4.0f};
    Setting<u16> custom_top_x, custom_top_y, custom_top_width{400}, custom_top_height{240};
    Setting<u16> custom_bottom_x, custom_bottom_y{240}, custom_bottom_width{320},
        custom_bottom_height{240};
    Setting<u16> custom_portrait_top_x, custom_portrait_top_y, custom_portrait_top_width{400},
        custom_portrait_top_height{240};
    Setting<u16> custom_portrait_bottom_x, custom_portrait_bottom_y{240},
        custom_portrait_bottom_width{320}, custom_portrait_bottom_height{240};
    Setting<u32> custom_second_layer_opacity{100};
    Setting<u32> cardboard_screen_size{100};
    Setting<s32> cardboard_x_shift, cardboard_y_shift;
};

inline Values values;
} // namespace Settings
