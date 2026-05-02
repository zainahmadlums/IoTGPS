# CODEX.md

## Current Task

Build the final audio tracking pipeline for the Android project:

```text
Android recording
  -> pocket-rubbing suppression / conditioning
  -> Silero VAD for speech vs silence
  -> pyannote diarization for who spoke when
  -> app-compatible JSON + dashboard timeline
```

The goal is not to keep improving the current Java-only speaker role classifier indefinitely. The Java-only diarization path has been useful for testing, but it is not the final diarization backend. The final direction is to connect the existing Android audio pipeline to a pyannote-based diarization backend for batch/pre-recorded evaluation first, then use the same architecture for delayed live/chunked diarization later.

## Repo

Workspace:

```text
/Users/Raahim/Documents/LUMS/Junior/spring_semester/coursework/CS677/project/IoTGPS/audio_dev
```

Android package:

```text
com.example.audio
```

Important folders:

```text
app/src/main/java/com/example/audio/
app/src/androidTest/java/com/example/audio/eval/
tools/
dataset_sources/
generated_dataset/
```

## Current Code State

There are uncommitted changes in the repo. Important changed files:

```text
app/src/androidTest/java/com/example/audio/eval/BatchEvaluationInstrumentedTest.java
app/src/main/assets/speaker_role_model.model
app/src/main/java/com/example/audio/speaker/SpeakerRoleClassifier.java
app/src/main/java/com/example/audio/util/Logger.java
app/src/test/java/com/example/audio/speaker/SpeakerRoleClassifierTest.java
tools/train_speaker_role_model.py
```

New pyannote-related files exist:

```text
tools/run_pyannote_baseline.py
tools/pyannote_requirements.txt
tools/PYANNOTE_BASELINE.md
colab_pyannote_eval/
colab_pyannote_eval.zip
```

Generated output cleanup was done. The only kept latest Java smoke result is:

```text
generated_dataset/smoke_window24_cached_10/
```

Core dataset folders are still present:

```text
generated_dataset/audio/
generated_dataset/truth/
generated_dataset/normalized_sources/
dataset_sources/
```

## Existing Android Pipeline

The app currently records audio and processes frames through:

```text
AudioRecorderManager
  -> AudioTrackingService
  -> AudioPipelineCoordinator
  -> SpeechDetectorFactory / SileroSpeechDetector
  -> DisturbanceDetector
  -> ReverbEstimator
  -> SpeakerRoleClassifier
```

Relevant files:

```text
app/src/main/java/com/example/audio/service/AudioTrackingService.java
app/src/main/java/com/example/audio/pipeline/AudioPipelineCoordinator.java
app/src/main/java/com/example/audio/vad/SileroSpeechDetector.java
app/src/main/java/com/example/audio/speaker/SpeakerRoleClassifier.java
```

The app also stores raw and filtered WAVs for playback/debugging in archived audio. The filtered audio is meant to represent the conditioned/pocket-rub-suppressed path, while raw should remain actual recorded audio.

## Current Java-Only Diarization Result

Latest kept Java smoke result:

```text
generated_dataset/smoke_window24_cached_10/metrics.json
```

Metrics:

```json
{
  "accuracy": 0.8215,
  "totalBins": 6000,
  "correctBins": 4929,
  "roles": {
    "SILENCE": {
      "precision": 0.9297042434633519,
      "recall": 0.9329032258064516,
      "f1": 0.9313009875483039,
      "supportBins": 4650
    },
    "INSTRUCTOR": {
      "precision": 0.5302013422818792,
      "recall": 0.17873303167420815,
      "f1": 0.2673434856175973,
      "supportBins": 442
    },
    "STUDENT": {
      "precision": 0.4320675105485232,
      "recall": 0.6918918918918919,
      "f1": 0.531948051948052,
      "supportBins": 740
    },
    "BOTH": {
      "precision": 0.0,
      "recall": 0.0,
      "f1": 0.0,
      "supportBins": 168
    }
  }
}
```

Conclusion: Java-only diarization is not good enough. It improved student recall but still fails `BOTH` completely and instructor recall is poor.

## Pyannote Baseline Already Ran

Do not repeat the discussion of whether pyannote might help. A 100-file pyannote-style baseline was already run and gave much better results than the Java-only classifier.

100-file result:

```json
{
  "accuracy": 0.9099833333333334,
  "totalBins": 60000,
  "correctBins": 54599,
  "scoredFiles": 100,
  "roles": {
    "SILENCE": {
      "precision": 0.946255312324593,
      "recall": 0.9982658348313419,
      "f1": 0.9715650053000443,
      "supportBins": 47285
    },
    "INSTRUCTOR": {
      "precision": 0.7179302045728039,
      "recall": 0.5974364109753655,
      "f1": 0.6521644075207695,
      "supportBins": 4993
    },
    "STUDENT": {
      "precision": 0.7142312821489808,
      "recall": 0.6027331189710611,
      "f1": 0.6537623158078298,
      "supportBins": 6220
    },
    "BOTH": {
      "precision": 0.9325842696629213,
      "recall": 0.4420772303595206,
      "f1": 0.5998193315266487,
      "supportBins": 1502
    }
  },
  "confusion": {
    "SILENCE": {
      "SILENCE": 47203,
      "INSTRUCTOR": 44,
      "STUDENT": 38,
      "BOTH": 0
    },
    "INSTRUCTOR": {
      "SILENCE": 750,
      "INSTRUCTOR": 2983,
      "STUDENT": 1230,
      "BOTH": 30
    },
    "STUDENT": {
      "SILENCE": 1843,
      "INSTRUCTOR": 610,
      "STUDENT": 3749,
      "BOTH": 18
    },
    "BOTH": {
      "SILENCE": 88,
      "INSTRUCTOR": 518,
      "STUDENT": 232,
      "BOTH": 664
    }
  }
}
```

