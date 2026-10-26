// Test the production EGL window, not a copy of its initialization logic. The fake driver
// rejects invalid lifetime operations and can fail every fallible startup stage independently.
#include <cassert>
#include <cstdint>
#include <iostream>
#include <map>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include <android/native_window.h>
#include <glad/glad.h>
#include "emu_window_gl.h"

namespace {
enum class Failure {
    None, Display, Initialize, BindApi, ChooseConfig, EmptyConfig, ConfigFormat, BufferGeometry,
    WindowSurface, MainContext, SurfaceAttrib, MainCurrent, Loader, Pbuffer, SharedContext,
    SharedCurrent, Release,
};

struct Driver {
    Failure failure = Failure::None;
    int egl_major = 1, egl_minor = 5;
    const char* extensions = "";
    bool gles32 = true;
    bool shared_gles32 = true;
    std::string version = "OpenGL ES 3.2 test driver";
    bool initialized = false;
    int initialized_count = 0, terminated_count = 0;
    int load_count = 0, bind_framebuffer_count = 0, pbuffer_attempts = 0;
    int release_attempts = 0;
    EGLint error = EGL_SUCCESS;
    EGLContext main_context = EGL_NO_CONTEXT, current_context = EGL_NO_CONTEXT;
    EGLSurface current_surface = EGL_NO_SURFACE;
    std::uintptr_t next_handle = 10;
    std::set<EGLContext> contexts;
    std::set<EGLSurface> surfaces;
    std::vector<std::pair<int, int>> requested_versions;

    void* Handle() { return reinterpret_cast<void*>(next_handle++); }
    bool Fail(Failure stage) {
        if (failure != stage) return false;
        error = EGL_BAD_MATCH;
        return true;
    }
} driver;

const EGLDisplay display = reinterpret_cast<void*>(1);
const EGLConfig config = reinterpret_cast<void*>(2);
ANativeWindow surface;

void RequireDisplay(EGLDisplay value) {
    assert(value == display);
    assert(driver.initialized);
}

std::map<EGLint, EGLint> Attributes(const EGLint* values) {
    std::map<EGLint, EGLint> result;
    for (int i = 0; values[i] != EGL_NONE; i += 2) {
        assert(i < 30);
        result[values[i]] = values[i + 1];
    }
    return result;
}

void AssertClean() {
    assert(driver.contexts.empty());
    assert(driver.surfaces.empty());
    assert(driver.current_context == EGL_NO_CONTEXT);
    assert(!driver.initialized);
    assert(driver.terminated_count == driver.initialized_count);
}

void CheckSuccess(int requested_minor) {
    {
        Mh4uAndroid::EmuWindowGL window(&surface);
        assert(window.Valid());
        assert(window.Error().empty());
        assert(driver.current_context == EGL_NO_CONTEXT);
        window.MakeCurrent();
        assert(driver.current_context != EGL_NO_CONTEXT);
        assert(driver.current_context != driver.main_context);
        window.DoneCurrent();
        {
            auto worker = window.CreateSharedContext();
            assert(worker);
            worker->MakeCurrent();
            // Destroying a current shared context must release it before destruction.
        }
        assert(driver.current_context == EGL_NO_CONTEXT);
        assert(driver.requested_versions.size() == 3);
        for (const auto& version : driver.requested_versions) {
            assert(version.first == 3 && version.second == requested_minor);
        }
        window.TryPresenting();
        assert(driver.bind_framebuffer_count == 1);
        // Leave the main context current: the window must clean that path up as well.
    }
    AssertClean();
}
} // namespace

int GLAD_GL_ES_VERSION_3_2 = 0;

