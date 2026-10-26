#pragma once
namespace Settings {
struct Vsync { bool GetValue() const { return true; } };
struct Values { Vsync use_vsync; };
inline Values values;
}
