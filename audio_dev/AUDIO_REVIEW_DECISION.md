# Audio Review Decision

## Decision
Keep WebRTC for the next validation round and fix the current WebRTC path first. Do not switch blindly to Silero yet.

The current codebase contains a concrete WebRTC configuration bug that is large enough to explain the present `isSpeech=false` failures. Because of that, the current behavior is not a fair benchmark of WebRTC for DeployTeach yet.

## Current pipeline summary
1. `MainActivity` starts `AudioTrackingService`, which creates `AudioConfig.defaultConfig()` and a `WebRtcSpeechDetector`.
2. `AudioConfig.defaultConfig()` fixes the capture format at 16 kHz, mono, PCM 16-bit, 20 ms frames, which is 320 samples per frame.
3. `AudioRecorderManager` creates `AudioRecord` with `VOICE_RECOGNITION` first and `MIC` as fallback, then reads PCM into a `short[]`.
4. `AudioRecorderManager` repacks the stream into exact 320-sample frames and sends each frame to `AudioPipelineCoordinator`.
5. `AudioPipelineCoordinator` runs three stages per frame: `WebRtcSpeechDetector`, `EnergySpikeDetector`, and `EnergyDecayReverbEstimator`.
6. `SessionRepository` stores only per-frame `SpeechEvent` labels and aggregate summaries in memory. It does not currently persist audio.
7. `CircularAudioBuffer` exists in the repo but is not wired into the live pipeline yet, so there is no current pre-roll or retained audio window.

## Verified current WebRTC wiring
| Item | Current code | Verdict |
| --- | --- | --- |
| Sample rate | `16000` Hz in `AudioConfig` | Correct |
| Frame size | `320` samples = `20` ms at 16 kHz | Correct |
| PCM format | Mono, `ENCODING_PCM_16BIT` | Correct |
| VAD input type | `short[]` passed to `VadWebRTC.isSpeech(short[])` | Correct |
| Byte count per frame | 320 samples = 640 bytes equivalent; library `ByteArray` path also documents `2x` frame size | Correct |
| Aggressiveness | `Mode.VERY_AGGRESSIVE` | Valid, but recall may be tight for pocket/classroom conditions |
| Smoothing / latching | App uses built-in latching in `VadWebRTC` | Correct mechanism, wrong values |
| Audio source | `VOICE_RECOGNITION`, fallback to `MIC` | Valid, but device-dependent and worth field testing |

### Evidence map
- `app/src/main/java/com/example/audio/audio/AudioConfig.java:35-44` sets the default capture format to 16 kHz, mono, PCM 16-bit, 20 ms, 320 samples.
- `app/src/main/java/com/example/audio/audio/AudioRecorderManager.java:95-118` creates `AudioRecord` and sizes the buffer from `frameSizeBytes`.
- `app/src/main/java/com/example/audio/audio/AudioRecorderManager.java:109-112` selects `VOICE_RECOGNITION` first and falls back to `MIC`.
- `app/src/main/java/com/example/audio/audio/AudioRecorderManager.java:121-145` repacks the stream into exact 320-sample `short[]` frames.
- `app/src/main/java/com/example/audio/vad/WebRtcSpeechDetector.java:13-30` configures the third-party WebRTC VAD.
- `app/src/main/java/com/example/audio/vad/WebRtcSpeechDetector.java:34-41` calls `VadWebRTC.isSpeech(short[])`.
- `app/src/main/java/com/example/audio/pipeline/AudioPipelineCoordinator.java:36-42` feeds the same frame and timestamp through VAD, disturbance, and reverb.
- `app/src/main/java/com/example/audio/data/SessionRepository.java:44-63` stores only `SpeechEvent` labels and summaries in memory.
- `app/src/main/AndroidManifest.xml:10-13` keeps app backup enabled.
- `app/src/main/res/xml/backup_rules.xml` and `app/src/main/res/xml/data_extraction_rules.xml` do not yet exclude any future debug artifacts.

