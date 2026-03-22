# Audio Implementation Next Steps

## Recommended order
### 1. Lock the current WebRTC fix and validate it
Files:
- `app/src/main/java/com/example/audio/vad/WebRtcSpeechDetector.java`
- `app/src/test/java/com/example/audio/vad/WebRtcSpeechDetectorConfigTest.java`

Test after this step:
- Confirm the app still captures at 16 kHz mono PCM 16-bit.
- On a real device, speak short phrases and verify speech turns on faster and holds through short pauses.
- Once Java is available locally, run `./gradlew testDebugUnitTest`.

### 2. Add better VAD diagnostics before any model switch
Files:
- `app/src/main/java/com/example/audio/audio/AudioRecorderManager.java`
- `app/src/main/java/com/example/audio/pipeline/FrameProcessingDiagnostics.java`
- `app/src/main/java/com/example/audio/service/AudioTrackingService.java`

Change next:
- Log which audio source was actually selected.
- Add counters for speech transitions, speech hold duration, and consecutive non-speech frames.
- Keep diagnostics local to Logcat or in-memory summaries only.

Test after this step:
- Compare quiet-room, classroom-noise, and pocket-placement logs on the same device.
- Verify whether `VOICE_RECOGNITION` or fallback `MIC` is being used.
- Check whether false negatives cluster around speech onset, muffled speech, or audio-source changes.

### 3. Add the privacy-preserving debug snippet path
Files to change:
- `app/src/main/java/com/example/audio/audio/CircularAudioBuffer.java`
- `app/src/main/java/com/example/audio/service/AudioTrackingService.java`
- `app/src/main/java/com/example/audio/data/SessionRepository.java`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/main/AndroidManifest.xml`

New files to add:
- `app/src/main/java/com/example/audio/debug/DebugAudioConfig.java`
- `app/src/main/java/com/example/audio/debug/DebugAudioTransformer.java`
- `app/src/main/java/com/example/audio/debug/DebugSnippetWriter.java`
- `app/src/main/java/com/example/audio/debug/DebugSnippetMetadata.java`

Change next:
- Implement opt-in snippet capture only.
- Use a small in-memory ring buffer for pre-roll.
- Transform audio before storage.
- Write snippets into a no-backup location and explicitly exclude them from cloud backup and device transfer.

Test after this step:
- Verify no raw full-session PCM is ever written.
- Listen to snippets and confirm they still reveal “nearby instructor speech vs mostly noise”.
- Confirm intelligibility is reduced compared with the live capture.
- Confirm files are quota-limited and deleted on schedule.

### 4. Improve missed-speech debugging if needed
Files:
- `app/src/main/java/com/example/audio/disturbance/EnergySpikeDetector.java`
- `app/src/main/java/com/example/audio/pipeline/AudioPipelineCoordinator.java`
- `app/src/main/java/com/example/audio/debug/DebugSnippetWriter.java`

Change next:
- Add a limited “possible missed speech” trigger for high-energy stable audio that VAD marked as non-speech.
- Keep the quota very small so this remains debug-only.

Test after this step:
- Reproduce a known WebRTC miss and verify the debug path captures a transformed snippet around it.
- Confirm short disturbances do not dominate the saved snippets.

### 5. Only then, add a controlled Silero experiment
Files:
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/java/com/example/audio/vad/SpeechDetector.java`
- `app/src/main/java/com/example/audio/service/AudioTrackingService.java`

New files:
- `app/src/main/java/com/example/audio/vad/SileroSpeechDetector.java`
- `app/src/main/java/com/example/audio/vad/SpeechDetectorFactory.java`

Change next:
- Add Silero as an alternative detector, not a wholesale replacement.
- Keep the rest of the pipeline unchanged.
- Make detector selection explicit and easy to switch for A/B tests.

Test after this step:
- Run the same device scenarios with WebRTC and Silero.
- Compare recall on real speech, rejection of short disturbances, and behavior in pocket/classroom placement.
- Measure CPU, memory, startup latency, and any frame-timing issues.

## Switch criteria
Switch from WebRTC to Silero only if the fixed WebRTC path still fails target-device tests in the real classroom/pocket setup.

Evidence to require before switching:
- speech recall is still poor after the latching fix
- disturbance rejection remains weak on the same scenarios
- source-selection experiments do not recover acceptable behavior
- Silero materially improves the same cases without unacceptable CPU, memory, or startup cost
