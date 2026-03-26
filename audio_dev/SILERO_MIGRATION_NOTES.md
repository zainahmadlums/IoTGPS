# Silero Migration Notes

## Files changed
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/java/com/example/audio/audio/AudioConfig.java`
- `app/src/main/java/com/example/audio/service/AudioTrackingService.java`
- `app/src/main/java/com/example/audio/vad/SileroSpeechDetector.java`
- `app/src/main/java/com/example/audio/vad/SpeechDetectorFactory.java`
- `app/src/test/java/com/example/audio/vad/SileroSpeechDetectorConfigTest.java`
- `app/src/test/java/com/example/audio/testsupport/SyntheticAudioFactory.java`
- `app/src/test/java/com/example/audio/features/FeatureCalculatorsTest.java`
- `app/src/test/java/com/example/audio/disturbance/EnergySpikeDetectorTest.java`
- `app/src/test/java/com/example/audio/reverb/EnergyDecayReverbEstimatorTest.java`
- removed `app/src/main/java/com/example/audio/vad/WebRtcSpeechDetector.java`
- removed `app/src/test/java/com/example/audio/vad/WebRtcSpeechDetectorConfigTest.java`

## Why WebRTC was replaced
The current speech-vs-silence path was using WebRTC VAD, which is lightweight but less accurate around speech versus background noise. The upstream `android-vad` README explicitly describes Silero as the higher-accuracy model with processing time close to WebRTC, which is a better fit for the classroom/pocket/noisy setting targeted by this app.

Upstream sources used:
- README: <https://github.com/gkonovalov/android-vad#silero-vad>
- Silero source: <https://raw.githubusercontent.com/gkonovalov/android-vad/main/silero/src/main/java/com/konovalov/vad/silero/VadSilero.kt>
- Silero module build config: <https://raw.githubusercontent.com/gkonovalov/android-vad/main/silero/build.gradle>

## Exact Silero configuration used
- Model: `com.konovalov.vad.silero.VadSilero`
- Sample rate: `16 kHz`
- Frame size: `512 samples`
- Mode: `NORMAL`
- Speech duration: `50 ms`
- Silence duration: `300 ms`

These values match the upstream recommended real-time Silero settings from the library README.

## Exact frame/sample assumptions
- Capture format remains mono PCM 16-bit.
- Audio sample rate remains `16000 Hz`.
- Each Silero frame is `512 samples`.
- At 16 kHz, `512 samples` corresponds to `32 ms`.
- Each frame passed into Silero is `1024 bytes` of PCM16 equivalent.
- `AudioRecorderManager` still reads `short[]` PCM and re-packs the stream into exact fixed-size frames before VAD.
- No raw audio is stored by default. The pipeline still only keeps timestamps and derived labels/results.
- `SpeechDetectorFactory` is now the single source of truth for the active detector and matching audio config.

## How to test speech vs silence manually
1. Install and run the app on a physical Android device.
2. Start the foreground microphone service from the app UI.
3. In a quiet room, speak short phrases and confirm the UI flips to speech quickly and returns to silence after pauses.
4. Repeat with the phone in the target pocket/classroom placement.
5. Test silence-only periods and short background disturbances to make sure they do not dominate the speech state.
6. Review Logcat for the existing `SileroSpeechDetector` RMS/state logs and the aggregate `FramePipeline` summary logs.

## Unresolved limitations
- I could not run Gradle tests locally in this environment because no Java runtime is installed.
- Moving from `320`-sample WebRTC frames to `512`-sample Silero frames changes frame duration from `20 ms` to `32 ms`. The rest of the pipeline is still stable, but disturbance/reverb timing now operates on a slightly larger frame window.
- `AudioRecord` source selection was not redesigned in this migration. The app keeps the current capture path stable aside from the VAD model change.
- The app does not yet include an A/B switch between WebRTC and Silero. This migration makes Silero the only speech detector path.
