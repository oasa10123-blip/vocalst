#include <android/log.h>
#include <jni.h>
#include <oboe/Oboe.h>
#include <time.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "KeyDetector.h"
#include "VocalEffectsProcessor.h"

#define LOG_TAG "VocalStageNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kMaxBurstFrames = 4096;
constexpr int kJitterRingCap = 8192;
constexpr int kJitterRingMask = kJitterRingCap - 1;
constexpr int kKeyRingCap = 8192;
constexpr int kKeyRingMask = kKeyRingCap - 1;

struct AtomicParams {
    std::atomic<float> micGainDb{2.0f};
    std::atomic<float> noiseGateDb{-56.0f};
    std::atomic<bool> directMonitor{false};
    std::atomic<float> keyDecayFactor{0.95f};
    std::atomic<int> keyLockHoldFrames{20};
    std::atomic<float> referenceA4Hz{440.0f};
    std::atomic<int> usbChannelMode{0}; // 0=MIX_1_2, 1=CH_1_LEFT, 2=CH_2_RIGHT
    std::atomic<bool> testFilePlaying{true};
};

class VocalNativeEngine : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    AtomicParams params;

    ~VocalNativeEngine() override {
        stop();
    }

    void setTestClip(const float* samples, int32_t length, int32_t clipSampleRate) {
        std::lock_guard<std::mutex> lock(clipMutex_);
        if (samples != nullptr && length > 0) {
            testClip_.assign(samples, samples + length);
            testClipSampleRate_ = std::max(8000, clipSampleRate);
            testPlayHead_ = 0.0;
        } else {
            testClip_.clear();
        }
    }

    void seekTestFile(float progress) {
        std::lock_guard<std::mutex> lock(clipMutex_);
        if (!testClip_.empty()) {
            const double p = std::clamp(static_cast<double>(progress), 0.0, 0.999);
            testPlayHead_ = p * static_cast<double>(testClip_.size());
        }
    }

    void resetPeakClips() {
        peakClipCount_.store(0, std::memory_order_relaxed);
    }

    void resetKeyDetection() {
        resetKeyRequested_.store(true, std::memory_order_release);
        asyncHasLockedKey_.store(false, std::memory_order_relaxed);
        asyncKeyConfidence_.store(0.0f, std::memory_order_relaxed);
        asyncSignalActive_.store(false, std::memory_order_relaxed);
        for (auto& bin : asyncChroma_) {
            bin.store(0.0f, std::memory_order_relaxed);
        }
    }

    bool start(
        int32_t requestedSampleRate,
        bool preferFloat,
        int32_t requestedFrames,
        int32_t bufferMultiplier,
        bool forceLowLatency,
        int32_t inputDeviceId,
        int32_t outputDeviceId,
        bool useTestFile,
        bool isUsbIn,
        bool isUsbOut,
        bool preferStereoIn
    ) {
        stop();

        std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);

        useTestFile_.store(useTestFile, std::memory_order_relaxed);
        forceLowLatency_.store(forceLowLatency, std::memory_order_relaxed);
        isUsbIn_.store(isUsbIn, std::memory_order_relaxed);
        isUsbOut_.store(isUsbOut, std::memory_order_relaxed);
        streamDisconnected_.store(false, std::memory_order_relaxed);
        peakClipCount_.store(0, std::memory_order_relaxed);

        // Reset analyzer & buffers cleanly before opening streams
        keyDetector_.reset();
        resetKeyRequested_.store(false, std::memory_order_relaxed);
        inJitterRing_.fill(0.0f);
        keyInputRing_.fill(0.0f);
        keySnapshot_.fill(0.0f);
        waveInSnapshot_.fill(0.0f);
        waveOutSnapshot_.fill(0.0f);
        jitterWritePos_ = 0;
        jitterReadPos_ = 0;
        keyWritePos_ = 0;
        decimAcc_ = 0.0f;
        decimCount_ = 0;
        decimSamplesSinceAnalysis_ = 0;
        keyWorkPending_.store(false, std::memory_order_relaxed);
        keyThreadBusy_.store(false, std::memory_order_relaxed);
        waveIdx_ = 0;
        callbackCount_ = 0;
        lastXruns_ = 0;
        inGlitchCount_ = 0;
        lastTuneCallback_ = 0;
        lastInSample_ = 0.0f;
        dspAvgMs_.store(0.0f, std::memory_order_relaxed);
        dspMaxMs_.store(0.0f, std::memory_order_relaxed);
        smoothedInQueueFrames_ = static_cast<float>(requestedFrames > 0 ? requestedFrames : 128);
        smoothedOutHwFrames_ = 0.0f;

        asyncHasLockedKey_.store(false, std::memory_order_relaxed);
        asyncLockedKey_.store(0, std::memory_order_relaxed);
        asyncLockedIsMinor_.store(false, std::memory_order_relaxed);
        asyncLiveKey_.store(0, std::memory_order_relaxed);
        asyncLiveIsMinor_.store(false, std::memory_order_relaxed);
        asyncKeyConfidence_.store(0.0f, std::memory_order_relaxed);
        asyncSignalActive_.store(false, std::memory_order_relaxed);
        asyncDomFreqHz_.store(0.0f, std::memory_order_relaxed);
        asyncDomMidi_.store(0, std::memory_order_relaxed);
        for (auto& b : asyncChroma_) b.store(0.0f, std::memory_order_relaxed);
        asyncTopKey_[0].store(0, std::memory_order_relaxed);
        asyncTopKey_[1].store(9, std::memory_order_relaxed);
        asyncTopKey_[2].store(7, std::memory_order_relaxed);
        asyncTopMinor_[0].store(0, std::memory_order_relaxed);
        asyncTopMinor_[1].store(1, std::memory_order_relaxed);
        asyncTopMinor_[2].store(0, std::memory_order_relaxed);
        asyncTopScore_[0].store(0, std::memory_order_relaxed);
        asyncTopScore_[1].store(0, std::memory_order_relaxed);
        asyncTopScore_[2].store(0, std::memory_order_relaxed);

        if (!openOutputStream(
                requestedSampleRate,
                preferFloat,
                requestedFrames,
                forceLowLatency,
                isUsbOut,
                outputDeviceId)) {
            LOGE("Failed to open Oboe output stream");
            closeStreamsLocked();
            return false;
        }

        actualSampleRate_ = outStream_->getSampleRate();
        decimFactor_ = std::max(1, static_cast<int32_t>(std::lround(static_cast<double>(actualSampleRate_) / 12000.0)));
        decimatedSampleRate_ = static_cast<float>(actualSampleRate_) / static_cast<float>(decimFactor_);

        outBurst_ = std::max(16, outStream_->getFramesPerBurst());
        outFormat_ = outStream_->getFormat();
        outChannels_ = outStream_->getChannelCount();
        const int32_t cbFrames = outStream_->getFramesPerDataCallback();
        activeCallbackFrames_ = (cbFrames > 0) ? cbFrames : outBurst_;

        const int32_t mult = std::clamp(bufferMultiplier, 1, 4);
        const int32_t baseBurst = std::max(outBurst_ * 2, requestedFrames * 2);
        const int32_t cap = std::max(outBurst_, outStream_->getBufferCapacityInFrames());
        const int32_t desiredBuf = std::clamp(baseBurst * mult, outBurst_, cap);
        auto setRes = outStream_->setBufferSizeInFrames(desiredBuf);
        if (!setRes) {
            outStream_->setBufferSizeInFrames(std::min(cap, outBurst_ * 2));
        }

        smoothedOutHwFrames_ = static_cast<float>(outStream_->getBufferSizeInFrames());

        if (!useTestFile) {
            if (!openInputStream(
                    actualSampleRate_,
                    outFormat_,
                    forceLowLatency,
                    isUsbIn,
                    preferStereoIn,
                    inputDeviceId)) {
                LOGE("Failed to open Oboe input stream");
                closeStreamsLocked();
                return false;
            }
            inChannels_ = std::max(1, inStream_->getChannelCount());
            inFormat_ = inStream_->getFormat();
            inCapFrames_ = std::max(outBurst_, inStream_->getBufferCapacityInFrames());
            inBurst_ = std::max(16, inStream_->getFramesPerBurst());
            targetInCushion_ = std::max(activeCallbackFrames_ * 2, inBurst_ + activeCallbackFrames_);
            inGlitchCount_ = 0;
            smoothedInQueueFrames_ = static_cast<float>(targetInCushion_);
        } else {
            inChannels_ = 1;
            inCapFrames_ = activeCallbackFrames_;
            inBurst_ = activeCallbackFrames_;
            targetInCushion_ = activeCallbackFrames_;
            inGlitchCount_ = 0;
            smoothedInQueueFrames_ = 0.0f;
        }

        running_.store(true, std::memory_order_release);
        keyThread_ = std::thread([this]() { keyLoop(); });

        if (inStream_) {
            auto inStartRes = inStream_->requestStart();
            if (inStartRes != oboe::Result::OK) {
                LOGE("Input stream requestStart failed: %s", oboe::convertToText(inStartRes));
                stopInternalLocked();
                return false;
            }
            drainInputPreRoll();
        }

        auto outStartRes = outStream_->requestStart();
        if (outStartRes != oboe::Result::OK) {
            LOGE("Output stream requestStart failed: %s", oboe::convertToText(outStartRes));
            stopInternalLocked();
            return false;
        }

        LOGI("Oboe Key Engine Started: SR=%d (Decim=%dx -> %.1fHz), API=%s, Sharing=%s, Cb=%d, Burst=%d, OutBuf=%d/%d, InCh=%d",
             actualSampleRate_,
             decimFactor_,
             decimatedSampleRate_,
             outStream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSLES",
             outStream_->getSharingMode() == oboe::SharingMode::Exclusive ? "EXCLUSIVE" : "SHARED",
             activeCallbackFrames_,
             outBurst_,
             outStream_->getBufferSizeInFrames(),
             outStream_->getBufferCapacityInFrames(),
             inChannels_);
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
        stopInternalLocked();
    }

    bool isRunning() const {
        return running_.load(std::memory_order_acquire) &&
               !streamDisconnected_.load(std::memory_order_relaxed);
    }

    void fillTelemetry(
        JNIEnv* env,
        jfloatArray floatOut, // size >= 160
        jintArray intOut      // size >= 32
    ) {
        std::array<float, 160> f{};
        std::array<int32_t, 32> iv{};

        const bool active = isRunning();
        const int32_t sr = std::max(8000, actualSampleRate_);
        const float inQ = active ? std::max(static_cast<float>(activeCallbackFrames_),
                                            lastTelemetryInQueueFrames_.load(std::memory_order_relaxed))
                                 : 0.0f;
        const float outHw = active ? std::max(static_cast<float>(lastTelemetryOutBufFrames_.load(std::memory_order_relaxed)),
                                              lastTelemetryOutHwFrames_.load(std::memory_order_relaxed))
                                   : 0.0f;
        const float inMs = useTestFile_.load(std::memory_order_relaxed)
            ? 0.0f
            : (inQ / static_cast<float>(sr)) * 1000.0f;
        const float dspMs = dspAvgMs_.load(std::memory_order_relaxed);
        const float outMs = (outHw / static_cast<float>(sr)) * 1000.0f;
        const float totalMs = active ? (inMs + dspMs + outMs) : 0.0f;

        f[0] = inRmsDb_.load(std::memory_order_relaxed);
        f[1] = inPeakDb_.load(std::memory_order_relaxed);
        f[2] = -60.0f;
        f[3] = outRmsDb_.load(std::memory_order_relaxed);
        f[4] = outPeakDb_.load(std::memory_order_relaxed);
        f[5] = asyncDomFreqHz_.load(std::memory_order_relaxed);
        f[6] = inMs;
        f[7] = dspMs;
        f[8] = dspMaxMs_.load(std::memory_order_relaxed);
        f[9] = outMs;
        f[10] = totalMs;
        f[11] = testProgress_.load(std::memory_order_relaxed);
        f[12] = asyncKeyConfidence_.load(std::memory_order_relaxed);

        const int32_t wHead = waveIdx_;
        for (int i = 0; i < 64; ++i) {
            const int idx = (wHead + i) & 63;
            f[18 + i] = waveInSnapshot_[idx];
            f[82 + i] = waveOutSnapshot_[idx];
        }
        for (int i = 0; i < 12; ++i) {
            f[146 + i] = asyncChroma_[i].load(std::memory_order_relaxed);
        }

        iv[0] = active ? 1 : 0;
        iv[1] = sr;
        iv[2] = activeCallbackFrames_;
        iv[3] = outBurst_;
        iv[4] = lastTelemetryOutBufFrames_.load(std::memory_order_relaxed);
        iv[5] = outCapFrames_;
        iv[6] = inCapFrames_;
        iv[7] = lastXruns_ + inGlitchCount_;
        iv[8] = peakClipCount_.load(std::memory_order_relaxed);
        iv[9] = inPeakClipping_.load(std::memory_order_relaxed) ? 1 : 0;
        iv[10] = asyncDomMidi_.load(std::memory_order_relaxed);
        iv[11] = asyncLockedKey_.load(std::memory_order_relaxed);
        iv[12] = asyncLockedIsMinor_.load(std::memory_order_relaxed) ? 1 : 0;
        iv[13] = inChannels_;
        iv[14] = (outStream_ && outStream_->getAudioApi() == oboe::AudioApi::AAudio) ? 1 : 0;
        iv[15] = (outStream_ && outStream_->getSharingMode() == oboe::SharingMode::Exclusive) ? 1 : 0;
        iv[16] = asyncHasLockedKey_.load(std::memory_order_relaxed) ? 1 : 0;
        iv[17] = asyncLiveKey_.load(std::memory_order_relaxed);
        iv[18] = asyncLiveIsMinor_.load(std::memory_order_relaxed) ? 1 : 0;
        iv[19] = asyncSignalActive_.load(std::memory_order_relaxed) ? 1 : 0;
        for (int i = 0; i < 3; ++i) {
            iv[20 + i * 3] = asyncTopKey_[i].load(std::memory_order_relaxed);
            iv[21 + i * 3] = asyncTopMinor_[i].load(std::memory_order_relaxed);
            iv[22 + i * 3] = asyncTopScore_[i].load(std::memory_order_relaxed);
        }
        iv[29] = asyncLiveChordRoot_.load(std::memory_order_relaxed);
        iv[30] = asyncLiveChordQuality_.load(std::memory_order_relaxed);

        env->SetFloatArrayRegion(floatOut, 0, 160, f.data());
        env->SetIntArrayRegion(intOut, 0, 32, iv.data());
    }

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* oboeStream,
        void* audioData,
        int32_t numFrames
    ) override {
        if (!running_.load(std::memory_order_acquire) || numFrames <= 0) {
            fillZeroOutput(audioData, numFrames);
            return oboe::DataCallbackResult::Stop;
        }

        const int32_t frames = std::min(numFrames, kMaxBurstFrames);
        callbackCount_++;

        const bool useTestFile = useTestFile_.load(std::memory_order_relaxed);
        if (useTestFile) {
            fillMonoFromTestClip(frames);
        } else {
            drainAvailableInputIntoRing(std::max(frames, inBurst_));

            const int32_t preAvail = jitterWritePos_ - jitterReadPos_;
            if (callbackCount_ <= 10 || (callbackCount_ <= 24 && preAvail < targetInCushion_)) {
                if (preAvail > targetInCushion_ + inBurst_) {
                    jitterReadPos_ = jitterWritePos_ - targetInCushion_;
                }
                fillZeroOutput(audioData, numFrames);
                return oboe::DataCallbackResult::Continue;
            }

            const int32_t avail = jitterWritePos_ - jitterReadPos_;
            const int32_t highWaterMark = targetInCushion_ + std::max(inBurst_ * 2, frames * 4);
            if (avail > highWaterMark) {
                const int32_t trimSamples = avail - targetInCushion_;
                if (trimSamples > 64) {
                    const int32_t xfadeLen = std::min<int32_t>(64, frames);
                    const int32_t oldStart = jitterReadPos_;
                    const int32_t newStart = jitterWritePos_ - targetInCushion_;
                    for (int k = 0; k < xfadeLen; ++k) {
                        const float alpha = static_cast<float>(k) / static_cast<float>(xfadeLen);
                        const float sOld = inJitterRing_[(oldStart + k) & kJitterRingMask];
                        const float sNew = inJitterRing_[(newStart + k) & kJitterRingMask];
                        inJitterRing_[(newStart + k) & kJitterRingMask] = sOld * (1.0f - alpha) + sNew * alpha;
                    }
                    jitterReadPos_ = newStart;
                }
            }

            const int32_t curAvail = jitterWritePos_ - jitterReadPos_;
            smoothedInQueueFrames_ = smoothedInQueueFrames_ * 0.90f +
                static_cast<float>(std::max(frames, curAvail)) * 0.10f;

            if (curAvail >= frames) {
                for (int i = 0; i < frames; ++i) {
                    const float s = inJitterRing_[jitterReadPos_ & kJitterRingMask];
                    jitterReadPos_++;
                    monoIn_[i] = s;
                    lastInSample_ = s;
                }
            } else {
                inGlitchCount_++;
                targetInCushion_ = std::min<int32_t>(
                    std::max(inBurst_ * 3, 512),
                    targetInCushion_ + std::max<int32_t>(32, frames / 2));
                for (int i = 0; i < curAvail; ++i) {
                    const float s = inJitterRing_[jitterReadPos_ & kJitterRingMask];
                    jitterReadPos_++;
                    monoIn_[i] = s;
                    lastInSample_ = s;
                }
                const int32_t missing = frames - curAvail;
                constexpr float kPi = 3.14159265358979323846f;
                for (int i = 0; i < missing; ++i) {
                    const float fade = 0.5f * (1.0f + std::cos(kPi * static_cast<float>(i + 1) / static_cast<float>(missing)));
                    monoIn_[curAvail + i] = lastInSample_ * fade;
                }
                lastInSample_ = 0.0f;
            }
        }

        const auto dspStart = std::chrono::steady_clock::now();

        const float micGainDb = params.micGainDb.load(std::memory_order_relaxed);
        const float micGainLinear = std::pow(10.0f, micGainDb / 20.0f);
        const bool monitorOut = useTestFile || params.directMonitor.load(std::memory_order_relaxed);

        float inSumSq = 0.0f;
        float inPeak = 0.0f;
        float outSumSq = 0.0f;
        float outPeak = 0.0f;

        const int waveStride = std::max(1, frames / 8);

        for (int i = 0; i < frames; ++i) {
            const float gained = monoIn_[i] * micGainLinear;
            const float absIn = std::fabs(gained);
            if (absIn > inPeak) inPeak = absIn;
            inSumSq += gained * gained;

            // Low-pass accumulate & decimate for high-resolution FFT Chromagram key detection
            decimAcc_ += gained;
            decimCount_++;
            if (decimCount_ >= decimFactor_) {
                keyInputRing_[keyWritePos_ & kKeyRingMask] = decimAcc_ / static_cast<float>(decimCount_);
                keyWritePos_++;
                decimAcc_ = 0.0f;
                decimCount_ = 0;
                decimSamplesSinceAnalysis_++;
            }

            const float finalOut = monitorOut ? VocalEffectsProcessorCpp::softLimit(gained) : 0.0f;
            const float absOut = std::fabs(finalOut);
            if (absOut > outPeak) outPeak = absOut;
            outSumSq += finalOut * finalOut;
            monoOut_[i] = finalOut;

            if ((i % waveStride) == 0) {
                const int w = waveIdx_ & 63;
                waveInSnapshot_[w] = std::clamp(gained, -1.0f, 1.0f);
                waveOutSnapshot_[w] = std::clamp(finalOut, -1.0f, 1.0f);
                waveIdx_++;
            }
        }

        // Dispatch lock-free 4096-sample decimated snapshot every 512 decimated samples (~42ms @ 12kHz)
        if (decimSamplesSinceAnalysis_ >= 512 &&
            keyWritePos_ >= KeyDetectorCpp::kFftSize &&
            !keyThreadBusy_.load(std::memory_order_acquire)) {
            decimSamplesSinceAnalysis_ = 0;
            const int32_t startCopy = keyWritePos_ - KeyDetectorCpp::kFftSize;
            for (int k = 0; k < KeyDetectorCpp::kFftSize; ++k) {
                keySnapshot_[k] = keyInputRing_[(startCopy + k) & kKeyRingMask];
            }
            keyWorkPending_.store(true, std::memory_order_release);
        }

        writeOutputFromMono(audioData, numFrames, frames);

        const auto dspEnd = std::chrono::steady_clock::now();
        const float dspMs = std::chrono::duration<float, std::milli>(dspEnd - dspStart).count();
        const float prevAvg = dspAvgMs_.load(std::memory_order_relaxed);
        dspAvgMs_.store((prevAvg == 0.0f) ? dspMs : (prevAvg * 0.90f + dspMs * 0.10f), std::memory_order_relaxed);
        if (dspMs > dspMaxMs_.load(std::memory_order_relaxed)) {
            dspMaxMs_.store(dspMs, std::memory_order_relaxed);
        }

        if (inPeak >= 0.94f) {
            peakClipCount_.fetch_add(1, std::memory_order_relaxed);
            inPeakClipping_.store(true, std::memory_order_relaxed);
        } else {
            inPeakClipping_.store(false, std::memory_order_relaxed);
        }

        if (callbackCount_ > 32) {
            auto xrunRes = oboeStream->getXRunCount();
            if (xrunRes) {
                const int32_t xruns = xrunRes.value();
                if (xruns > lastXruns_ && (callbackCount_ - lastTuneCallback_) > 32) {
                    lastXruns_ = xruns;
                    lastTuneCallback_ = callbackCount_;
                    const int32_t curBuf = oboeStream->getBufferSizeInFrames();
                    const int32_t maxAutoBuf = std::min(outCapFrames_, std::max(outBurst_ * 6, 1024));
                    if (curBuf < maxAutoBuf) {
                        const int32_t nextBuf = std::min(maxAutoBuf, curBuf + std::max(outBurst_, frames));
                        oboeStream->setBufferSizeInFrames(nextBuf);
                    }
                }
            }
        }

        if ((callbackCount_ & 7) == 0) {
            inRmsDb_.store(ampToDb(std::sqrt(inSumSq / static_cast<float>(frames))), std::memory_order_relaxed);
            inPeakDb_.store(ampToDb(inPeak), std::memory_order_relaxed);
            outRmsDb_.store(ampToDb(std::sqrt(outSumSq / static_cast<float>(frames))), std::memory_order_relaxed);
            outPeakDb_.store(ampToDb(outPeak), std::memory_order_relaxed);

            const int32_t activeOutBuf = oboeStream->getBufferSizeInFrames();
            lastTelemetryOutBufFrames_.store(activeOutBuf, std::memory_order_relaxed);
            lastTelemetryInQueueFrames_.store(smoothedInQueueFrames_, std::memory_order_relaxed);

            float hwOutFrames = static_cast<float>(activeOutBuf);
            auto latRes = oboeStream->calculateLatencyMillis();
            if (latRes && latRes.value() > 0.0) {
                const float latFrames = static_cast<float>((latRes.value() * actualSampleRate_) / 1000.0);
                if (latFrames >= static_cast<float>(frames) && latFrames <= static_cast<float>(outCapFrames_ + frames * 4)) {
                    smoothedOutHwFrames_ = smoothedOutHwFrames_ * 0.80f + latFrames * 0.20f;
                    hwOutFrames = smoothedOutHwFrames_;
                }
            } else {
                int64_t hwFrame = 0;
                int64_t hwNs = 0;
                if (oboeStream->getTimestamp(CLOCK_MONOTONIC, &hwFrame, &hwNs) == oboe::Result::OK) {
                    const auto nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::steady_clock::now().time_since_epoch()).count();
                    const int64_t deltaNs = std::max<int64_t>(0, nowNs - hwNs);
                    const double playedNow = static_cast<double>(hwFrame) +
                        (static_cast<double>(deltaNs) * actualSampleRate_) / 1'000'000'000.0;
                    const float queued = static_cast<float>(static_cast<double>(oboeStream->getFramesWritten()) - playedNow);
                    if (queued >= static_cast<float>(frames) && queued <= static_cast<float>(outCapFrames_ + frames)) {
                        smoothedOutHwFrames_ = smoothedOutHwFrames_ * 0.80f + queued * 0.20f;
                        hwOutFrames = smoothedOutHwFrames_;
                    }
                }
            }
            lastTelemetryOutHwFrames_.store(hwOutFrames, std::memory_order_relaxed);
        }

        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream* oboeStream, oboe::Result error) override {
        LOGW("Oboe stream error after close: %s", oboe::convertToText(error));
        if (error == oboe::Result::ErrorDisconnected) {
            streamDisconnected_.store(true, std::memory_order_relaxed);
        }
    }

