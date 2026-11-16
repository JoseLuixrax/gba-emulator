// Save states, rewind and Action Replay cheats for the DS core.
#include "nds_core.h"
#include "nds_session.h"

#include "NDS.h"
#include "Platform.h"
#include "Savestate.h"

#include <zlib.h>

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace melonDS;

namespace {

// State files: this magic, the uncompressed length (u32, little endian),
// then the melonDS state compressed with zlib.
const char FILE_MAGIC[8] = {'A', 'B', 'D', 'S', 'S', 'T', '0', '1'};
const size_t FILE_HEADER = 12;
const u32 MAX_STATE_SIZE = 256u * 1024 * 1024;

// Rewind memory budget. A DS snapshot is ~7 MB, but consecutive ones differ
// little, so each step back usually costs tens to hundreds of KB.
const size_t REWIND_BUDGET = 96u * 1024 * 1024;

void logMessage(const char* message) {
	Platform::Log(Platform::LogLevel::Warn, "%s\n", message);
}

/* Writes the current state into session->stateBuffer; returns its length or 0. */
u32 captureState(NdsSession* s) {
	if (!s->stateBuffer.empty()) {
		Savestate state(s->stateBuffer.data(), (u32) s->stateBuffer.size(), true);
		if (s->nds->DoSavestate(&state) && !state.Error) {
			return state.Length();
		}
	}
	// First time, or the state outgrew the buffer: let melonDS size it, then
	// keep a buffer with room to spare so the next ones avoid allocating.
	Savestate state;
	if (state.Error || !s->nds->DoSavestate(&state) || state.Error) {
		logMessage("Could not create a DS save state");
		return 0;
	}
	u32 length = state.Length();
	s->stateBuffer.resize(length + length / 8 + 64 * 1024);
	memcpy(s->stateBuffer.data(), state.Buffer(), length);
	return length;
}

bool restoreState(NdsSession* s, u8* data, u32 length) {
	Savestate state(data, length, false);
	if (state.Error) {
		return false;
	}
	return s->nds->DoSavestate(&state) && !state.Error;
}

void putVarint(std::vector<u8>& out, size_t value) {
	while (value >= 0x80) {
		out.push_back((u8) (value | 0x80));
		value >>= 7;
	}
	out.push_back((u8) value);
}

bool getVarint(const u8*& p, const u8* end, size_t& value) {
	value = 0;
	for (int shift = 0; p < end && shift < 64; shift += 7) {
		u8 b = *p++;
		value |= (size_t) (b & 0x7F) << shift;
		if (!(b & 0x80)) {
			return true;
		}
	}
	return false;
}

/*
 * older XOR newer (the shorter one padded with zeros), stored as runs of
 * [number of zero bytes][number of literal bytes][literal bytes]. Snapshots
 * a few frames apart are mostly identical, so this is mostly zero runs.
 */
std::vector<u8> encodeDelta(const u8* older, size_t olderLen, const u8* newer, size_t newerLen) {
	const size_t n = std::max(olderLen, newerLen);
	const size_t common = std::min(olderLen, newerLen);
	auto x = [&](size_t i) -> u8 {
		return (u8) ((i < olderLen ? older[i] : 0) ^ (i < newerLen ? newer[i] : 0));
	};
	std::vector<u8> out;
	out.reserve(64 * 1024);
	size_t i = 0;
	while (i < n) {
		size_t zeroStart = i;
		while (i + 8 <= common) {
			u64 a, b;
			memcpy(&a, older + i, 8);
			memcpy(&b, newer + i, 8);
			if (a != b) {
				break;
			}
			i += 8;
		}
		while (i < n && x(i) == 0) {
			++i;
		}
		size_t literalStart = i;
		size_t literalEnd = i;
		// A literal ends where at least 8 zero bytes follow.
		while (i < n) {
			if (x(i) != 0) {
				literalEnd = ++i;
			} else if (i - literalEnd >= 7) {
				break;
			} else {
				++i;
			}
		}
		putVarint(out, literalStart - zeroStart);
		putVarint(out, literalEnd - literalStart);
		for (size_t k = literalStart; k < literalEnd; ++k) {
			out.push_back(x(k));
		}
		i = literalEnd;
	}
	return out;
}

/* Turns `state` (the newer snapshot) into the older one described by `step`. */
bool applyDelta(std::vector<u8>& state, const NdsSession::RewindStep& step) {
	const size_t n = std::max(state.size(), (size_t) step.length);
	state.resize(n, 0);
	const u8* p = step.delta.data();
	const u8* end = p + step.delta.size();
	size_t i = 0;
	while (p < end) {
		size_t zeros, literal;
		if (!getVarint(p, end, zeros) || !getVarint(p, end, literal)) {
			return false;
		}
		i += zeros;
		if (i > n || literal > n - i || literal > (size_t) (end - p)) {
			return false;
		}
		for (size_t k = 0; k < literal; ++k) {
			state[i + k] ^= p[k];
		}
		i += literal;
		p += literal;
	}
	state.resize(step.length);
	return true;
}

bool writeFileAtomically(const std::string& path, const std::vector<u8>& data) {
	std::string tmp = path + ".tmp";
	FILE* file = fopen(tmp.c_str(), "wb");
	if (!file) {
		return false;
	}
	bool ok = fwrite(data.data(), 1, data.size(), file) == data.size();
	ok = fflush(file) == 0 && ok;
	fclose(file);
	if (!ok || rename(tmp.c_str(), path.c_str()) != 0) {
		remove(tmp.c_str());
		return false;
	}
	return true;
}

bool readWholeFile(const char* path, std::vector<u8>& data) {
	FILE* file = fopen(path, "rb");
	if (!file) {
		return false;
	}
	fseek(file, 0, SEEK_END);
	long size = ftell(file);
	fseek(file, 0, SEEK_SET);
	bool ok = size > 0 && (u64) size <= MAX_STATE_SIZE;
	if (ok) {
		data.resize((size_t) size);
		ok = fread(data.data(), 1, data.size(), file) == data.size();
	}
	fclose(file);
	return ok;
}

bool isHexToken(const std::string& token) {
	return !token.empty() && std::all_of(token.begin(), token.end(), [](char c) { return isxdigit((unsigned char) c); });
}

} // namespace

