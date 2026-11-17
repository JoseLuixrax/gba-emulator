// The file system the 3DS core sees (libretro VFS). Ordinary paths are real
// files; a registered path is a window into an already open descriptor, so
// a ROM from Android's storage (or the game inside a CIA) is read in place.
#ifndef N3DS_VFS_H
#define N3DS_VFS_H

#include <cstdint>
#include <string>

#include "libretro.h"

// Makes `path` read [offset, offset + size) of `fd`. The fd is duplicated,
// the caller keeps its own.
bool n3dsVfsMap(const std::string& path, int fd, uint64_t offset, uint64_t size);
void n3dsVfsUnmapAll();

// The interface handed to the core through RETRO_ENVIRONMENT_GET_VFS_INTERFACE.
const retro_vfs_interface* n3dsVfsInterface();
unsigned n3dsVfsVersion();

#endif
