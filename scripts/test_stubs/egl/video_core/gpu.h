#pragma once
#include "video_core/renderer_base.h"
namespace VideoCore {
class GPU {
public:
    RendererBase& Renderer() { return renderer; }
private:
    RendererBase renderer;
};
}
