#pragma once

#include <cassert>
#include <cstdlib>

#define ASSERT(condition) assert(condition)
#define UNREACHABLE() std::abort()
#define LOG_ERROR(category, ...) ((void)0)
