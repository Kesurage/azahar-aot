#pragma once
using GLenum = unsigned;
using GLuint = unsigned;
using GLint = int;
using GLubyte = unsigned char;
using GLADloadproc = void* (*)(const char*);
constexpr GLenum GL_VERSION = 0x1F02, GL_DRAW_FRAMEBUFFER = 0x8CA9;
constexpr GLenum GL_MAJOR_VERSION = 0x821B, GL_MINOR_VERSION = 0x821C;
extern int GLAD_GL_ES_VERSION_3_2;
int gladLoadGLES2Loader(GLADloadproc);
const GLubyte* glGetString(GLenum);
void glGetIntegerv(GLenum, GLint*);
void glBindFramebuffer(GLenum, GLuint);