### Upstream `android-vad` evidence
- README: WebRTC requires 16-bit mono PCM and recommends `sampleRate=16K`, `frameSize=320`, `mode=VERY_AGGRESSIVE`, `silenceDurationMs=300`, `speechDurationMs=50`: <https://github.com/gkonovalov/android-vad/blob/main/README.md#webrtc-vad>
- README: Silero requires `Context`, recommends `sampleRate=16K`, `frameSize=512`, `mode=NORMAL`, and uses the `silero` dependency: <https://github.com/gkonovalov/android-vad/blob/main/README.md#silero-vad>
- Source: `VadWebRTC` accepts `ShortArray`, `ByteArray`, and `FloatArray`, and its constructor order is `(sampleRate, frameSize, mode, speechDurationMs, silenceDurationMs)`: <https://github.com/gkonovalov/android-vad/blob/main/webrtc/src/main/java/com/konovalov/vad/webrtc/VadWebRTC.kt>
- Source: `VadSilero` also applies frame-based latching, but uses ONNX Runtime, loads `silero_vad.onnx` from assets, and builds tensors from a `FloatArray`: <https://github.com/gkonovalov/android-vad/blob/main/silero/src/main/java/com/konovalov/vad/silero/VadSilero.kt>
- Releases: the project already depends on `2.0.10`, while older WebRTC fixes landed earlier in the upstream release history: <https://github.com/gkonovalov/android-vad/releases>

## Exact bugs and misconfigurations found
### 1. WebRTC latching is reversed
- App code configured `speechDurationMs=300` and `silenceDurationMs=50` in `WebRtcSpeechDetector`.
- The library README recommends the opposite for WebRTC: `speechDurationMs=50`, `silenceDurationMs=300`.
- With 20 ms frames, the current app config means roughly:
  - speech must stay positive for about 320 ms before the detector returns `true`
  - only about 60 ms of negative frames are needed to drop back to `false`
- In practice, that is exactly the wrong direction for classroom speech. It delays speech onset too much and releases too quickly.

### 2. The repo’s test contradicts production
- `WebRtcSpeechDetectorConfigTest` claimed the app used `0/0` durations and “without extra smoothing”.
- Production code was actually using built-in latching.
- This mismatch is evidence that the code and the intended behavior were already out of sync.

### 3. Future debug files would currently be backup-eligible
- `AndroidManifest.xml` has `android:allowBackup="true"`.
- `backup_rules.xml` and `data_extraction_rules.xml` are still sample/default shells with no explicit excludes.
- If local debug artifacts are added later, they should not go into normal backed-up app storage without explicit exclusion.

## Keep WebRTC vs switch to Silero
### A. Is WebRTC broken because of app bugs, or fundamentally a poor fit?
Primarily app-side bugs and configuration problems right now.

Evidence:
- The capture format and frame wiring are correct.
- The major configuration that controls state stability is wrong.
- The current code therefore cannot be used as evidence that WebRTC itself is failing fairly.

That said, WebRTC is still a weaker model family for speech-vs-noise than Silero for this use case. The library’s own docs describe WebRTC as fast and lightweight but less accurate in background noise, while Silero is the higher-accuracy DNN path. So the correct conclusion is:

Current failures are explainable by implementation/configuration bugs now.

WebRTC may still be a weaker long-term fit for pocket/classroom audio even after the bug fix.

### B. Should we switch now, or first fix current WebRTC?
First fix current WebRTC.

Why:
- The present false-negative behavior is already explained by a real bug.
- Switching immediately would mix two variables at once: model family and broken current config.
- The project already has a clean seam for later migration through `SpeechDetector`.
- DeployTeach already targets `minSdk 24`, so Silero remains a feasible next step if the fixed WebRTC path still underperforms on real device tests.

### C. What exact evidence supports that recommendation?
- `AudioConfig` and `AudioRecorderManager` are correctly producing 16 kHz mono PCM 16-bit 320-sample frames.
- `WebRtcSpeechDetector` feeds those frames through the correct `short[]` API.
- The third-party WebRTC library documents the same valid format and recommends `speech=50 ms` and `silence=300 ms`.
- The app had those values reversed.
- The third-party library version in use is already `2.0.10`, and upstream release notes show prior WebRTC fixes landed earlier. So the strongest current problem is not “we are on an ancient broken upstream release”; it is local configuration.

