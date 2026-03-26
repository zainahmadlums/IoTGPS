# Audio Implementation Next Steps

## Recommended order

### 1. Restore build verification
Files:
- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/src/main/java/com/example/audio/vad/SpeechDetectorFactory.java`

Do next:
- Install a JDK locally or configure Android Studio to use one.
- Run `./gradlew :app:compileDebugJavaWithJavac`.
- Run `./gradlew testDebugUnitTest`.

Reason:
- The source tree now points consistently at Silero, but the build still has not been verified in this environment.

### 2. Improve live diagnostics
Files:
- `app/src/main/java/com/example/audio/audio/AudioRecorderManager.java`
- `app/src/main/java/com/example/audio/pipeline/FrameProcessingDiagnostics.java`
- `app/src/main/java/com/example/audio/service/AudioTrackingService.java`

Do next:
- Log the selected audio source.
- Add speech transition counters and consecutive non-speech counters.
- Keep logs local to Logcat or in-memory summaries.

### 3. Add detector selection only if comparison is required
Files:
- `app/src/main/java/com/example/audio/vad/SpeechDetectorFactory.java`
- `app/src/main/java/com/example/audio/service/AudioTrackingService.java`

Do next:
- Turn the current factory into a small explicit selector if you need A/B testing.
- Keep the service dependent on the factory only.

### 4. Add privacy-safe debug capture later
Files:
- `app/src/main/java/com/example/audio/audio/CircularAudioBuffer.java`
- `app/src/main/java/com/example/audio/service/AudioTrackingService.java`
- `app/src/main/java/com/example/audio/data/SessionRepository.java`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`

Do next:
- Keep raw PCM in memory only.
- If snippets are added, store transformed low-intelligibility clips only.
- Exclude any debug artifacts from backup and device transfer.
