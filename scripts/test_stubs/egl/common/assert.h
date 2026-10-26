#pragma once
#include <stdexcept>
#define ASSERT_MSG(condition, ...) \
    do { if (!(condition)) throw std::runtime_error("unexpected renderer assertion"); } while (false)
