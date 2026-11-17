// Desktop test for the 3DS host (app/src/main/cpp/n3ds): ROM inspection with
// synthetic files, the virtual file system, then the real Azahar libretro
// core (Linux build) booting the homebrew from native-test/3ds-rom on Mesa's
// software OpenGL, with frames, audio, presentation and save states. Exit
// code is non-zero on failure.
//
// Only the Android build of the core reads files through the frontend's VFS,
// so here it opens the homebrew itself; the VFS windows (how the app serves
// ROMs and the game inside a CIA) are checked on their own above.
//
//   n3ds_host_test <azahar_libretro.so> <test.3dsx> <output dir>
#include "n3ds_gl.h"
#include "n3ds_host.h"
#include "n3ds_rom.h"
#include "n3ds_vfs.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static int failures = 0;

#define CHECK(cond, ...)                          \
	do {                                          \
		if (!(cond)) {                            \
			fprintf(stderr, "FAIL: " __VA_ARGS__); \
			fputc('\n', stderr);                  \
			++failures;                           \
		}                                         \
	} while (0)

static void writeFile(const std::string& path, const std::vector<uint8_t>& data) {
	FILE* f = fopen(path.c_str(), "wb");
	if (!f) return;
	fwrite(data.data(), 1, data.size(), f);
	fclose(f);
}

static std::vector<uint8_t> readFile(const std::string& path) {
	std::vector<uint8_t> data;
	FILE* f = fopen(path.c_str(), "rb");
	if (!f) return data;
	fseek(f, 0, SEEK_END);
	data.resize(ftell(f));
	fseek(f, 0, SEEK_SET);
	if (fread(data.data(), 1, data.size(), f) != data.size()) data.clear();
	fclose(f);
	return data;
}

static void put32le(std::vector<uint8_t>& d, size_t at, uint32_t v) {
	for (int i = 0; i < 4; ++i) d[at + i] = (uint8_t) (v >> (8 * i));
}
static void put64le(std::vector<uint8_t>& d, size_t at, uint64_t v) {
	put32le(d, at, (uint32_t) v);
	put32le(d, at + 4, (uint32_t) (v >> 32));
}
static void put32be(std::vector<uint8_t>& d, size_t at, uint32_t v) {
	for (int i = 0; i < 4; ++i) d[at + i] = (uint8_t) (v >> (24 - 8 * i));
}
static void put16be(std::vector<uint8_t>& d, size_t at, uint16_t v) {
	d[at] = (uint8_t) (v >> 8);
	d[at + 1] = (uint8_t) v;
}
static void put64be(std::vector<uint8_t>& d, size_t at, uint64_t v) {
	put32be(d, at, (uint32_t) (v >> 32));
	put32be(d, at + 4, (uint32_t) v);
}

// An NCCH header at `at`, NoCrypto or not.
static void writeNcch(std::vector<uint8_t>& d, size_t at, bool decrypted) {
	memcpy(&d[at + 0x100], "NCCH", 4);
	d[at + 0x18F] = decrypted ? 0x04 : 0x00;
}

static N3dsRomInfo inspect(const std::string& path) {
	int fd = open(path.c_str(), O_RDONLY);
	struct stat st;
	fstat(fd, &st);
	N3dsRomInfo info = n3dsInspectRom(fd, (uint64_t) st.st_size);
	close(fd);
	return info;
}

// A CIA: header, empty cert/ticket, a TMD with one content, then the content.
static std::vector<uint8_t> makeCia(bool contentEncryptedFlag, bool ncchDecrypted, uint32_t sigType) {
	const uint32_t tmdSize = 4 + 0x100 + 0x3C + 0xC4 + 0x24 * 64 + 0x30;
	const uint64_t contentSize = 0x400;
	std::vector<uint8_t> d(0x2040 + 0x40 + ((tmdSize + 63) & ~63u) + contentSize, 0);
	put32le(d, 0, 0x2020);      // header size
	put32le(d, 0x08, 0x40);     // certificate chain
	put32le(d, 0x0C, 0);        // ticket
	put32le(d, 0x10, tmdSize);  // TMD
	put64le(d, 0x18, contentSize);
	size_t certOffset = 0x2040;
	size_t tmdOffset = certOffset + 0x40;
	size_t contentOffset = tmdOffset + ((tmdSize + 63) & ~63u);
	put32be(d, tmdOffset, sigType);
	size_t tmdHeader = tmdOffset + 4 + 0x100 + 0x3C;
	put16be(d, tmdHeader + 0x9E, 1); // one content
	size_t chunk = tmdHeader + 0xC4 + 0x24 * 64;
	put32be(d, chunk, 0);
	put16be(d, chunk + 4, 0);
	put16be(d, chunk + 6, contentEncryptedFlag ? 1 : 0);
	put64be(d, chunk + 8, contentSize);
	writeNcch(d, contentOffset, ncchDecrypted);
	return d;
}

