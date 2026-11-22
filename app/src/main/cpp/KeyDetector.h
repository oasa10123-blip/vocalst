#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

struct KeyCandidateCpp {
    int keyIndex = 0;      // 0=C .. 11=B
    bool isMinor = false;
    int scorePct = 0;      // 0..100
};

struct KeyAnalysisResultCpp {
    bool hasLockedKey = false;
    int lockedKeyIndex = 0;
    bool lockedIsMinor = false;
    int liveKeyIndex = 0;
    bool liveIsMinor = false;
    float confidence = 0.0f;       // 0.0..1.0
    bool signalActive = false;
    float dominantFreqHz = 0.0f;
    int dominantMidi = 0;
    int liveChordRoot = 0;         // 0=C .. 11=B
    int liveChordQuality = -1;     // -1=None, 0=Maj, 1=Min, 2=Note/5th
    std::array<float, 12> chroma{};
    std::array<KeyCandidateCpp, 3> topCandidates{};
};

/**
 * Guitar Chord-Progression & Melody Key Detector (HPCP + Dual-Timescale Memory).
 *
 * Designed specifically for setting automatic vocal harmony keys from live guitar
 * strumming, chord progressions (I-V-vi-IV, etc.), fingerpicking, or lead melodies:
 * 1. Local spectral peak picking + parabolic frequency interpolation (rejects strum/pick noise)
 * 2. Fast ~0.38s chord/note recognizer for real-time chord & root tracking
 * 3. Long chord-progression Chromagram accumulator + non-diatonic modulation detector
 *    (stays locked across diatonic chord changes like C->F->G->Am, adapts fast on key modulation)
 * 4. Krumhansl-Schmuckler 24-key correlation + bass tonic tie-breaking + hysteresis lock
 */
class KeyDetectorCpp {
public:
    static constexpr int kFftSize = 4096;
    static constexpr float kPi = 3.14159265358979323846f;

    KeyDetectorCpp() {
        for (int i = 0; i < kFftSize; ++i) {
            hannWindow_[i] = 0.5f * (1.0f - std::cos((2.0f * kPi * static_cast<float>(i)) / static_cast<float>(kFftSize - 1)));
        }
        for (int i = 0; i < kFftSize; ++i) {
            int rev = 0;
            int val = i;
            for (int b = 0; b < 12; ++b) {
                rev = (rev << 1) | (val & 1);
                val >>= 1;
            }
            bitRev_[i] = static_cast<uint16_t>(rev);
        }
        initTemplateStats();
        reset();
    }

    void reset() {
        fastChroma_.fill(0.0f);
        progChroma_.fill(0.0f);
        bassAccum_.fill(0.0f);
        lastNormalizedChroma_.fill(0.0f);
        voicedFrames_ = 0;
        modulationFrames_ = 0;
        hasLockedKey_ = false;
        lockedKey_ = 0;
        lockedIsMinor_ = false;
        liveKey_ = 0;
        liveIsMinor_ = false;
        pendingKey_ = 0;
        pendingIsMinor_ = false;
        pendingHoldCount_ = 0;
        liveChordRoot_ = 0;
        liveChordQuality_ = -1;
        lastConfidence_ = 0.0f;
        lastTop_[0] = {0, false, 0};
        lastTop_[1] = {9, true, 0};
        lastTop_[2] = {7, false, 0};
    }

