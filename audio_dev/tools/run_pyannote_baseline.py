#!/usr/bin/env python3
"""Run a pyannote diarization baseline and export predictions in app JSON format.

This script is intended for local Python/Kaggle experiments, not Android. It
answers: "what can a real diarization pipeline achieve on generated_dataset?"
"""

from __future__ import annotations

import argparse
import json
import math
import os
from collections import defaultdict
from pathlib import Path


ROLE_SILENCE = "SILENCE"
ROLE_INSTRUCTOR = "INSTRUCTOR"
ROLE_STUDENT = "STUDENT"
ROLE_BOTH = "BOTH"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run pyannote diarization baseline.")
    parser.add_argument("--dataset", type=Path, default=Path("generated_dataset"))
    parser.add_argument("--audio", type=Path, default=None, help="Directory of WAV files to diarize.")
    parser.add_argument("--truth", type=Path, default=None, help="Directory of truth JSON files for scoring/mapping.")
    parser.add_argument("--vad", type=Path, default=None, help="Directory of Android VAD JSON exports.")
    parser.add_argument("--out", type=Path, default=Path("generated_dataset/pyannote_baseline"))
    parser.add_argument("--model", default="pyannote/speaker-diarization-3.1")
    parser.add_argument("--hf-token", default=os.environ.get("HF_TOKEN"))
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--device", default=None, help="Optional torch device, e.g. cpu, cuda, mps.")
    parser.add_argument("--instructor-profile", type=Path, default=None)
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


def require_pyannote():
    try:
        from pyannote.audio import Pipeline
        return Pipeline
    except ImportError as exception:
        raise SystemExit(
            "Missing pyannote.audio. Install with:\n"
            "  pip install pyannote.audio\n"
            "On Kaggle, enable internet/GPU and set HF_TOKEN."
        ) from exception


def maybe_to_device(pipeline, device_name: str | None):
    if not device_name:
        return pipeline
    try:
        import torch
        pipeline.to(torch.device(device_name))
    except Exception as exception:
        raise SystemExit(f"Failed to move pyannote pipeline to device={device_name}: {exception}") from exception
    return pipeline


def load_duration_ms(truth_path: Path) -> int:
    payload = json.loads(truth_path.read_text(encoding="utf-8"))
    return int(payload["durationMillis"])


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


def choose_instructor_speaker(
    segments: list[dict],
    truth_path: Path,
    collar_ms: int,
) -> str | None:
    """Map anonymous pyannote speaker tag to instructor using generated truth.

    This is for baseline scoring only. In the real app, instructor mapping must
    come from enrollment audio or cluster-centroid embedding matching.
    """
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
        if not is_vad_speech(start_ms, end_ms, android_vad):
            role = ROLE_SILENCE
        else:
            active = [
                segment
                for segment in segments
                if segment["startMs"] < end_ms and segment["endMs"] > start_ms
            ]
            if not active:
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
        if role_intervals and role_intervals[-1]["role"] == role and role_intervals[-1]["endOffsetMillis"] == start_ms:
            role_intervals[-1]["endOffsetMillis"] = end_ms
        else:
            role_intervals.append({
                "role": role,
                "startOffsetMillis": start_ms,
                "endOffsetMillis": end_ms,
            })
    return role_intervals


def process_one(
    pipeline,
    audio_path: Path,
    truth_path: Path,
    prediction_path: Path,
    collar_ms: int,
    android_vad: dict | None,
    vad_fill_role: str,
) -> None:
    diarization = pipeline(str(audio_path))
    segments = diarization_segments(diarization)
    duration_ms = load_duration_ms(truth_path)
    instructor_speaker = choose_instructor_speaker(segments, truth_path, collar_ms)
    prediction = {
        "id": audio_path.stem,
        "audioFile": f"audio/{audio_path.name}",
        "durationMillis": duration_ms,
        "sampleRateHz": 16000,
        "diarizationEngine": "pyannote",
        "diarizationAudioFile": audio_path.as_posix(),
        "instructorSpeakerTag": instructor_speaker,
        "speakerSegments": segments,
        "roleIntervals": segments_to_role_intervals(
            segments,
            duration_ms,
            instructor_speaker,
            android_vad,
            vad_fill_role,
        ),
    }
    if android_vad is not None:
        prediction["vadEngine"] = android_vad["engine"]
        prediction["vadFile"] = android_vad["path"].as_posix()
        prediction["conditionedAudioFile"] = android_vad["conditionedAudioFile"]
    prediction_path.parent.mkdir(parents=True, exist_ok=True)
    prediction_path.write_text(json.dumps(prediction, indent=2), encoding="utf-8")


