#include "emu_window_gl.h"

#include <array>
#include <sstream>
#include <string_view>
#include <utility>

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
                                             EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
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

bool HasExtension(const char* extensions, std::string_view name) {
    if (!extensions) {
        return false;
    }
    const std::string_view list{extensions};
    for (std::size_t pos = 0; (pos = list.find(name, pos)) != std::string_view::npos; ++pos) {
        const auto end = pos + name.size();
        if ((pos == 0 || list[pos - 1] == ' ') && (end == list.size() || list[end] == ' ')) {
            return true;
        }
    }
    return false;
}

std::string EglError(const char* operation, EGLint code) {
    std::ostringstream message;
    message << "OpenGL ES 3.2 could not start: " << operation << " failed (EGL 0x" << std::hex
            << code << "). Choose Vulkan in Display settings and try again.";
    return message.str();
}

/// A context for the renderer's worker threads (the disk shader cache and friends): its own
/// pbuffer surface, sharing with the window's context.
class SharedContext_Android final : public Frontend::GraphicsContext {
public:
    SharedContext_Android(EGLDisplay egl_display, EGLConfig egl_config,
                          EGLContext egl_share_context, const EGLint* context_attributes)
        : egl_display{egl_display} {
        egl_surface = eglCreatePbufferSurface(egl_display, egl_config, egl_empty_attribs.data());
        if (egl_surface == EGL_NO_SURFACE) {
            error = EglError("eglCreatePbufferSurface", eglGetError());
            return;
        }
        egl_context = eglCreateContext(egl_display, egl_config, egl_share_context, context_attributes);
        if (egl_context == EGL_NO_CONTEXT) {
            error = EglError("eglCreateContext (shared)", eglGetError());
        }
    }

    ~SharedContext_Android() override {
        if (egl_context != EGL_NO_CONTEXT && eglGetCurrentDisplay() == egl_display &&
            eglGetCurrentContext() == egl_context) {
            DoneCurrent();
        }
        if (egl_surface != EGL_NO_SURFACE && !eglDestroySurface(egl_display, egl_surface)) {
            LOG_CRITICAL(Frontend, "eglDestroySurface() failed");
        }
        if (egl_context != EGL_NO_CONTEXT && !eglDestroyContext(egl_display, egl_context)) {
            LOG_CRITICAL(Frontend, "eglDestroyContext() failed");
        }
    }

    bool Valid() const {
        return egl_surface != EGL_NO_SURFACE && egl_context != EGL_NO_CONTEXT;
    }

    const std::string& Error() const {
        return error;
    }

    bool TryMakeCurrent() {
        return eglMakeCurrent(egl_display, egl_surface, egl_surface, egl_context) == EGL_TRUE;
    }

    void MakeCurrent() override {
        TryMakeCurrent();
    }

    void DoneCurrent() override {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }

private:
    EGLDisplay egl_display{};
    EGLSurface egl_surface{};
    EGLContext egl_context{};
    std::string error;
};

} // namespace

