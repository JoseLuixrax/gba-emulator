// State shared between nds_core.cpp and nds_platform.cpp.
#ifndef NDS_SESSION_H
#define NDS_SESSION_H

#include <atomic>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "NDS.h"

struct NdsSession {
	std::unique_ptr<melonDS::NDS> nds;
	std::string savePath;
	std::string dataDir;

	// Battery save, filled by Platform::WriteNDSSave and written to disk once
	// the game stops touching it for a moment.
	std::mutex saveLock;
	std::vector<melonDS::u8> saveData;
	bool saveDirty = false;
	int framesSinceSaveChange = 0;

	std::atomic<uint32_t> keys{0};
	std::atomic<int> touchX{0};
	std::atomic<int> touchY{0};
	std::atomic<bool> touching{false};
	std::atomic<bool> micBlow{false};
	std::atomic<bool> lidClosed{false};
	bool lidApplied = false; // what melonDS was last told, set on the emulator thread
	uint32_t noiseState = 0x1234567;

	unsigned sampleRate = 48000;

	// Slow motion audio is stretched here (melonDS can't resample upwards that
	// far): speed below 1, position in the input stream and the last sample.
	float slowSpeed = 1.0f;
	double stretchPos = -1.0;
	melonDS::s16 lastLeft = 0;
	melonDS::s16 lastRight = 0;

	// Scratch buffer the savestates are written into (reused, ~7 MB).
	std::vector<melonDS::u8> stateBuffer;

	// Rewind: the newest snapshot in full, and for each older one the
	// compressed XOR difference that turns its newer neighbour into it.
	struct RewindStep {
		std::vector<melonDS::u8> delta;
		melonDS::u32 length; // size of the older snapshot
	};
	bool rewindEnabled = false;
	int rewindCapacity = 0;
	int rewindInterval = 1;
	int framesSinceSnapshot = 0;
	size_t rewindBytes = 0;
	std::vector<melonDS::u8> rewindNewest;
	std::deque<RewindStep> rewindSteps;
};

extern NdsSession* gNdsSession;

// nds_states.cpp: rewind snapshots after each frame, and forgetting them.
void ndsRewindAfterFrame(NdsSession* session);
void ndsRewindClear(NdsSession* session);

#endif
