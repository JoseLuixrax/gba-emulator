// The few GL calls the 3DS host makes itself: the framebuffer the core draws
// into and the quad that shows it. Functions come through eglGetProcAddress
// so the same code runs on GLES (Android) and desktop GL (the Linux test).
#ifndef N3DS_GL_H
#define N3DS_GL_H

#include <cstdint>

namespace n3ds {

// Looks a GL function up; also what the core gets as get_proc_address.
void* glProc(const char* name);

// Loads the functions below; call with any context current.
bool glLoad();

// A colour texture + framebuffer object, created in the *core's* context
// (framebuffers aren't shared between contexts, textures are).
struct Target {
	uint32_t fbo = 0;
	uint32_t texture = 0;
	unsigned width = 0;
	unsigned height = 0;
};
bool targetResize(Target& target, unsigned width, unsigned height);
void targetDestroy(Target& target);

// Draws the used part (frameWidth x frameHeight, bottom-left origin) of
// `texture` into the rectangle (x, y, w, h) of a surfaceWidth x surfaceHeight
// window, origin top-left, everything else black. Present context only.
bool presenterInit();
void presenterDestroy();
void present(uint32_t texture, unsigned textureWidth, unsigned textureHeight, unsigned frameWidth,
             unsigned frameHeight, int x, int y, int w, int h, int surfaceWidth, int surfaceHeight);

// The core's context finishes its frame with a fence that the present
// context waits for: commands of two contexts are otherwise unordered.
void* fenceAfterFrame();
void fenceWait(void* fence);

// For tests: reads the window's pixel at (x, y) from the top-left, as 0xRRGGBB.
uint32_t readPixel(int x, int y, int surfaceHeight);

} // namespace n3ds

#endif
