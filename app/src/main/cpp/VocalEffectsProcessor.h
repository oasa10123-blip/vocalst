#pragma once

#include <algorithm>
#include <array>
#include <cmath>

class VocalEffectsProcessorCpp {
public:
    static constexpr int kDelayCapacity = 32768;
    static constexpr int kDelayMask = kDelayCapacity - 1;
    static constexpr int kCombCapacity = 2400;
    static constexpr int kAllpassCapacity = 1024;

    void configureSampleRate(int sampleRate) {
        sr_ = std::max(8000.0f, static_cast<float>(sampleRate));
        constexpr float kTwoPi = 6.28318530718f;
        const float rc = 1.0f / (kTwoPi * 80.0f);
        const float dt = 1.0f / sr_;
        hpfCoeff_ = rc / (rc + dt);
        presenceLpCoeff_ = std::exp((-kTwoPi * 2400.0f) / sr_);
    }

    void reset() {
        hpfPrevIn_ = 0.0f;
        hpfPrevOut_ = 0.0f;
        presenceLpState_ = 0.0f;
        gateEnv_ = 0.0f;
        gateGain_ = 1.0f;
        compEnv_ = 0.0f;
        delayBuffer_.fill(0.0f);
        delayWriteIdx_ = 0;
        delayLpState_ = 0.0f;
        for (int i = 0; i < 4; ++i) {
            combBuffers_[i].fill(0.0f);
            combIndices_[i] = 0;
            combLpStates_[i] = 0.0f;
        }
        for (int i = 0; i < 2; ++i) {
            allpassBuffers_[i].fill(0.0f);
            allpassIndices_[i] = 0;
        }
    }

    inline float processInputChannel(
        float sample,
        float micGainLinear,
        float noiseGateDb,
        bool enhanceEnabled
    ) {
        float x = sample * micGainLinear;

        const float hpfOut = hpfCoeff_ * (hpfPrevOut_ + x - hpfPrevIn_);
        hpfPrevIn_ = x;
        hpfPrevOut_ = hpfOut;
        x = hpfOut;

        const float absX = std::fabs(x);
        gateEnv_ += (absX > gateEnv_) ? (0.02f * (absX - gateEnv_)) : (0.0008f * (absX - gateEnv_));
        const float gateThreshLinear = std::pow(10.0f, noiseGateDb / 20.0f);
        const float targetGateGain = (gateEnv_ > gateThreshLinear) ? 1.0f : 0.04f;
        gateGain_ += (targetGateGain - gateGain_) * ((targetGateGain > gateGain_) ? 0.015f : 0.0015f);
        x *= gateGain_;

        if (enhanceEnabled) {
            presenceLpState_ = presenceLpCoeff_ * presenceLpState_ + (1.0f - presenceLpCoeff_) * x;
            const float highs = x - presenceLpState_;
            x += highs * 0.42f;

            const float envAbs = std::fabs(x);
            compEnv_ += (envAbs > compEnv_) ? (0.01f * (envAbs - compEnv_)) : (0.0005f * (envAbs - compEnv_));
            constexpr float kCompThreshold = 0.18f;
            if (compEnv_ > kCompThreshold) {
                const float excess = compEnv_ - kCompThreshold;
                const float gain = (kCompThreshold + excess * 0.35f) / compEnv_;
                x *= gain * 1.15f;
            } else {
                x *= 1.15f;
            }
        }
        return x;
    }

