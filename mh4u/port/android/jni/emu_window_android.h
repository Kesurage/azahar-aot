// The game's window on Android: a Surface the Kotlin side hands over, drawn on by Azahar's
// Vulkan renderer (the system's Vulkan driver), or with the OpenGL (GLES) renderer through
// EmuWindowGL. Touches on the touch screen come in as window pixels. (Modelled on Azahar's
// own Android window; one window.)
#pragma once

#include <memory>

#include "core/frontend/emu_window.h"

struct ANativeWindow;

namespace Mh4uAndroid {

class EmuWindow : public Frontend::EmuWindow {
public:
    /// `width` and `height` are the surface's size as the activity knows it (0: ask the window).
    explicit EmuWindow(ANativeWindow* surface, int width = 0, int height = 0);
    ~EmuWindow() override = default;

    /// The surface changed (rotation, the app came back). False if nothing differs. The size is
    /// the one the activity was told (surfaceChanged): ANativeWindow_getWidth/Height can still
    /// give the old one for a while after a rotation, which laid the screens out for the
    /// wrong orientation (0: ask the window anyway).
    virtual bool OnSurfaceChanged(ANativeWindow* surface, int width = 0, int height = 0);

    /// Works the screen layout out again for the window as it is: the layout options changed.
    void Relayout() { Resize(); }

    void OnTouch(int x, int y, bool down);
    void OnTouchMoved(int x, int y);

    void PollEvents() override {}
    void MakeCurrent() override {}
    void DoneCurrent() override {}
    std::unique_ptr<GraphicsContext> CreateSharedContext() const override;

    /// Presentation driven from the UI thread's Choreographer (the OpenGL window presents the
    /// renderer's mailbox there); nothing to do for Vulkan, which presents on the emu thread.
    virtual void TryPresenting() {}
    virtual void StopPresenting() {}

    int Width() const { return width; }
    int Height() const { return height; }

private:
    void Resize();

protected:
    /// The context the emulation thread (and so the renderer) works on: the Vulkan window has
    /// none (its driver needs no context); the OpenGL window keeps a shared pbuffer here.
    std::unique_ptr<GraphicsContext> core_context;

    int width = 0;
    int height = 0;
};

} // namespace Mh4uAndroid