    KeyAnalysisResultCpp analyzeFrame(
        const float* decimated4096,
        float decimatedSampleRate,
        float noiseGateDb,
        float decayFactor,
        int lockHoldFrames,
        float referenceA4Hz
    ) {
        KeyAnalysisResultCpp out;
        if (!decimated4096 || decimatedSampleRate < 4000.0f) {
            return out;
        }

        // 1. Frame RMS energy check against Noise Gate
        double energySum = 0.0;
        for (int i = 0; i < kFftSize; ++i) {
            const float s = decimated4096[i];
            energySum += static_cast<double>(s) * static_cast<double>(s);
            fftRe_[i] = s * hannWindow_[i];
            fftIm_[i] = 0.0f;
        }
        const float rms = static_cast<float>(std::sqrt(energySum / static_cast<double>(kFftSize)));
        const float rmsDb = (rms > 1e-6f) ? (20.0f * std::log10(rms)) : -120.0f;
        const float effectiveGateDb = std::clamp(noiseGateDb, -72.0f, -18.0f);

        if (rmsDb < effectiveGateDb) {
            // Silence / between chords: freeze chord-progression memory and hold locked key
            out.hasLockedKey = hasLockedKey_;
            out.lockedKeyIndex = lockedKey_;
            out.lockedIsMinor = lockedIsMinor_;
            out.liveKeyIndex = liveKey_;
            out.liveIsMinor = liveIsMinor_;
            out.confidence = lastConfidence_;
            out.signalActive = false;
            out.liveChordRoot = liveChordRoot_;
            out.liveChordQuality = liveChordQuality_;
            out.chroma = lastNormalizedChroma_;
            out.topCandidates = lastTop_;
            return out;
        }

        // 2. Compute 4096-pt in-place Radix-2 FFT
        computeFft();

        // 3. Extract HPCP from local spectral peaks (65 Hz .. 1850 Hz) with parabolic pitch interpolation
        const float binHz = decimatedSampleRate / static_cast<float>(kFftSize);
        const int minBin = std::max(2, static_cast<int>(std::floor(65.0f / binHz)));
        const int maxBin = std::min(kFftSize / 2 - 2, static_cast<int>(std::ceil(1850.0f / binHz)));
        const float safeRefA4 = std::clamp(referenceA4Hz, 425.0f, 455.0f);
        const float invLog2 = 1.44269504089f;

        float peakBinMag = 0.0f;
        float magSum = 0.0f;
        int bestBin = 0;
        int binCount = 0;

        for (int b = minBin - 1; b <= maxBin + 1; ++b) {
            const float re = fftRe_[b];
            const float im = fftIm_[b];
            const float mag = std::sqrt(re * re + im * im) * (2.0f / static_cast<float>(kFftSize));
            binMags_[b] = mag;
            if (b >= minBin && b <= maxBin) {
                magSum += mag;
                binCount++;
                if (mag > peakBinMag) {
                    peakBinMag = mag;
                    bestBin = b;
                }
            }
        }

        const float meanMag = (binCount > 0) ? (magSum / static_cast<float>(binCount)) : 0.0f;
        const float crestRatio = peakBinMag / std::max(1e-7f, meanMag);

        // Reject broadband strum pick-scrape / percussion burst frames with no clear tonal partials
        if (peakBinMag < 2e-5f || crestRatio < 2.8f) {
            out.hasLockedKey = hasLockedKey_;
            out.lockedKeyIndex = lockedKey_;
            out.lockedIsMinor = lockedIsMinor_;
            out.liveKeyIndex = liveKey_;
            out.liveIsMinor = liveIsMinor_;
            out.confidence = lastConfidence_;
            out.signalActive = true;
            out.liveChordRoot = liveChordRoot_;
            out.liveChordQuality = liveChordQuality_;
            out.chroma = lastNormalizedChroma_;
            out.topCandidates = lastTop_;
            return out;
        }

        std::array<float, 12> frameChroma{};
        std::array<float, 12> frameBass{};
        const float minPeakMag = peakBinMag * 0.055f;

        for (int b = minBin; b <= maxBin; ++b) {
            const float m = binMags_[b];
            if (m < minPeakMag) continue;
            const float mL = binMags_[b - 1];
            const float mR = binMags_[b + 1];
            if (m < mL || m < mR) continue; // Local peaks only -> eliminates strum/noise floor

            // Parabolic sub-bin interpolation
            const float denom = mL - 2.0f * m + mR;
            const float delta = (std::fabs(denom) > 1e-9f)
                ? std::clamp(0.5f * (mL - mR) / denom, -0.5f, 0.5f)
                : 0.0f;
            const float freq = (static_cast<float>(b) + delta) * binHz;
            if (freq < 65.0f || freq > 1850.0f) continue;

            // Guitar & melody weighting: ground bass roots strongly, attenuate upper overtones
            const float bandWeight = (freq < 290.0f) ? 1.55f : ((freq < 920.0f) ? 1.10f : 0.38f);
            const float specEnergy = std::log1p(120.0f * m) * bandWeight;

            const float midi = 69.0f + 12.0f * (std::log(freq / safeRefA4) * invLog2);
            float pc = std::fmod(midi, 12.0f);
            if (pc < 0.0f) pc += 12.0f;

            const int pc0 = static_cast<int>(pc) % 12;
            const int pc1 = (pc0 + 1) % 12;
            const float frac = pc - std::floor(pc);

            // Sharpened cosine-squared projection across adjacent semitones
            const float w0 = std::cos(frac * (kPi * 0.5f));
            const float w1 = std::sin(frac * (kPi * 0.5f));
            frameChroma[pc0] += specEnergy * (w0 * w0);
            frameChroma[pc1] += specEnergy * (w1 * w1);

            if (freq < 265.0f) {
                frameBass[pc0] += specEnergy * (w0 * w0);
                frameBass[pc1] += specEnergy * (w1 * w1);
            }
        }

        if (bestBin > 0) {
            out.dominantFreqHz = static_cast<float>(bestBin) * binHz;
            const float m = 69.0f + 12.0f * (std::log(out.dominantFreqHz / safeRefA4) * invLog2);
            out.dominantMidi = static_cast<int>(std::lround(m));
        }

        // Normalize frame chroma vector (L2)
        float frameNormSq = 0.0f;
        for (float v : frameChroma) frameNormSq += v * v;
        if (frameNormSq > 1e-9f) {
            const float invNorm = 1.0f / std::sqrt(frameNormSq);
            for (int i = 0; i < 12; ++i) {
                frameChroma[i] *= invNorm;
            }
        }
        float bassNormSq = 0.0f;
        for (float v : frameBass) bassNormSq += v * v;
        if (bassNormSq > 1e-9f) {
            const float invBass = 1.0f / std::sqrt(bassNormSq);
            for (int i = 0; i < 12; ++i) frameBass[i] *= invBass;
        }

        // 4. Update Fast Chord Tracker (~0.35s) + Live Chord Estimate
        voicedFrames_++;
        const float fastDecay = (voicedFrames_ <= 3) ? (1.0f - 1.0f / static_cast<float>(voicedFrames_)) : 0.84f;
        const float fastAlpha = 1.0f - fastDecay;
        for (int i = 0; i < 12; ++i) {
            fastChroma_[i] = fastChroma_[i] * fastDecay + frameChroma[i] * fastAlpha;
        }
        estimateLiveChord();

        // 5. Check for sustained non-diatonic modulation against locked key
        float nonDiatonicRatio = 0.0f;
        if (hasLockedKey_) {
            float totalFast = 0.0f;
            float outsideFast = 0.0f;
            for (int i = 0; i < 12; ++i) {
                totalFast += fastChroma_[i];
                const int rel = ((i - lockedKey_) + 12) % 12;
                if (!isDiatonicStep(rel, lockedIsMinor_)) {
                    outsideFast += fastChroma_[i];
                }
            }
            nonDiatonicRatio = (totalFast > 1e-6f) ? (outsideFast / totalFast) : 0.0f;
            if (nonDiatonicRatio > 0.34f) {
                modulationFrames_ = std::min(modulationFrames_ + 1, 30);
            } else {
                modulationFrames_ = std::max(0, modulationFrames_ - 1);
            }
        } else {
            modulationFrames_ = 0;
        }

        // 6. Progression-length Chromagram Accumulation
        // Diatonic progressions (e.g. C -> F -> G -> Am) stay unified cleanly;
        // True key modulations (e.g. switching to D - A - Bm - G) accelerate transition.
        const float baseDecay = std::clamp(decayFactor, 0.88f, 0.996f);
        float progDecay = baseDecay;
        float progAlpha = 1.0f - progDecay;

        if (modulationFrames_ >= 5) {
            progAlpha = std::min(0.068f, std::max(0.052f, progAlpha * 3.0f));
            progDecay = 1.0f - progAlpha;
        }

        float maxChroma = 1e-6f;
        for (int i = 0; i < 12; ++i) {
            progChroma_[i] = progChroma_[i] * progDecay + frameChroma[i] * progAlpha;
            bassAccum_[i] = bassAccum_[i] * progDecay + frameBass[i] * progAlpha;
            if (progChroma_[i] > maxChroma) maxChroma = progChroma_[i];
        }
        for (int i = 0; i < 12; ++i) {
            lastNormalizedChroma_[i] = std::clamp(progChroma_[i] / maxChroma, 0.0f, 1.0f);
        }

        // 7. Evaluate 24 Major + Minor keys via Krumhansl-Schmuckler + slight tonic bass anchor
        float meanChroma = 0.0f;
        float totalBass = 1e-6f;
        for (int i = 0; i < 12; ++i) {
            meanChroma += progChroma_[i];
            totalBass += bassAccum_[i];
        }
        meanChroma /= 12.0f;

        float varChroma = 0.0f;
        for (float v : progChroma_) {
            const float d = v - meanChroma;
            varChroma += d * d;
        }
        const float stdChroma = std::sqrt(varChroma);

        struct ScoredKey {
            int key;
            bool isMinor;
            float corr;
        };
        std::array<ScoredKey, 24> scores{};

        float lockedKeyCorr = -1.0f;
        if (stdChroma > 1e-6f) {
            for (int k = 0; k < 12; ++k) {
                float dotMaj = 0.0f;
                float dotMin = 0.0f;
                for (int i = 0; i < 12; ++i) {
                    const int idx = (k + i) % 12;
                    const float cCentered = progChroma_[idx] - meanChroma;
                    dotMaj += cCentered * majorCentered_[i];
                    dotMin += cCentered * minorCentered_[i];
                }
                const float tonicBassBias = 0.045f * (bassAccum_[k] / totalBass);
                const float majScore = (dotMaj / (stdChroma * majorNorm_)) + tonicBassBias;
                const float minScore = (dotMin / (stdChroma * minorNorm_)) + tonicBassBias * 0.9f;
                scores[k * 2] = {k, false, majScore};
                scores[k * 2 + 1] = {k, true, minScore};
                if (hasLockedKey_ && k == lockedKey_) {
                    lockedKeyCorr = lockedIsMinor_ ? minScore : majScore;
                }
            }
        } else {
            for (int k = 0; k < 12; ++k) {
                scores[k * 2] = {k, false, 0.0f};
                scores[k * 2 + 1] = {k, true, 0.0f};
            }
            lockedKeyCorr = 0.0f;
        }

        std::sort(scores.begin(), scores.end(), [](const ScoredKey& a, const ScoredKey& b) {
            return a.corr > b.corr;
        });

        liveKey_ = scores[0].key;
        liveIsMinor_ = scores[0].isMinor;

        const float r1 = scores[0].corr;
        const float r2 = scores[1].corr;
        const float rawConf = std::clamp((r1 - 0.14f) / 0.72f, 0.0f, 1.0f);
        const float marginBoost = std::clamp((r1 - r2) * 2.4f, 0.0f, 0.24f);
        lastConfidence_ = std::clamp(rawConf * 0.82f + marginBoost * 0.75f, 0.0f, 1.0f);

        for (int i = 0; i < 3; ++i) {
            const int pct = static_cast<int>(std::lround(std::clamp(scores[i].corr, 0.0f, 1.0f) * 100.0f));
            lastTop_[i] = {scores[i].key, scores[i].isMinor, pct};
        }

        // Count distinct active pitch classes in progression chromagram so single-note riffs don't lock onto note #1
        int activePitchClasses = 0;
        const float activeThresh = maxChroma * 0.14f;
        for (int i = 0; i < 12; ++i) {
            if (progChroma_[i] >= activeThresh) {
                activePitchClasses++;
            }
        }

        // 8. Stability hysteresis lock across chord progressions
        const int reqHold = std::clamp(lockHoldFrames, 4, 60);
        if (!hasLockedKey_) {
            const bool isMelodyMode = (liveChordQuality_ == 2);
            const int minActivePcs = (isMelodyMode && voicedFrames_ < 65) ? 4 : 3;
            const float minMargin = (isMelodyMode && voicedFrames_ < 65) ? 0.095f : 0.0f;
            if (voicedFrames_ >= 5 &&
                activePitchClasses >= minActivePcs &&
                r1 >= 0.28f &&
                (r1 - r2) >= minMargin) {
                hasLockedKey_ = true;
                lockedKey_ = liveKey_;
                lockedIsMinor_ = liveIsMinor_;
                pendingHoldCount_ = 0;
            }
        } else if (liveKey_ != lockedKey_ || liveIsMinor_ != lockedIsMinor_) {
            const float corrGain = r1 - lockedKeyCorr;
            const float requiredGain = (modulationFrames_ >= 5) ? 0.03f : 0.08f;
            if (corrGain >= requiredGain) {
                if (liveKey_ == pendingKey_ && liveIsMinor_ == pendingIsMinor_) {
                    pendingHoldCount_++;
                } else {
                    pendingKey_ = liveKey_;
                    pendingIsMinor_ = liveIsMinor_;
                    pendingHoldCount_ = 1;
                }
                const int effectiveHold = (modulationFrames_ >= 5) ? std::max(5, reqHold / 2) : reqHold;
                if (pendingHoldCount_ >= effectiveHold && lastConfidence_ >= 0.34f) {
                    lockedKey_ = pendingKey_;
                    lockedIsMinor_ = pendingIsMinor_;
                    pendingHoldCount_ = 0;
                }
            } else {
                // Keep hold steady when diatonic chords (e.g. vi / IV) stay tied with the locked key
                pendingHoldCount_ = std::max(0, pendingHoldCount_ - 1);
            }
        } else {
            pendingHoldCount_ = 0;
        }

        out.hasLockedKey = hasLockedKey_;
        out.lockedKeyIndex = lockedKey_;
        out.lockedIsMinor = lockedIsMinor_;
        out.liveKeyIndex = liveKey_;
        out.liveIsMinor = liveIsMinor_;
        out.confidence = lastConfidence_;
        out.signalActive = true;
        out.liveChordRoot = liveChordRoot_;
        out.liveChordQuality = liveChordQuality_;
        out.chroma = lastNormalizedChroma_;
        out.topCandidates = lastTop_;
        return out;
    }

private:
    static constexpr std::array<float, 12> kMajorProfile = {
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f,
        2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f
    };