    inline float processEcho(
        float dryPlusHarmony,
        int echoModeOrdinal, // 0=OFF, 1=REVERB, 2=REV_DLY, 3=DELAY
        float echoMix
    ) {
        if (echoModeOrdinal == 0 || echoMix <= 0.001f) {
            delayBuffer_[delayWriteIdx_] = 0.0f;
            delayWriteIdx_ = (delayWriteIdx_ + 1) & kDelayMask;
            return dryPlusHarmony;
        }

        const float scaleFactor = std::clamp(sr_ / 44100.0f, 0.5f, 2.5f);
        float reverbOut = 0.0f;
        float delayOut = 0.0f;

        if (echoModeOrdinal == 1 || echoModeOrdinal == 2) {
            static constexpr int kCombDelays[4] = {1557, 1617, 1491, 1422};
            static constexpr int kAllpassDelays[2] = {225, 556};
            float combSum = 0.0f;
            constexpr float kFeedback = 0.78f;

            for (int i = 0; i < 4; ++i) {
                const int len = std::clamp(
                    static_cast<int>(static_cast<float>(kCombDelays[i]) * scaleFactor),
                    64, kCombCapacity - 1);
                const int idx = combIndices_[i];
                float* buf = combBuffers_[i].data();
                const float delayed = buf[idx];
                combLpStates_[i] = delayed * 0.72f + combLpStates_[i] * 0.28f;
                buf[idx] = dryPlusHarmony * 0.25f + combLpStates_[i] * kFeedback;
                combIndices_[i] = (idx + 1 >= len) ? 0 : (idx + 1);
                combSum += delayed;
            }

            float ap = combSum;
            for (int i = 0; i < 2; ++i) {
                const int len = std::clamp(
                    static_cast<int>(static_cast<float>(kAllpassDelays[i]) * scaleFactor),
                    32, kAllpassCapacity - 1);
                const int idx = allpassIndices_[i];
                float* buf = allpassBuffers_[i].data();
                const float delayed = buf[idx];
                const float out = -ap + delayed;
                buf[idx] = ap + delayed * 0.5f;
                allpassIndices_[i] = (idx + 1 >= len) ? 0 : (idx + 1);
                ap = out;
            }
            reverbOut = ap * 0.45f;
        }

        if (echoModeOrdinal == 3 || echoModeOrdinal == 2) {
            const int delaySamples = std::clamp(
                static_cast<int>(0.295f * sr_), 256, kDelayCapacity - 1);
            const int readIdx = (delayWriteIdx_ - delaySamples + kDelayCapacity) & kDelayMask;
            const float tap = delayBuffer_[readIdx];
            delayLpState_ = tap * 0.65f + delayLpState_ * 0.35f;
            delayBuffer_[delayWriteIdx_] = dryPlusHarmony + delayLpState_ * 0.34f;
            delayWriteIdx_ = (delayWriteIdx_ + 1) & kDelayMask;
            delayOut = tap * 0.55f;
        } else {
            delayBuffer_[delayWriteIdx_] = 0.0f;
            delayWriteIdx_ = (delayWriteIdx_ + 1) & kDelayMask;
        }

        float wet = 0.0f;
        if (echoModeOrdinal == 1) wet = reverbOut;
        else if (echoModeOrdinal == 3) wet = delayOut;
        else if (echoModeOrdinal == 2) wet = reverbOut * 0.72f + delayOut * 0.62f;

        return dryPlusHarmony + wet * (echoMix * 0.95f);
    }

    static inline float softLimit(float sample) {
        return (std::fabs(sample) > 0.75f) ? (std::tanh(sample) * 0.96f) : sample;
    }

private:
    float sr_ = 48000.0f;
    float hpfPrevIn_ = 0.0f;
    float hpfPrevOut_ = 0.0f;
    float hpfCoeff_ = 0.989f;
    float presenceLpState_ = 0.0f;
    float presenceLpCoeff_ = 0.72f;
    float gateEnv_ = 0.0f;
    float gateGain_ = 1.0f;
    float compEnv_ = 0.0f;

    std::array<float, kDelayCapacity> delayBuffer_{};
    int delayWriteIdx_ = 0;
    float delayLpState_ = 0.0f;

    std::array<std::array<float, kCombCapacity>, 4> combBuffers_{};
    std::array<int, 4> combIndices_{};
    std::array<float, 4> combLpStates_{};

    std::array<std::array<float, kAllpassCapacity>, 2> allpassBuffers_{};
    std::array<int, 2> allpassIndices_{};
};