EGLDisplay eglGetDisplay(EGLNativeDisplayType) {
    return driver.Fail(Failure::Display) ? EGL_NO_DISPLAY : display;
}
EGLBoolean eglInitialize(EGLDisplay value, EGLint* major, EGLint* minor) {
    assert(value == display);
    if (driver.Fail(Failure::Initialize)) return EGL_FALSE;
    driver.initialized = true;
    ++driver.initialized_count;
    if (major) *major = driver.egl_major;
    if (minor) *minor = driver.egl_minor;
    return EGL_TRUE;
}
EGLBoolean eglBindAPI(EGLenum api) {
    assert(api == EGL_OPENGL_ES_API);
    return driver.Fail(Failure::BindApi) ? EGL_FALSE : EGL_TRUE;
}
const char* eglQueryString(EGLDisplay value, EGLint name) {
    RequireDisplay(value);
    assert(name == EGL_EXTENSIONS);
    return driver.extensions;
}
EGLBoolean eglChooseConfig(EGLDisplay value, const EGLint* attrs, EGLConfig* out,
                           EGLint capacity, EGLint* count) {
    RequireDisplay(value);
    assert(capacity == 1);
    const auto requested = Attributes(attrs);
    assert(requested.at(EGL_SURFACE_TYPE) == (EGL_WINDOW_BIT | EGL_PBUFFER_BIT));
    assert(requested.at(EGL_RENDERABLE_TYPE) == EGL_OPENGL_ES3_BIT_KHR);
    if (driver.Fail(Failure::ChooseConfig)) return EGL_FALSE;
    *count = driver.failure == Failure::EmptyConfig ? 0 : 1;
    if (*count) *out = config;
    return EGL_TRUE;
}
EGLBoolean eglGetConfigAttrib(EGLDisplay value, EGLConfig selected, EGLint name, EGLint* out) {
    RequireDisplay(value);
    assert(selected == config && name == EGL_NATIVE_VISUAL_ID);
    if (driver.Fail(Failure::ConfigFormat)) return EGL_FALSE;
    *out = 1;
    return EGL_TRUE;
}
int ANativeWindow_setBuffersGeometry(ANativeWindow* value, int, int, int) {
    assert(value == &surface);
    return driver.failure == Failure::BufferGeometry ? -1 : 0;
}
EGLSurface eglCreateWindowSurface(EGLDisplay value, EGLConfig selected, EGLNativeWindowType window,
                                  const EGLint*) {
    RequireDisplay(value);
    assert(selected == config && window == &surface);
    if (driver.Fail(Failure::WindowSurface)) return EGL_NO_SURFACE;
    const auto result = driver.Handle();
    driver.surfaces.insert(result);
    return result;
}
EGLSurface eglCreatePbufferSurface(EGLDisplay value, EGLConfig selected, const EGLint*) {
    RequireDisplay(value);
    assert(selected == config);
    ++driver.pbuffer_attempts;
    if (driver.Fail(Failure::Pbuffer)) return EGL_NO_SURFACE;
    const auto result = driver.Handle();
    driver.surfaces.insert(result);
    return result;
}
EGLContext eglCreateContext(EGLDisplay value, EGLConfig selected, EGLContext share,
                            const EGLint* attrs) {
    RequireDisplay(value);
    assert(selected == config);
    auto requested = Attributes(attrs);
    driver.requested_versions.emplace_back(requested.at(EGL_CONTEXT_CLIENT_VERSION),
                                           requested[EGL_CONTEXT_MINOR_VERSION_KHR]);
    if (share) assert(driver.contexts.contains(share));
    if (driver.Fail(share ? Failure::SharedContext : Failure::MainContext)) return EGL_NO_CONTEXT;
    const auto result = driver.Handle();
    driver.contexts.insert(result);
    if (!share) driver.main_context = result;
    return result;
}
EGLBoolean eglSurfaceAttrib(EGLDisplay value, EGLSurface target, EGLint, EGLint) {
    RequireDisplay(value);
    assert(driver.surfaces.contains(target));
    return driver.Fail(Failure::SurfaceAttrib) ? EGL_FALSE : EGL_TRUE;
}
EGLBoolean eglMakeCurrent(EGLDisplay value, EGLSurface draw, EGLSurface read, EGLContext context) {
    RequireDisplay(value);
    assert(draw == read);
    if (!context) {
        assert(!draw);
        if (++driver.release_attempts == 1 && driver.Fail(Failure::Release)) return EGL_FALSE;
    } else {
        assert(driver.contexts.contains(context) && driver.surfaces.contains(draw));
        if (driver.Fail(context == driver.main_context ? Failure::MainCurrent : Failure::SharedCurrent)) {
            return EGL_FALSE;
        }
    }
    driver.current_context = context;
    driver.current_surface = draw;
    return EGL_TRUE;
}
EGLBoolean eglSwapInterval(EGLDisplay value, EGLint) {
    RequireDisplay(value);
    assert(driver.current_context);
    return EGL_TRUE;
}
EGLBoolean eglSwapBuffers(EGLDisplay value, EGLSurface target) {
    RequireDisplay(value);
    assert(target == driver.current_surface);
    return EGL_TRUE;
}
EGLDisplay eglGetCurrentDisplay() { return driver.current_context ? display : EGL_NO_DISPLAY; }
EGLContext eglGetCurrentContext() { return driver.current_context; }
EGLSurface eglGetCurrentSurface(EGLint) { return driver.current_surface; }
EGLBoolean eglDestroySurface(EGLDisplay value, EGLSurface target) {
    RequireDisplay(value);
    assert(target != driver.current_surface);
    assert(driver.surfaces.erase(target) == 1);
    return EGL_TRUE;
}
EGLBoolean eglDestroyContext(EGLDisplay value, EGLContext target) {
    RequireDisplay(value);
    assert(target != driver.current_context);
    assert(driver.contexts.erase(target) == 1);
    return EGL_TRUE;
}
EGLBoolean eglTerminate(EGLDisplay value) {
    RequireDisplay(value);
    // No EGL child objects may be destroyed later by a base/member destructor.
    assert(driver.contexts.empty() && driver.surfaces.empty());
    driver.initialized = false;
    ++driver.terminated_count;
    return EGL_TRUE;
}
EGLint eglGetError() {
    return std::exchange(driver.error, EGL_SUCCESS);
}
__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char*) { return nullptr; }
int gladLoadGLES2Loader(GLADloadproc) {
    assert(driver.current_context);
    ++driver.load_count;
    if (driver.failure == Failure::Loader) return 0;
    GLAD_GL_ES_VERSION_3_2 = driver.gles32;
    return 1;
}
const GLubyte* glGetString(GLenum name) {
    assert(name == GL_VERSION && driver.current_context);
    return reinterpret_cast<const GLubyte*>(driver.version.c_str());
}
void glGetIntegerv(GLenum name, GLint* value) {
    assert(driver.current_context && driver.current_context != driver.main_context);
    assert(name == GL_MAJOR_VERSION || name == GL_MINOR_VERSION);
    *value = name == GL_MAJOR_VERSION ? 3 : (driver.shared_gles32 ? 2 : 0);
}
void glBindFramebuffer(GLenum target, GLuint framebuffer) {
    assert(target == GL_DRAW_FRAMEBUFFER && framebuffer == 0);
    assert(driver.current_context && driver.gles32);
    ++driver.bind_framebuffer_count;
}

