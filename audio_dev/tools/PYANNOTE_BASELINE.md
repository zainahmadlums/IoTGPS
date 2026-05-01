# Pyannote Baseline

This baseline is for offline evaluation, preferably on Kaggle GPU. It does not modify the Android app.

## Requirements

You need a Hugging Face token with access to the pyannote diarization model.

Before running, accept the model terms on Hugging Face for:

- `pyannote/speaker-diarization-3.1`
- any gated dependency model requested by pyannote

Set the token:

```bash
export HF_TOKEN=hf_xxx
```

## Install

```bash
pip install -r tools/pyannote_requirements.txt
```

On Kaggle, enable internet and GPU first.

## Run 10-File Smoke Baseline

```bash
python3 tools/run_pyannote_baseline.py \
  --dataset generated_dataset \
  --out generated_dataset/pyannote_baseline_10 \
  --limit 10 \
  --device cuda \
  --score
```

Score it:

```bash
python3 tools/score_dataset.py \
  --truth generated_dataset/truth \
  --pred generated_dataset/pyannote_baseline_10/predictions \
  --out generated_dataset/pyannote_baseline_10/metrics.json
```

## Run Full 100-File Baseline

```bash
python3 tools/run_pyannote_baseline.py \
  --dataset generated_dataset \
  --out generated_dataset/pyannote_baseline_100 \
  --device cuda \
  --score
```

Score it:

```bash
python3 tools/score_dataset.py \
  --truth generated_dataset/truth \
  --pred generated_dataset/pyannote_baseline_100/predictions \
  --out generated_dataset/pyannote_baseline_100/metrics.json
```

## Run Android VAD + Conditioned Audio + Pyannote

First run the Android batch evaluator. It writes app-side artifacts under
`files/DeployTeachEval/`:

```text
vad/*.json
filtered_audio/*.wav
predictions/*.json
```

After exporting those directories locally to
`generated_dataset/android_pipeline_100/`, run pyannote on the conditioned
audio and use Android/Silero VAD as the speech/silence gate:

```bash
python3 tools/run_pyannote_baseline.py \
  --audio generated_dataset/android_pipeline_100/filtered_audio \
  --truth generated_dataset/truth \
  --vad generated_dataset/android_pipeline_100/vad \
  --out generated_dataset/pyannote_android_pipeline_100 \
  --device cpu \
  --score
```

Use `--device cuda` on a GPU machine. This keeps the Android signal path
consistent with the app: pocket-rub conditioning and Silero VAD come from
Android, while speaker turns come from pyannote.

For live tracking, the equivalent shape is chunked instead of whole-file:

```text
Android mic
  -> conditioned rolling WAV chunk
  -> Android VAD JSON for that chunk
  -> remote/Python pyannote service
  -> delayed roleIntervals update in app metadata/timeline
```

The live UI should still show immediate speech/silence from Android VAD, then
apply delayed pyannote role labels when the service returns.

## Output Format

Predictions are written as app-compatible JSON:

```text
generated_dataset/pyannote_baseline_10/predictions/*.json
generated_dataset/pyannote_baseline_10/metrics.json
```

Each prediction contains:

- `speakerSegments`: raw anonymous pyannote speaker tags.
- `instructorSpeakerTag`: baseline-only mapping from generated truth to instructor.
- `roleIntervals`: `SILENCE`, `INSTRUCTOR`, `STUDENT`, `BOTH`.

## Important Caveat

The baseline maps pyannote's anonymous speaker tags to instructor using generated truth. That is valid only for measuring the upper-bound quality of diarization on this dataset.

For the Android app, instructor mapping must come from enrollment audio or cluster-centroid matching, not truth labels.