    static constexpr std::array<float, 12> kMinorProfile = {
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f,
        2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f
    };

    static bool isDiatonicStep(int rel, bool isMinor) {
        if (!isMinor) {
            return rel == 0 || rel == 2 || rel == 4 || rel == 5 || rel == 7 || rel == 9 || rel == 11;
        } else {
            // Natural minor + harmonic minor leading tone (11)
            return rel == 0 || rel == 2 || rel == 3 || rel == 5 || rel == 7 || rel == 8 || rel == 10 || rel == 11;
        }
    }

    void estimateLiveChord() {
        float sum = 0.0f;
        float top1 = 0.0f;
        float top2 = 0.0f;
        int top1Idx = 0;
        for (int i = 0; i < 12; ++i) {
            const float v = fastChroma_[i];
            sum += v;
            if (v > top1) {
                top2 = top1;
                top1 = v;
                top1Idx = i;
            } else if (v > top2) {
                top2 = v;
            }
        }
        if (sum < 1e-4f) {
            liveChordQuality_ = -1;
            return;
        }
        // Check single note melody / octaves vs polyphonic triad (doubled guitar roots keep 2nd/3rd >= 13% of sum)
        if (top1 > top2 * 3.8f && top2 < sum * 0.13f) {
            liveChordRoot_ = top1Idx;
            liveChordQuality_ = 2; // Melodic note / unison
            return;
        }

        float bestScore = -1e9f;
        int bestRoot = 0;
        int bestQual = 0;
        for (int r = 0; r < 12; ++r) {
            const float root = fastChroma_[r];
            const float m3 = fastChroma_[(r + 3) % 12];
            const float maj3 = fastChroma_[(r + 4) % 12];
            const float p5 = fastChroma_[(r + 7) % 12];
            const float nonChordMaj = sum - (root + maj3 + p5);
            const float nonChordMin = sum - (root + m3 + p5);

            const float scoreMaj = root * 1.15f + maj3 * 0.95f + p5 * 0.80f - nonChordMaj * 0.35f;
            const float scoreMin = root * 1.15f + m3 * 0.95f + p5 * 0.80f - nonChordMin * 0.35f;

            if (scoreMaj > bestScore) {
                bestScore = scoreMaj;
                bestRoot = r;
                bestQual = 0;
            }
            if (scoreMin > bestScore) {
                bestScore = scoreMin;
                bestRoot = r;
                bestQual = 1;
            }
        }
        liveChordRoot_ = bestRoot;
        liveChordQuality_ = bestQual;
    }

