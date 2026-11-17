#include "n3ds_gl.h"

#include <EGL/egl.h>
#include <dlfcn.h>

#include <cstdio>

#ifdef __ANDROID__
#include <android/log.h>
#define GL_LOG(...) __android_log_print(ANDROID_LOG_WARN, "n3ds", __VA_ARGS__)
#else
#define GL_LOG(...) fprintf(stderr, __VA_ARGS__)
#endif

// Only the handful of GL ES 3.0 constants used here.
#define GL_TEXTURE_2D 0x0DE1
#define GL_TEXTURE0 0x84C0
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_LINEAR 0x2601
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_RGBA 0x1908
#define GL_RGBA8 0x8058
#define GL_UNSIGNED_BYTE 0x1401
#define GL_FRAMEBUFFER 0x8D40
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82
#define GL_COLOR_BUFFER_BIT 0x4000
#define GL_TRIANGLE_STRIP 0x0005
#define GL_BLEND 0x0BE2
#define GL_DEPTH_TEST 0x0B71
#define GL_SCISSOR_TEST 0x0C11
#define GL_STENCIL_TEST 0x0B90
#define GL_CULL_FACE 0x0B44

namespace n3ds {

namespace {

typedef unsigned int GLenum;
typedef unsigned int GLuint;
typedef int GLint;
typedef int GLsizei;
typedef float GLfloat;
typedef char GLchar;
typedef unsigned int GLbitfield;

struct Functions {
	void (*GenFramebuffers)(GLsizei, GLuint*);
	void (*DeleteFramebuffers)(GLsizei, const GLuint*);
	void (*BindFramebuffer)(GLenum, GLuint);
	void (*FramebufferTexture2D)(GLenum, GLenum, GLenum, GLuint, GLint);
	GLenum (*CheckFramebufferStatus)(GLenum);
	void (*GenTextures)(GLsizei, GLuint*);
	void (*DeleteTextures)(GLsizei, const GLuint*);
	void (*BindTexture)(GLenum, GLuint);
	void (*TexImage2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void*);
	void (*TexParameteri)(GLenum, GLenum, GLint);
	void (*ActiveTexture)(GLenum);
	void (*Viewport)(GLint, GLint, GLsizei, GLsizei);
	void (*ClearColor)(GLfloat, GLfloat, GLfloat, GLfloat);
	void (*Clear)(GLbitfield);
	void (*Disable)(GLenum);
	GLuint (*CreateShader)(GLenum);
	void (*ShaderSource)(GLuint, GLsizei, const GLchar* const*, const GLint*);
	void (*CompileShader)(GLuint);
	void (*GetShaderiv)(GLuint, GLenum, GLint*);
	void (*GetShaderInfoLog)(GLuint, GLsizei, GLsizei*, GLchar*);
	void (*DeleteShader)(GLuint);
	GLuint (*CreateProgram)();
	void (*AttachShader)(GLuint, GLuint);
	void (*LinkProgram)(GLuint);
	void (*GetProgramiv)(GLuint, GLenum, GLint*);
	void (*DeleteProgram)(GLuint);
	void (*UseProgram)(GLuint);
	GLint (*GetUniformLocation)(GLuint, const GLchar*);
	void (*Uniform1i)(GLint, GLint);
	void (*Uniform4f)(GLint, GLfloat, GLfloat, GLfloat, GLfloat);
	void (*GenVertexArrays)(GLsizei, GLuint*);
	void (*DeleteVertexArrays)(GLsizei, const GLuint*);
	void (*BindVertexArray)(GLuint);
	void (*DrawArrays)(GLenum, GLint, GLsizei);
	void (*ReadPixels)(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void*);
	void* (*FenceSync)(GLenum, GLbitfield);
	void (*WaitSync)(void*, GLbitfield, uint64_t);
	void (*DeleteSync)(void*);
	void (*Flush)();
};

Functions gl;
bool loaded = false;

GLuint program = 0;
GLuint vao = 0;
GLint dstLocation = -1;
GLint srcLocation = -1;

const char* VERTEX = R"(#version 300 es
uniform vec4 uDst;
uniform vec4 uSrc;
out vec2 vUv;
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    gl_Position = vec4(mix(uDst.xy, uDst.zw, corner), 0.0, 1.0);
    vUv = mix(uSrc.xy, uSrc.zw, corner);
}
)";

const char* FRAGMENT = R"(#version 300 es
precision mediump float;
uniform sampler2D uTexture;
in vec2 vUv;
out vec4 color;
void main() {
    color = vec4(texture(uTexture, vUv).rgb, 1.0);
}
)";