EmuWindowGL::EmuWindowGL(ANativeWindow* surface, int given_width, int given_height)
    : EmuWindow(surface, given_width, given_height) {
    if (egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY); egl_display == EGL_NO_DISPLAY) {
        SetEglError("eglGetDisplay");
        return;
    }
    EGLint major{}, minor{};
    if (eglInitialize(egl_display, &major, &minor) != EGL_TRUE) {
        SetEglError("eglInitialize");
        return;
    }
    display_initialized = true;
    if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
        SetEglError("eglBindAPI");
        return;
    }
    // EGL 1.5 and KHR_create_context accept a minor version. Older EGL may return a newer
    // context for the legacy ES 3 request; it must pass the same actual-version check below.
    if (major > 1 || (major == 1 && minor >= 5) ||
        HasExtension(eglQueryString(egl_display, EGL_EXTENSIONS), "EGL_KHR_create_context")) {
        context_attributes = {EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR,
                              2, EGL_NONE};
    }
    if (EGLint egl_num_configs{};
        eglChooseConfig(egl_display, egl_attribs.data(), &egl_config, 1, &egl_num_configs) !=
        EGL_TRUE) {
        SetEglError("eglChooseConfig");
        return;
    } else if (egl_num_configs == 0) {
        SetError("OpenGL ES 3.2 could not start: no EGL configuration supports both window and "
                 "pbuffer surfaces. Choose Vulkan in Display settings and try again.");
        return;
    }

    if (!CreateWindowSurface(surface)) {
        return;
    }

    if (egl_context = eglCreateContext(egl_display, egl_config, EGL_NO_CONTEXT,
                                       context_attributes.data());
        egl_context == EGL_NO_CONTEXT) {
        SetEglError("eglCreateContext (GLES 3.2 required)");
        return;
    }
    if (eglSurfaceAttrib(egl_display, egl_surface, EGL_SWAP_BEHAVIOR, EGL_BUFFER_DESTROYED) !=
        EGL_TRUE) {
        SetEglError("eglSurfaceAttrib");
        return;
    }
    // The GL entry points are loaded once, on the window's context; the emulation thread then
    // works on the shared pbuffer context, and the UI thread presents on the window's.
    if (eglMakeCurrent(egl_display, egl_surface, egl_surface, egl_context) != EGL_TRUE) {
        SetEglError("eglMakeCurrent");
        return;
    }
    if (!gladLoadGLES2Loader((GLADloadproc)eglGetProcAddress)) {
        SetError("OpenGL ES 3.2 could not start: GL entry points could not be loaded. "
                 "Choose Vulkan in Display settings and try again.");
        return;
    }
    if (!GLAD_GL_ES_VERSION_3_2) {
        const auto* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
        SetError(std::string{"OpenGL ES 3.2 is required, but this device returned "} +
                 (version ? version : "an unknown GL version") +
                 ". Choose Vulkan in Display settings and try again.");
        return;
    }
    eglSwapInterval(egl_display, Settings::values.use_vsync.GetValue() ? 1 : 0);

    // Check the initial renderer context without asserting: unsupported or failed driver
    // initialization must return a useful error before system.Load constructs any shaders.
    auto shared = std::make_unique<SharedContext_Android>(egl_display, egl_config, egl_context,
                                                        context_attributes.data());
    if (!shared->Valid()) {
        SetError(shared->Error());
        return;
    }
    if (!shared->TryMakeCurrent()) {
        SetEglError("eglMakeCurrent (shared)");
        return;
    }
    // In the legacy major-only request path, verify the context used by the renderer too.
    GLint shared_major{}, shared_minor{};
    glGetIntegerv(GL_MAJOR_VERSION, &shared_major);
    glGetIntegerv(GL_MINOR_VERSION, &shared_minor);
    if (shared_major < 3 || (shared_major == 3 && shared_minor < 2)) {
        SetError("OpenGL ES 3.2 is required, but the shared renderer context does not support it. "
                 "Choose Vulkan in Display settings and try again.");
        return;
    }
    if (eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) != EGL_TRUE) {
        SetEglError("eglMakeCurrent (release)");
        return;
    }
    core_context = std::move(shared);
    ready = true;
}

EmuWindowGL::~EmuWindowGL() {
    ready = false;
    // This base-owned context must be destroyed while its EGL display is still initialized.
    core_context.reset();
    DestroyWindowSurface();
    DestroyContext();
}

void EmuWindowGL::SetError(std::string message) {
    error = std::move(message);
    LOG_CRITICAL(Frontend, "{}", error);
}

void EmuWindowGL::SetEglError(const char* operation) {
    SetError(EglError(operation, eglGetError()));
}

bool EmuWindowGL::CreateWindowSurface(ANativeWindow* surface) {
    if (!surface) {
        SetError("OpenGL ES 3.2 could not start: the Android drawing surface is unavailable.");
        return false;
    }
    EGLint format{};
    if (eglGetConfigAttrib(egl_display, egl_config, EGL_NATIVE_VISUAL_ID, &format) != EGL_TRUE) {
        SetEglError("eglGetConfigAttrib");
        return false;
    }
    if (ANativeWindow_setBuffersGeometry(surface, 0, 0, format) != 0) {
        SetError("OpenGL ES 3.2 could not start: the Android surface format could not be set.");
        return false;
    }
    if (egl_surface = eglCreateWindowSurface(egl_display, egl_config, surface, 0);
        egl_surface == EGL_NO_SURFACE) {
        SetEglError("eglCreateWindowSurface");
        return false;
    }
    return true;
}

void EmuWindowGL::DestroyWindowSurface() {
    if (!egl_surface) {
        return;
    }
    if (eglGetCurrentDisplay() == egl_display && eglGetCurrentSurface(EGL_DRAW) == egl_surface) {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (!eglDestroySurface(egl_display, egl_surface)) {
        LOG_CRITICAL(Frontend, "eglDestroySurface() failed");
    }
    egl_surface = EGL_NO_SURFACE;
}

void EmuWindowGL::DestroyContext() {
    if (egl_context != EGL_NO_CONTEXT) {
        if (eglGetCurrentDisplay() == egl_display && eglGetCurrentContext() == egl_context) {
            eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (!eglDestroyContext(egl_display, egl_context)) {
            LOG_CRITICAL(Frontend, "eglDestroyContext() failed");
        }
        egl_context = EGL_NO_CONTEXT;
    }
    if (display_initialized && !eglTerminate(egl_display)) {
        LOG_CRITICAL(Frontend, "eglTerminate() failed");
    }
    display_initialized = false;
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
    auto context = std::make_unique<SharedContext_Android>(egl_display, egl_config, egl_context,
                                                         context_attributes.data());
    // The core's worker-context callers require a non-null usable context. Keep their existing
    // assertion contract; the initial context above is checked and reported without asserting.
    ASSERT_MSG(context->Valid(), "{}", context->Error());
    return context;
}

void EmuWindowGL::TryPresenting() {
    auto& system = Core::System::GetInstance();
    if (!ready || !system.IsPoweredOn()) {
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