int main() {
    for (const auto failure : {Failure::Display, Failure::Initialize, Failure::BindApi,
                              Failure::ChooseConfig, Failure::EmptyConfig, Failure::ConfigFormat,
                              Failure::BufferGeometry, Failure::WindowSurface, Failure::MainContext,
                              Failure::SurfaceAttrib, Failure::MainCurrent, Failure::Loader,
                              Failure::Pbuffer, Failure::SharedContext, Failure::SharedCurrent,
                              Failure::Release}) {
        driver = {};
        driver.failure = failure;
        {
            Mh4uAndroid::EmuWindowGL window(&surface);
            assert(!window.Valid());
            assert(!window.Error().empty());
            window.TryPresenting();
            assert(driver.bind_framebuffer_count == 0);
        }
        AssertClean();
    }

    for (const auto* version : {"OpenGL ES 3.0 test driver", "OpenGL ES 3.1 test driver"}) {
        driver = {};
        driver.gles32 = false;
        driver.version = version;
        // A lower context must be rejected even if EGL incorrectly accepts the 3.2 request.
        GLAD_GL_ES_VERSION_3_2 = 1;
        {
            Mh4uAndroid::EmuWindowGL window(&surface);
            assert(!window.Valid());
            assert(window.Error().find(version) != std::string::npos);
            assert(window.Error().find("3.2 is required") != std::string::npos);
            assert(driver.load_count == 1 && driver.pbuffer_attempts == 0);
        }
        AssertClean();
    }

    driver = {};
    CheckSuccess(2); // EGL 1.5 core minor-version attributes.
    driver = {};
    driver.egl_minor = 4;
    driver.shared_gles32 = false;
    {
        Mh4uAndroid::EmuWindowGL window(&surface);
        assert(!window.Valid());
        assert(window.Error().find("shared renderer context") != std::string::npos);
    }
    AssertClean();
    driver = {};
    {
        Mh4uAndroid::EmuWindowGL window(&surface);
        assert(window.Valid());
        window.MakeCurrent();
        // The inherited core_context can still be current when the derived destructor runs.
    }
    AssertClean();
    driver = {};
    driver.egl_minor = 4;
    driver.extensions = "EGL_other EGL_KHR_create_context EGL_another";
    CheckSuccess(2); // EGL 1.4 extension path.
    for (const auto* extensions : {"", "EGL_KHR_create_context_extra", "X_EGL_KHR_create_context",
                                   static_cast<const char*>(nullptr)}) {
        driver = {};
        driver.egl_minor = 4;
        driver.extensions = extensions;
        CheckSuccess(0); // Legacy major-only request; actual GL still must be >= 3.2.
    }
    driver = {};
    driver.egl_minor = 4;
    driver.gles32 = false;
    {
        Mh4uAndroid::EmuWindowGL window(&surface);
        assert(!window.Valid());
        assert(driver.requested_versions.at(0).second == 0);
        assert(driver.pbuffer_attempts == 0);
    }
    AssertClean();
    driver = {};
    {
        Mh4uAndroid::EmuWindowGL window(nullptr);
        assert(!window.Valid() && !window.Error().empty());
    }
    AssertClean();
    std::cout << "EGL startup: failure matrix, GLES minimum, version requests, and teardown passed\n";
}