GLuint compile(GLenum type, const char* source) {
	GLuint shader = gl.CreateShader(type);
	gl.ShaderSource(shader, 1, &source, nullptr);
	gl.CompileShader(shader);
	GLint ok = 0;
	gl.GetShaderiv(shader, GL_COMPILE_STATUS, &ok);
	if (!ok) {
		char log[512] = {0};
		gl.GetShaderInfoLog(shader, sizeof(log) - 1, nullptr, log);
		GL_LOG("n3ds: shader failed: %s\n", log);
		gl.DeleteShader(shader);
		return 0;
	}
	return shader;
}

template <typename T>
bool load(T& slot, const char* name) {
	slot = reinterpret_cast<T>(glProc(name));
	if (!slot) {
		GL_LOG("n3ds: missing GL function %s\n", name);
	}
	return slot != nullptr;
}

} // namespace

void* glProc(const char* name) {
	void* fn = reinterpret_cast<void*>(eglGetProcAddress(name));
	if (fn) {
		return fn;
	}
	// Older EGL implementations only return extension functions.
#ifdef __ANDROID__
	static void* lib = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
#else
	static void* lib = dlopen("libGLESv2.so.2", RTLD_NOW | RTLD_LOCAL);
#endif
	return lib ? dlsym(lib, name) : nullptr;
}

bool glLoad() {
	if (loaded) {
		return true;
	}
	bool ok = true;
	ok &= load(gl.GenFramebuffers, "glGenFramebuffers");
	ok &= load(gl.DeleteFramebuffers, "glDeleteFramebuffers");
	ok &= load(gl.BindFramebuffer, "glBindFramebuffer");
	ok &= load(gl.FramebufferTexture2D, "glFramebufferTexture2D");
	ok &= load(gl.CheckFramebufferStatus, "glCheckFramebufferStatus");
	ok &= load(gl.GenTextures, "glGenTextures");
	ok &= load(gl.DeleteTextures, "glDeleteTextures");
	ok &= load(gl.BindTexture, "glBindTexture");
	ok &= load(gl.TexImage2D, "glTexImage2D");
	ok &= load(gl.TexParameteri, "glTexParameteri");
	ok &= load(gl.ActiveTexture, "glActiveTexture");
	ok &= load(gl.Viewport, "glViewport");
	ok &= load(gl.ClearColor, "glClearColor");
	ok &= load(gl.Clear, "glClear");
	ok &= load(gl.Disable, "glDisable");
	ok &= load(gl.CreateShader, "glCreateShader");
	ok &= load(gl.ShaderSource, "glShaderSource");
	ok &= load(gl.CompileShader, "glCompileShader");
	ok &= load(gl.GetShaderiv, "glGetShaderiv");
	ok &= load(gl.GetShaderInfoLog, "glGetShaderInfoLog");
	ok &= load(gl.DeleteShader, "glDeleteShader");
	ok &= load(gl.CreateProgram, "glCreateProgram");
	ok &= load(gl.AttachShader, "glAttachShader");
	ok &= load(gl.LinkProgram, "glLinkProgram");
	ok &= load(gl.GetProgramiv, "glGetProgramiv");
	ok &= load(gl.DeleteProgram, "glDeleteProgram");
	ok &= load(gl.UseProgram, "glUseProgram");
	ok &= load(gl.GetUniformLocation, "glGetUniformLocation");
	ok &= load(gl.Uniform1i, "glUniform1i");
	ok &= load(gl.Uniform4f, "glUniform4f");
	ok &= load(gl.GenVertexArrays, "glGenVertexArrays");
	ok &= load(gl.DeleteVertexArrays, "glDeleteVertexArrays");
	ok &= load(gl.BindVertexArray, "glBindVertexArray");
	ok &= load(gl.DrawArrays, "glDrawArrays");
	ok &= load(gl.ReadPixels, "glReadPixels");
	ok &= load(gl.FenceSync, "glFenceSync");
	ok &= load(gl.WaitSync, "glWaitSync");
	ok &= load(gl.DeleteSync, "glDeleteSync");
	ok &= load(gl.Flush, "glFlush");
	loaded = ok;
	return ok;
}

bool targetResize(Target& target, unsigned width, unsigned height) {
	if (target.fbo && target.width == width && target.height == height) {
		return true;
	}
	if (!target.texture) {
		gl.GenTextures(1, &target.texture);
	}
	gl.BindTexture(GL_TEXTURE_2D, target.texture);
	gl.TexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei) width, (GLsizei) height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
	gl.TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	gl.TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	gl.TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	gl.TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	gl.BindTexture(GL_TEXTURE_2D, 0);
	if (!target.fbo) {
		gl.GenFramebuffers(1, &target.fbo);
	}
	gl.BindFramebuffer(GL_FRAMEBUFFER, target.fbo);
	gl.FramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target.texture, 0);
	bool complete = gl.CheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
	gl.ClearColor(0, 0, 0, 1);
	gl.Clear(GL_COLOR_BUFFER_BIT);
	gl.BindFramebuffer(GL_FRAMEBUFFER, 0);
	target.width = width;
	target.height = height;
	if (!complete) {
		GL_LOG("n3ds: framebuffer %ux%u incomplete\n", width, height);
	}
	return complete;
}

