# Local Pyannote Sidecar

This is the local-only live diarization sidecar contract.

Runtime privacy rules:

- No cloud backend.
- No remote upload.
- No Hugging Face login at runtime.
- Models must be bundled on disk before deployment.
- App talks only to `127.0.0.1`.

## Start Sidecar

```bash
MPLCONFIGDIR=/tmp/mplconfig XDG_CACHE_HOME=/tmp/xdg-cache \
python3 tools/local_pyannote_sidecar.py \
  --diarization-model bundled_models/pyannote_speaker_diarization_3_1 \
  --embedding-model bundled_models/pyannote_embedding \
  --work-dir live_sidecar_state \
  --device mps \
  --port 8765
```

Use `--device cpu` if MPS fails. For a self-contained instructor bundle,
package this script with:

- a private Python runtime
- installed pyannote/torch dependencies
- local pyannote diarization model files
- local pyannote embedding model files

The sidecar does not accept model names like `pyannote/speaker-diarization-3.1`
in production because that can trigger network downloads.

## API

### `GET /health`

Returns:

```json
{"ok": true, "privacy_mode": "local_only"}
```

### `POST /session/start`

```json
{
  "session_id": "session-123",
  "chunk_duration_ms": 40000,
  "overlap_ms": 5000,
  "instructor_audio_path": "/local/path/to/live/instructor_enrollment.wav"
}
```

The instructor path must be the live saved instructor enrollment for this
profile/session. The sidecar caches the embedding by file path, size, and mtime.

### `POST /chunk`

```json
{
  "session_id": "session-123",
  "chunk_index": 0,
  "chunk_start_ms": 0,
  "chunk_end_ms": 40000,
  "chunk_duration_ms": 40000,
  "overlap_ms": 5000,
  "sample_rate": 16000,
  "audio_path": "/local/path/to/chunk_0000.wav",
  "vad_speech_segments": [
    {"start_ms": 1200, "end_ms": 9800}
  ]
}
```

Returns stable global speaker labels:

```json
{
  "ok": true,
  "privacy_mode": "local_only",
  "segments": [
    {
      "start_ms": 1200,
      "end_ms": 9800,
      "speaker": "GLOBAL_INSTRUCTOR",
      "source_chunk_index": 0,
      "confidence": null,
      "processing_stage": "live_chunk"
    }
  ]
}
```

### `POST /session/end`

```json
{
  "session_id": "session-123",
  "audio_duration_ms": 3600000
}
```

Writes and returns:

```text
live_sidecar_state/<session_id>/final_diarization.json
```

Final JSON includes:

- `session_id`
- `audio_duration_ms`
- `chunk_duration_ms`
- `overlap_ms`
- `privacy_mode: local_only`
- `speakers`
- `segments`

## Stitching

The sidecar maps chunk-local pyannote speakers to stable global labels using:

1. instructor anchor embedding
2. overlap-region speaker agreement
3. embedding centroid similarity

The instructor anchor wins when similarity is strong. Raw pyannote labels such
as `SPEAKER_00` are never written as final speaker IDs.
