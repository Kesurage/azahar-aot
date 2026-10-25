#include "emu_window_gl.h"

#include <array>

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <glad/glad.h>

#include "common/assert.h"
#include "common/logging/log.h"
#include "common/settings.h"
#include "core/core.h"
#include "video_core/gpu.h"
#include "video_core/renderer_base.h"

namespace Mh4uAndroid {
namespace {

constexpr std::array<EGLint, 15> egl_attribs{EGL_SURFACE_TYPE,
                                             EGL_WINDOW_BIT,
                                             EGL_RENDERABLE_TYPE,
                                             EGL_OPENGL_ES3_BIT_KHR,
                                             EGL_BLUE_SIZE,
                                             8,
                                             EGL_GREEN_SIZE,
                                             8,
                                             EGL_RED_SIZE,
                                             8,
                                             EGL_DEPTH_SIZE,
                                             0,
                                             EGL_STENCIL_SIZE,
                                             0,
                                             EGL_NONE};
constexpr std::array<EGLint, 5> egl_empty_attribs{EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
constexpr std::array<EGLint, 4> egl_context_attribs{EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};

/// A context for the renderer's worker threads (the disk shader cache and friends): its own
/// pbuffer surface, sharing with the window's context.
class SharedContext_Android final : public Frontend::GraphicsContext {
public:
    SharedContext_Android(EGLDisplay egl_display, EGLConfig egl_config,
                          EGLContext egl_share_context)
        : egl_display{egl_display},
          egl_surface{eglCreatePbufferSurface(egl_display, egl_config, egl_empty_attribs.data())},
          egl_context{eglCreateContext(egl_display, egl_config, egl_share_context,
                                       egl_context_attribs.data())} {
        ASSERT_MSG(egl_surface, "eglCreatePbufferSurface() failed!");
        ASSERT_MSG(egl_context, "eglCreateContext() failed!");
    }

    ~SharedContext_Android() override {
        if (!eglDestroySurface(egl_display, egl_surface)) {
            LOG_CRITICAL(Frontend, "eglDestroySurface() failed");
        }
        if (!eglDestroyContext(egl_display, egl_context)) {
            LOG_CRITICAL(Frontend, "eglDestroyContext() failed");
        }
    }

    void MakeCurrent() override {
        eglMakeCurrent(egl_display, egl_surface, egl_surface, egl_context);
    }

    void DoneCurrent() override {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }

private:
    EGLDisplay egl_display{};
    EGLSurface egl_surface{};
    EGLContext egl_context{};
};

} // namespace

EmuWindowGL::EmuWindowGL(ANativeWindow* surface, int given_width, int given_height)
    : EmuWindow(surface, given_width, given_height) {
    if (egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY); egl_display == EGL_NO_DISPLAY) {
        LOG_CRITICAL(Frontend, "eglGetDisplay() failed");
        return;
    }
    if (eglInitialize(egl_display, 0, 0) != EGL_TRUE) {
        LOG_CRITICAL(Frontend, "eglInitialize() failed");
        return;
    }
    if (EGLint egl_num_configs{};
        eglChooseConfig(egl_display, egl_attribs.data(), &egl_config, 1, &egl_num_configs) !=
        EGL_TRUE) {
        LOG_CRITICAL(Frontend, "eglChooseConfig() failed");
        return;
    }

    if (!(surface && CreateWindowSurface(surface))) {
        return;
    }

