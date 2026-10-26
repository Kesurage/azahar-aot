#pragma once
#include "video_core/gpu.h"
namespace Core {
class System {
public:
    static System& GetInstance() { static System system; return system; }
    bool IsPoweredOn() const { return powered_on; }
    VideoCore::GPU& GPU() { return gpu; }
    bool powered_on = true;
private:
    VideoCore::GPU gpu;
};
}
