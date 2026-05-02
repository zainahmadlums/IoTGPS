#!/usr/bin/env python3
"""Run a pyannote diarization baseline and export predictions in app JSON format.

This script is the canonical batch diarization runner. It supports:
1. Diarization with pyannote/speaker-diarization-3.1
2. Instructor mapping via pyannote/embedding and an instructor profile WAV
3. Gating speech with optional Android/Silero VAD JSON exports
4. Exporting app-compatible JSON with roleIntervals and frames
"""

from __future__ import annotations

import argparse
import json
import math
import os
import time
from collections import defaultdict
from pathlib import Path


ROLE_SILENCE = "SILENCE"
ROLE_INSTRUCTOR = "INSTRUCTOR"
ROLE_STUDENT = "STUDENT"
ROLE_BOTH = "BOTH"

# Android/App frame size
FRAME_MS = 32


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run pyannote diarization baseline.")
    parser.add_argument("--dataset", type=Path, default=Path("generated_dataset"))
    parser.add_argument("--audio", type=Path, default=None, help="Directory of WAV files to diarize.")
    parser.add_argument("--truth", type=Path, default=None, help="Directory of truth JSON files for scoring.")
    parser.add_argument("--vad", type=Path, default=None, help="Directory of Android VAD JSON exports.")
    parser.add_argument("--out", type=Path, default=Path("generated_dataset/pyannote_baseline"))
    parser.add_argument("--model", default="pyannote/speaker-diarization-3.1")
    parser.add_argument("--embedding-model", default="pyannote/wespeaker-voxceleb-resnet34-LM")
    parser.add_argument("--hf-token", default=os.environ.get("HF_TOKEN"))
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--device", default=None, help="Optional torch device, e.g. cpu, cuda, mps.")
    parser.add_argument("--instructor-profile", type=Path, default=None, help="Instructor enrollment WAV for mapping.")
    parser.add_argument("--profile", dest="instructor_profile", type=Path, help="Alias for --instructor-profile.")
    parser.add_argument("--collar-ms", type=int, default=250)
    parser.add_argument("--score", action="store_true", help="Also write metrics.json after predictions.")
    parser.add_argument("--bin-ms", type=int, default=100)
    parser.add_argument(
        "--vad-fill-role",
        choices=(ROLE_INSTRUCTOR, ROLE_STUDENT),
        default=ROLE_STUDENT,
        help="Role to use for Android-VAD speech spans with no overlapping pyannote speaker turn.",
    )
    return parser.parse_args()


def require_token(token: str | None) -> str:
    if not token:
        raise SystemExit(
            "HF token required. Set HF_TOKEN or pass --hf-token. "
            "You must also accept the pyannote model terms on Hugging Face."
        )
    return token


def load_models(args: argparse.Namespace):
    from pyannote.audio import Inference, Model, Pipeline

    token = require_token(args.hf_token)
    pipeline = Pipeline.from_pretrained(args.model, use_auth_token=token)
    pipeline = maybe_to_device(pipeline, args.device)

    embedding_model = Model.from_pretrained(args.embedding_model, use_auth_token=token)
    embedding_inference = Inference(maybe_to_device(embedding_model, args.device), window="whole")
    return pipeline, embedding_inference


def maybe_to_device(obj, device_name: str | None):
    if not device_name:
        return obj
    try:
        import torch
        device = torch.device(device_name)
        obj.to(device)
        return obj
    except Exception as exception:
        print(f"Warning: Failed to move to device={device_name}: {exception}. Falling back to CPU.")
        import torch
        obj.to(torch.device("cpu"))
        return obj


def load_duration_ms(audio_path: Path, truth_path: Path | None) -> int:
    if truth_path and truth_path.exists():
        payload = json.loads(truth_path.read_text(encoding="utf-8"))
        return int(payload["durationMillis"])
    # Fallback to wave duration if truth not available
    import wave
    with wave.open(str(audio_path), "rb") as f:
        return int((f.getnframes() / f.getframerate()) * 1000)


def diarization_segments(diarization) -> list[dict]:
    segments = []
    for turn, _, speaker in diarization.itertracks(yield_label=True):
        segments.append({
            "startMs": int(round(float(turn.start) * 1000.0)),
            "endMs": int(round(float(turn.end) * 1000.0)),
            "speaker": str(speaker),
        })
    return sorted(segments, key=lambda item: (item["startMs"], item["endMs"], item["speaker"]))


