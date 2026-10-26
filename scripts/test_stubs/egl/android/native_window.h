#pragma once
struct ANativeWindow {};
inline int ANativeWindow_getWidth(ANativeWindow*) { return 1280; }
inline int ANativeWindow_getHeight(ANativeWindow*) { return 720; }
int ANativeWindow_setBuffersGeometry(ANativeWindow*, int, int, int);