private:
    bool openOutputStream(
        int32_t requestedSampleRate,
        bool preferFloat,
        int32_t requestedFrames,
        bool forceLowLatency,
        bool isUsbOut,
        int32_t outputDeviceId
    ) {
        struct OutAttempt {
            int32_t cbFrames;
            oboe::SharingMode sharing;
            oboe::Usage usage;
            oboe::AudioFormat format;
        };

        const oboe::AudioFormat primaryFmt = preferFloat ? oboe::AudioFormat::Float : oboe::AudioFormat::I16;
        const oboe::AudioFormat altFmt = preferFloat ? oboe::AudioFormat::I16 : oboe::AudioFormat::Float;

        std::vector<OutAttempt> attempts;
        if (forceLowLatency) {
            if (requestedFrames > 0) {
                attempts.push_back({requestedFrames, oboe::SharingMode::Exclusive, oboe::Usage::Game, primaryFmt});
            }
            attempts.push_back({oboe::kUnspecified, oboe::SharingMode::Exclusive, oboe::Usage::Game, primaryFmt});
            attempts.push_back({oboe::kUnspecified, oboe::SharingMode::Exclusive, oboe::Usage::Game, altFmt});
            if (isUsbOut) {
                attempts.push_back({oboe::kUnspecified, oboe::SharingMode::Exclusive, oboe::Usage::Media, primaryFmt});
                attempts.push_back({oboe::kUnspecified, oboe::SharingMode::Exclusive, oboe::Usage::Media, altFmt});
            }
        }
        if (requestedFrames > 0) {
            attempts.push_back({requestedFrames, oboe::SharingMode::Shared, oboe::Usage::Game, primaryFmt});
            if (isUsbOut) {
                attempts.push_back({requestedFrames, oboe::SharingMode::Shared, oboe::Usage::Media, primaryFmt});
            }
        }
        attempts.push_back({oboe::kUnspecified, oboe::SharingMode::Shared, oboe::Usage::Game, primaryFmt});
        attempts.push_back({oboe::kUnspecified, oboe::SharingMode::Shared, oboe::Usage::Media, primaryFmt});
        attempts.push_back({oboe::kUnspecified, oboe::SharingMode::Shared, oboe::Usage::Media, altFmt});

        for (const auto& att : attempts) {
            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(forceLowLatency ? oboe::PerformanceMode::LowLatency : oboe::PerformanceMode::None)
                ->setSharingMode(att.sharing)
                ->setUsage(att.usage)
                ->setContentType(oboe::ContentType::Music)
                ->setChannelCount(2)
                ->setFormat(att.format)
                ->setFormatConversionAllowed(true)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setDataCallback(this)
                ->setErrorCallback(this);

            if (requestedSampleRate > 0) {
                builder.setSampleRate(requestedSampleRate);
            }
            if (outputDeviceId > 0) {
                builder.setDeviceId(outputDeviceId);
            }
            if (att.cbFrames > 0) {
                builder.setFramesPerDataCallback(att.cbFrames);
            }

            oboe::Result res = builder.openStream(outStream_);
            if (res == oboe::Result::OK && outStream_) {
                outCapFrames_ = std::max(1, outStream_->getBufferCapacityInFrames());
                return true;
            }
            outStream_.reset();
        }
        return false;
    }

    bool openInputStream(
        int32_t sampleRate,
        oboe::AudioFormat preferredFmt,
        bool forceLowLatency,
        bool isUsbIn,
        bool preferStereoIn,
        int32_t inputDeviceId
    ) {
        const oboe::SharingMode primaryShare = forceLowLatency ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared;
        const oboe::InputPreset primaryPreset = isUsbIn
            ? oboe::InputPreset::VoiceRecognition
            : (forceLowLatency ? oboe::InputPreset::Unprocessed : oboe::InputPreset::VoicePerformance);
        const int chPrimary = preferStereoIn ? 2 : 1;
        const int chAlt = preferStereoIn ? 1 : 2;
        const oboe::AudioFormat altFmt = (preferredFmt == oboe::AudioFormat::Float)
            ? oboe::AudioFormat::I16
            : oboe::AudioFormat::Float;

        struct InAttempt {
            int channels;
            oboe::SharingMode sharing;
            oboe::InputPreset preset;
            oboe::AudioFormat fmt;
        };

        std::vector<InAttempt> attempts = {
            {chPrimary, primaryShare, primaryPreset, preferredFmt},
            {chPrimary, oboe::SharingMode::Shared, primaryPreset, preferredFmt},
            {chPrimary, oboe::SharingMode::Shared, oboe::InputPreset::Generic, preferredFmt},
            {chAlt, oboe::SharingMode::Shared, primaryPreset, preferredFmt},
            {chAlt, oboe::SharingMode::Shared, oboe::InputPreset::Generic, preferredFmt},
            {chPrimary, oboe::SharingMode::Shared, oboe::InputPreset::Generic, altFmt},
            {1, oboe::SharingMode::Shared, oboe::InputPreset::VoiceRecognition, oboe::AudioFormat::I16}
        };

        for (const auto& att : attempts) {
            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(forceLowLatency ? oboe::PerformanceMode::LowLatency : oboe::PerformanceMode::None)
                ->setSharingMode(att.sharing)
                ->setInputPreset(att.preset)
                ->setSampleRate(sampleRate)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setChannelCount(att.channels)
                ->setFormat(att.fmt)
                ->setFormatConversionAllowed(true)
                ->setBufferCapacityInFrames(std::max(outBurst_ * 8, 1024));

            if (inputDeviceId > 0) {
                builder.setDeviceId(inputDeviceId);
            }

            oboe::Result res = builder.openStream(inStream_);
            if (res == oboe::Result::OK && inStream_) {
                return true;
            }
            inStream_.reset();
        }
        return false;
    }

    void drainInputPreRoll() {
        if (!inStream_) return;
        for (int iter = 0; iter < 16; ++iter) {
            auto r = (inFormat_ == oboe::AudioFormat::Float)
                ? inStream_->read(inScratchFloat_.data(), std::min(512, kMaxBurstFrames), 0)
                : inStream_->read(inScratchI16_.data(), std::min(512, kMaxBurstFrames), 0);
            if (!r || r.value() <= 0) break;
        }
    }

    void drainAvailableInputIntoRing(int32_t burstFrames) {
        if (!inStream_) return;
        const int chMode = params.usbChannelMode.load(std::memory_order_relaxed);
        const int32_t readChunk = std::min(burstFrames, kMaxBurstFrames);

        for (int iter = 0; iter < 8; ++iter) {
            if ((jitterWritePos_ - jitterReadPos_) > (kJitterRingCap - readChunk - 16)) {
                break;
            }
            auto r = (inFormat_ == oboe::AudioFormat::Float)
                ? inStream_->read(inScratchFloat_.data(), readChunk, 0)
                : inStream_->read(inScratchI16_.data(), readChunk, 0);
            if (!r || r.value() <= 0) break;

            const int32_t got = r.value();
            if (inFormat_ == oboe::AudioFormat::Float) {
                for (int i = 0; i < got; ++i) {
                    float m;
                    if (inChannels_ <= 1) {
                        m = inScratchFloat_[i];
                    } else {
                        const float l = inScratchFloat_[i * inChannels_];
                        const float rv = inScratchFloat_[i * inChannels_ + 1];
                        if (chMode == 1) m = l;
                        else if (chMode == 2) m = rv;
                        else m = std::clamp(l + rv, -1.0f, 1.0f);
                    }
                    inJitterRing_[jitterWritePos_ & kJitterRingMask] = m;
                    jitterWritePos_++;
                }
            } else {
                constexpr float kInv32768 = 1.0f / 32768.0f;
                for (int i = 0; i < got; ++i) {
                    float m;
                    if (inChannels_ <= 1) {
                        m = static_cast<float>(inScratchI16_[i]) * kInv32768;
                    } else {
                        const float l = static_cast<float>(inScratchI16_[i * inChannels_]) * kInv32768;
                        const float rv = static_cast<float>(inScratchI16_[i * inChannels_ + 1]) * kInv32768;
                        if (chMode == 1) m = l;
                        else if (chMode == 2) m = rv;
                        else m = std::clamp(l + rv, -1.0f, 1.0f);
                    }
                    inJitterRing_[jitterWritePos_ & kJitterRingMask] = m;
                    jitterWritePos_++;
                }
            }
            if (got < readChunk) break;
        }
    }

    void fillMonoFromTestClip(int32_t frames) {
        if (!params.testFilePlaying.load(std::memory_order_relaxed)) {
            std::fill_n(monoIn_.data(), frames, 0.0f);
            return;
        }
        std::unique_lock<std::mutex> lock(clipMutex_, std::try_to_lock);
        if (!lock.owns_lock() || testClip_.empty()) {
            std::fill_n(monoIn_.data(), frames, 0.0f);
            return;
        }
        const int32_t totalLen = static_cast<int32_t>(testClip_.size());
        const double step = static_cast<double>(testClipSampleRate_) / static_cast<double>(std::max(8000, actualSampleRate_));
        double pos = testPlayHead_;
        for (int i = 0; i < frames; ++i) {
            const int32_t idx0 = static_cast<int32_t>(std::floor(pos)) % totalLen;
            const int32_t idx1 = (idx0 + 1) % totalLen;
            const float frac = static_cast<float>(pos - std::floor(pos));
            monoIn_[i] = testClip_[idx0] + (testClip_[idx1] - testClip_[idx0]) * frac;
            pos += step;
            if (pos >= totalLen) pos -= totalLen;
        }
        testPlayHead_ = pos;
        testProgress_.store(
            std::clamp(static_cast<float>(pos / static_cast<double>(totalLen)), 0.0f, 1.0f),
            std::memory_order_relaxed);
    }

    void fillZeroOutput(void* audioData, int32_t numFrames) const {
        if (!audioData || numFrames <= 0) return;
        const size_t bytesPerFrame = (outFormat_ == oboe::AudioFormat::Float ? sizeof(float) : sizeof(int16_t)) *
            static_cast<size_t>(std::max(1, outChannels_));
        std::memset(audioData, 0, static_cast<size_t>(numFrames) * bytesPerFrame);
    }

    void writeOutputFromMono(void* audioData, int32_t totalFrames, int32_t processedFrames) const {
        const int ch = std::max(1, outChannels_);
        if (outFormat_ == oboe::AudioFormat::Float) {
            float* out = static_cast<float*>(audioData);
            for (int i = 0; i < processedFrames; ++i) {
                const float v = monoOut_[i];
                for (int c = 0; c < ch; ++c) {
                    out[i * ch + c] = v;
                }
            }
            for (int i = processedFrames; i < totalFrames; ++i) {
                for (int c = 0; c < ch; ++c) {
                    out[i * ch + c] = 0.0f;
                }
            }
        } else {
            int16_t* out = static_cast<int16_t*>(audioData);
            for (int i = 0; i < processedFrames; ++i) {
                const int sampleInt = std::clamp(static_cast<int>(monoOut_[i] * 32767.0f), -32768, 32767);
                const int16_t pcm = static_cast<int16_t>(sampleInt);
                for (int c = 0; c < ch; ++c) {
                    out[i * ch + c] = pcm;
                }
            }
            for (int i = processedFrames; i < totalFrames; ++i) {
                for (int c = 0; c < ch; ++c) {
                    out[i * ch + c] = 0;
                }
            }
        }
    }

    void keyLoop() {
        std::array<float, KeyDetectorCpp::kFftSize> localWin{};
        while (running_.load(std::memory_order_acquire)) {
            if (resetKeyRequested_.exchange(false, std::memory_order_acquire)) {
                keyDetector_.reset();
            }
            if (keyWorkPending_.exchange(false, std::memory_order_acquire)) {
                keyThreadBusy_.store(true, std::memory_order_release);
                localWin = keySnapshot_;
                keyThreadBusy_.store(false, std::memory_order_release);

                const float gateDb = params.noiseGateDb.load(std::memory_order_relaxed);
                const float decay = params.keyDecayFactor.load(std::memory_order_relaxed);
                const int hold = params.keyLockHoldFrames.load(std::memory_order_relaxed);
                const float refA4 = params.referenceA4Hz.load(std::memory_order_relaxed);

                const KeyAnalysisResultCpp res = keyDetector_.analyzeFrame(
                    localWin.data(),
                    decimatedSampleRate_,
                    gateDb,
                    decay,
                    hold,
                    refA4
                );

                asyncHasLockedKey_.store(res.hasLockedKey, std::memory_order_relaxed);
                asyncLockedKey_.store(res.lockedKeyIndex, std::memory_order_relaxed);
                asyncLockedIsMinor_.store(res.lockedIsMinor, std::memory_order_relaxed);
                asyncLiveKey_.store(res.liveKeyIndex, std::memory_order_relaxed);
                asyncLiveIsMinor_.store(res.liveIsMinor, std::memory_order_relaxed);
                asyncKeyConfidence_.store(res.confidence, std::memory_order_relaxed);
                asyncSignalActive_.store(res.signalActive, std::memory_order_relaxed);
                asyncDomFreqHz_.store(res.dominantFreqHz, std::memory_order_relaxed);
                asyncDomMidi_.store(res.dominantMidi, std::memory_order_relaxed);
                asyncLiveChordRoot_.store(res.liveChordRoot, std::memory_order_relaxed);
                asyncLiveChordQuality_.store(res.liveChordQuality, std::memory_order_relaxed);
                for (int i = 0; i < 12; ++i) {
                    asyncChroma_[i].store(res.chroma[i], std::memory_order_relaxed);
                }
                for (int i = 0; i < 3; ++i) {
                    asyncTopKey_[i].store(res.topCandidates[i].keyIndex, std::memory_order_relaxed);
                    asyncTopMinor_[i].store(res.topCandidates[i].isMinor ? 1 : 0, std::memory_order_relaxed);
                    asyncTopScore_[i].store(res.topCandidates[i].scorePct, std::memory_order_relaxed);
                }
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(4));
            }
        }
    }

    void stopInternalLocked() {
        running_.store(false, std::memory_order_release);
        closeStreamsLocked();
        if (keyThread_.joinable()) {
            keyThread_.join();
        }
    }

    void closeStreamsLocked() {
        if (outStream_) {
            outStream_->requestStop();
            outStream_->close();
            outStream_.reset();
        }
        if (inStream_) {
            inStream_->requestStop();
            inStream_->close();
            inStream_.reset();
        }
    }

    static float ampToDb(float amp) {
        if (amp <= 0.001f) return -60.0f;
        return std::clamp(20.0f * std::log10(amp), -60.0f, 0.0f);
    }

    std::mutex lifecycleMutex_;
    std::shared_ptr<oboe::AudioStream> outStream_;
    std::shared_ptr<oboe::AudioStream> inStream_;
    std::thread keyThread_;

    std::atomic<bool> running_{false};
    std::atomic<bool> streamDisconnected_{false};
    std::atomic<bool> useTestFile_{false};
    std::atomic<bool> forceLowLatency_{true};
    std::atomic<bool> isUsbIn_{false};
    std::atomic<bool> isUsbOut_{false};
    std::atomic<bool> resetKeyRequested_{false};

    int32_t actualSampleRate_ = 48000;
    int32_t decimFactor_ = 4;
    float decimatedSampleRate_ = 12000.0f;

    int32_t outBurst_ = 96;
    int32_t outCapFrames_ = 192;
    int32_t activeCallbackFrames_ = 96;
    int32_t outChannels_ = 2;
    oboe::AudioFormat outFormat_ = oboe::AudioFormat::I16;

    int32_t inChannels_ = 1;
    int32_t inCapFrames_ = 192;
    int32_t inBurst_ = 96;
    oboe::AudioFormat inFormat_ = oboe::AudioFormat::I16;

    std::array<float, kMaxBurstFrames * 2> inScratchFloat_{};
    std::array<int16_t, kMaxBurstFrames * 2> inScratchI16_{};
    std::array<float, kMaxBurstFrames> monoIn_{};
    std::array<float, kMaxBurstFrames> monoOut_{};
    std::array<float, kJitterRingCap> inJitterRing_{};
    int32_t jitterWritePos_ = 0;
    int32_t jitterReadPos_ = 0;
    float lastInSample_ = 0.0f;

    // Decimation + lock-free 4096-pt FFT Chromagram bridge
    KeyDetectorCpp keyDetector_;
    std::array<float, kKeyRingCap> keyInputRing_{};
    std::array<float, KeyDetectorCpp::kFftSize> keySnapshot_{};
    int32_t keyWritePos_ = 0;
    float decimAcc_ = 0.0f;
    int32_t decimCount_ = 0;
    int32_t decimSamplesSinceAnalysis_ = 0;
    std::atomic<bool> keyWorkPending_{false};
    std::atomic<bool> keyThreadBusy_{false};

    std::atomic<bool> asyncHasLockedKey_{false};
    std::atomic<int> asyncLockedKey_{0};
    std::atomic<bool> asyncLockedIsMinor_{false};
    std::atomic<int> asyncLiveKey_{0};
    std::atomic<bool> asyncLiveIsMinor_{false};
    std::atomic<float> asyncKeyConfidence_{0.0f};
    std::atomic<bool> asyncSignalActive_{false};
    std::atomic<float> asyncDomFreqHz_{0.0f};
    std::atomic<int> asyncDomMidi_{0};
    std::atomic<int> asyncLiveChordRoot_{0};
    std::atomic<int> asyncLiveChordQuality_{-1};
    std::array<std::atomic<float>, 12> asyncChroma_{};
    std::array<std::atomic<int>, 3> asyncTopKey_{};
    std::array<std::atomic<int>, 3> asyncTopMinor_{};
    std::array<std::atomic<int>, 3> asyncTopScore_{};

    // Telemetry state
    int64_t callbackCount_ = 0;
    int32_t lastXruns_ = 0;
    int32_t inGlitchCount_ = 0;
    int32_t targetInCushion_ = 192;
    int64_t lastTuneCallback_ = 0;
    float smoothedInQueueFrames_ = 96.0f;
    float smoothedOutHwFrames_ = 192.0f;

    std::atomic<float> inRmsDb_{-60.0f};
    std::atomic<float> inPeakDb_{-60.0f};
    std::atomic<float> outRmsDb_{-60.0f};
    std::atomic<float> outPeakDb_{-60.0f};
    std::atomic<float> dspAvgMs_{0.0f};
    std::atomic<float> dspMaxMs_{0.0f};
    std::atomic<int> peakClipCount_{0};
    std::atomic<bool> inPeakClipping_{false};
    std::atomic<int32_t> lastTelemetryOutBufFrames_{0};
    std::atomic<float> lastTelemetryInQueueFrames_{0.0f};
    std::atomic<float> lastTelemetryOutHwFrames_{0.0f};

    std::array<float, 64> waveInSnapshot_{};
    std::array<float, 64> waveOutSnapshot_{};
    int32_t waveIdx_ = 0;

    std::mutex clipMutex_;
    std::vector<float> testClip_;
    int32_t testClipSampleRate_ = 48000;
    double testPlayHead_ = 0.0;
    std::atomic<float> testProgress_{0.0f};
};

