#!/usr/bin/env python3
"""Generate synthetic role-labeled audio for batch evaluation."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import re
import shutil
import struct
import subprocess
import wave
from dataclasses import dataclass
from pathlib import Path


SAMPLE_RATE_HZ = 16000
SAMPLE_WIDTH_BYTES = 2
CHANNELS = 1
ROLE_SILENCE = "SILENCE"
ROLE_INSTRUCTOR = "INSTRUCTOR"
ROLE_STUDENT = "STUDENT"
SUPPORTED_AUDIO_EXTENSIONS = {
    ".aac",
    ".aif",
    ".aiff",
    ".caf",
    ".flac",
    ".m4a",
    ".mp3",
    ".mp4",
    ".ogg",
    ".opus",
    ".wav",
}

SCENARIOS = (
    "instructor_only",
    "student_only",
    "mixed_turns",
    "overlap_heavy",
    "silence_heavy",
    "noise_only",
)


@dataclass(frozen=True)
class Clip:
    path: Path
    speaker_id: str
    samples: tuple[int, ...]

    @property
    def duration_ms(self) -> int:
        return int(round((len(self.samples) * 1000) / SAMPLE_RATE_HZ))


@dataclass(frozen=True)
class Event:
    role: str
    speaker_id: str
    source_file: str
    start_ms: int
    end_ms: int
    gain: float


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate synthetic instructor/student evaluation WAVs.")
    parser.add_argument("--sources", type=Path, default=Path("dataset_sources"))
    parser.add_argument("--out", type=Path, default=Path("generated_dataset"))
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--duration-ms", type=int, default=60_000)
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--max-instructor-clips", type=int, default=5)
    parser.add_argument("--max-student-clips", type=int, default=7)
    parser.add_argument("--max-noise-clips", type=int, default=3)
    parser.add_argument("--min-gain", type=float, default=0.65)
    parser.add_argument("--max-gain", type=float, default=1.15)
    parser.add_argument("--min-noise-gain", type=float, default=0.10)
    parser.add_argument("--max-noise-gain", type=float, default=0.55)
    parser.add_argument("--corruption-prob", type=float, default=0.20)
    parser.add_argument("--min-segment-ms", type=int, default=8_000)
    parser.add_argument("--max-segment-ms", type=int, default=30_000)
    parser.add_argument(
        "--scenario",
        choices=(*SCENARIOS, "auto"),
        default="auto",
        help="Generate one scenario type, or auto for a realistic mix.",
    )
    parser.add_argument(
        "--normalized-cache",
        type=Path,
        default=None,
        help="Where converted 16 kHz mono PCM16 WAV source clips are cached. Defaults to <out>/normalized_sources.",
    )
    return parser.parse_args()


def looks_like_ogg(path: Path) -> bool:
    try:
        with path.open("rb") as input_file:
            return input_file.read(4) == b"OggS"
    except OSError:
        return False


def read_pcm16_wav(path: Path) -> tuple[int, ...]:
    with wave.open(str(path), "rb") as wav_file:
        if wav_file.getframerate() != SAMPLE_RATE_HZ:
            raise ValueError(f"{path} must be {SAMPLE_RATE_HZ} Hz.")
        if wav_file.getnchannels() != CHANNELS:
            raise ValueError(f"{path} must be mono.")
        if wav_file.getsampwidth() != SAMPLE_WIDTH_BYTES:
            raise ValueError(f"{path} must be 16-bit PCM.")
        frame_count = wav_file.getnframes()
        payload = wav_file.readframes(frame_count)
    return struct.unpack(f"<{len(payload) // SAMPLE_WIDTH_BYTES}h", payload)


def source_audio_files(root: Path, pattern: str) -> list[Path]:
    return [
        path
        for path in sorted(root.glob(pattern))
        if path.is_file()
        and not path.name.startswith(".")
        and path.suffix.lower() in SUPPORTED_AUDIO_EXTENSIONS
    ]


def converter_name() -> str | None:
    if shutil.which("ffmpeg"):
        return "ffmpeg"
    if shutil.which("afconvert"):
        return "afconvert"
    return None


def normalized_cache_path(path: Path, cache_dir: Path) -> Path:
    stat = path.stat()
    digest = hashlib.sha256(
        f"{path.resolve()}:{stat.st_size}:{stat.st_mtime_ns}".encode("utf-8")
    ).hexdigest()[:16]
    safe_stem = re.sub(r"[^A-Za-z0-9_.-]+", "_", path.stem).strip("._") or "clip"
    return cache_dir / f"{safe_stem}_{digest}.wav"


def convert_to_pcm16_wav(path: Path, cache_dir: Path) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    output_path = normalized_cache_path(path, cache_dir)
    if output_path.exists():
        return output_path

    converter = converter_name()
    is_ogg_opus = path.suffix.lower() in {".ogg", ".opus"} or looks_like_ogg(path)
    if is_ogg_opus and not shutil.which("ffmpeg"):
        raise RuntimeError(
            f"{path} is Ogg/Opus audio. Install ffmpeg to decode it; macOS afconvert cannot read this format."
        )
    if converter is None:
        raise RuntimeError(
            "Input clip conversion needs ffmpeg or macOS afconvert. Install ffmpeg, "
            "or run this script on macOS where afconvert is available."
        )
    if is_ogg_opus:
        converter = "ffmpeg"

    if converter == "ffmpeg":
        command = [
            "ffmpeg",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(path),
            "-ar",
            str(SAMPLE_RATE_HZ),
            "-ac",
            "1",
            "-sample_fmt",
            "s16",
            str(output_path),
        ]
    else:
        command = [
            "afconvert",
            "-f",
            "WAVE",
            "-d",
            f"LEI16@{SAMPLE_RATE_HZ}",
            "-c",
            "1",
            str(path),
            str(output_path),
        ]

    try:
        subprocess.run(command, check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as exception:
        raise RuntimeError(
            f"Failed to convert {path} with {converter}: "
            f"{exception.stderr.strip() or exception.stdout.strip()}"
        ) from exception
    return output_path


def crop_clip_samples(rng: random.Random, samples: tuple[int, ...], target_ms: int) -> tuple[int, ...]:
    target_samples = max(1, int((target_ms * SAMPLE_RATE_HZ) / 1000))
    if len(samples) <= target_samples:
        return samples
    start = rng.randint(0, len(samples) - target_samples)
    return samples[start:start + target_samples]


def read_any_supported_audio(path: Path, cache_dir: Path) -> tuple[int, ...]:
    if path.suffix.lower() == ".wav":
        try:
            return read_pcm16_wav(path)
        except (ValueError, wave.Error):
            pass
    normalized_path = convert_to_pcm16_wav(path, cache_dir)
    return read_pcm16_wav(normalized_path)


def discover_clips(sources: Path, cache_dir: Path) -> tuple[list[Clip], list[Clip], list[Clip]]:
    instructor = [
        Clip(path=path, speaker_id="instructor", samples=read_any_supported_audio(path, cache_dir))
        for path in source_audio_files(sources / "instructor", "*")
    ]
    students: list[Clip] = []
    for path in source_audio_files(sources / "students", "*/*"):
        students.append(Clip(path=path, speaker_id=path.parent.name, samples=read_any_supported_audio(path, cache_dir)))
    noise = [
        Clip(path=path, speaker_id=path.parent.name, samples=read_any_supported_audio(path, cache_dir))
        for path in source_audio_files(sources / "noise", "*/*")
    ]
    if not instructor:
        raise ValueError(f"No instructor audio files found in {sources / 'instructor'}.")
    if not students:
        raise ValueError(f"No student audio files found in {sources / 'students' / '<student_id>'}.")
    return instructor, students, noise


def relative_source(path: Path, sources: Path) -> str:
    try:
        return path.relative_to(sources).as_posix()
    except ValueError:
        return path.as_posix()


def random_start_ms(rng: random.Random, duration_ms: int, clip_duration_ms: int) -> int:
    latest_start = max(0, duration_ms - min(duration_ms, clip_duration_ms))
    return rng.randint(0, latest_start)


def choose_scenario(rng: random.Random, requested: str) -> str:
    if requested != "auto":
        return requested
    roll = rng.random()
    if roll < 0.18:
        return "instructor_only"
    if roll < 0.36:
        return "student_only"
    if roll < 0.70:
        return "mixed_turns"
    if roll < 0.84:
        return "overlap_heavy"
    if roll < 0.94:
        return "silence_heavy"
    return "noise_only"


def segment_count_for_scenario(rng: random.Random, scenario: str) -> int:
    if scenario in {"instructor_only", "student_only"}:
        return rng.randint(1, 3)
    if scenario == "mixed_turns":
        return rng.randint(3, 6)
    if scenario == "overlap_heavy":
        return rng.randint(3, 5)
    if scenario == "silence_heavy":
        return rng.randint(1, 3)
    return 0


def next_segment_start(
    rng: random.Random,
    scenario: str,
    previous_end_ms: int,
    duration_ms: int,
    segment_ms: int,
) -> int:
    if previous_end_ms <= 0:
        return random_start_ms(rng, duration_ms, segment_ms)
    if scenario == "overlap_heavy":
        overlap_ms = rng.randint(int(segment_ms * 0.25), int(segment_ms * 0.70))
        return max(0, min(duration_ms - segment_ms, previous_end_ms - overlap_ms))
    if scenario == "silence_heavy":
        gap_ms = rng.randint(8_000, 22_000)
    else:
        gap_ms = rng.randint(500, 6_000)
    return max(0, min(duration_ms - segment_ms, previous_end_ms + gap_ms))


def scenario_role_plan(rng: random.Random, scenario: str, segment_count: int) -> list[str]:
    if scenario == "instructor_only":
        return [ROLE_INSTRUCTOR] * segment_count
    if scenario == "student_only":
        return [ROLE_STUDENT] * segment_count
    if scenario == "overlap_heavy":
        return [ROLE_INSTRUCTOR if index % 2 == 0 else ROLE_STUDENT for index in range(segment_count)]
    roles = []
    previous = rng.choice((ROLE_INSTRUCTOR, ROLE_STUDENT))
    for _ in range(segment_count):
        if rng.random() < 0.75:
            previous = ROLE_STUDENT if previous == ROLE_INSTRUCTOR else ROLE_INSTRUCTOR
        roles.append(previous)
    return roles


def mix_clip(
    output: list[float],
    clip: Clip,
    start_ms: int,
    duration_ms: int,
    gain: float,
) -> int:
    start_sample = int((start_ms * SAMPLE_RATE_HZ) / 1000)
    max_samples = min(len(clip.samples), len(output) - start_sample)
    if max_samples <= 0:
        return start_ms
    for index in range(max_samples):
        output[start_sample + index] += clip.samples[index] * gain
    end_sample = start_sample + max_samples
    return min(duration_ms, int(round((end_sample * 1000) / SAMPLE_RATE_HZ)))


def maybe_corrupt(rng: random.Random, samples: list[float], probability: float) -> None:
    if rng.random() >= probability:
        return
    mode = rng.choice(("soft_clip", "dropout", "global_gain"))
    if mode == "soft_clip":
        threshold = rng.uniform(18_000.0, 28_000.0)
        for index, sample in enumerate(samples):
            if sample > threshold:
                samples[index] = threshold + math.tanh((sample - threshold) / threshold) * threshold * 0.35
            elif sample < -threshold:
                samples[index] = -threshold + math.tanh((sample + threshold) / threshold) * threshold * 0.35
    elif mode == "dropout":
        start = rng.randint(0, max(0, len(samples) - 1))
        length = rng.randint(80, min(1200, max(80, len(samples) - start)))
        for index in range(start, min(len(samples), start + length)):
            samples[index] *= rng.uniform(0.05, 0.25)
    else:
        gain = rng.uniform(0.75, 1.25)
        for index, sample in enumerate(samples):
            samples[index] = sample * gain


def clip_to_pcm16(samples: list[float]) -> bytes:
    ints = [max(-32768, min(32767, int(round(sample)))) for sample in samples]
    return struct.pack(f"<{len(ints)}h", *ints)


def write_wav(path: Path, samples: list[float]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(CHANNELS)
        wav_file.setsampwidth(SAMPLE_WIDTH_BYTES)
        wav_file.setframerate(SAMPLE_RATE_HZ)
        wav_file.writeframes(clip_to_pcm16(samples))


def build_role_intervals(events: list[Event], duration_ms: int) -> list[dict[str, int | str]]:
    boundaries = {0, duration_ms}
    speech_events = [event for event in events if event.role in (ROLE_INSTRUCTOR, ROLE_STUDENT)]
    for event in speech_events:
        boundaries.add(max(0, min(duration_ms, event.start_ms)))
        boundaries.add(max(0, min(duration_ms, event.end_ms)))

    intervals: list[dict[str, int | str]] = []
    sorted_boundaries = sorted(boundaries)
    for start_ms, end_ms in zip(sorted_boundaries, sorted_boundaries[1:]):
        if start_ms >= end_ms:
            continue
        instructor_active = any(
            event.role == ROLE_INSTRUCTOR and event.start_ms < end_ms and event.end_ms > start_ms
            for event in speech_events
        )
        student_active = any(
            event.role == ROLE_STUDENT and event.start_ms < end_ms and event.end_ms > start_ms
            for event in speech_events
        )
        if instructor_active and student_active:
            role = "BOTH"
        elif instructor_active:
            role = ROLE_INSTRUCTOR
        elif student_active:
            role = ROLE_STUDENT
        else:
            role = ROLE_SILENCE
        if intervals and intervals[-1]["role"] == role and intervals[-1]["endOffsetMillis"] == start_ms:
            intervals[-1]["endOffsetMillis"] = end_ms
        else:
            intervals.append({
                "role": role,
                "startOffsetMillis": start_ms,
                "endOffsetMillis": end_ms,
            })
    return intervals


def generate_item(
    rng: random.Random,
    item_id: str,
    sources: Path,
    duration_ms: int,
    instructor_clips: list[Clip],
    student_clips: list[Clip],
    noise_clips: list[Clip],
    args: argparse.Namespace,
) -> tuple[list[float], dict]:
    total_samples = int((duration_ms * SAMPLE_RATE_HZ) / 1000)
    output = [0.0] * total_samples
    events: list[Event] = []
    noise_events: list[Event] = []

    scenario = choose_scenario(rng, args.scenario)
    segment_count = segment_count_for_scenario(rng, scenario)
    role_plan = scenario_role_plan(rng, scenario, segment_count)
    previous_end_ms = 0

    for role in role_plan:
        source_pool = instructor_clips if role == ROLE_INSTRUCTOR else student_clips
        clip = rng.choice(source_pool)
        segment_ms = rng.randint(args.min_segment_ms, args.max_segment_ms)
        segment_ms = min(segment_ms, duration_ms)
        samples = crop_clip_samples(rng, clip.samples, segment_ms)
        clip = Clip(path=clip.path, speaker_id=clip.speaker_id, samples=samples)
        start_ms = next_segment_start(rng, scenario, previous_end_ms, duration_ms, clip.duration_ms)
        gain = rng.uniform(args.min_gain, args.max_gain)
        end_ms = mix_clip(output, clip, start_ms, duration_ms, gain)
        previous_end_ms = max(previous_end_ms, end_ms)
        events.append(Event(role, clip.speaker_id, relative_source(clip.path, sources), start_ms, end_ms, gain))

    if noise_clips:
        min_noise = 1 if scenario == "noise_only" else 0
        max_noise = max(min_noise, args.max_noise_clips)
        for _ in range(rng.randint(min_noise, max_noise)):
            clip = rng.choice(noise_clips)
            start_ms = random_start_ms(rng, duration_ms, clip.duration_ms)
            gain = rng.uniform(args.min_noise_gain, args.max_noise_gain)
            end_ms = mix_clip(output, clip, start_ms, duration_ms, gain)
            noise_events.append(Event("NOISE", clip.speaker_id, relative_source(clip.path, sources), start_ms, end_ms, gain))

    maybe_corrupt(rng, output, args.corruption_prob)
    truth = {
        "id": item_id,
        "audioFile": f"audio/{item_id}.wav",
        "durationMillis": duration_ms,
        "sampleRateHz": SAMPLE_RATE_HZ,
        "scenario": scenario,
        "roleIntervals": build_role_intervals(events, duration_ms),
        "events": [event.__dict__ for event in sorted(events, key=lambda item: item.start_ms)],
        "noiseEvents": [event.__dict__ for event in sorted(noise_events, key=lambda item: item.start_ms)],
    }
    return output, truth


def main() -> None:
    args = parse_args()
    if args.count <= 0:
        raise ValueError("--count must be positive.")
    if args.duration_ms <= 0:
        raise ValueError("--duration-ms must be positive.")

    cache_dir = args.normalized_cache or (args.out / "normalized_sources")
    instructor_clips, student_clips, noise_clips = discover_clips(args.sources, cache_dir)
    audio_dir = args.out / "audio"
    truth_dir = args.out / "truth"
    audio_dir.mkdir(parents=True, exist_ok=True)
    truth_dir.mkdir(parents=True, exist_ok=True)

    rng = random.Random(args.seed)
    manifest_path = args.out / "manifest.jsonl"
    with manifest_path.open("w", encoding="utf-8") as manifest_file:
        for index in range(1, args.count + 1):
            item_id = f"sample_{index:06d}"
            samples, truth = generate_item(
                rng,
                item_id,
                args.sources,
                args.duration_ms,
                instructor_clips,
                student_clips,
                noise_clips,
                args,
            )
            write_wav(audio_dir / f"{item_id}.wav", samples)
            truth_path = truth_dir / f"{item_id}.json"
            truth_path.write_text(json.dumps(truth, indent=2), encoding="utf-8")
            manifest_file.write(json.dumps({
                "id": item_id,
                "audioFile": truth["audioFile"],
                "truthFile": f"truth/{item_id}.json",
            }) + "\n")

    print(f"Generated {args.count} items in {args.out}.")


if __name__ == "__main__":
    main()