## WebRTC vs Silero for DeployTeach
### WebRTC strengths
- Already integrated.
- Small dependency footprint.
- Very fast.
- 20 ms frames align naturally with the current pipeline.

### WebRTC risks
- Lower robustness in speech-vs-background-noise settings.
- `VERY_AGGRESSIVE` mode can over-reject weak or muffled speech.
- Pocket/classroom audio is a harder condition than near-field clean speech.

### Silero strengths
- Library docs describe it as more accurate for speech-vs-noise.
- Current library implementation exposes a plain `isSpeech(...)` API similar to WebRTC.
- Integration is feasible because the app already uses API 24+ and has a `SpeechDetector` abstraction.

### Silero costs
- Heavier integration path: new dependency, ONNX Runtime Mobile, asset/model loading, and larger frame size.
- Recommended frame size changes from 320 samples to 512 samples at 16 kHz.
- A switch now would hide whether the current WebRTC problem was just the reversed latching bug.

## Privacy-preserving debug recording recommendation
### Bottom line
Do not store raw continuous audio.

For debug/testing, use an opt-in local-only snippet mode that stores:
- transformed low-intelligibility audio snippets
- only for short, quota-limited, speech-like windows
- plus lightweight per-snippet features and labels

### Explicit evaluation of the required options
| Option | Verdict | Why |
| --- | --- | --- |
| 1. Low sample rate only | Reject as insufficient | Narrowband speech at 8 kHz is still very intelligible. Lowering sample rate alone does not meaningfully solve privacy. |
| 2. Low-pass + downsample | Better, but not enough by itself | This reduces intelligibility if done aggressively, but continuous storage still captures a lot of speech content. |
| 3. VAD-gated speech-only segments | Useful filter, not enough alone | Good for storage and noise reduction, but it still stores the most privacy-sensitive parts and can hide false-negative cases. |
| 4. Transformed / obfuscated speech | Best balance for debug listening | This is the most practical way to keep human verification while reducing intelligibility. |
| 5. Features only | Best privacy, weak verification | Good for production telemetry, but humans cannot reliably verify “this is mostly the instructor speaking” from features alone. |

### Recommended debug method
Use a hybrid of option 4 plus option 5:

1. Keep raw PCM in memory only.
2. When debug mode is manually enabled, capture only short snippets, not full sessions.
3. Gate snippets on stable speech-like windows and reject obvious disturbances.
4. Before writing any snippet, transform it:
   - low-pass to about 1.2 to 1.5 kHz
   - downsample to about 4 kHz
   - store in a low-bitrate format such as 8-bit mu-law WAV or IMA ADPCM
5. Also store lightweight features beside each snippet: RMS, speech flag, disturbance flag, selected audio source, and timestamps.
6. Store the files under a no-backup location and add explicit backup excludes.
7. Cap both per-clip duration and total clips per session. Auto-delete old debug artifacts.

This is not zero-risk. Some words may still be partially intelligible. But it is materially safer than low-sample-rate-only audio, while still letting a developer hear whether the phone is mostly capturing the nearby instructor rather than random classroom noise.

## Concrete next-step implementation plan
1. Fix the WebRTC latching bug now.
2. Run target-device tests in quiet, classroom-noise, and pocket-placement conditions before changing model families.
3. Add better diagnostics next:
   - selected audio source
   - speech-state transition counts
   - optional “possible missed speech” counters
4. Add opt-in transformed debug snippets only after backup exclusions and storage location are designed.
5. Only if fixed WebRTC still performs poorly on real-device tests, add a `SileroSpeechDetector` behind the existing `SpeechDetector` interface and compare both paths using the same captured scenarios.

## Uncertainty
- I could not run Gradle tests locally because this machine currently has no Java runtime configured.
- The current codebase explains the present false-negative behavior well.
- The earlier historical state where “almost everything was speech” is not explained by the current code alone, so I am not attributing that earlier behavior to this exact revision without stronger evidence.