void ndsRewindClear(NdsSession* s) {
	s->rewindSteps.clear();
	s->rewindBytes = 0;
	s->rewindNewest.clear();
	s->rewindNewest.shrink_to_fit();
	s->framesSinceSnapshot = 0;
}

void ndsRewindAfterFrame(NdsSession* s) {
	if (!s->rewindEnabled || ++s->framesSinceSnapshot < s->rewindInterval) {
		return;
	}
	s->framesSinceSnapshot = 0;
	u32 length = captureState(s);
	if (!length) {
		return;
	}
	if (!s->rewindNewest.empty()) {
		NdsSession::RewindStep step;
		step.length = (u32) s->rewindNewest.size();
		step.delta = encodeDelta(s->rewindNewest.data(), s->rewindNewest.size(), s->stateBuffer.data(), length);
		step.delta.shrink_to_fit();
		s->rewindBytes += step.delta.size();
		s->rewindSteps.push_back(std::move(step));
		while (!s->rewindSteps.empty() &&
		       ((int) s->rewindSteps.size() > s->rewindCapacity || s->rewindBytes > REWIND_BUDGET)) {
			s->rewindBytes -= s->rewindSteps.front().delta.size();
			s->rewindSteps.pop_front();
		}
	}
	// The snapshot just taken becomes the newest one; its old buffer (big
	// enough already) becomes the scratch buffer. No 7 MB copy.
	size_t scratchSize = s->stateBuffer.size();
	s->rewindNewest.swap(s->stateBuffer);
	s->rewindNewest.resize(length);
	s->stateBuffer.resize(scratchSize);
}