static void testRomInspection(const std::string& dir) {
	std::string path = dir + "/probe.bin";

	std::vector<uint8_t> ncsd(0x8000, 0);
	memcpy(&ncsd[0x100], "NCSD", 4);
	put32le(ncsd, 0x120, 0x20); // partition 0 at 0x20 * 0x200 = 0x4000
	writeNcch(ncsd, 0x4000, true);
	writeFile(path, ncsd);
	N3dsRomInfo info = inspect(path);
	CHECK(info.kind == N3dsRomKind::Ncsd && info.problem == N3dsRomProblem::None, "decrypted .3ds not accepted");
	writeNcch(ncsd, 0x4000, false);
	writeFile(path, ncsd);
	CHECK(inspect(path).problem == N3dsRomProblem::Encrypted, "encrypted .3ds not detected");

	std::vector<uint8_t> ncch(0x400, 0);
	writeNcch(ncch, 0, true);
	writeFile(path, ncch);
	info = inspect(path);
	CHECK(info.kind == N3dsRomKind::Ncch && info.problem == N3dsRomProblem::None, "decrypted .cxi not accepted");

	std::vector<uint8_t> cia = makeCia(false, true, 0x010004);
	writeFile(path, cia);
	info = inspect(path);
	CHECK(info.kind == N3dsRomKind::Cia && info.problem == N3dsRomProblem::None, "decrypted CIA not accepted");
	CHECK(info.size == 0x400 && info.offset + info.size == cia.size(), "CIA game window %llx+%llx",
	      (unsigned long long) info.offset, (unsigned long long) info.size);
	CHECK(memcmp(&cia[info.offset + 0x100], "NCCH", 4) == 0, "CIA window doesn't start at the NCCH");
	writeFile(path, makeCia(true, true, 0x010004));
	CHECK(inspect(path).problem == N3dsRomProblem::Encrypted, "CIA with encrypted content not detected");
	writeFile(path, makeCia(false, false, 0x010004));
	CHECK(inspect(path).problem == N3dsRomProblem::Encrypted, "CIA with encrypted NCCH not detected");
	writeFile(path, makeCia(false, true, 0xDEAD));
	CHECK(inspect(path).problem == N3dsRomProblem::Damaged, "CIA with an unknown signature not refused");
	std::vector<uint8_t> truncated = makeCia(false, true, 0x010004);
	truncated.resize(truncated.size() - 0x100);
	writeFile(path, truncated);
	CHECK(inspect(path).problem == N3dsRomProblem::Damaged, "truncated CIA not refused");

	writeFile(path, std::vector<uint8_t>(0x300, 0x55));
	CHECK(inspect(path).kind == N3dsRomKind::Unknown, "random data recognised as a 3DS file");
}

static void testVfs(const std::string& dir) {
	const retro_vfs_interface* vfs = n3dsVfsInterface();
	std::string backing = dir + "/backing.bin";
	std::vector<uint8_t> data(1000);
	for (size_t i = 0; i < data.size(); ++i) data[i] = (uint8_t) i;
	writeFile(backing, data);
	int fd = open(backing.c_str(), O_RDONLY);
	std::string virt = dir + "/window.cxi";
	CHECK(n3dsVfsMap(virt, fd, 100, 200), "map failed");
	close(fd); // the mapping keeps its own copy

	retro_vfs_file_handle* h = vfs->open(virt.c_str(), RETRO_VFS_FILE_ACCESS_READ, 0);
	CHECK(h != nullptr, "window open failed");
	if (h) {
		uint8_t buf[300];
		CHECK(vfs->size(h) == 200, "window size %lld", (long long) vfs->size(h));
		CHECK(vfs->read(h, buf, 10) == 10 && buf[0] == 100 && buf[9] == 109, "window read");
		vfs->seek(h, -5, RETRO_VFS_SEEK_POSITION_END);
		CHECK(vfs->tell(h) == 195, "seek from the end");
		CHECK(vfs->read(h, buf, 100) == 5 && buf[4] == (uint8_t) 299, "read past the window end");
		CHECK(vfs->read(h, buf, 1) == 0, "read at the end");
		CHECK(vfs->write(h, buf, 1) == -1, "window writable");
		vfs->close(h);
	}
	CHECK(vfs->open(virt.c_str(), RETRO_VFS_FILE_ACCESS_WRITE, 0) == nullptr, "window opened for writing");

	std::string plain = dir + "/plain.bin";
	retro_vfs_file_handle* w = vfs->open(plain.c_str(), RETRO_VFS_FILE_ACCESS_WRITE, 0);
	CHECK(w && vfs->write(w, "hello world", 11) == 11, "plain write");
	if (w) {
		vfs->truncate(w, 5);
		vfs->close(w);
	}
	retro_vfs_file_handle* r = vfs->open(plain.c_str(), RETRO_VFS_FILE_ACCESS_READ_WRITE | RETRO_VFS_FILE_ACCESS_UPDATE_EXISTING, 0);
	char text[16] = {0};
	CHECK(r && vfs->size(r) == 5 && vfs->read(r, text, 15) == 5 && strcmp(text, "hello") == 0, "update-existing keeps data");
	if (r) vfs->close(r);
	n3dsVfsUnmapAll();
}

