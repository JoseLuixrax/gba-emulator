// Internal side of nds_mic.cpp, used by nds_platform.cpp and nds_core.cpp.
#ifndef NDS_MIC_H
#define NDS_MIC_H

#include <cstdint>

// melonDS opened (true) or closed (false) the microphone.
void ndsMicSetWanted(bool wanted);

// True while the app is recording and pushing samples.
bool ndsMicLive();

// Takes up to maxLength recorded samples; returns how many there were.
int ndsMicRead(int16_t* data, int maxLength);

#endif
