// The game's window drawn on by the OpenGL (GLES) renderer: an EGL context and window surface
// for the presenting thread (the Choreographer's doFrame, on the UI thread), and a pbuffer
// context shared with the emulation thread, where the renderer draws into its mailbox. The
// Vulkan window has none of this: its driver works right off the window. Modelled on Azahar's
// own Android window (jni/emu_window/emu_window_gl.cpp); one window, no second screen.
#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include "emu_window_android.h"

namespace Mh4uAndroid {

class EmuWindowGL final : public EmuWindow {
public:
    /// `width` and `height` are the surface's size as the activity knows it (0: ask the window).
    explicit EmuWindowGL(ANativeWindow* surface, int width = 0, int height = 0);
    ~EmuWindowGL() override;

    /// False when the EGL display, config or context could not be made: the caller falls back
    /// to the Vulkan window, so the game still runs.
    bool Valid() const {
        return egl_display != EGL_NO_DISPLAY && egl_context != EGL_NO_CONTEXT && core_context;
    }

    void MakeCurrent() override;
    void DoneCurrent() override;
    std::unique_ptr<GraphicsContext> CreateSharedContext() const override;

    void TryPresenting() override;
    void StopPresenting() override;
    void PollEvents() override;
    bool OnSurfaceChanged(ANativeWindow* surface, int width = 0, int height = 0) override;

private:
    bool CreateWindowSurface(ANativeWindow* surface);
    void DestroyWindowSurface();
    void DestroyContext();

    EGLConfig egl_config{};
    EGLSurface egl_surface{};
    EGLContext egl_context{};
    EGLDisplay egl_display{EGL_NO_DISPLAY};

    enum class PresentingState {
        Initial,
        Running,
        Stopped,
    };
    PresentingState presenting_state{PresentingState::Initial};
};

} // namespace Mh4uAndroid
