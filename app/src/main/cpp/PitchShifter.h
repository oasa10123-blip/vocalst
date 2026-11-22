#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

class PitchShifterCpp {
public:
    static constexpr int kRingCapacity = 16384;
    static constexpr int kMask = kRingCapacity - 1;
    static constexpr int kMarkCapacity = 64;
    static constexpr int kMarkMask = kMarkCapacity - 1;
    static constexpr int kTableSize = 1024;
    static constexpr float kClassicWindowSize = 1024.0f;

    // Tight ~8.75ms @ 48kHz COLA lag for instant live feel with safe grain completion margin
    static constexpr int kAnalysisLag = 384;
    static constexpr int kOutputReadLag = 420;
    static constexpr int kMaxHalfWin = 368;

    PitchShifterCpp() {
        constexpr double kPi = 3.14159265358979323846;
        for (int i = 0; i <= kTableSize; ++i) {
            const double u = static_cast<double>(i) / static_cast<double>(kTableSize);
            hannTable_[i] = static_cast<float>(0.5 * (1.0 - std::cos(2.0 * kPi * u)));
        }
        reset();
    }

    void reset() {
        inputRing_.fill(0.0f);
        outputRing_.fill(0.0f);
        weightRing_.fill(0.0f);
        markRing_.fill(0);
        markWriteCount_ = 0;
        lastMarkPos_ = 0;
        writePos_ = 0;
        nextSynthPos_ = 0.0;
        currentRatio_ = 1.0f;
        activePeriodIn_ = 220.0f;
        voicedEnvelope_ = 1.0f;
        classicPhase_ = 0.0f;
        lastModeTdPsola_ = true;
        modeSwitchFade_ = 1.0f;
    }

    inline float processSample(
        float input,
        float targetRatio,
        float detectedFreqHz,
        int sampleRate,
        bool tdPsolaEnabled,
        float pitchSmoothing,
        float grainOverlap,
        float peakSearchRatio,
        float unvoicedDamping,
        bool isVoicedFrame
    ) {
        if (tdPsolaEnabled != lastModeTdPsola_) {
            lastModeTdPsola_ = tdPsolaEnabled;
            modeSwitchFade_ = 0.0f;
            if (tdPsolaEnabled) {
                outputRing_.fill(0.0f);
                weightRing_.fill(0.0f);
                markWriteCount_ = 0;
                lastMarkPos_ = writePos_ - kAnalysisLag;
                nextSynthPos_ = static_cast<double>(writePos_);
            } else {
                classicPhase_ = 0.0f;
            }
        }
        if (modeSwitchFade_ < 1.0f) {
            modeSwitchFade_ = std::min(1.0f, modeSwitchFade_ + 0.005f);
        }

        const double smoothNorm = std::clamp(pitchSmoothing, 0.0f, 1.0f);
        const float slewAlpha = static_cast<float>(0.025 * std::pow(0.024, smoothNorm));
        currentRatio_ += (targetRatio - currentRatio_) * slewAlpha;

        inputRing_[writePos_ & kMask] = input;

        const float outSample = tdPsolaEnabled
            ? processTdPsolaSample(
                  detectedFreqHz,
                  sampleRate,
                  std::clamp(grainOverlap, 0.80f, 1.50f),
                  std::clamp(peakSearchRatio, 0.05f, 0.45f),
                  std::clamp(unvoicedDamping, 0.10f, 1.00f),
                  isVoicedFrame)
            : processClassicDualTapSample();

        writePos_++;
        return outSample * modeSwitchFade_;
    }

private:
    inline float processTdPsolaSample(
        float detectedFreqHz,
        int sampleRate,
        float grainOverlap,
        float peakSearchRatio,
        float unvoicedDamping,
        bool isVoicedFrame
    ) {
        const bool hasPitch = (detectedFreqHz >= 70.0f && detectedFreqHz <= 950.0f);
        if (hasPitch) {
            const float targetPeriodIn = std::clamp(
                static_cast<float>(sampleRate) / detectedFreqHz, 48.0f, 640.0f);
            activePeriodIn_ += (targetPeriodIn - activePeriodIn_) * 0.012f;
        }
        const float pIn = activePeriodIn_;

        const float targetVoicedEnv =
            (isVoicedFrame || (currentRatio_ >= 0.97f && currentRatio_ <= 1.03f))
                ? 1.0f
                : unvoicedDamping;
        voicedEnvelope_ += (targetVoicedEnv - voicedEnvelope_) * 0.004f;

        const float ratio = std::clamp(currentRatio_, 0.45f, 2.2f);
        const float periodOut = std::clamp(pIn / ratio, 32.0f, 680.0f);

        const int safeHead = writePos_ - kAnalysisLag;
        if (markWriteCount_ == 0) {
            if (safeHead >= 0) {
                lastMarkPos_ = safeHead;
                markRing_[0] = lastMarkPos_;
                markWriteCount_ = 1;
            }
        } else {
            int guard = 0;
            while (static_cast<float>(safeHead - lastMarkPos_) >= pIn && guard < 4) {
                guard++;
                const int expected = lastMarkPos_ + static_cast<int>(std::lround(pIn));
                int nextMark = std::min(expected, safeHead);

                if (isVoicedFrame && hasPitch && peakSearchRatio > 0.01f) {
                    const int radius = std::clamp(static_cast<int>(pIn * peakSearchRatio), 4, 150);
                    const int minAllowed = lastMarkPos_ + std::max(32, static_cast<int>(pIn * 0.65f));
                    const int startSearch = std::max(minAllowed, expected - radius);
                    const int endSearch = std::min(safeHead, expected + radius);
                    if (startSearch <= endSearch) {
                        int bestIdx = std::clamp(expected, startSearch, endSearch);
                        float bestScore = 0.0f;
                        const float invRadiusSq = 1.0f / static_cast<float>(std::max(1, radius * radius));
                        for (int p = startSearch; p <= endSearch; ++p) {
                            const float smoothed =
                                0.10f * inputRing_[(p - 2) & kMask] +
                                0.20f * inputRing_[(p - 1) & kMask] +
                                0.40f * inputRing_[p & kMask] +
                                0.20f * inputRing_[(p + 1) & kMask] +
                                0.10f * inputRing_[(p + 2) & kMask];
                            if (smoothed > 0.0f) {
                                const float dist = static_cast<float>(p - expected);
                                const float centerBias = 1.0f - 0.18f * (dist * dist * invRadiusSq);
                                const float score = smoothed * centerBias;
                                if (score > bestScore) {
                                    bestScore = score;
                                    bestIdx = p;
                                }
                            }
                        }
                        nextMark = (bestScore > 0.0f) ? bestIdx : std::clamp(expected, minAllowed, safeHead);
                    } else {
                        nextMark = std::clamp(expected, minAllowed, safeHead);
                    }
                }

                lastMarkPos_ = nextMark;
                markRing_[markWriteCount_ & kMarkMask] = nextMark;
                markWriteCount_++;
            }
        }

        const double analysisCursor = std::max(0.0, static_cast<double>(safeHead) - pIn * 0.65);
        if (nextSynthPos_ < static_cast<double>(writePos_) - 32.0) {
            nextSynthPos_ = static_cast<double>(writePos_);
        }
        while (nextSynthPos_ <= static_cast<double>(writePos_)) {
            const int synthCenter = static_cast<int>(nextSynthPos_);
            const int minSafeHalfWin = static_cast<int>(periodOut * 1.04f);
            const int desiredHalfWin = static_cast<int>(std::max(pIn, periodOut) * grainOverlap);
            const int halfWin = std::clamp(std::max(minSafeHalfWin, desiredHalfWin), 64, kMaxHalfWin);

            int pm = safeHead;
            const int count = std::min(markWriteCount_, 20);
            if (count > 0) {
                double bestDist = 1e18;
                for (int i = 1; i <= count; ++i) {
                    const int candidate = markRing_[(markWriteCount_ - i) & kMarkMask];
                    if (candidate > safeHead) continue;
                    const double d = std::fabs(static_cast<double>(candidate) - analysisCursor);
                    if (d < bestDist) {
                        bestDist = d;
                        pm = candidate;
                    } else if (static_cast<double>(candidate) < analysisCursor) {
                        break;
                    }
                }
            }

            const float invDoubleW = static_cast<float>(kTableSize) / (2.0f * static_cast<float>(halfWin));
            for (int k = -halfWin; k <= halfWin; ++k) {
                const int tIdx = std::clamp(
                    static_cast<int>(static_cast<float>(k + halfWin) * invDoubleW), 0, kTableSize);
                const float w = hannTable_[tIdx];
                const float s = inputRing_[(pm + k) & kMask];
                const int oIdx = (synthCenter + k) & kMask;
                outputRing_[oIdx] += s * w;
                weightRing_[oIdx] += w;
            }
            nextSynthPos_ += static_cast<double>(periodOut);
        }

        const int outPos = writePos_ - kOutputReadLag;
        const int outIdx = outPos & kMask;
        const float wSum = weightRing_[outIdx];
        const float outSample = (wSum > 0.05f) ? ((outputRing_[outIdx] / wSum) * voicedEnvelope_) : 0.0f;
        outputRing_[outIdx] = 0.0f;
        weightRing_[outIdx] = 0.0f;
        return outSample;
    }

