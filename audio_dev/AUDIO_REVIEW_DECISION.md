# Audio Review Decision

## Current decision
Keep Silero as the active detector path.

The repository is no longer in the earlier "fix WebRTC first" state. The live service now uses `SpeechDetectorFactory`, which returns `AudioConfig.sileroConfig()` and creates `SileroSpeechDetector`. The old WebRTC detector files are gone.

## Current live pipeline
1. `MainActivity` starts `AudioTrackingService`.
2. `AudioTrackingService` asks `SpeechDetectorFactory` for the active detector and matching `AudioConfig`.
3. `SpeechDetectorFactory` currently selects Silero with `16 kHz`, mono, PCM 16-bit, `512` samples per frame, `Mode.NORMAL`, `speechDurationMs=50`, and `silenceDurationMs=300`.
4. `AudioRecorderManager` creates `AudioRecord`, preferring `MIC` and falling back to `VOICE_RECOGNITION`, then repacks the stream into exact fixed-size `short[]` frames.
5. `AudioPipelineCoordinator` runs speech, disturbance, and reverb analysis on each frame.
6. `SessionRepository` stores derived events and summaries only. It does not persist raw audio.

## Why this decision stands
- The Silero dependency and API match the current source.
- The active configuration is internally consistent end-to-end: sample rate, PCM format, and frame size all line up.
- The repo now has a single source of truth for the active detector path through `SpeechDetectorFactory`, which removes the earlier half-migrated state.

## Remaining risks
- I could not run Gradle compilation or tests in this environment because no Java runtime is installed.
- Field diagnostics are still thin. The app logs aggregate pipeline stats, but not enough speech-transition detail to explain borderline misses quickly.
- The project still lacks an explicit A/B switch if you want to compare Silero against another detector later.

## Next recommendation
1. Install or point the environment at a JDK and run `./gradlew testDebugUnitTest` and `./gradlew :app:compileDebugJavaWithJavac`.
2. Validate on a physical Android device with quiet-room and noisy placement tests.
3. If comparison work is needed later, extend `SpeechDetectorFactory` into a small runtime-selectable factory rather than hard-coding another detector in the service.
