#pragma once

#include <algorithm>
#include <array>
#include <cmath>

class PitchDetectorCpp {
public:
    static constexpr int kWindowSize = 1024;
    static constexpr int kHalfWindow = kWindowSize / 2;

    bool isCurrentFrameVoiced = false;

    void reset() {
        smoothedFreqHz_ = 0.0f;
        unvoicedCount_ = 0;
        jumpCandidateHz_ = 0.0f;
        jumpCandidateCount_ = 0;
        isCurrentFrameVoiced = false;
    }

    float detectPitchHz(const float* samples1024, int sampleRate, float minRmsThreshold = 0.008f) {
        float energy = 0.0f;
        for (int i = 0; i < kWindowSize; ++i) {
            const float s = samples1024[i];
            analysisFrame_[i] = s;
            energy += s * s;
        }
        const float rms = std::sqrt(energy / static_cast<float>(kWindowSize));
        if (rms < minRmsThreshold) {
            isCurrentFrameVoiced = false;
            unvoicedCount_++;
            if (unvoicedCount_ > 18) {
                smoothedFreqHz_ = 0.0f;
                jumpCandidateCount_ = 0;
            }
            return smoothedFreqHz_;
        }

        const int minTau = std::max(16, sampleRate / 950);
        const int maxTau = std::min(kHalfWindow - 1, sampleRate / 75);
        if (maxTau <= minTau + 3) {
            isCurrentFrameVoiced = false;
            return smoothedFreqHz_;
        }

        diffBuffer_[0] = 0.0f;
        for (int tau = 1; tau <= maxTau; ++tau) {
            float sum = 0.0f;
            for (int j = 0; j < kHalfWindow; j += 2) {
                const float delta = analysisFrame_[j] - analysisFrame_[j + tau];
                sum += delta * delta;
            }
            diffBuffer_[tau] = sum;
        }

        cmndBuffer_[0] = 1.0f;
        float runningSum = 0.0f;
        for (int tau = 1; tau <= maxTau; ++tau) {
            runningSum += diffBuffer_[tau];
            cmndBuffer_[tau] = (runningSum > 1e-9f)
                ? (diffBuffer_[tau] * static_cast<float>(tau) / runningSum)
                : 1.0f;
        }

        constexpr float kThreshold = 0.16f;
        int foundTau = -1;
        int tau = minTau;
        while (tau < maxTau) {
            if (cmndBuffer_[tau] < kThreshold) {
                while (tau + 1 < maxTau && cmndBuffer_[tau + 1] < cmndBuffer_[tau]) {
                    tau++;
                }
                foundTau = tau;
                break;
            }
            tau++;
        }

        if (foundTau == -1) {
            float minVal = 0.28f;
            for (int t = minTau; t < maxTau; ++t) {
                if (cmndBuffer_[t] < minVal) {
                    minVal = cmndBuffer_[t];
                    foundTau = t;
                }
            }
        }

        if (foundTau <= 2 || foundTau >= maxTau - 2) {
            isCurrentFrameVoiced = false;
            unvoicedCount_++;
            if (unvoicedCount_ > 18) {
                smoothedFreqHz_ = 0.0f;
                jumpCandidateCount_ = 0;
            }
            return smoothedFreqHz_;
        }

        const int fineStart = std::max(1, std::max(minTau - 1, foundTau - 2));
        const int fineEnd = std::min(maxTau - 1, foundTau + 2);
        int bestFineTau = foundTau;
        float bestFineDiff = 1e30f;
        std::array<float, 5> fineSums{};

        for (int t = fineStart; t <= fineEnd; ++t) {
            float sum = 0.0f;
            for (int j = 0; j < kHalfWindow; ++j) {
                const float delta = analysisFrame_[j] - analysisFrame_[j + t];
                sum += delta * delta;
            }
            fineSums[t - fineStart] = sum;
            if (sum < bestFineDiff) {
                bestFineDiff = sum;
                bestFineTau = t;
            }
        }

        const int idx = bestFineTau - fineStart;
        float betterTau = static_cast<float>(bestFineTau);
        if (idx >= 1 && idx <= (fineEnd - fineStart - 1)) {
            const float s0 = fineSums[idx - 1];
            const float s1 = fineSums[idx];
            const float s2 = fineSums[idx + 1];
            const float denom = 2.0f * (2.0f * s1 - s2 - s0);
            if (std::fabs(denom) > 1e-6f) {
                betterTau = static_cast<float>(bestFineTau) + (s2 - s0) / denom;
            }
        }

        const float freq = static_cast<float>(sampleRate) / betterTau;
        if (freq >= 70.0f && freq <= 1000.0f) {
            unvoicedCount_ = 0;
            isCurrentFrameVoiced = true;
            if (smoothedFreqHz_ <= 0.0f) {
                smoothedFreqHz_ = freq;
                jumpCandidateCount_ = 0;
            } else {
                const float ratio = freq / smoothedFreqHz_;
                if (ratio >= 0.78f && ratio <= 1.28f) {
                    smoothedFreqHz_ = smoothedFreqHz_ * 0.65f + freq * 0.35f;
                    jumpCandidateCount_ = 0;
                } else {
                    if (jumpCandidateCount_ > 0 && (freq / jumpCandidateHz_) >= 0.88f &&
                        (freq / jumpCandidateHz_) <= 1.14f) {
                        jumpCandidateCount_++;
                    } else {
                        jumpCandidateHz_ = freq;
                        jumpCandidateCount_ = 1;
                    }
                    if (jumpCandidateCount_ >= 3) {
                        smoothedFreqHz_ = freq;
                        jumpCandidateCount_ = 0;
                    }
                }
            }
        } else {
            isCurrentFrameVoiced = false;
        }
        return smoothedFreqHz_;
    }

private:
    std::array<float, kHalfWindow> diffBuffer_{};
    std::array<float, kHalfWindow> cmndBuffer_{};
    std::array<float, kWindowSize> analysisFrame_{};

    float smoothedFreqHz_ = 0.0f;
    int unvoicedCount_ = 0;
    float jumpCandidateHz_ = 0.0f;
    int jumpCandidateCount_ = 0;
};