def labels_from_intervals(payload: dict, duration_ms: int, bin_ms: int) -> list[str]:
    roles = {ROLE_SILENCE, ROLE_INSTRUCTOR, ROLE_STUDENT, ROLE_BOTH}
    bin_count = max(1, math.ceil(duration_ms / bin_ms))
    labels = [ROLE_SILENCE] * bin_count
    for interval in payload.get("roleIntervals", []):
        role = interval.get("role", ROLE_SILENCE)
        if role not in roles:
            role = ROLE_SILENCE
        start_ms = max(0, int(interval.get("startOffsetMillis", 0)))
        end_ms = max(start_ms, min(duration_ms, int(interval.get("endOffsetMillis", start_ms))))
        for index in range(max(0, start_ms // bin_ms), min(bin_count, math.ceil(end_ms / bin_ms))):
            labels[index] = role
    return labels


def write_metrics(truth_dir: Path, prediction_dir: Path, output_path: Path, bin_ms: int) -> None:
    roles = (ROLE_SILENCE, ROLE_INSTRUCTOR, ROLE_STUDENT, ROLE_BOTH)
    confusion = {truth: {pred: 0 for pred in roles} for truth in roles}
    scored = 0
    for truth_path in sorted(truth_dir.glob("*.json")):
        pred_path = prediction_dir / truth_path.name
        if not pred_path.exists():
            continue
        truth = json.loads(truth_path.read_text(encoding="utf-8"))
        pred = json.loads(pred_path.read_text(encoding="utf-8"))
        duration_ms = int(truth["durationMillis"])
        truth_labels = labels_from_intervals(truth, duration_ms, bin_ms)
        pred_labels = labels_from_intervals(pred, duration_ms, bin_ms)
        for truth_label, pred_label in zip(truth_labels, pred_labels):
            confusion[truth_label][pred_label] += 1
        scored += 1

    total = sum(sum(row.values()) for row in confusion.values())
    correct = sum(confusion[role][role] for role in roles)
    role_metrics = {}
    for role in roles:
        tp = confusion[role][role]
        fp = sum(confusion[truth][role] for truth in roles if truth != role)
        fn = sum(confusion[role][pred] for pred in roles if pred != role)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = (2 * precision * recall / (precision + recall)) if precision + recall else 0.0
        role_metrics[role] = {
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "supportBins": sum(confusion[role].values()),
        }
    metrics = {
        "accuracy": correct / total if total else 0.0,
        "totalBins": total,
        "correctBins": correct,
        "scoredFiles": scored,
        "roles": role_metrics,
        "confusion": confusion,
    }
    output_path.write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    print(f"Wrote metrics to {output_path}")


def main() -> None:
    args = parse_args()
    if not args.hf_token:
        raise SystemExit(
            "HF token required. Set HF_TOKEN or pass --hf-token. "
            "You must also accept the pyannote model terms on Hugging Face."
        )

    Pipeline = require_pyannote()
    pipeline = Pipeline.from_pretrained(args.model, use_auth_token=args.hf_token)
    pipeline = maybe_to_device(pipeline, args.device)

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
        if not truth_path.exists():
            raise FileNotFoundError(f"Missing truth file: {truth_path}")
        prediction_path = prediction_dir / f"{audio_path.stem}.json"
        vad_path = args.vad / f"{audio_path.stem}.json" if args.vad is not None else None
        android_vad = load_android_vad(vad_path)
        if args.vad is not None and android_vad is None:
            raise FileNotFoundError(f"Missing Android VAD export: {vad_path}")
        print(f"[{index}/{len(audio_paths)}] {audio_path.name}")
        process_one(
            pipeline,
            audio_path,
            truth_path,
            prediction_path,
            args.collar_ms,
            android_vad,
            args.vad_fill_role,
        )

    print(f"Wrote predictions to {prediction_dir}")
    if args.score:
        write_metrics(truth_dir, prediction_dir, args.out / "metrics.json", args.bin_ms)
    print("Score with:")
    print(f"  python3 tools/score_dataset.py --truth {truth_dir} --pred {prediction_dir} --out {args.out / 'metrics.json'}")


if __name__ == "__main__":
    main()