    inline float processClassicDualTapSample() {
        const float ratio = std::clamp(currentRatio_, 0.45f, 2.2f);
        classicPhase_ += (1.0f - ratio) / kClassicWindowSize;
        while (classicPhase_ >= 1.0f) classicPhase_ -= 1.0f;
        while (classicPhase_ < 0.0f) classicPhase_ += 1.0f;

        const float phaseA = classicPhase_;
        const float phaseB = (phaseA + 0.5f >= 1.0f) ? (phaseA - 0.5f) : (phaseA + 0.5f);

        const float delayA = 64.0f + phaseA * kClassicWindowSize;
        const float delayB = 64.0f + phaseB * kClassicWindowSize;

        const float sampleA = readInterpolated(static_cast<float>(writePos_) - delayA);
        const float sampleB = readInterpolated(static_cast<float>(writePos_) - delayB);

        const float wA = hannTable_[std::clamp(static_cast<int>(phaseA * kTableSize), 0, kTableSize)];
        const float wB = 1.0f - wA;
        return sampleA * wA + sampleB * wB;
    }

    inline float readInterpolated(float pos) const {
        const int i0 = static_cast<int>(std::floor(pos));
        const float frac = pos - static_cast<float>(i0);
        const float s0 = inputRing_[i0 & kMask];
        const float s1 = inputRing_[(i0 + 1) & kMask];
        return s0 + (s1 - s0) * frac;
    }

    std::array<float, kRingCapacity> inputRing_{};
    std::array<float, kRingCapacity> outputRing_{};
    std::array<float, kRingCapacity> weightRing_{};
    std::array<int, kMarkCapacity> markRing_{};
    std::array<float, kTableSize + 1> hannTable_{};

    int markWriteCount_ = 0;
    int lastMarkPos_ = 0;
    int writePos_ = 0;
    double nextSynthPos_ = 0.0;
    float currentRatio_ = 1.0f;
    float activePeriodIn_ = 220.0f;
    float voicedEnvelope_ = 1.0f;
    float classicPhase_ = 0.0f;
    bool lastModeTdPsola_ = true;
    float modeSwitchFade_ = 1.0f;
};
