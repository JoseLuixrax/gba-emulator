// Looks inside 3DS game files before handing them to the core: what kind
// they are, whether they're encrypted (the core can't play those) and, for a
// CIA, where the game itself sits inside it.
#ifndef N3DS_ROM_H
#define N3DS_ROM_H

#include <cstdint>
#include <string>

enum class N3dsRomKind {
	Unknown,  // let the core decide (e.g. its own compressed formats)
	Ncsd,     // .3ds / .cci cartridge image
	Ncch,     // .cxi / .app executable
	ThreeDsx, // .3dsx homebrew
	Cia,      // installable archive: the game is its first content (an NCCH)
};

enum class N3dsRomProblem {
	None,
	Encrypted,      // needs decrypting first
	NotAGame,       // a CIA without a playable first content (update, DLC...)
	Damaged,        // headers point outside the file
};

struct N3dsRomInfo {
	N3dsRomKind kind = N3dsRomKind::Unknown;
	N3dsRomProblem problem = N3dsRomProblem::None;
	// The part of the file the core should see as the game.
	uint64_t offset = 0;
	uint64_t size = 0;
	// Extension for the name the core sees ("3ds", "cxi", "3dsx").
	std::string extension;
};

// fd is read with pread and left open. fileSize is the whole file's size.
N3dsRomInfo n3dsInspectRom(int fd, uint64_t fileSize);

#endif
