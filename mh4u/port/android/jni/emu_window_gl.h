// The game's window drawn on by the OpenGL (GLES) renderer: an EGL context and window surface
// for the presenting thread (the Choreographer's doFrame, on the UI thread), and a pbuffer
// context shared with the emulation thread, where the renderer draws into its mailbox. The
// Vulkan window has none of this: its driver works right off the window. Modelled on Azahar's
// own Android window (jni/emu_window/emu_window_gl.cpp); one window, no second screen.
#pragma once

#include <array>
#include <string>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include "emu_window_android.h"

namespace Mh4uAndroid {

class EmuWindowGL final : public EmuWindow {
public:
    /// `width` and `height` are the surface's size as the activity knows it (0: ask the window).
    explicit EmuWindowGL(ANativeWindow* surface, int width = 0, int height = 0);
    ~EmuWindowGL() override;

    /// True only after both contexts are usable and the renderer's GLES 3.2 minimum is met.
    bool Valid() const {
        return ready;
    }

    const std::string& Error() const {
        return error;
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
    void SetError(std::string message);
    void SetEglError(const char* operation);

    EGLConfig egl_config{};
    EGLSurface egl_surface{};
    EGLContext egl_context{};
    EGLDisplay egl_display{EGL_NO_DISPLAY};
    std::array<EGLint, 5> context_attributes{EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE,
                                             EGL_NONE, EGL_NONE};
    bool display_initialized = false;
    bool ready = false;
    std::string error;

    enum class PresentingState {
        Initial,
        Running,
        Stopped,
    };
    PresentingState presenting_state{PresentingState::Initial};
};

} // namespace Mh4uAndroid