    void initTemplateStats() {
        float majMean = 0.0f;
        float minMean = 0.0f;
        for (int i = 0; i < 12; ++i) {
            majMean += kMajorProfile[i];
            minMean += kMinorProfile[i];
        }
        majMean /= 12.0f;
        minMean /= 12.0f;

        float majSq = 0.0f;
        float minSq = 0.0f;
        for (int i = 0; i < 12; ++i) {
            majorCentered_[i] = kMajorProfile[i] - majMean;
            minorCentered_[i] = kMinorProfile[i] - minMean;
            majSq += majorCentered_[i] * majorCentered_[i];
            minSq += minorCentered_[i] * minorCentered_[i];
        }
        majorNorm_ = std::max(1e-6f, std::sqrt(majSq));
        minorNorm_ = std::max(1e-6f, std::sqrt(minSq));
    }

    void computeFft() {
        for (int i = 0; i < kFftSize; ++i) {
            const int j = bitRev_[i];
            if (i < j) {
                std::swap(fftRe_[i], fftRe_[j]);
                std::swap(fftIm_[i], fftIm_[j]);
            }
        }
        for (int len = 2; len <= kFftSize; len <<= 1) {
            const float angle = -2.0f * kPi / static_cast<float>(len);
            const float wLenCos = std::cos(angle);
            const float wLenSin = std::sin(angle);
            const int half = len >> 1;
            for (int start = 0; start < kFftSize; start += len) {
                float wRe = 1.0f;
                float wIm = 0.0f;
                for (int k = 0; k < half; ++k) {
                    const int even = start + k;
                    const int odd = even + half;
                    const float oddRe = fftRe_[odd];
                    const float oddIm = fftIm_[odd];
                    const float tRe = wRe * oddRe - wIm * oddIm;
                    const float tIm = wRe * oddIm + wIm * oddRe;

                    fftRe_[odd] = fftRe_[even] - tRe;
                    fftIm_[odd] = fftIm_[even] - tIm;
                    fftRe_[even] += tRe;
                    fftIm_[even] += tIm;

                    const float nextRe = wRe * wLenCos - wIm * wLenSin;
                    wIm = wRe * wLenSin + wIm * wLenCos;
                    wRe = nextRe;
                }
            }
        }
    }

    std::array<float, kFftSize> hannWindow_{};
    std::array<uint16_t, kFftSize> bitRev_{};
    std::array<float, kFftSize> fftRe_{};
    std::array<float, kFftSize> fftIm_{};
    std::array<float, kFftSize / 2> binMags_{};

    std::array<float, 12> majorCentered_{};
    std::array<float, 12> minorCentered_{};
    float majorNorm_ = 1.0f;
    float minorNorm_ = 1.0f;

    std::array<float, 12> fastChroma_{};
    std::array<float, 12> progChroma_{};
    std::array<float, 12> bassAccum_{};
    std::array<float, 12> lastNormalizedChroma_{};
    int voicedFrames_ = 0;
    int modulationFrames_ = 0;
    bool hasLockedKey_ = false;
    int lockedKey_ = 0;
    bool lockedIsMinor_ = false;
    int liveKey_ = 0;
    bool liveIsMinor_ = false;
    int pendingKey_ = 0;
    bool pendingIsMinor_ = false;
    int pendingHoldCount_ = 0;
    int liveChordRoot_ = 0;
    int liveChordQuality_ = -1;
    float lastConfidence_ = 0.0f;
    std::array<KeyCandidateCpp, 3> lastTop_{};
};
