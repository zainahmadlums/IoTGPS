# Audio Bug Audit

## Scope
This audit covers the current live code path after the Silero migration:
- active speech detector wiring
- capture format and frame sizing
- frame handoff into disturbance and reverb stages
- test alignment with the active frame model
- debug and storage implications

## Current live-code audit result

### `app/src/main/java/com/example/audio/vad/SpeechDetectorFactory.java`
- This is now the single source of truth for the active speech detector path.
- It selects Silero and returns the matching `AudioConfig`.

Audit result:
- Good change. It removes the prior ambiguity where the service directly hard-coded migration details.

### `app/src/main/java/com/example/audio/vad/SileroSpeechDetector.java`
- Uses `VadSilero` with `16 kHz`, `512` samples, `Mode.NORMAL`, `speechDurationMs=50`, `silenceDurationMs=300`.
- Rejects frames that do not match the configured sample count.

Audit result:
- Internally consistent with the current factory-selected audio config.
- I did not find evidence that this file is the source-level error.

### `app/src/main/java/com/example/audio/audio/AudioConfig.java`
- The repo now contains both `defaultConfig()` and `sileroConfig()`.
- The active path uses `sileroConfig()`.

Audit result:
- No live-path mismatch. The current live detector and recorder agree on `512` samples.

### `app/src/main/java/com/example/audio/audio/AudioRecorderManager.java`
- Uses the active `AudioConfig` supplied by the service.
- Re-packs the `AudioRecord` stream into exact fixed-size frames before dispatch.
- Prefers `MIC`, then falls back to `VOICE_RECOGNITION`.

Audit result:
- Frame sizing is consistent with the active config.
- Remaining runtime risk is field behavior across devices, not a clear source bug.

### `app/src/main/java/com/example/audio/pipeline/AudioPipelineCoordinator.java`
- Passes the same frame and timestamp through speech, disturbance, and reverb logic.

Audit result:
- No handoff bug found.

### `app/src/test/java/com/example/audio/testsupport/SyntheticAudioFactory.java`
- Test helpers now expose the current active frame size and duration instead of hard-coding WebRTC-era values.

Audit result:
- Good change. Unit tests no longer encode `320`-sample assumptions by default.

### `app/src/test/java/com/example/audio/features/FeatureCalculatorsTest.java`
### `app/src/test/java/com/example/audio/disturbance/EnergySpikeDetectorTest.java`
### `app/src/test/java/com/example/audio/reverb/EnergyDecayReverbEstimatorTest.java`
- These tests now build frames from the current active frame model rather than fixed `320`-sample frames.

Audit result:
- Better aligned with the live path.
- They still need an actual Gradle run to confirm behavior.

### `app/src/main/AndroidManifest.xml`
### `app/src/main/res/xml/backup_rules.xml`
### `app/src/main/res/xml/data_extraction_rules.xml`
- No raw audio persistence exists in the current live path.
- Backup/data extraction rules are still default shells.

Audit result:
- No active storage bug today.
- This remains a future privacy risk if debug snippets are added later.

## Remaining issues
1. Build verification is still blocked locally by the missing Java runtime.
2. Field diagnostics remain limited for debugging missed speech.
3. Backup/data-extraction rules still need tightening before any on-disk debug capture is introduced.
