// The phone's microphone for the DS core. Android records on its own thread
// and pushes samples here; melonDS pulls them through Platform::Mic_ReadInput.
// Kept outside the session so a late push can never touch a freed one.
#include "nds_core.h"
#include "nds_mic.h"

#include <algorithm>
#include <atomic>
#include <mutex>
#include <vector>

namespace {

// ~86 ms at the DS mic rate: enough to smooth out Android's bursts while
// keeping the delay short. When the phone records a little faster than the
// game listens, the oldest samples are dropped.
const size_t RING_SIZE = 4096;

std::mutex ringLock;
std::vector<int16_t> ring(RING_SIZE);
size_t readPos = 0;
size_t count = 0;
std::atomic<bool> live{false};
std::atomic<bool> wanted{false};

void clearRing() {
	std::lock_guard<std::mutex> guard(ringLock);
	readPos = 0;
	count = 0;
}

} // namespace

void ndsMicSetWanted(bool value) {
	if (value && !wanted) {
		// Don't play back what was said before the game started listening.
		clearRing();
	}
	wanted = value;
}

bool ndsMicLive() {
	return live;
}

int ndsMicRead(int16_t* data, int maxLength) {
	std::lock_guard<std::mutex> guard(ringLock);
	size_t n = std::min(count, (size_t) std::max(maxLength, 0));
	for (size_t i = 0; i < n; ++i) {
		data[i] = ring[(readPos + i) % RING_SIZE];
	}
	readPos = (readPos + n) % RING_SIZE;
	count -= n;
	return (int) n;
}

extern "C" {

void nds_push_mic(const int16_t* samples, size_t length) {
	if (!live || !samples) {
		return;
	}
	std::lock_guard<std::mutex> guard(ringLock);
	for (size_t i = 0; i < length; ++i) {
		ring[(readPos + count) % RING_SIZE] = samples[i];
		if (count == RING_SIZE) {
			readPos = (readPos + 1) % RING_SIZE;
		} else {
			++count;
		}
	}
}

void nds_set_mic_live(bool value) {
	live = value;
	if (!value) {
		clearRing();
	}
}

bool nds_mic_wanted(void) {
	return wanted;
}

size_t nds_mic_buffered(void) {
	std::lock_guard<std::mutex> guard(ringLock);
	return count;
}

} // extern "C"
