#!/usr/bin/env python3
"""Apply delayed pyannote role intervals to an app session metadata JSON."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROLES = {"SILENCE", "INSTRUCTOR", "STUDENT", "BOTH"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Patch session metadata with pyannote role intervals.")
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--prediction", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--engine", default="pyannote")
    return parser.parse_args()


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def normalize_intervals(payload: dict, duration_ms: int) -> list[dict]:
    intervals = []
    for interval in payload.get("roleIntervals", []):
        role = interval.get("role", "SILENCE")
        if role not in ROLES:
            role = "SILENCE"
        start_ms = max(0, int(interval.get("startOffsetMillis", 0)))
        end_ms = max(start_ms, min(duration_ms, int(interval.get("endOffsetMillis", start_ms))))
        if end_ms <= start_ms:
            continue
        item = {
            "role": role,
            "startOffsetMillis": start_ms,
            "endOffsetMillis": end_ms,
            "durationMillis": end_ms - start_ms,
        }
        if intervals and intervals[-1]["role"] == role and intervals[-1]["endOffsetMillis"] == start_ms:
            intervals[-1]["endOffsetMillis"] = end_ms
            intervals[-1]["durationMillis"] = intervals[-1]["endOffsetMillis"] - intervals[-1]["startOffsetMillis"]
        else:
            intervals.append(item)
    return intervals


def role_total(intervals: list[dict], role: str) -> int:
    return sum(int(interval["durationMillis"]) for interval in intervals if interval["role"] == role)


def speech_intervals_from_roles(intervals: list[dict]) -> list[dict]:
    speech = []
    for interval in intervals:
        if interval["role"] == "SILENCE":
            continue
        start_ms = interval["startOffsetMillis"]
        end_ms = interval["endOffsetMillis"]
        if speech and speech[-1]["endOffsetMillis"] == start_ms:
            speech[-1]["endOffsetMillis"] = end_ms
            speech[-1]["durationMillis"] = speech[-1]["endOffsetMillis"] - speech[-1]["startOffsetMillis"]
        else:
            speech.append({
                "startOffsetMillis": start_ms,
                "endOffsetMillis": end_ms,
                "durationMillis": end_ms - start_ms,
            })
    return speech


def main() -> None:
    args = parse_args()
    metadata = load_json(args.metadata)
    prediction = load_json(args.prediction)
    duration_ms = int(metadata.get("durationMillis", prediction.get("durationMillis", 0)))
    role_intervals = normalize_intervals(prediction, duration_ms)
    speech_intervals = speech_intervals_from_roles(role_intervals)

    total_speech_ms = sum(interval["durationMillis"] for interval in speech_intervals)
    metadata["roleIntervals"] = role_intervals
    metadata["speechIntervals"] = speech_intervals
    metadata["totalSpeechMillis"] = total_speech_ms
    metadata["totalSilenceMillis"] = max(0, duration_ms - total_speech_ms)
    metadata["totalInstructorMillis"] = role_total(role_intervals, "INSTRUCTOR")
    metadata["totalStudentMillis"] = role_total(role_intervals, "STUDENT")
    metadata["totalBothMillis"] = role_total(role_intervals, "BOTH")
    metadata["speakingRatio"] = (total_speech_ms / duration_ms) if duration_ms else 0.0
    metadata["intervalCount"] = len(speech_intervals)
    metadata["longestSpeechMillis"] = max((interval["durationMillis"] for interval in speech_intervals), default=0)
    metadata["diarizationStatus"] = "COMPLETE"
    metadata["diarizationEngine"] = args.engine

    output_path = args.out or args.metadata
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(f"Wrote updated metadata to {output_path}")


if __name__ == "__main__":
    main()
