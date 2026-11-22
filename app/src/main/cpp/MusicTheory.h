#pragma once

#include <algorithm>
#include <cmath>

struct HarmonyResolutionCpp {
    int detectedMidi = 0;
    int voice1Semitones = 0;
    int voice2Semitones = 0;
    bool hasVoice2 = false;
    float voice1Gain = 0.85f;
    float voice2Gain = 0.0f;
    float voice1DetuneCents = 0.0f;
    float voice2DetuneCents = 0.0f;
};

namespace MusicTheoryCpp {

inline float semitonesToRatio(float semitones) {
    return std::pow(2.0f, semitones / 12.0f);
}

inline float freqToMidiFloat(float freqHz) {
    if (freqHz <= 20.0f) return 0.0f;
    return 69.0f + 12.0f * (std::log(freqHz / 440.0f) / std::log(2.0f));
}

inline int floorDiv(int a, int b) {
    int q = a / b;
    int r = a % b;
    if (r != 0 && ((r < 0) != (b < 0))) {
        q--;
    }
    return q;
}

inline int floorMod(int a, int b) {
    int m = a % b;
    if (m != 0 && ((m < 0) != (b < 0))) {
        m += b;
    }
    return m;
}

inline HarmonyResolutionCpp solveHarmony(
    float detectedFreqHz,
    int keyOrdinal,
    int scaleOrdinal,
    int harmonyTypeOrdinal,
    bool variation,
    bool autoHarmonist,
    int previousMidi = 0,
    float hysteresisThreshold = 0.65f
) {
    static constexpr int kMajorIntervals[7] = {0, 2, 4, 5, 7, 9, 11};
    static constexpr int kMinorIntervals[7] = {0, 2, 3, 5, 7, 8, 10};

    const float lockWindow = std::clamp(hysteresisThreshold, 0.05f, 0.95f);
    int rawMidi = 60;
    if (detectedFreqHz > 40.0f) {
        const float midiFloat = freqToMidiFloat(detectedFreqHz);
        if (previousMidi > 0 && std::fabs(midiFloat - static_cast<float>(previousMidi)) < lockWindow) {
            rawMidi = previousMidi;
        } else {
            rawMidi = static_cast<int>(std::lround(midiFloat));
        }
    } else if (previousMidi > 0) {
        rawMidi = previousMidi;
    }

    const int rootOffset = std::clamp(keyOrdinal, 0, 11);
    const int* intervals = (scaleOrdinal == 1) ? kMinorIntervals : kMajorIntervals;

    const int pitchClass = ((rawMidi - rootOffset) % 12 + 12) % 12;
    int bestDegreeIdx = 0;
    int minDist = 12;
    for (int i = 0; i < 7; ++i) {
        const int d = std::abs(intervals[i] - pitchClass);
        const int wrapped = std::min(d, 12 - d);
        if (wrapped < minDist) {
            minDist = wrapped;
            bestDegreeIdx = i;
        }
    }

    const int quantizedMidi = (autoHarmonist && detectedFreqHz > 40.0f)
        ? ((rawMidi - pitchClass) + intervals[bestDegreeIdx])
        : rawMidi;

    auto diatonicInterval = [&](int steps) -> int {
        const int total = bestDegreeIdx + steps;
        const int octaveShift = floorDiv(total, 7);
        const int deg = floorMod(total, 7);
        return (octaveShift * 12 + intervals[deg]) - intervals[bestDegreeIdx];
    };

    HarmonyResolutionCpp out;
    out.detectedMidi = (detectedFreqHz > 40.0f) ? quantizedMidi : 0;

    switch (harmonyTypeOrdinal) {
        case 0: // OCT_DOWN
            out.voice1Semitones = -12;
            if (variation) {
                out.voice2Semitones = diatonicInterval(-2);
                out.hasVoice2 = true;
                out.voice2Gain = 0.65f;
            }
            break;
        case 1: // LOWER_5TH
            out.voice1Semitones = diatonicInterval(-3);
            if (variation) {
                out.voice2Semitones = diatonicInterval(-2);
                out.hasVoice2 = true;
                out.voice2Gain = 0.65f;
            }
            break;
        case 2: // LOW_3RD
            out.voice1Semitones = diatonicInterval(-2);
            if (variation) {
                out.voice2Semitones = diatonicInterval(-4);
                out.hasVoice2 = true;
                out.voice2Gain = 0.60f;
            }
            break;
        case 3: // UNISON
            out.voice1Semitones = 0;
            out.voice1DetuneCents = variation ? 8.0f : 4.0f;
            out.voice1Gain = 0.72f;
            out.voice2Semitones = 0;
            out.hasVoice2 = true;
            out.voice2DetuneCents = variation ? -8.0f : -4.0f;
            out.voice2Gain = 0.72f;
            break;
        case 4: // HIGH_3RD
        default:
            out.voice1Semitones = diatonicInterval(2);
            if (variation) {
                out.voice2Semitones = diatonicInterval(4);
                out.hasVoice2 = true;
                out.voice2Gain = 0.62f;
            }
            break;
        case 5: // HIGHER_5TH
            out.voice1Semitones = diatonicInterval(4);
            if (variation) {
                out.voice2Semitones = diatonicInterval(2);
                out.hasVoice2 = true;
                out.voice2Gain = 0.65f;
            }
            break;
        case 6: // HIGH_AND_LOW
            out.voice1Semitones = diatonicInterval(2);
            out.voice2Semitones = diatonicInterval(-2);
            out.hasVoice2 = true;
            out.voice1Gain = 0.75f;
            out.voice2Gain = 0.75f;
            if (variation) {
                out.voice1DetuneCents = 4.0f;
                out.voice2DetuneCents = -4.0f;
            }
            break;
        case 7: // THIRD_AND_FIFTH
            out.voice1Semitones = diatonicInterval(2);
            out.voice2Semitones = diatonicInterval(4);
            out.hasVoice2 = true;
            out.voice1Gain = 0.78f;
            out.voice2Gain = 0.72f;
            if (variation) {
                out.voice1DetuneCents = 4.0f;
                out.voice2DetuneCents = -4.0f;
            }
            break;
        case 8: // OCT_UP
            out.voice1Semitones = 12;
            if (variation) {
                out.voice2Semitones = diatonicInterval(2);
                out.hasVoice2 = true;
                out.voice2Gain = 0.65f;
            }
            break;
    }

    return out;
}

} // namespace MusicTheoryCpp
