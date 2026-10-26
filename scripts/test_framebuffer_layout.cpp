#include <cassert>
#include <cmath>
#include <iostream>

#include "common/settings.h"
#include "core/3ds.h"
#include "core/frontend/framebuffer_layout.h"
#include "core/frontend/top_screen.h"
#include "screen_layout.h"

namespace {

void CheckInside(const Common::Rectangle<u32>& rect, const Layout::FramebufferLayout& layout) {
    assert(rect.left < rect.right && rect.top < rect.bottom);
    assert(rect.right <= layout.width && rect.bottom <= layout.height);
}

void CheckAspect(const Common::Rectangle<u32>& rect, u32 width, u32 height, bool upright) {
    if (upright) {
        std::swap(width, height);
    }
    // Integer pixel rounding may lose less than one output pixel.
    const double expected_width = static_cast<double>(rect.GetHeight()) * width / height;
    assert(std::abs(static_cast<double>(rect.GetWidth()) - expected_width) < 1.01);
}

void CheckScale(u32 top_width, u32 scale, bool swapped, bool upright) {
    auto& settings = Settings::values;
    settings.layout_option = Settings::LayoutOption::CustomLayout;
    settings.swap_screen = swapped;
    settings.upright_screen = upright;
    Layout::SetTopScreenWidth(top_width);
    Layout::ScreenOverlay overlay;
    overlay.height = 0.30f;
    Layout::SetScreenOverlay(overlay);

    const auto layout = Layout::FrameLayoutFromResolutionScale(scale);
    const u32 main_width = swapped ? Core::kScreenBottomWidth : top_width;
    u32 expected_width = main_width * scale / 100;
    u32 expected_height = Core::kScreenTopHeight * scale / 100;
    if (upright) {
        std::swap(expected_width, expected_height);
    }
    assert(layout.width == expected_width && layout.height == expected_height);
    assert(layout.top_screen_enabled && layout.bottom_screen_enabled);
    assert(layout.is_rotated == !upright);
    CheckInside(layout.top_screen, layout);
    CheckInside(layout.bottom_screen, layout);
    CheckAspect(layout.top_screen, top_width, Core::kScreenTopHeight, upright);
    CheckAspect(layout.bottom_screen, Core::kScreenBottomWidth, Core::kScreenBottomHeight, upright);

    // These dimensions feed RPC screenshots and Qt capture/video dumping. At native scale
    // the old bug requested 40000x24000 (3.84 GB RGBA), rather than 400x240 (384 KB).
    const auto rgba_bytes = static_cast<uint64_t>(layout.width) * layout.height * 4;
    assert(rgba_bytes == static_cast<uint64_t>(expected_width) * expected_height * 4);
    assert(rgba_bytes < 2'000'000);
}

void CheckWindowAspect(bool swapped, bool upright) {
    Settings::values.upright_screen = upright;
    Layout::SetTopScreenWidth(400);
    Layout::ScreenOverlay overlay;
    overlay.height = 0.30f;
    Layout::SetScreenOverlay(overlay);
    // Android's live surface path calls CustomFrameLayout directly with window dimensions.
    const auto layout = Layout::CustomFrameLayout(1920, 1080, swapped, false);
    assert(layout.width == 1920 && layout.height == 1080);
    CheckInside(layout.top_screen, layout);
    CheckInside(layout.bottom_screen, layout);
    CheckAspect(layout.top_screen, 400, 240, upright);
    CheckAspect(layout.bottom_screen, 320, 240, upright);
}

void CheckHideAndShow(bool swapped, bool upright) {
    Settings::values.swap_screen = swapped;
    Settings::values.upright_screen = upright;
    Layout::SetTopScreenWidth(400);
    Layout::ScreenOverlay overlay;
    // Exercise the same effective layout selection used at startup and by live JNI updates.
    for (float height : {0.30f, 0.0f, 0.45f}) {
        overlay.height = height;
        Layout::SetScreenOverlay(overlay);
        Settings::values.layout_option =
            Mh4uAndroid::ScreenLayoutOption("overlay", Layout::GetScreenOverlay().height);
        const auto layout = Layout::FrameLayoutFromResolutionScale(100);
        const bool shown = height > 0.0f;
        assert(layout.top_screen_enabled == (shown || !swapped));
        assert(layout.bottom_screen_enabled == (shown || swapped));
        const auto& main = swapped ? layout.bottom_screen : layout.top_screen;
        CheckInside(main, layout);
        CheckAspect(main, swapped ? 320 : 400, 240, upright);
        if (!shown && !swapped) {
            assert(!layout.IsWithinTouchscreen(1, 1));
            assert(layout.BottomViewAt(1, 1) == -1);
        }
    }

    // Overlay visibility must not replace another layout selected by the player.
    for (float height : {0.0f, 0.30f}) {
        using Option = Settings::LayoutOption;
        assert(Mh4uAndroid::ScreenLayoutOption("single", height) == Option::SingleScreen);
        assert(Mh4uAndroid::ScreenLayoutOption("stacked", height) == Option::Default);
        assert(Mh4uAndroid::ScreenLayoutOption("side", height) == Option::SideScreen);
        assert(Mh4uAndroid::ScreenLayoutOption("large", height) == Option::LargeScreen);
        assert(Mh4uAndroid::ScreenLayoutOption("hybrid", height) == Option::HybridScreen);
    }
}

} // namespace

int main() {
    for (u32 width : {400u, 428u}) {
        for (u32 scale : {100u, 150u, 200u}) {
            for (bool swapped : {false, true}) {
                for (bool upright : {false, true}) {
                    CheckScale(width, scale, swapped, upright);
                }
            }
        }
    }
    for (bool swapped : {false, true}) {
        for (bool upright : {false, true}) {
            CheckWindowAspect(swapped, upright);
            CheckHideAndShow(swapped, upright);
        }
    }
    std::cout << "framebuffer_layout: scale, swap, rotation, aspect, and visibility checks passed\n";
}
