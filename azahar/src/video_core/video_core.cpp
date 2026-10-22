// Copyright 2014-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#include "common/logging/log.h"
#include "common/settings.h"
#include "video_core/gpu.h"
#ifdef ENABLE_OPENGL
#include "video_core/renderer_opengl/renderer_opengl.h"
#endif
#ifdef ENABLE_SOFTWARE_RENDERER
#include "video_core/renderer_software/renderer_software.h"
#endif
#ifdef ENABLE_VULKAN
#include "video_core/renderer_vulkan/renderer_vulkan.h"
#endif
#include "video_core/video_core.h"

#include <atomic>
#include <mutex>

#ifdef ENABLE_SDL2
#include <SDL.h>
#endif

namespace VideoCore {

namespace {
std::mutex background_mutex;
BackgroundImageData background_images[2]; // [0] landscape, [1] portrait
std::atomic<u64> background_generation{0};
} // namespace

void SetBackgroundImage(bool portrait, const u32* rgba_pixels, u32 width, u32 height) {
    std::scoped_lock lock{background_mutex};
    auto& image = background_images[portrait ? 1 : 0];
    if (rgba_pixels == nullptr || width == 0 || height == 0) {
        image = {};
    } else {
        image.pixels.assign(rgba_pixels, rgba_pixels + static_cast<size_t>(width) * height);
        image.width = width;
        image.height = height;
    }
    background_generation.fetch_add(1, std::memory_order_release);
}

u64 GetBackgroundImageGeneration() {
    return background_generation.load(std::memory_order_acquire);
}

void GetBackgroundImage(bool portrait, BackgroundImageData& out) {
    std::scoped_lock lock{background_mutex};
    const auto& preferred = background_images[portrait ? 1 : 0];
    const auto& other = background_images[portrait ? 0 : 1];
    out = preferred.pixels.empty() ? other : preferred;
}

std::unique_ptr<RendererBase> CreateRenderer(Frontend::EmuWindow& emu_window,
                                             Frontend::EmuWindow* secondary_window,
                                             Pica::PicaCore& pica, Core::System& system) {
    const auto graphics_api = Settings::GetWorkingGraphicsAPI();
    switch (graphics_api) {
#ifdef ENABLE_SOFTWARE_RENDERER
    case Settings::GraphicsAPI::Software:
        return std::make_unique<SwRenderer::RendererSoftware>(system, pica, emu_window);
#endif
#ifdef ENABLE_VULKAN
    case Settings::GraphicsAPI::Vulkan:
#if defined(ENABLE_SDL2) && !defined(__APPLE__)
        // TODO: When we migrate to SDL3, refactor so that we don't need to init here.
        if (SDL_WasInit(SDL_INIT_VIDEO) == 0) {
            SDL_Init(SDL_INIT_VIDEO);
        }
#endif // ENABLE_SDL2
        return std::make_unique<Vulkan::RendererVulkan>(system, pica, emu_window, secondary_window);
#endif
#ifdef ENABLE_OPENGL
    case Settings::GraphicsAPI::OpenGL:
        return std::make_unique<OpenGL::RendererOpenGL>(system, pica, emu_window, secondary_window);
#endif
    default:
        LOG_CRITICAL(Render,
                     "Unknown or unsupported graphics API {}, falling back to available default",
                     graphics_api);
#ifdef ENABLE_OPENGL
        return std::make_unique<OpenGL::RendererOpenGL>(system, pica, emu_window, secondary_window);
#elif ENABLE_VULKAN
        return std::make_unique<Vulkan::RendererVulkan>(system, pica, emu_window, secondary_window);
#elif ENABLE_SOFTWARE_RENDERER
        return std::make_unique<SwRenderer::RendererSoftware>(system, pica, emu_window);
#else
// TODO: Add a null renderer backend for this, perhaps.
#error "At least one renderer must be enabled."
#endif
    }
}

} // namespace VideoCore