def load_android_vad(vad_path: Path | None) -> dict | None:
    if vad_path is None or not vad_path.exists():
        return None
    payload = json.loads(vad_path.read_text(encoding="utf-8"))
    speech_intervals = []
    for interval in payload.get("speechIntervals", []):
        start_ms = int(interval.get("startOffsetMillis", 0))
        end_ms = int(interval.get("endOffsetMillis", start_ms))
        if end_ms > start_ms:
            speech_intervals.append({"startMs": start_ms, "endMs": end_ms})
    if not speech_intervals:
        for frame in payload.get("frames", []):
            if not frame.get("speech", False):
                continue
            start_ms = int(frame.get("startOffsetMillis", 0))
            end_ms = int(frame.get("endOffsetMillis", start_ms))
            if end_ms <= start_ms:
                continue
            if speech_intervals and speech_intervals[-1]["endMs"] == start_ms:
                speech_intervals[-1]["endMs"] = end_ms
            else:
                speech_intervals.append({"startMs": start_ms, "endMs": end_ms})
    return {
        "path": vad_path,
        "engine": payload.get("vadEngine", "android"),
        "conditionedAudioFile": payload.get("conditionedAudioFile"),
        "speechIntervals": speech_intervals,
    }


def intervals_overlap(start_ms: int, end_ms: int, interval: dict) -> bool:
    return int(interval["startMs"]) < end_ms and int(interval["endMs"]) > start_ms


def is_vad_speech(start_ms: int, end_ms: int, android_vad: dict | None) -> bool:
    if android_vad is None:
        return True
    return any(intervals_overlap(start_ms, end_ms, interval) for interval in android_vad["speechIntervals"])


def cosine_similarity(a: list[float], b: list[float]) -> float:
    numerator = sum(x * y for x, y in zip(a, b))
    a_norm = math.sqrt(sum(x * x for x in a))
    b_norm = math.sqrt(sum(y * y for y in b))
    return numerator / (a_norm * b_norm) if a_norm and b_norm else 0.0


def flatten_embedding(value) -> list[float]:
    import numpy as np
    return np.asarray(value, dtype=float).reshape(-1).tolist()


def get_speaker_embedding(inference, audio_path: Path, segments: list[dict], speaker_tag: str) -> list[float] | None:
    from pyannote.core import Segment
    
    speaker_segments = [s for s in segments if s["speaker"] == speaker_tag]
    # Filter for segments long enough to get a good embedding (> 500ms)
    long_segments = [s for s in speaker_segments if (s["endMs"] - s["startMs"]) >= 500]
    if not long_segments:
        # Fallback to longest segment if none are > 500ms
        if not speaker_segments:
            return None
        long_segments = [max(speaker_segments, key=lambda s: s["endMs"] - s["startMs"])]
    
    # Limit to top 5 longest segments to speed up processing
    long_segments = sorted(long_segments, key=lambda s: s["endMs"] - s["startMs"], reverse=True)[:5]

    embeddings = []
    for s in long_segments:
        try:
            local_start = s["startMs"] / 1000.0
            local_end = s["endMs"] / 1000.0
            emb = inference.crop(str(audio_path), Segment(local_start, local_end))
            embeddings.append(flatten_embedding(emb))
        except Exception:
            continue
    
    if not embeddings:
        return None
    
    # Return centroid
    import numpy as np
    return np.mean(embeddings, axis=0).tolist()


def choose_instructor_speaker_via_profile(
    inference,
    audio_path: Path,
    segments: list[dict],
    instructor_embedding: list[float],
) -> str | None:
    speaker_tags = {s["speaker"] for s in segments}
    best_speaker = None
    best_score = -1.0
    
    for tag in speaker_tags:
        emb = get_speaker_embedding(inference, audio_path, segments, tag)
        if emb is None:
            continue
        score = cosine_similarity(emb, instructor_embedding)
        if score > best_score:
            best_score = score
            best_speaker = tag
            
    return best_speaker


