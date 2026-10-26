#pragma once

// A deliberately small EGL driver interface for compiling the real Android window on a host.
using EGLint = int;
using EGLBoolean = unsigned;
using EGLenum = unsigned;
using EGLDisplay = void*;
using EGLConfig = void*;
using EGLContext = void*;
using EGLSurface = void*;
using EGLNativeDisplayType = void*;
struct ANativeWindow;
using EGLNativeWindowType = ANativeWindow*;
using __eglMustCastToProperFunctionPointerType = void (*)();

constexpr EGLBoolean EGL_FALSE = 0, EGL_TRUE = 1;
constexpr EGLDisplay EGL_NO_DISPLAY = nullptr;
constexpr EGLContext EGL_NO_CONTEXT = nullptr;
constexpr EGLSurface EGL_NO_SURFACE = nullptr;
constexpr EGLNativeDisplayType EGL_DEFAULT_DISPLAY = nullptr;
constexpr EGLint EGL_SUCCESS = 0x3000, EGL_BAD_MATCH = 0x3009;
constexpr EGLint EGL_NONE = 0x3038, EGL_EXTENSIONS = 0x3055;
constexpr EGLint EGL_SURFACE_TYPE = 0x3033, EGL_WINDOW_BIT = 4, EGL_PBUFFER_BIT = 1;
constexpr EGLint EGL_RENDERABLE_TYPE = 0x3040;
constexpr EGLint EGL_RED_SIZE = 0x3024, EGL_GREEN_SIZE = 0x3023, EGL_BLUE_SIZE = 0x3022;
constexpr EGLint EGL_DEPTH_SIZE = 0x3025, EGL_STENCIL_SIZE = 0x3026;
constexpr EGLint EGL_WIDTH = 0x3057, EGL_HEIGHT = 0x3056;
constexpr EGLint EGL_CONTEXT_CLIENT_VERSION = 0x3098;
constexpr EGLint EGL_SWAP_BEHAVIOR = 0x3093, EGL_BUFFER_DESTROYED = 0x3095;
constexpr EGLint EGL_NATIVE_VISUAL_ID = 0x302E, EGL_DRAW = 0x3059;
constexpr EGLenum EGL_OPENGL_ES_API = 0x30A0;

EGLDisplay eglGetDisplay(EGLNativeDisplayType);
EGLBoolean eglInitialize(EGLDisplay, EGLint*, EGLint*);
EGLBoolean eglBindAPI(EGLenum);
const char* eglQueryString(EGLDisplay, EGLint);
EGLBoolean eglChooseConfig(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*);
EGLBoolean eglGetConfigAttrib(EGLDisplay, EGLConfig, EGLint, EGLint*);
EGLSurface eglCreateWindowSurface(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint*);
EGLSurface eglCreatePbufferSurface(EGLDisplay, EGLConfig, const EGLint*);
EGLContext eglCreateContext(EGLDisplay, EGLConfig, EGLContext, const EGLint*);
EGLBoolean eglSurfaceAttrib(EGLDisplay, EGLSurface, EGLint, EGLint);
EGLBoolean eglMakeCurrent(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
EGLBoolean eglSwapInterval(EGLDisplay, EGLint);
EGLBoolean eglSwapBuffers(EGLDisplay, EGLSurface);
EGLDisplay eglGetCurrentDisplay();
EGLContext eglGetCurrentContext();
EGLSurface eglGetCurrentSurface(EGLint);
EGLBoolean eglDestroySurface(EGLDisplay, EGLSurface);
EGLBoolean eglDestroyContext(EGLDisplay, EGLContext);
EGLBoolean eglTerminate(EGLDisplay);
EGLint eglGetError();
__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char*);
