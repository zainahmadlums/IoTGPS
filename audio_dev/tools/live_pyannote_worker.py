#!/usr/bin/env python3
"""Process live Android diarization chunks with pyannote.

This is the Mac-side experiment worker. Android records live speech/silence and
40s conditioned chunks with 5s overlap. This worker processes completed chunks,
maps anonymous pyannote speakers to INSTRUCTOR using the live enrolled
instructor audio, and incrementally writes app-compatible role intervals.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import time
from pathlib import Path


ROLE_SILENCE = "SILENCE"
ROLE_INSTRUCTOR = "INSTRUCTOR"
ROLE_STUDENT = "STUDENT"
ROLE_BOTH = "BOTH"
CHUNK_MS = 40_000
OVERLAP_MS = 5_000
STEP_MS = CHUNK_MS - OVERLAP_MS


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run delayed pyannote on live Android chunks.")
    parser.add_argument("--chunk-dir", type=Path, required=True, help="Directory containing diarization chunk WAVs.")
    parser.add_argument("--metadata", type=Path, default=None, help="Optional app session metadata JSON to patch.")
    parser.add_argument("--instructor-audio", type=Path, required=True, help="Live enrolled instructor WAV.")
    parser.add_argument("--out", type=Path, required=True, help="Worker output/state directory.")
    parser.add_argument("--model", default="pyannote/speaker-diarization-3.1")
    parser.add_argument("--embedding-model", default="pyannote/embedding")
    parser.add_argument("--hf-token", default=os.environ.get("HF_TOKEN"))
    parser.add_argument("--device", default="mps", help="Try mps on Mac M1; use cpu if unsupported.")
    parser.add_argument("--poll-sec", type=float, default=5.0)
    parser.add_argument("--once", action="store_true")
    return parser.parse_args()


def require_token(token: str | None) -> str:
    if not token:
        raise SystemExit("HF_TOKEN required for pyannote models.")
    return token


def maybe_to_device(obj, device_name: str | None):
    if not device_name:
        return obj
    try:
        import torch
        obj.to(torch.device(device_name))
        return obj
    except Exception:
        if device_name == "mps":
            obj.to(torch.device("cpu"))
            return obj
        raise


def load_models(args: argparse.Namespace):
    from pyannote.audio import Inference, Model, Pipeline

    token = require_token(args.hf_token)
    pipeline = Pipeline.from_pretrained(args.model, use_auth_token=token)
    pipeline = maybe_to_device(pipeline, args.device)
    embedding_model = Model.from_pretrained(args.embedding_model, use_auth_token=token)
    embedding_inference = Inference(maybe_to_device(embedding_model, args.device), window="whole")
    return pipeline, embedding_inference


def cosine(a: list[float], b: list[float]) -> float:
    numerator = sum(x * y for x, y in zip(a, b))
    a_norm = math.sqrt(sum(x * x for x in a))
    b_norm = math.sqrt(sum(y * y for y in b))
    return numerator / (a_norm * b_norm) if a_norm and b_norm else 0.0


def flatten_embedding(value) -> list[float]:
    try:
        import numpy as np
        return np.asarray(value, dtype=float).reshape(-1).tolist()
    except Exception:
        return [float(item) for item in value]


def cache_key(path: Path) -> dict:
    stat = path.stat()
    return {"path": str(path.resolve()), "size": stat.st_size, "mtime_ns": stat.st_mtime_ns}


def load_or_build_instructor_embedding(inference, instructor_audio: Path, cache_path: Path) -> list[float]:
    key = cache_key(instructor_audio)
    if cache_path.exists():
        payload = json.loads(cache_path.read_text(encoding="utf-8"))
        if payload.get("source") == key:
            return [float(item) for item in payload["embedding"]]
    embedding = flatten_embedding(inference(str(instructor_audio)))
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps({"source": key, "embedding": embedding}), encoding="utf-8")
    return embedding


def diarization_segments(diarization, offset_ms: int) -> list[dict]:
    segments = []
    for turn, _, speaker in diarization.itertracks(yield_label=True):
        segments.append({
            "startMs": offset_ms + int(round(float(turn.start) * 1000.0)),
            "endMs": offset_ms + int(round(float(turn.end) * 1000.0)),
            "speaker": str(speaker),
        })
    return sorted(segments, key=lambda item: (item["startMs"], item["endMs"], item["speaker"]))


def chunk_index(path: Path) -> int:
    match = re.search(r"_(\d{4})\.wav$", path.name)
    return int(match.group(1)) if match else 1


def chunk_offset_ms(path: Path, metadata: dict | None) -> int:
    if metadata:
        for chunk in metadata.get("diarizationChunks", []):
            if chunk.get("audioFileName") == path.name:
                return int(chunk.get("startOffsetMillis", 0))
    return max(0, (chunk_index(path) - 1) * STEP_MS)


def stable_chunks(chunk_dir: Path) -> list[Path]:
    return sorted(
        path for path in chunk_dir.glob("deployteach_diarization_chunk_*.wav")
        if path.is_file() and path.stat().st_size > 44
    )


def speaker_embedding(inference, audio_path: Path, start_ms: int, end_ms: int, offset_ms: int) -> list[float] | None:
    if end_ms - start_ms < 500:
        return None
    from pyannote.core import Segment

    local_start = max(0.0, (start_ms - offset_ms) / 1000.0)
    local_end = max(local_start, (end_ms - offset_ms) / 1000.0)
    try:
        return flatten_embedding(inference.crop(str(audio_path), Segment(local_start, local_end)))
    except Exception:
        return None


def choose_instructor_speaker(
    inference,
    audio_path: Path,
    segments: list[dict],
    offset_ms: int,
    instructor_embedding: list[float],
) -> str | None:
    embeddings_by_speaker: dict[str, list[list[float]]] = {}
    for segment in segments:
        embedding = speaker_embedding(
            inference,
            audio_path,
            int(segment["startMs"]),
            int(segment["endMs"]),
            offset_ms,
        )
        if embedding is not None:
            embeddings_by_speaker.setdefault(segment["speaker"], []).append(embedding)
    best_speaker = None
    best_score = -1.0
    for speaker, embeddings in embeddings_by_speaker.items():
        centroid = [
            sum(values) / len(values)
            for values in zip(*embeddings)
        ]
        score = cosine(centroid, instructor_embedding)
        if score > best_score:
            best_score = score
            best_speaker = speaker
    return best_speaker


def segments_to_role_intervals(segments: list[dict], instructor_speaker: str | None) -> list[dict]:
    if not segments:
        return []
    boundaries = set()
    for segment in segments:
        boundaries.add(segment["startMs"])
        boundaries.add(segment["endMs"])
    intervals = []
    for start_ms, end_ms in zip(sorted(boundaries), sorted(boundaries)[1:]):
        active = [s for s in segments if s["startMs"] < end_ms and s["endMs"] > start_ms]
        if not active:
            continue
        has_instructor = any(s["speaker"] == instructor_speaker for s in active)
        has_student = any(s["speaker"] != instructor_speaker for s in active)
        if has_instructor and has_student:
            role = ROLE_BOTH
        elif has_instructor:
            role = ROLE_INSTRUCTOR
        else:
            role = ROLE_STUDENT
        append_interval(intervals, role, start_ms, end_ms)
    return intervals


def append_interval(intervals: list[dict], role: str, start_ms: int, end_ms: int) -> None:
    if end_ms <= start_ms:
        return
    if intervals and intervals[-1]["role"] == role and intervals[-1]["endOffsetMillis"] == start_ms:
        intervals[-1]["endOffsetMillis"] = end_ms
        intervals[-1]["durationMillis"] = intervals[-1]["endOffsetMillis"] - intervals[-1]["startOffsetMillis"]
    else:
        intervals.append({
            "role": role,
            "startOffsetMillis": start_ms,
            "endOffsetMillis": end_ms,
            "durationMillis": end_ms - start_ms,
        })


def load_json(path: Path) -> dict | None:
    if not path or not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def load_state(path: Path) -> dict:
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"processedChunks": [], "chunkPredictions": []}


def write_state(path: Path, state: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, indent=2), encoding="utf-8")


def merged_intervals(chunk_predictions: list[dict]) -> list[dict]:
    intervals = []
    for prediction in sorted(chunk_predictions, key=lambda item: item["chunkStartMs"]):
        keep_start = prediction["chunkStartMs"] if prediction["chunkIndex"] == 1 else prediction["chunkStartMs"] + OVERLAP_MS
        keep_end = prediction["chunkStartMs"] + CHUNK_MS
        for interval in prediction.get("roleIntervals", []):
            start_ms = max(keep_start, int(interval["startOffsetMillis"]))
            end_ms = min(keep_end, int(interval["endOffsetMillis"]))
            append_interval(intervals, interval["role"], start_ms, end_ms)
    return intervals


def patch_metadata(metadata_path: Path | None, role_intervals: list[dict], out_path: Path) -> None:
    metadata = load_json(metadata_path) if metadata_path else None
    if metadata is None:
        metadata = {"durationMillis": max((i["endOffsetMillis"] for i in role_intervals), default=0)}
    duration_ms = int(metadata.get("durationMillis", max((i["endOffsetMillis"] for i in role_intervals), default=0)))
    speech_intervals = []
    for interval in role_intervals:
        if interval["role"] != ROLE_SILENCE:
            append_speech_interval(speech_intervals, interval["startOffsetMillis"], interval["endOffsetMillis"])
    total_speech = sum(item["durationMillis"] for item in speech_intervals)
    metadata["roleIntervals"] = role_intervals
    metadata["speechIntervals"] = speech_intervals
    metadata["totalSpeechMillis"] = total_speech
    metadata["totalSilenceMillis"] = max(0, duration_ms - total_speech)
    metadata["totalInstructorMillis"] = sum(i["durationMillis"] for i in role_intervals if i["role"] == ROLE_INSTRUCTOR)
    metadata["totalStudentMillis"] = sum(i["durationMillis"] for i in role_intervals if i["role"] == ROLE_STUDENT)
    metadata["totalBothMillis"] = sum(i["durationMillis"] for i in role_intervals if i["role"] == ROLE_BOTH)
    metadata["speakingRatio"] = (total_speech / duration_ms) if duration_ms else 0.0
    metadata["intervalCount"] = len(speech_intervals)
    metadata["longestSpeechMillis"] = max((i["durationMillis"] for i in speech_intervals), default=0)
    metadata["diarizationStatus"] = "PARTIAL"
    metadata["diarizationEngine"] = "pyannote-live"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")


def append_speech_interval(intervals: list[dict], start_ms: int, end_ms: int) -> None:
    if intervals and intervals[-1]["endOffsetMillis"] == start_ms:
        intervals[-1]["endOffsetMillis"] = end_ms
        intervals[-1]["durationMillis"] = intervals[-1]["endOffsetMillis"] - intervals[-1]["startOffsetMillis"]
    else:
        intervals.append({
            "startOffsetMillis": start_ms,
            "endOffsetMillis": end_ms,
            "durationMillis": end_ms - start_ms,
        })


def process_available_chunks(args, pipeline, embedding_inference, instructor_embedding) -> int:
    metadata = load_json(args.metadata)
    state_path = args.out / "live_state.json"
    state = load_state(state_path)
    processed = set(state.get("processedChunks", []))
    processed_count = 0
    prediction_dir = args.out / "chunk_predictions"
    for chunk_path in stable_chunks(args.chunk_dir):
        if chunk_path.name in processed:
            continue
        offset_ms = chunk_offset_ms(chunk_path, metadata)
        diarization = pipeline(str(chunk_path))
        segments = diarization_segments(diarization, offset_ms)
        instructor_speaker = choose_instructor_speaker(
            embedding_inference,
            chunk_path,
            segments,
            offset_ms,
            instructor_embedding,
        )
        prediction = {
            "chunkFile": chunk_path.name,
            "chunkIndex": chunk_index(chunk_path),
            "chunkStartMs": offset_ms,
            "diarizationEngine": "pyannote-live",
            "instructorSpeakerTag": instructor_speaker,
            "speakerSegments": segments,
            "roleIntervals": segments_to_role_intervals(segments, instructor_speaker),
        }
        prediction_dir.mkdir(parents=True, exist_ok=True)
        (prediction_dir / f"{chunk_path.stem}.json").write_text(json.dumps(prediction, indent=2), encoding="utf-8")
        state["processedChunks"].append(chunk_path.name)
        state["chunkPredictions"].append(prediction)
        merged = merged_intervals(state["chunkPredictions"])
        patch_metadata(args.metadata, merged, args.out / "session_metadata_pyannote_partial.json")
        write_state(state_path, state)
        processed_count += 1
        print(f"processed {chunk_path.name}")
    return processed_count


def main() -> None:
    args = parse_args()
    pipeline, embedding_inference = load_models(args)
    instructor_embedding = load_or_build_instructor_embedding(
        embedding_inference,
        args.instructor_audio,
        args.out / "instructor_embedding_cache.json",
    )
    while True:
        count = process_available_chunks(args, pipeline, embedding_inference, instructor_embedding)
        if args.once:
            break
        if count == 0:
            time.sleep(args.poll_sec)


if __name__ == "__main__":
    main()
