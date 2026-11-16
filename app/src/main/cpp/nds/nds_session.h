// State shared between nds_core.cpp and nds_platform.cpp.
#ifndef NDS_SESSION_H
#define NDS_SESSION_H

#include <atomic>
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
	uint32_t noiseState = 0x1234567;

	unsigned sampleRate = 48000;

	// Slow motion audio is stretched here (melonDS can't resample upwards that
	// far): speed below 1, position in the input stream and the last sample.
	float slowSpeed = 1.0f;
	double stretchPos = -1.0;
	melonDS::s16 lastLeft = 0;
	melonDS::s16 lastRight = 0;
};

extern NdsSession* gNdsSession;

#endif