def choose_instructor_speaker_via_truth(
    segments: list[dict],
    truth_path: Path,
    collar_ms: int,
) -> str | None:
    """Fallback mapping using truth data."""
    if not truth_path.exists():
        return None
    truth = json.loads(truth_path.read_text(encoding="utf-8"))
    instructor_intervals = [
        (
            int(item["startOffsetMillis"]) + collar_ms,
            int(item["endOffsetMillis"]) - collar_ms,
        )
        for item in truth.get("roleIntervals", [])
        if item.get("role") in {ROLE_INSTRUCTOR, ROLE_BOTH}
    ]
    overlaps_by_speaker = defaultdict(int)
    for segment in segments:
        for start_ms, end_ms in instructor_intervals:
            overlap = max(0, min(segment["endMs"], end_ms) - max(segment["startMs"], start_ms))
            overlaps_by_speaker[segment["speaker"]] += overlap
    if not overlaps_by_speaker:
        return None
    return max(overlaps_by_speaker.items(), key=lambda item: item[1])[0]


def segments_to_role_intervals(
    segments: list[dict],
    duration_ms: int,
    instructor_speaker: str | None,
    android_vad: dict | None = None,
    vad_fill_role: str = ROLE_STUDENT,
) -> list[dict]:
    boundaries = {0, duration_ms}
    for segment in segments:
        boundaries.add(max(0, min(duration_ms, segment["startMs"])))
        boundaries.add(max(0, min(duration_ms, segment["endMs"])))
    if android_vad is not None:
        for interval in android_vad["speechIntervals"]:
            boundaries.add(max(0, min(duration_ms, interval["startMs"])))
            boundaries.add(max(0, min(duration_ms, interval["endMs"])))

    role_intervals = []
    sorted_boundaries = sorted(boundaries)
    for start_ms, end_ms in zip(sorted_boundaries, sorted_boundaries[1:]):
        if start_ms >= end_ms:
            continue
            
        active = [
            segment
            for segment in segments
            if segment["startMs"] < end_ms and segment["endMs"] > start_ms
        ]
        
        if android_vad is not None:
            if not is_vad_speech(start_ms, end_ms, android_vad):
                role = ROLE_SILENCE
            elif not active:
                role = vad_fill_role
            else:
                has_instructor = any(segment["speaker"] == instructor_speaker for segment in active)
                has_student = any(segment["speaker"] != instructor_speaker for segment in active)
                if has_instructor and has_student:
                    role = ROLE_BOTH
                elif has_instructor:
                    role = ROLE_INSTRUCTOR
                else:
                    role = ROLE_STUDENT
        else:
            # No Android VAD: pyannote segments determine speech vs silence
            if not active:
                role = ROLE_SILENCE
            else:
                has_instructor = any(segment["speaker"] == instructor_speaker for segment in active)
                has_student = any(segment["speaker"] != instructor_speaker for segment in active)
                if has_instructor and has_student:
                    role = ROLE_BOTH
                elif has_instructor:
                    role = ROLE_INSTRUCTOR
                else:
                    role = ROLE_STUDENT
        if role_intervals and role_intervals[-1]["role"] == role and role_intervals[-1]["endOffsetMillis"] == start_ms:
            role_intervals[-1]["endOffsetMillis"] = end_ms
        else:
            role_intervals.append({
                "role": role,
                "startOffsetMillis": start_ms,
                "endOffsetMillis": end_ms,
            })
    return role_intervals


def generate_frames(role_intervals: list[dict], duration_ms: int) -> list[dict]:
    frames = []
    bin_count = math.ceil(duration_ms / FRAME_MS)
    
    # Map intervals to role per bin
    roles_by_bin = [ROLE_SILENCE] * bin_count
    for interval in role_intervals:
        role = interval["role"]
        start_ms = interval["startOffsetMillis"]
        end_ms = interval["endOffsetMillis"]
        start_bin = start_ms // FRAME_MS
        end_bin = math.ceil(end_ms / FRAME_MS)
        for b in range(max(0, start_bin), min(bin_count, end_bin)):
            roles_by_bin[b] = role
            
    for i in range(bin_count):
        start_ms = i * FRAME_MS
        end_ms = min(duration_ms, (i + 1) * FRAME_MS)
        role = roles_by_bin[i]
        frames.append({
            "startOffsetMillis": start_ms,
            "endOffsetMillis": end_ms,
            "role": role,
            "speech": role != ROLE_SILENCE,
            "confidence": 1.0 if role != ROLE_SILENCE else 0.0 # Placeholder
        })
    return frames


