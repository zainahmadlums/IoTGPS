# Synthetic Dataset Evaluation Tools

Step 1: collect source clips.

Put clips into:

- `dataset_sources/instructor/*.wav`
- `dataset_sources/students/<student_id>/*.wav`
- optional `dataset_sources/noise/background/*.wav`
- optional `dataset_sources/noise/pocket_rub/*.wav`

Source clips can be WAV, M4A/AAC, MP3, CAF/AIFF, FLAC, or MP4 audio. The generator normalizes them to 16 kHz mono PCM16 WAV in `generated_dataset/normalized_sources/`; your original clips are not modified. Noise that is already inside a speech clip stays part of that speech event; the separate noise folders are only for extra overlays.

Step 2: generate synthetic audio plus ground truth.

```bash
python3 tools/generate_synthetic_dataset.py --sources dataset_sources --out generated_dataset --count 1000
```

This creates `generated_dataset/audio/*.wav`, `generated_dataset/truth/*.json`, and `generated_dataset/manifest.jsonl`. The truth JSON contains `roleIntervals` with `SILENCE`, `INSTRUCTOR`, `STUDENT`, and `BOTH`.

Step 3: run the app pipeline in batch later.

Push generated files to the app-specific external directory on a device/emulator:

```bash
adb shell mkdir -p /sdcard/Android/data/com.example.audio/files/DeployTeachEval/audio
adb shell mkdir -p /sdcard/Android/data/com.example.audio/files/DeployTeachEval/truth
adb push generated_dataset/audio/. /sdcard/Android/data/com.example.audio/files/DeployTeachEval/audio/
adb push generated_dataset/truth/. /sdcard/Android/data/com.example.audio/files/DeployTeachEval/truth/
```

Then run the instrumentation test when ready:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.audio.eval.BatchEvaluationInstrumentedTest
```

The test writes predictions to:

```text
/sdcard/Android/data/com.example.audio/files/DeployTeachEval/predictions/
```

Step 4, not done yet: pull predictions and score them.

```bash
adb pull /sdcard/Android/data/com.example.audio/files/DeployTeachEval/predictions generated_dataset/predictions
python3 tools/score_dataset.py --truth generated_dataset/truth --pred generated_dataset/predictions --out generated_dataset/metrics.json
```