Conclusion: pyannote-style diarization is the better backend. The next work should integrate it into the project pipeline instead of further tuning the Java-only speaker classifier.

## Batch Evaluation Direction

Batch/pre-recorded evaluation is now Python-canonical via the refactored `tools/run_pyannote_baseline.py`.

It supports:
- **Conditioned Signal Path**: Uses `filtered_audio/*.wav` and `vad/*.json` exported from Android.
- **Real Speaker Mapping**: Uses `pyannote/wespeaker-voxceleb-resnet34-LM` to map anonymous speakers to the enrolled instructor profile.
- **App-Compatible JSON**: Writes `roleIntervals` and 32ms `frames` to match the Android schema.

Run it with:
```bash
python3 tools/run_pyannote_baseline.py \
  --audio generated_dataset_100/android_pipeline_100/filtered_audio \
  --vad generated_dataset_100/android_pipeline_100/vad \
  --instructor-profile generated_dataset_100/results/android_batch_eval_100/profile/instructor_profile.wav \
  --out generated_dataset_100/results/pyannote_android_pipeline_100 \
  --score
```

## Android Integration Direction

Android should not run local pyannote. Keep Android lightweight.

Live path:

```text
Android mic recording
  -> pocket-rubbing suppression / conditioning
  -> Silero VAD for immediate speech/silence
  -> save rolling WAV chunks
  -> send chunks to Python/remote pyannote service
  -> receive delayed speaker turns
  -> update dashboard/metadata timeline
```

This means:

```text
Immediate UI = Silero speech/silence
Delayed correction = pyannote instructor/student/both diarization
```

Expect delay. Do not promise instant pyannote labels in live mode.

## Pocket Rubbing Requirement

The final pipeline must preserve:

```text
raw audio = actual recorded audio
filtered audio = pocket-rub-suppressed / conditioned audio
```

The app already has raw/filtered playback/debugging work. The important requirement is:

```text
pocket-rub suppression happens before speech/silence and diarization decisions
```

If batch evaluation uses pre-recorded audio, either:

```text
1. run the same Android/Java conditioning path to generate filtered WAVs, then feed filtered WAVs to pyannote
```

or:

```text
2. port the conditioning/pocket-rub filter into the Python batch pipeline so batch and Android use equivalent audio
```

Do not evaluate diarization on a different signal than the one the app uses for decisions without calling that out.

## Current Eval Harness Details

Android instrumentation batch evaluator:

```text
app/src/androidTest/java/com/example/audio/eval/BatchEvaluationInstrumentedTest.java
```

It uses internal eval root when present:

```text
/data/user/0/com.example.audio/files/DeployTeachEval/
```

Expected structure:

```text
files/DeployTeachEval/audio/*.wav
files/DeployTeachEval/truth/*.json
files/DeployTeachEval/profile/instructor_profile.wav
files/DeployTeachEval/profile/instructor_profile_cache.json
files/DeployTeachEval/predictions/*.json
```

Recent change: evaluator now caches instructor profile embedding in:

```text
files/DeployTeachEval/profile/instructor_profile_cache.json
```

This avoids rebuilding the instructor embedding from WAV every smoke run.

## Commands Used Recently

Build/test:

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Install:

```bash
/Users/Raahim/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
/Users/Raahim/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Run Android batch evaluator:

```bash
/Users/Raahim/Library/Android/sdk/platform-tools/adb shell am instrument -w -r -e debug false -e class com.example.audio.eval.BatchEvaluationInstrumentedTest com.example.audio.test/androidx.test.runner.AndroidJUnitRunner
```

Score predictions:

```bash
python3 tools/score_dataset.py --truth generated_dataset/smoke_window24_cached_10/truth --pred generated_dataset/smoke_window24_cached_10/predictions --out generated_dataset/smoke_window24_cached_10/metrics.json
```

## Next Best Step

Implement the final batch pyannote pipeline around the already-good 100-file baseline:

1. Make `tools/run_pyannote_baseline.py` the canonical batch diarization runner.
2. Ensure it writes the same JSON schema as Android predictions.
3. Add support for using the filtered/conditioned audio path if available.
4. Keep Silero VAD as the speech/silence gate.
5. Use pyannote for diarization labels.
6. Score on 100 generated files.
7. Only after batch is correct, add Android-to-service integration for live delayed diarization.

Do not spend more time trying to force the Java-only speaker classifier to match pyannote.
