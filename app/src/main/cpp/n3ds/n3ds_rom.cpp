#include "n3ds_rom.h"

#include <unistd.h>

#include <cstring>

namespace {

const uint64_t MEDIA_UNIT = 0x200;

bool readAt(int fd, uint64_t offset, void* out, size_t length) {
	uint8_t* dst = static_cast<uint8_t*>(out);
	size_t done = 0;
	while (done < length) {
		ssize_t n = pread(fd, dst + done, length - done, (off_t) (offset + done));
		if (n <= 0) {
			return false;
		}
		done += (size_t) n;
	}
	return true;
}

uint32_t le32(const uint8_t* p) {
	return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t) p[3] << 24);
}

uint16_t le16(const uint8_t* p) {
	return (uint16_t) (p[0] | (p[1] << 8));
}

uint64_t le64(const uint8_t* p) {
	return le32(p) | ((uint64_t) le32(p + 4) << 32);
}

uint32_t be32(const uint8_t* p) {
	return ((uint32_t) p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3];
}

uint16_t be16(const uint8_t* p) {
	return (uint16_t) ((p[0] << 8) | p[1]);
}

uint64_t be64(const uint8_t* p) {
	return ((uint64_t) be32(p) << 32) | be32(p + 4);
}

uint64_t align64(uint64_t value) {
	return (value + 63) & ~(uint64_t) 63;
}

// An NCCH header at `offset`: true if it is one; `encrypted` tells whether its
// contents are (flag 7 bit 2 is "NoCrypto").
bool checkNcch(int fd, uint64_t offset, bool& encrypted) {
	uint8_t header[0x200];
	if (!readAt(fd, offset, header, sizeof(header)) || memcmp(header + 0x100, "NCCH", 4) != 0) {
		return false;
	}
	encrypted = (header[0x18F] & 0x04) == 0;
	return true;
}

// Size of the signature block that starts a TMD, by signature type.
uint64_t signatureBlockSize(uint32_t type) {
	switch (type) {
	case 0x010000:
	case 0x010003:
		return 4 + 0x200 + 0x3C; // RSA-4096
	case 0x010001:
	case 0x010004:
		return 4 + 0x100 + 0x3C; // RSA-2048
	case 0x010002:
	case 0x010005:
		return 4 + 0x3C + 0x40; // ECDSA
	default:
		return 0;
	}
}

void inspectCia(int fd, uint64_t fileSize, const uint8_t* header, N3dsRomInfo& info) {
	info.kind = N3dsRomKind::Cia;
	uint32_t headerSize = le32(header);
	uint32_t certSize = le32(header + 0x08);
	uint32_t ticketSize = le32(header + 0x0C);
	uint32_t tmdSize = le32(header + 0x10);
	uint64_t contentSize = le64(header + 0x18);
	uint64_t certOffset = align64(headerSize);
	uint64_t ticketOffset = align64(certOffset + certSize);
	uint64_t tmdOffset = align64(ticketOffset + ticketSize);
	uint64_t contentOffset = align64(tmdOffset + tmdSize);
	if (contentOffset + contentSize > fileSize || tmdSize < 0x200) {
		info.problem = N3dsRomProblem::Damaged;
		return;
	}

	uint8_t sig[4];
	if (!readAt(fd, tmdOffset, sig, 4)) {
		info.problem = N3dsRomProblem::Damaged;
		return;
	}
	uint64_t tmdHeader = tmdOffset + signatureBlockSize(be32(sig));
	if (tmdHeader == tmdOffset) {
		info.problem = N3dsRomProblem::Damaged;
		return;
	}
	// TMD header (0xC4 bytes), 64 content info records (0x24 each), then the
	// content chunk records: id u32, index u16, type u16, size u64, hash.
	uint8_t tmd[0xC4];
	uint8_t chunk[0x30];
	if (!readAt(fd, tmdHeader, tmd, sizeof(tmd)) ||
	    !readAt(fd, tmdHeader + 0xC4 + 0x24 * 64, chunk, sizeof(chunk))) {
		info.problem = N3dsRomProblem::Damaged;
		return;
	}
	if (be16(tmd + 0x9E) == 0) {
		info.problem = N3dsRomProblem::NotAGame;
		return;
	}
	uint16_t type = be16(chunk + 6);
	uint64_t size = be64(chunk + 8);
	if (size == 0 || size > contentSize) {
		info.problem = N3dsRomProblem::Damaged;
		return;
	}
	info.offset = contentOffset;
	info.size = size;
	info.extension = "cxi";
	if (type & 0x0001) {
		// The content itself is encrypted with the title key.
		info.problem = N3dsRomProblem::Encrypted;
		return;
	}
	bool encrypted = false;
	if (!checkNcch(fd, contentOffset, encrypted)) {
		info.problem = N3dsRomProblem::NotAGame;
		return;
	}
	if (encrypted) {
		info.problem = N3dsRomProblem::Encrypted;
	}
}

} // namespace

N3dsRomInfo n3dsInspectRom(int fd, uint64_t fileSize) {
	N3dsRomInfo info;
	info.size = fileSize;
	uint8_t header[0x200];
	if (fileSize < 0x40 || !readAt(fd, 0, header, fileSize < sizeof(header) ? (size_t) fileSize : sizeof(header))) {
		return info;
	}

	if (memcmp(header, "3DSX", 4) == 0) {
		info.kind = N3dsRomKind::ThreeDsx;
		info.extension = "3dsx";
		return info;
	}
	if (fileSize >= 0x200 && memcmp(header + 0x100, "NCSD", 4) == 0) {
		info.kind = N3dsRomKind::Ncsd;
		info.extension = "3ds";
		// Partition 0 is the game; its NCCH tells whether it's encrypted.
		uint64_t mediaUnit = MEDIA_UNIT << (header[0x188 + 6] & 0x0F);
		uint64_t partition = le32(header + 0x120) * mediaUnit;
		bool encrypted = false;
		if (partition == 0 || partition >= fileSize || !checkNcch(fd, partition, encrypted)) {
			info.problem = N3dsRomProblem::Damaged;
		} else if (encrypted) {
			info.problem = N3dsRomProblem::Encrypted;
		}
		return info;
	}
	if (fileSize >= 0x200 && memcmp(header + 0x100, "NCCH", 4) == 0) {
		info.kind = N3dsRomKind::Ncch;
		info.extension = "cxi";
		if ((header[0x18F] & 0x04) == 0) {
			info.problem = N3dsRomProblem::Encrypted;
		}
		return info;
	}
	// CIA: a fixed 0x2020-byte header, type 0.
	if (le32(header) == 0x2020 && le16(header + 4) == 0) {
		inspectCia(fd, fileSize, header, info);
		return info;
	}
	return info;
}