void targetDestroy(Target& target) {
	if (target.fbo) {
		gl.DeleteFramebuffers(1, &target.fbo);
	}
	if (target.texture) {
		gl.DeleteTextures(1, &target.texture);
	}
	target = Target();
}

bool presenterInit() {
	if (program) {
		return true;
	}
	GLuint vs = compile(GL_VERTEX_SHADER, VERTEX);
	GLuint fs = compile(GL_FRAGMENT_SHADER, FRAGMENT);
	if (!vs || !fs) {
		return false;
	}
	program = gl.CreateProgram();
	gl.AttachShader(program, vs);
	gl.AttachShader(program, fs);
	gl.LinkProgram(program);
	gl.DeleteShader(vs);
	gl.DeleteShader(fs);
	GLint ok = 0;
	gl.GetProgramiv(program, GL_LINK_STATUS, &ok);
	if (!ok) {
		gl.DeleteProgram(program);
		program = 0;
		return false;
	}
	dstLocation = gl.GetUniformLocation(program, "uDst");
	srcLocation = gl.GetUniformLocation(program, "uSrc");
	gl.UseProgram(program);
	gl.Uniform1i(gl.GetUniformLocation(program, "uTexture"), 0);
	// Desktop core profiles can't draw without a vertex array object.
	gl.GenVertexArrays(1, &vao);
	return true;
}

void presenterDestroy() {
	if (program) {
		gl.DeleteProgram(program);
		program = 0;
	}
	if (vao) {
		gl.DeleteVertexArrays(1, &vao);
		vao = 0;
	}
}

void present(uint32_t texture, unsigned textureWidth, unsigned textureHeight, unsigned frameWidth,
             unsigned frameHeight, int x, int y, int w, int h, int surfaceWidth, int surfaceHeight) {
	gl.BindFramebuffer(GL_FRAMEBUFFER, 0);
	gl.Disable(GL_BLEND);
	gl.Disable(GL_DEPTH_TEST);
	gl.Disable(GL_SCISSOR_TEST);
	gl.Disable(GL_STENCIL_TEST);
	gl.Disable(GL_CULL_FACE);
	gl.Viewport(0, 0, surfaceWidth, surfaceHeight);
	gl.ClearColor(0, 0, 0, 1);
	gl.Clear(GL_COLOR_BUFFER_BIT);
	if (!program || !texture || !textureWidth || !textureHeight || w <= 0 || h <= 0) {
		return;
	}
	// Window rectangle (top-left origin) to normalized device coordinates.
	float left = 2.0f * x / surfaceWidth - 1.0f;
	float right = 2.0f * (x + w) / surfaceWidth - 1.0f;
	float bottom = 1.0f - 2.0f * (y + h) / surfaceHeight;
	float top = 1.0f - 2.0f * y / surfaceHeight;
	gl.UseProgram(program);
	gl.BindVertexArray(vao);
	gl.Uniform4f(dstLocation, left, bottom, right, top);
	// The core draws with the origin at the bottom left, like GL.
	gl.Uniform4f(srcLocation, 0.0f, 0.0f, (float) frameWidth / textureWidth, (float) frameHeight / textureHeight);
	gl.ActiveTexture(GL_TEXTURE0);
	gl.BindTexture(GL_TEXTURE_2D, texture);
	gl.DrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	gl.BindTexture(GL_TEXTURE_2D, 0);
	gl.BindVertexArray(0);
}

void* fenceAfterFrame() {
	void* fence = gl.FenceSync(0x9117 /* GL_SYNC_GPU_COMMANDS_COMPLETE */, 0);
	gl.Flush();
	return fence;
}

void fenceWait(void* fence) {
	if (fence) {
		gl.WaitSync(fence, 0, 0xFFFFFFFFFFFFFFFFull /* GL_TIMEOUT_IGNORED */);
		gl.DeleteSync(fence);
	}
}

uint32_t readPixel(int x, int y, int surfaceHeight) {
	uint8_t rgba[4] = {0, 0, 0, 0};
	gl.BindFramebuffer(GL_FRAMEBUFFER, 0);
	gl.ReadPixels(x, surfaceHeight - 1 - y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
	return ((uint32_t) rgba[0] << 16) | (rgba[1] << 8) | rgba[2];
}

} // namespace n3ds
