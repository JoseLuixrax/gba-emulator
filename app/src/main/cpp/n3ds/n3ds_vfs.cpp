#include "n3ds_vfs.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <mutex>

struct retro_vfs_file_handle {
	int fd = -1;
	bool window = false; // a read-only slice of another file
	int64_t base = 0;
	int64_t length = 0;  // window size
	int64_t pos = 0;
	std::string path;
};

namespace {

struct Mapping {
	int fd;
	uint64_t offset;
	uint64_t size;
};

std::mutex mapLock;
std::map<std::string, Mapping> mappings;

int64_t fileSize(retro_vfs_file_handle* h) {
	if (h->window) {
		return h->length;
	}
	struct stat st;
	return fstat(h->fd, &st) == 0 ? (int64_t) st.st_size : -1;
}

const char* RETRO_CALLCONV vfsGetPath(retro_vfs_file_handle* h) {
	return h ? h->path.c_str() : nullptr;
}

retro_vfs_file_handle* RETRO_CALLCONV vfsOpen(const char* path, unsigned mode, unsigned hints) {
	if (!path) {
		return nullptr;
	}
	if (getenv("N3DS_VFS_LOG")) {
		fprintf(stderr, "vfs open %s mode %u\n", path, mode);
	}
	{
		std::lock_guard<std::mutex> guard(mapLock);
		auto it = mappings.find(path);
		if (it != mappings.end()) {
			if (mode & RETRO_VFS_FILE_ACCESS_WRITE) {
				return nullptr; // the ROM is read-only
			}
			int fd = dup(it->second.fd);
			if (fd < 0) {
				return nullptr;
			}
			auto* h = new retro_vfs_file_handle();
			h->fd = fd;
			h->window = true;
			h->base = (int64_t) it->second.offset;
			h->length = (int64_t) it->second.size;
			h->path = path;
			return h;
		}
	}
	int flags;
	bool write = mode & RETRO_VFS_FILE_ACCESS_WRITE;
	bool read = mode & RETRO_VFS_FILE_ACCESS_READ;
	if (write) {
		flags = (read ? O_RDWR : O_WRONLY) | O_CREAT;
		// Writing discards the old content unless asked to keep it.
		if (!(mode & RETRO_VFS_FILE_ACCESS_UPDATE_EXISTING)) {
			flags |= O_TRUNC;
		}
	} else {
		flags = O_RDONLY;
	}
	int fd = open(path, flags | O_CLOEXEC, 0644);
	if (fd < 0) {
		return nullptr;
	}
	auto* h = new retro_vfs_file_handle();
	h->fd = fd;
	h->path = path;
	return h;
}

int RETRO_CALLCONV vfsClose(retro_vfs_file_handle* h) {
	if (!h) {
		return -1;
	}
	int result = h->fd >= 0 ? close(h->fd) : 0;
	delete h;
	return result == 0 ? 0 : -1;
}

int64_t RETRO_CALLCONV vfsSize(retro_vfs_file_handle* h) {
	return h ? fileSize(h) : -1;
}

int64_t RETRO_CALLCONV vfsTell(retro_vfs_file_handle* h) {
	return h ? h->pos : -1;
}

int64_t RETRO_CALLCONV vfsSeek(retro_vfs_file_handle* h, int64_t offset, int whence) {
	if (!h) {
		return -1;
	}
	int64_t target;
	switch (whence) {
	case RETRO_VFS_SEEK_POSITION_START:
		target = offset;
		break;
	case RETRO_VFS_SEEK_POSITION_CURRENT:
		target = h->pos + offset;
		break;
	case RETRO_VFS_SEEK_POSITION_END: {
		int64_t size = fileSize(h);
		if (size < 0) {
			return -1;
		}
		target = size + offset;
		break;
	}
	default:
		return -1;
	}
	if (target < 0) {
		return -1;
	}
	h->pos = target;
	return 0;
}

int64_t RETRO_CALLCONV vfsRead(retro_vfs_file_handle* h, void* buffer, uint64_t length) {
	if (!h || !buffer) {
		return -1;
	}
	if (h->window) {
		if (h->pos >= h->length) {
			return 0;
		}
		if ((int64_t) length > h->length - h->pos) {
			length = (uint64_t) (h->length - h->pos);
		}
	}
	uint8_t* dst = static_cast<uint8_t*>(buffer);
	uint64_t done = 0;
	while (done < length) {
		ssize_t n = pread(h->fd, dst + done, (size_t) (length - done), (off_t) (h->base + h->pos + (int64_t) done));
		if (n < 0 && errno == EINTR) {
			continue;
		}
		if (n < 0) {
			return done ? (int64_t) done : -1;
		}
		if (n == 0) {
			break;
		}
		done += (uint64_t) n;
	}
	h->pos += (int64_t) done;
	return (int64_t) done;
}

int64_t RETRO_CALLCONV vfsWrite(retro_vfs_file_handle* h, const void* buffer, uint64_t length) {
	if (!h || !buffer || h->window) {
		return -1;
	}
	const uint8_t* src = static_cast<const uint8_t*>(buffer);
	uint64_t done = 0;
	while (done < length) {
		ssize_t n = pwrite(h->fd, src + done, (size_t) (length - done), (off_t) (h->pos + (int64_t) done));
		if (n < 0 && errno == EINTR) {
			continue;
		}
		if (n <= 0) {
			return done ? (int64_t) done : -1;
		}
		done += (uint64_t) n;
	}
	h->pos += (int64_t) done;
	return (int64_t) done;
}

int RETRO_CALLCONV vfsFlush(retro_vfs_file_handle* h) {
	if (!h) {
		return -1;
	}
	return h->window || fdatasync(h->fd) == 0 ? 0 : -1;
}

int RETRO_CALLCONV vfsRemove(const char* path) {
	return path && remove(path) == 0 ? 0 : -1;
}

int RETRO_CALLCONV vfsRename(const char* oldPath, const char* newPath) {
	return oldPath && newPath && rename(oldPath, newPath) == 0 ? 0 : -1;
}

int64_t RETRO_CALLCONV vfsTruncate(retro_vfs_file_handle* h, int64_t length) {
	if (!h || h->window) {
		return -1;
	}
	return ftruncate(h->fd, (off_t) length) == 0 ? 0 : -1;
}

const retro_vfs_interface INTERFACE = [] {
	retro_vfs_interface vfs{};
	vfs.get_path = vfsGetPath;
	vfs.open = vfsOpen;
	vfs.close = vfsClose;
	vfs.size = vfsSize;
	vfs.tell = vfsTell;
	vfs.seek = vfsSeek;
	vfs.read = vfsRead;
	vfs.write = vfsWrite;
	vfs.flush = vfsFlush;
	vfs.remove = vfsRemove;
	vfs.rename = vfsRename;
	vfs.truncate = vfsTruncate;
	return vfs;
}();

} // namespace

bool n3dsVfsMap(const std::string& path, int fd, uint64_t offset, uint64_t size) {
	int copy = dup(fd);
	if (copy < 0) {
		return false;
	}
	std::lock_guard<std::mutex> guard(mapLock);
	auto it = mappings.find(path);
	if (it != mappings.end()) {
		close(it->second.fd);
	}
	mappings[path] = Mapping{copy, offset, size};
	return true;
}

void n3dsVfsUnmapAll() {
	std::lock_guard<std::mutex> guard(mapLock);
	for (auto& entry : mappings) {
		close(entry.second.fd);
	}
	mappings.clear();
}

const retro_vfs_interface* n3dsVfsInterface() {
	return &INTERFACE;
}

unsigned n3dsVfsVersion() {
	// File functions plus truncate. The core only asks for version 1.
	return 2;
}