VocalNativeEngine& engine() {
    static VocalNativeEngine instance;
    return instance;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_audio_NativeOboeBridge_nativeIsAvailable(JNIEnv*, jclass) {
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_audio_NativeOboeBridge_nativeStart(
    JNIEnv*, jclass,
    jint requestedSampleRate,
    jboolean preferFloat,
    jint requestedFrames,
    jint bufferMultiplier,
    jboolean forceLowLatency,
    jint inputDeviceId,
    jint outputDeviceId,
    jboolean useTestFile,
    jboolean isUsbIn,
    jboolean isUsbOut,
    jboolean preferStereoIn
) {
    return engine().start(
        requestedSampleRate,
        preferFloat == JNI_TRUE,
        requestedFrames,
        bufferMultiplier,
        forceLowLatency == JNI_TRUE,
        inputDeviceId,
        outputDeviceId,
        useTestFile == JNI_TRUE,
        isUsbIn == JNI_TRUE,
        isUsbOut == JNI_TRUE,
        preferStereoIn == JNI_TRUE
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeOboeBridge_nativeStop(JNIEnv*, jclass) {
    engine().stop();
}

JNIEXPORT jboolean JNICALL
Java_com_example_audio_NativeOboeBridge_nativeIsRunning(JNIEnv*, jclass) {
    return engine().isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeOboeBridge_nativeUpdateDetectorParams(
    JNIEnv*, jclass,
    jfloat micGainDb,
    jfloat noiseGateDb,
    jboolean directMonitor,
    jfloat keyDecayFactor,
    jint keyLockHoldFrames,
    jfloat referenceA4Hz,
    jint usbChannelMode,
    jboolean testFilePlaying
) {
    auto& p = engine().params;
    p.micGainDb.store(micGainDb, std::memory_order_relaxed);
    p.noiseGateDb.store(noiseGateDb, std::memory_order_relaxed);
    p.directMonitor.store(directMonitor == JNI_TRUE, std::memory_order_relaxed);
    p.keyDecayFactor.store(keyDecayFactor, std::memory_order_relaxed);
    p.keyLockHoldFrames.store(keyLockHoldFrames, std::memory_order_relaxed);
    p.referenceA4Hz.store(referenceA4Hz, std::memory_order_relaxed);
    p.usbChannelMode.store(usbChannelMode, std::memory_order_relaxed);
    p.testFilePlaying.store(testFilePlaying == JNI_TRUE, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeOboeBridge_nativeResetKeyDetection(JNIEnv*, jclass) {
    engine().resetKeyDetection();
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeOboeBridge_nativeSetTestClip(
    JNIEnv* env, jclass,
    jfloatArray monoSamples,
    jint sampleRate
) {
    if (monoSamples == nullptr) {
        engine().setTestClip(nullptr, 0, sampleRate);
        return;
    }
    const jsize len = env->GetArrayLength(monoSamples);
    if (len <= 0) {
        engine().setTestClip(nullptr, 0, sampleRate);
        return;
    }
    std::vector<float> buf(static_cast<size_t>(len));
    env->GetFloatArrayRegion(monoSamples, 0, len, buf.data());
    engine().setTestClip(buf.data(), static_cast<int32_t>(len), sampleRate);
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeOboeBridge_nativeSeekTestFile(JNIEnv*, jclass, jfloat progress) {
    engine().seekTestFile(progress);
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeOboeBridge_nativeResetPeakClips(JNIEnv*, jclass) {
    engine().resetPeakClips();
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeOboeBridge_nativeReadTelemetry(
    JNIEnv* env, jclass,
    jfloatArray floatOut,
    jintArray intOut
) {
    engine().fillTelemetry(env, floatOut, intOut);
}

} // extern "C"