extern "C" {

bool nds_save_state(const char* path) {
	NdsSession* s = gNdsSession;
	if (!s || !s->nds || !path) {
		return false;
	}
	u32 length = captureState(s);
	if (!length) {
		return false;
	}
	uLongf packed = compressBound(length);
	std::vector<u8> out(FILE_HEADER + packed);
	memcpy(out.data(), FILE_MAGIC, sizeof(FILE_MAGIC));
	for (int i = 0; i < 4; ++i) {
		out[8 + i] = (u8) (length >> (8 * i));
	}
	if (compress2(out.data() + FILE_HEADER, &packed, s->stateBuffer.data(), length, Z_BEST_SPEED) != Z_OK) {
		return false;
	}
	out.resize(FILE_HEADER + packed);
	return writeFileAtomically(path, out);
}

bool nds_load_state(const char* path) {
	NdsSession* s = gNdsSession;
	if (!s || !s->nds || !path) {
		return false;
	}
	std::vector<u8> file;
	if (!readWholeFile(path, file)) {
		return false;
	}
	std::vector<u8> raw;
	if (file.size() >= 4 && memcmp(file.data(), "MELN", 4) == 0) {
		// A state saved by melonDS itself.
		raw.swap(file);
	} else if (file.size() > FILE_HEADER && memcmp(file.data(), FILE_MAGIC, sizeof(FILE_MAGIC)) == 0) {
		u32 length = file[8] | (file[9] << 8) | (file[10] << 16) | ((u32) file[11] << 24);
		if (length == 0 || length > MAX_STATE_SIZE) {
			return false;
		}
		raw.resize(length);
		uLongf unpacked = length;
		if (uncompress(raw.data(), &unpacked, file.data() + FILE_HEADER, file.size() - FILE_HEADER) != Z_OK ||
		    unpacked != length) {
			logMessage("Damaged DS save state");
			return false;
		}
	} else {
		return false;
	}

	// Keep the current moment, in case the file turns out to be unusable.
	u32 backupLength = captureState(s);
	if (!backupLength) {
		return false;
	}
	std::vector<u8> backup(s->stateBuffer.begin(), s->stateBuffer.begin() + backupLength);
	if (!restoreState(s, raw.data(), (u32) raw.size())) {
		logMessage("Could not load the DS save state, going back to where the game was");
		restoreState(s, backup.data(), backupLength);
		return false;
	}
	// The rewind history no longer leads to this point.
	ndsRewindClear(s);
	return true;
}

void nds_set_rewind(bool enabled, int capacity, int interval) {
	NdsSession* s = gNdsSession;
	if (!s) {
		return;
	}
	capacity = std::max(capacity, 1);
	interval = std::max(interval, 1);
	if (enabled == s->rewindEnabled && capacity == s->rewindCapacity && interval == s->rewindInterval) {
		return;
	}
	s->rewindEnabled = enabled;
	s->rewindCapacity = capacity;
	s->rewindInterval = interval;
	ndsRewindClear(s);
}

bool nds_rewind_step(void) {
	NdsSession* s = gNdsSession;
	if (!s || !s->nds || s->rewindSteps.empty() || s->rewindNewest.empty()) {
		return false;
	}
	NdsSession::RewindStep step = std::move(s->rewindSteps.back());
	s->rewindSteps.pop_back();
	s->rewindBytes -= step.delta.size();
	if (!applyDelta(s->rewindNewest, step) ||
	    !restoreState(s, s->rewindNewest.data(), (u32) s->rewindNewest.size())) {
		logMessage("Rewind data is damaged, starting a new history");
		ndsRewindClear(s);
		return false;
	}
	// The screens aren't part of a state: run one frame (no input, no sound)
	// so the picture shows the moment we went back to.
	s->framesSinceSnapshot = 0;
	s->nds->SetKeyMask(0xFFF);
	s->nds->ReleaseScreen();
	s->nds->RunFrame();
	nds_discard_audio();
	return true;
}

void nds_cheats_clear(void) {
	NdsSession* s = gNdsSession;
	if (s && s->nds) {
		s->nds->AREngine.Cheats.clear();
	}
}

bool nds_cheats_add(const char* name, const char* code, bool enabled) {
	NdsSession* s = gNdsSession;
	if (!s || !s->nds || !code) {
		return false;
	}
	// Words of 8 hex digits (16 digits written together count as two),
	// separated by spaces, new lines or the odd punctuation people paste.
	std::vector<u32> words;
	std::string token;
	auto flush = [&]() -> bool {
		if (token.empty()) {
			return true;
		}
		if (!isHexToken(token) || (token.size() != 8 && token.size() != 16)) {
			return false;
		}
		for (size_t at = 0; at < token.size(); at += 8) {
			words.push_back((u32) strtoul(token.substr(at, 8).c_str(), nullptr, 16));
		}
		token.clear();
		return true;
	};
	for (const char* c = code;; ++c) {
		if (*c == '\0' || strchr(" \t\r\n,:;-", *c)) {
			if (!flush()) {
				return false;
			}
			if (*c == '\0') {
				break;
			}
		} else {
			token.push_back(*c);
		}
	}
	if (words.empty() || words.size() % 2 != 0) {
		return false;
	}
	ARCode cheat;
	cheat.Parent = nullptr;
	cheat.Name = name ? name : "";
	cheat.Enabled = enabled;
	cheat.Code = std::move(words);
	s->nds->AREngine.Cheats.push_back(std::move(cheat));
	return true;
}

} // extern "C"