struct Egl {
	EGLDisplay display = EGL_NO_DISPLAY;
	EGLConfig config = nullptr;
	EGLContext core = EGL_NO_CONTEXT;
	EGLContext present = EGL_NO_CONTEXT;
	EGLSurface coreSurface = EGL_NO_SURFACE;
	EGLSurface window = EGL_NO_SURFACE;
};

static bool createContexts(Egl& egl, retro_hw_context_type type, unsigned major, unsigned minor, int width, int height) {
	auto getPlatformDisplay = (PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
	egl.display = getPlatformDisplay ? getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr)
	                                 : eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if (!eglInitialize(egl.display, nullptr, nullptr)) return false;
	bool gles = type == RETRO_HW_CONTEXT_OPENGLES3 || type == RETRO_HW_CONTEXT_OPENGLES_VERSION;
	eglBindAPI(gles ? EGL_OPENGL_ES_API : EGL_OPENGL_API);
	EGLint configAttribs[] = {EGL_RENDERABLE_TYPE, gles ? EGL_OPENGL_ES3_BIT : EGL_OPENGL_BIT, EGL_SURFACE_TYPE,
	                          EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
	EGLint count = 0;
	if (!eglChooseConfig(egl.display, configAttribs, &egl.config, 1, &count) || count == 0) return false;
	std::vector<EGLint> ctx = {EGL_CONTEXT_MAJOR_VERSION, (EGLint) major, EGL_CONTEXT_MINOR_VERSION, (EGLint) minor};
	if (!gles) {
		ctx.push_back(EGL_CONTEXT_OPENGL_PROFILE_MASK);
		ctx.push_back(EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT);
	}
	ctx.push_back(EGL_NONE);
	egl.present = eglCreateContext(egl.display, egl.config, EGL_NO_CONTEXT, ctx.data());
	egl.core = eglCreateContext(egl.display, egl.config, egl.present, ctx.data());
	EGLint small[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
	EGLint big[] = {EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE};
	egl.coreSurface = eglCreatePbufferSurface(egl.display, egl.config, small);
	egl.window = eglCreatePbufferSurface(egl.display, egl.config, big);
	return egl.present && egl.core && egl.coreSurface && egl.window;
}

int main(int argc, char** argv) {
	if (argc < 4) {
		fprintf(stderr, "usage: %s <azahar_libretro.so> <test.3dsx> <output dir>\n", argv[0]);
		return 2;
	}
	std::string corePath = argv[1];
	std::string out = argv[3];
	mkdir(out.c_str(), 0755);
	mkdir((out + "/system").c_str(), 0755);
	mkdir((out + "/saves").c_str(), 0755);

	testRomInspection(out);
	testVfs(out);

	std::string gamePath = argv[2];
	N3dsRomInfo info = inspect(gamePath);
	CHECK(info.kind == N3dsRomKind::ThreeDsx && info.problem == N3dsRomProblem::None, "test homebrew not recognised");

	n3ds::Host& host = n3ds::Host::get();
	host.setDirectories(out + "/system", out + "/saves");
	std::string error;
	if (!host.open(corePath, error)) {
		fprintf(stderr, "FAIL: %s\n", error.c_str());
		return 1;
	}
	// Every option the core offers, for reference.
	FILE* list = fopen((out + "/core-options.txt").c_str(), "w");
	for (const auto& option : host.options()) {
		if (list) {
			fprintf(list, "%s = %s  [", option.key.c_str(), option.defaultValue.c_str());
			for (size_t i = 0; i < option.values.size(); ++i) fprintf(list, "%s%s", i ? "|" : "", option.values[i].c_str());
			fprintf(list, "]\n");
		}
		if (option.key.size() >= 12 && option.key.compare(option.key.size() - 12, 12, "graphics_api") == 0) {
			host.setOption(option.key, "OpenGL");
		}
	}
	if (list) fclose(list);
	CHECK(!host.options().empty(), "the core registered no options");

	bool loaded = host.loadGame(gamePath, error);
	CHECK(loaded, "loadGame: %s", error.c_str());
	if (!loaded) return 1;
	printf("core wants context %d %u.%u\n", host.hwContextType(), host.hwVersionMajor(), host.hwVersionMinor());

	Egl egl;
	const int windowW = 720, windowH = 1280;
	if (!createContexts(egl, host.hwContextType(), host.hwVersionMajor(), host.hwVersionMinor(), windowW, windowH)) {
		fprintf(stderr, "FAIL: cannot create the GL contexts (0x%x)\n", eglGetError());
		return 1;
	}
	eglMakeCurrent(egl.display, egl.coreSurface, egl.coreSurface, egl.core);
	CHECK(host.startRenderer(error), "startRenderer: %s", error.c_str());
	CHECK(host.lastMessage().empty(), "core message: %s", host.lastMessage().c_str());

	std::vector<int16_t> audio;
	size_t samples = 0;
	const int frames = 120;
	for (int i = 0; i < frames; ++i) {
		host.runFrame();
		host.takeAudio(audio);
		samples += audio.size() / 2;
	}
	printf("%llu frames shown, frame %ux%u in a %ux%u target, %.1f audio frames per video frame at %.0f Hz\n",
	       (unsigned long long) host.framesShown(), host.frameWidth(), host.frameHeight(), host.target().width,
	       host.target().height, samples / (double) frames, host.sampleRate());
	CHECK(host.framesShown() >= (uint64_t) frames - 5, "only %llu frames shown", (unsigned long long) host.framesShown());
	CHECK(host.frameWidth() > 0 && host.frameHeight() > 0, "no frame size");
	// No audio expected: the homebrew never starts the 3DS sound chip, and the
	// core only sends what the chip produces.
	CHECK(host.sampleRate() > 30000 && host.fps() > 50, "timing %.0f Hz %.1f fps", host.sampleRate(), host.fps());

	// Show it: the present context draws the core's texture.
	eglMakeCurrent(egl.display, egl.window, egl.window, egl.present);
	CHECK(n3ds::presenterInit(), "presenter shaders failed");
	n3ds::present(host.target().texture, host.target().width, host.target().height, host.frameWidth(),
	               host.frameHeight(), 10, 20, 600, 720, windowW, windowH);
	uint32_t outside = n3ds::readPixel(5, 5, windowH);
	CHECK(outside == 0, "outside the game rect should be black, is %06X", outside);

	// Save states round trip, then a reset.
	eglMakeCurrent(egl.display, egl.coreSurface, egl.coreSurface, egl.core);
	std::vector<uint8_t> state;
	CHECK(host.saveState(state) && state.size() > 1024, "save state (%zu bytes)", state.size());
	printf("save state: %zu bytes\n", state.size());
	for (int i = 0; i < 10; ++i) host.runFrame();
	CHECK(host.loadState(state), "load state failed");
	std::vector<uint8_t> junk(1000, 0x42);
	CHECK(!host.loadState(junk), "junk accepted as a state");
	for (int i = 0; i < 10; ++i) host.runFrame();
	uint64_t before = host.framesShown();
	host.reset();
	for (int i = 0; i < 10; ++i) host.runFrame();
	CHECK(host.framesShown() > before, "no frames after reset");

	// Input reaches the core's callbacks without trouble.
	host.setButtons(1u << RETRO_DEVICE_ID_JOYPAD_A);
	host.setAnalog(0, 20000, -20000);
	host.setPointer(0, 16000, true);
	for (int i = 0; i < 5; ++i) host.runFrame();
	host.setButtons(0);
	host.setPointer(0, 0, false);

	host.stopRenderer();
	host.unloadGame();
	n3dsVfsUnmapAll();

	if (failures) {
		fprintf(stderr, "%d check(s) failed\n", failures);
		return 1;
	}
	printf("3DS host OK\n");
	// The core leaves threads behind; don't run its global destructors.
	fflush(stdout);
	_exit(0);
}