def process_one(
    pipeline,
    embedding_inference,
    instructor_embedding: list[float] | None,
    audio_path: Path,
    truth_path: Path | None,
    prediction_path: Path,
    collar_ms: int,
    android_vad: dict | None,
    vad_fill_role: str,
) -> None:
    diarization = pipeline(str(audio_path))
    segments = diarization_segments(diarization)
    duration_ms = load_duration_ms(audio_path, truth_path)
    
    if instructor_embedding is not None:
        instructor_speaker = choose_instructor_speaker_via_profile(
            embedding_inference,
            audio_path,
            segments,
            instructor_embedding
        )
    elif truth_path and truth_path.exists():
        instructor_speaker = choose_instructor_speaker_via_truth(segments, truth_path, collar_ms)
    else:
        instructor_speaker = None
        
    role_intervals = segments_to_role_intervals(
        segments,
        duration_ms,
        instructor_speaker,
        android_vad,
        vad_fill_role,
    )
    
    prediction = {
        "id": audio_path.stem,
        "audioFile": f"audio/{audio_path.name}",
        "durationMillis": duration_ms,
        "sampleRateHz": 16000,
        "diarizationEngine": "pyannote",
        "diarizationAudioFile": audio_path.as_posix(),
        "instructorSpeakerTag": instructor_speaker,
        "speakerSegments": segments,
        "roleIntervals": role_intervals,
        "frames": generate_frames(role_intervals, duration_ms)
    }
    
    if android_vad is not None:
        prediction["vadEngine"] = android_vad["engine"]
        prediction["vadFile"] = android_vad["path"].as_posix()
        prediction["conditionedAudioFile"] = android_vad["conditionedAudioFile"]
        
    prediction_path.parent.mkdir(parents=True, exist_ok=True)
    prediction_path.write_text(json.dumps(prediction, indent=2), encoding="utf-8")


def write_metrics(truth_dir: Path, prediction_dir: Path, output_path: Path, bin_ms: int) -> None:
    # Use the existing score_dataset.py logic or call it
    import subprocess
    cmd = [
        "python3", "tools/score_dataset.py",
        "--truth", str(truth_dir),
        "--pred", str(prediction_dir),
        "--out", str(output_path),
        "--bin-ms", str(bin_ms),
        "--allow-missing"
    ]
    subprocess.run(cmd, check=True)


def main() -> None:
    args = parse_args()
    pipeline, embedding_inference = load_models(args)
    
    instructor_embedding = None
    if args.instructor_profile:
        print(f"Loading instructor profile: {args.instructor_profile}")
        instructor_embedding = flatten_embedding(embedding_inference(str(args.instructor_profile)))

    audio_dir = args.audio or args.dataset / "audio"
    truth_dir = args.truth or args.dataset / "truth"
    prediction_dir = args.out / "predictions"
    audio_paths = sorted(audio_dir.glob("*.wav"))
    if args.limit is not None:
        audio_paths = audio_paths[:args.limit]
    if not audio_paths:
        raise SystemExit(f"No WAV files found in {audio_dir}")

    for index, audio_path in enumerate(audio_paths, start=1):
        truth_path = truth_dir / f"{audio_path.stem}.json"
        prediction_path = prediction_dir / f"{audio_path.stem}.json"
        vad_path = args.vad / f"{audio_path.stem}.json" if args.vad is not None else None
        android_vad = load_android_vad(vad_path)
        
        print(f"[{index}/{len(audio_paths)}] {audio_path.name}")
        process_one(
            pipeline,
            embedding_inference,
            instructor_embedding,
            audio_path,
            truth_path if truth_path.exists() else None,
            prediction_path,
            args.collar_ms,
            android_vad,
            args.vad_fill_role,
        )

    print(f"Wrote predictions to {prediction_dir}")
    if args.score and truth_dir.exists():
        write_metrics(truth_dir, prediction_dir, args.out / "metrics.json", args.bin_ms)


if __name__ == "__main__":
    main()