    if (egl_context = eglCreateContext(egl_display, egl_config, 0, egl_context_attribs.data());
        egl_context == EGL_NO_CONTEXT) {
        LOG_CRITICAL(Frontend, "eglCreateContext() failed");
        return;
    }
    if (eglSurfaceAttrib(egl_display, egl_surface, EGL_SWAP_BEHAVIOR, EGL_BUFFER_DESTROYED) !=
        EGL_TRUE) {
        LOG_CRITICAL(Frontend, "eglSurfaceAttrib() failed");
        return;
    }
    if (core_context = CreateSharedContext(); !core_context) {
        LOG_CRITICAL(Frontend, "CreateSharedContext() failed");
        return;
    }
    // The GL entry points are loaded once, on the window's context; the emulation thread then
    // works on the shared pbuffer context, and the UI thread presents on the window's.
    if (eglMakeCurrent(egl_display, egl_surface, egl_surface, egl_context) != EGL_TRUE) {
        LOG_CRITICAL(Frontend, "eglMakeCurrent() failed");
        return;
    }
    if (!gladLoadGLES2Loader((GLADloadproc)eglGetProcAddress)) {
        LOG_CRITICAL(Frontend, "gladLoadGLES2Loader() failed");
        return;
    }
    eglSwapInterval(egl_display, Settings::values.use_vsync.GetValue() ? 1 : 0);
    eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

EmuWindowGL::~EmuWindowGL() {
    DestroyWindowSurface();
    DestroyContext();
}

bool EmuWindowGL::CreateWindowSurface(ANativeWindow* surface) {
    if (!surface) {
        return false;
    }
    EGLint format{};
    eglGetConfigAttrib(egl_display, egl_config, EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(surface, 0, 0, format);
    if (egl_surface = eglCreateWindowSurface(egl_display, egl_config, surface, 0);
        egl_surface == EGL_NO_SURFACE) {
        LOG_CRITICAL(Frontend, "eglCreateWindowSurface() failed");
        return false;
    }
    return true;
}

void EmuWindowGL::DestroyWindowSurface() {
    if (!egl_surface) {
        return;
    }
    if (eglGetCurrentSurface(EGL_DRAW) == egl_surface) {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (!eglDestroySurface(egl_display, egl_surface)) {
        LOG_CRITICAL(Frontend, "eglDestroySurface() failed");
    }
    egl_surface = EGL_NO_SURFACE;
}

void EmuWindowGL::DestroyContext() {
    if (!egl_context) {
        return;
    }
    if (eglGetCurrentContext() == egl_context) {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (!eglDestroyContext(egl_display, egl_context)) {
        LOG_CRITICAL(Frontend, "eglDestroyContext() failed");
    }
    if (!eglTerminate(egl_display)) {
        LOG_CRITICAL(Frontend, "eglTerminate() failed");
    }
    egl_context = EGL_NO_CONTEXT;
    egl_display = EGL_NO_DISPLAY;
}

void EmuWindowGL::MakeCurrent() {
    if (core_context) {
        core_context->MakeCurrent();
    }
}

void EmuWindowGL::DoneCurrent() {
    if (core_context) {
        core_context->DoneCurrent();
    }
}

std::unique_ptr<Frontend::GraphicsContext> EmuWindowGL::CreateSharedContext() const {
    return std::make_unique<SharedContext_Android>(egl_display, egl_config, egl_context);
}

void EmuWindowGL::TryPresenting() {
    auto& system = Core::System::GetInstance();
    if (!system.IsPoweredOn()) {
        return;
    }
    if (presenting_state == PresentingState::Initial) [[unlikely]] {
        presenting_state = PresentingState::Running;
    }
    if (presenting_state != PresentingState::Running || !egl_surface) [[unlikely]] {
        return;
    }
    eglMakeCurrent(egl_display, egl_surface, egl_surface, egl_context);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    eglSwapInterval(egl_display, Settings::values.use_vsync.GetValue() ? 1 : 0);
    system.GPU().Renderer().TryPresent(0, false);
    eglSwapBuffers(egl_display, egl_surface);
}

void EmuWindowGL::StopPresenting() {
    if (presenting_state == PresentingState::Running) {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    presenting_state = PresentingState::Stopped;
}

void EmuWindowGL::PollEvents() {}

bool EmuWindowGL::OnSurfaceChanged(ANativeWindow* surface, int given_width, int given_height) {
    // The layout follows at once (the base does it), and so does the EGL surface: the activity
    // calls this, doFrame (TryPresenting) and surfaceDestroyed all on the UI thread, so there
    // is no one else halfway through a swap on the old surface.
    const bool changed = EmuWindow::OnSurfaceChanged(surface, given_width, given_height);
    if (!changed) {
        return false;
    }
    StopPresenting();
    DestroyWindowSurface();
    if (surface) {
        CreateWindowSurface(surface);
    }
    presenting_state = PresentingState::Initial;
    return true;
}

} // namespace Mh4uAndroid
