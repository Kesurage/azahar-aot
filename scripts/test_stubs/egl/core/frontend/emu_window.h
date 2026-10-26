#pragma once
#include <memory>
namespace Frontend {
class GraphicsContext {
public:
    virtual ~GraphicsContext() = default;
    virtual void MakeCurrent() {}
    virtual void DoneCurrent() {}
};
enum class WindowSystemType { Android };
class EmuWindow {
public:
    using GraphicsContext = Frontend::GraphicsContext;
    explicit EmuWindow(bool) {}
    virtual ~EmuWindow() = default;
    virtual void PollEvents() {}
    virtual void MakeCurrent() {}
    virtual void DoneCurrent() {}
    virtual std::unique_ptr<GraphicsContext> CreateSharedContext() const { return {}; }
    void UpdateCurrentFramebufferLayout(unsigned, unsigned, bool) {}
    void TouchPressed(unsigned, unsigned) {}
    void TouchReleased() {}
    void TouchMoved(unsigned, unsigned) {}
protected:
    struct {
        WindowSystemType type{};
        void* render_surface = nullptr;
        float render_surface_scale = 1.0f;
    } window_info;
};
}
