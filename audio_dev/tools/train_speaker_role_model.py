#!/usr/bin/env python3
"""Train a compact speaker-role model for the Android diarization layer."""

from __future__ import annotations

import argparse
import json
import math
import random
import shutil
import struct
import subprocess
import wave
from collections import deque
from pathlib import Path

SAMPLE_RATE_HZ = 16000
FRAME_SIZE = 512
BAND_COUNT = 24
MIN_DFT_BIN = 2
MAX_DFT_BIN = 96
FEATURE_COUNT = BAND_COUNT + 4
SPEAKER_WINDOW_FRAMES = 24
EMBEDDING_SAMPLE_FRAMES = 8
LABELS = ("INSTRUCTOR", "STUDENT", "BOTH")
SUPPORTED_AUDIO_EXTENSIONS = {".wav", ".opus", ".ogg", ".m4a", ".aac", ".mp3", ".flac", ".caf", ".aif", ".aiff", ".mp4"}
MAX_PROFILE_FRAMES_PER_CLIP = 40


def parse_args():
    parser = argparse.ArgumentParser(description="Train lightweight speaker role classifier.")
    parser.add_argument("--sources", type=Path, default=Path("dataset_sources"))
    parser.add_argument("--out", type=Path, default=Path("app/src/main/assets/speaker_role_model.model"))
    parser.add_argument("--cache", type=Path, default=Path("generated_dataset/normalized_sources"))
    parser.add_argument("--epochs", type=int, default=220)
    parser.add_argument("--learning-rate", type=float, default=0.055)
    parser.add_argument("--seed", type=int, default=2026)
    parser.add_argument("--max-windows-per-role", type=int, default=650)
    return parser.parse_args()


def source_audio_files(root, pattern):
    return [
        path for path in sorted(root.glob(pattern))
        if path.is_file() and not path.name.startswith(".") and path.suffix.lower() in SUPPORTED_AUDIO_EXTENSIONS
    ]


def read_audio(path, cache):
    cache.mkdir(parents=True, exist_ok=True)
    output = cache / f"{path.parent.name}_{path.stem}_{abs(hash(str(path.resolve())))}.wav"
    if not output.exists():
        if not shutil.which("ffmpeg"):
            raise RuntimeError("ffmpeg is required to train from the provided audio clips.")
        subprocess.run(
            [
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(path),
                "-ar", str(SAMPLE_RATE_HZ), "-ac", "1", "-sample_fmt", "s16", str(output),
            ],
            check=True,
        )
    with wave.open(str(output), "rb") as wav_file:
        payload = wav_file.readframes(wav_file.getnframes())
    return struct.unpack(f"<{len(payload) // 2}h", payload)


def frames(samples):
    for offset in range(0, len(samples) - FRAME_SIZE + 1, FRAME_SIZE):
        yield samples[offset:offset + FRAME_SIZE]


def sampled_frames(samples, limit=MAX_PROFILE_FRAMES_PER_CLIP):
    all_frames = list(frames(samples))
    if len(all_frames) <= limit:
        return all_frames
    output = []
    for index in range(limit):
        frame_index = round(index * (len(all_frames) - 1) / (limit - 1))
        output.append(all_frames[frame_index])
    return output


def frame_features(frame):
    energy = 0.0
    crossings = 0
    previous = frame[0]
    for sample in frame:
        energy += sample * sample
        if (previous >= 0 > sample) or (previous < 0 <= sample):
            crossings += 1
        previous = sample
    rms = math.sqrt(energy / len(frame)) / 32767.0
    zcr = crossings / len(frame)
    pitch = pitch_correlation(frame, energy)
    bands, centroid = log_bands(frame)
    return bands + [rms, zcr, pitch, centroid]


def log_bands(frame):
    total_power = 0.0
    weighted_bin_sum = 0.0
    band_power = [0.0] * BAND_COUNT
    n = len(frame)
    window = [0.5 - (0.5 * math.cos((2.0 * math.pi * i) / (n - 1))) for i in range(n)]
    for bin_index in range(MIN_DFT_BIN, MAX_DFT_BIN + 1):
        real = 0.0
        imag = 0.0
        for index, sample in enumerate(frame):
            angle = (2.0 * math.pi * bin_index * index) / n
            value = sample * window[index]
            real += value * math.cos(angle)
            imag -= value * math.sin(angle)
        power = (real * real) + (imag * imag)
        band = min(BAND_COUNT - 1, (bin_index - MIN_DFT_BIN) * BAND_COUNT // (MAX_DFT_BIN - MIN_DFT_BIN + 1))
        band_power[band] += power
        total_power += power
        weighted_bin_sum += power * bin_index
    values = [math.log(1.0e-6 + (power / max(1.0e-6, total_power))) for power in band_power]
    mean = sum(values) / len(values)
    centroid = 0.0 if total_power <= 0.0 else weighted_bin_sum / (total_power * MAX_DFT_BIN)
    return [value - mean for value in values], centroid


def pitch_correlation(frame, total_energy):
    if total_energy <= 0.0:
        return 0.0
    best = 0.0
    for lag in range(32, min(160, len(frame) // 2) + 1, 4):
        corr = 0.0
        for index in range(lag, len(frame)):
            corr += frame[index] * frame[index - lag]
        best = max(best, corr)
    return max(0.0, min(1.0, best / total_energy))


def build_embedding(rows):
    if not rows:
        return [0.0] * (FEATURE_COUNT * 2)
    output = []
    count = len(rows)
    for index in range(FEATURE_COUNT):
        values = [row[index] for row in rows]
        mean = sum(values) / count
        variance = max(0.0, (sum(value * value for value in values) / count) - (mean * mean))
        output.append(mean)
        output.append(math.sqrt(variance))
    return normalize(output)


def normalize(values):
    norm = math.sqrt(sum(value * value for value in values))
    if norm <= 0.0:
        return list(values)
    return [value / norm for value in values]


def cosine(first, second):
    if len(first) != len(second):
        return 0.0
    denom = math.sqrt(sum(value * value for value in first)) * math.sqrt(sum(value * value for value in second))
    if denom <= 0.0:
        return 0.0
    return sum(a * b for a, b in zip(first, second)) / denom


def voice_features(frame):
    energy = 0.0
    low_energy = 0.0
    high_energy = 0.0
    crossings = 0
    low_pass = 0.0
    previous = frame[0]
    for sample in frame:
        energy += sample * sample
        low_pass += 0.12 * (sample - low_pass)
        low = low_pass
        high = sample - low
        low_energy += low * low
        high_energy += high * high
        if (previous >= 0 > sample) or (previous < 0 <= sample):
            crossings += 1
        previous = sample
    return (
        math.sqrt(energy / len(frame)) / 32767.0,
        crossings / len(frame),
        0.0 if energy <= 0.0 else low_energy / energy,
        0.0 if energy <= 0.0 else high_energy / energy,
    )


def window_embedding(feature_window):
    if not feature_window:
        return [0.0] * (FEATURE_COUNT * 2)
    sample_count = min(EMBEDDING_SAMPLE_FRAMES, len(feature_window))
    rows = []
    for index in range(sample_count):
        if sample_count == 1:
            frame_index = len(feature_window) - 1
        else:
            frame_index = round(index * (len(feature_window) - 1) / (sample_count - 1))
        rows.append(feature_window[frame_index])
    return build_embedding(rows)


def flatten_window(window):
    samples = []
    for frame in window:
        samples.extend(frame)
    return tuple(samples)


def clip_windows(samples, limit):
    window = deque(maxlen=SPEAKER_WINDOW_FRAMES)
    feature_window = deque(maxlen=SPEAKER_WINDOW_FRAMES)
    output = []
    for frame in frames(samples):
        window.append(frame)
        feature_window.append(frame_features(frame))
        if len(window) >= SPEAKER_WINDOW_FRAMES:
            window_frames = list(window)
            output.append((flatten_window(window_frames), window_embedding(list(feature_window))))
        if len(output) >= limit:
            break
    return output


def model_features(frame, embedding, instructor_embedding, student_embeddings, instructor_stats):
    rms, zcr, low, high = voice_features(frame)
    instructor_similarity = cosine(embedding, instructor_embedding)
    student_similarity = max((cosine(embedding, item) for item in student_embeddings), default=0.0)
    avg_rms, avg_low, avg_high = instructor_stats
    low_delta = abs(low - avg_low)
    high_delta = abs(high - avg_high)
    return [
        instructor_similarity,
        student_similarity,
        instructor_similarity - student_similarity,
        rms,
        zcr,
        low,
        high,
        0.90,
        rms / avg_rms if avg_rms > 0.0 else 0.0,
        low_delta,
        high_delta,
        1.0 if avg_rms > 0.0 and rms >= avg_rms * 1.45 else 0.0,
        1.0 if low_delta >= 0.22 or high_delta >= 0.22 else 0.0,
        0.0,
        float(len(student_embeddings)),
    ]


def train_softmax(rows, labels, epochs, learning_rate):
    feature_count = len(rows[0])
    class_count = len(LABELS)
    means = [sum(row[i] for row in rows) / len(rows) for i in range(feature_count)]
    scales = []
    normalized = []
    for index in range(feature_count):
        variance = sum((row[index] - means[index]) ** 2 for row in rows) / len(rows)
        scales.append(max(1.0e-6, math.sqrt(variance)))
    for row in rows:
        normalized.append([(row[index] - means[index]) / scales[index] for index in range(feature_count)])
    weights = [[0.0] * feature_count for _ in range(class_count)]
    biases = [0.0] * class_count
    label_index = {label: index for index, label in enumerate(LABELS)}
    counts = {label: labels.count(label) for label in LABELS}
    class_weights = {label: len(labels) / (class_count * max(1, count)) for label, count in counts.items()}
    for _ in range(epochs):
        for row, label in zip(normalized, labels):
            logits = [biases[c] + sum(weights[c][i] * row[i] for i in range(feature_count)) for c in range(class_count)]
            max_logit = max(logits)
            exp_values = [math.exp(value - max_logit) for value in logits]
            total = sum(exp_values)
            probs = [value / total for value in exp_values]
            target = label_index[label]
            sample_weight = class_weights[label]
            for c in range(class_count):
                error = (probs[c] - (1.0 if c == target else 0.0)) * sample_weight
                biases[c] -= learning_rate * error
                for i in range(feature_count):
                    weights[c][i] -= learning_rate * error * row[i]
    return means, scales, weights, biases


def main():
    args = parse_args()
    rng = random.Random(args.seed)
    instructor_clips = [read_audio(path, args.cache) for path in source_audio_files(args.sources / "instructor", "*")]
    student_clips = [read_audio(path, args.cache) for path in source_audio_files(args.sources / "students", "*/*")]
    if not instructor_clips or not student_clips:
        raise RuntimeError("Need instructor and student clips under dataset_sources.")

    instructor_sampled_frames = [frame for samples in instructor_clips for frame in sampled_frames(samples)]
    instructor_rows = [frame_features(frame) for frame in instructor_sampled_frames]
    instructor_voice = [voice_features(frame) for frame in instructor_sampled_frames]
    instructor_embedding = build_embedding(instructor_rows)
    instructor_stats = (
        sum(row[0] for row in instructor_voice) / len(instructor_voice),
        sum(row[2] for row in instructor_voice) / len(instructor_voice),
        sum(row[3] for row in instructor_voice) / len(instructor_voice),
    )
    # Runtime starts without known student clusters, so train the model to survive cold-start
    # speaker decisions instead of depending on unavailable student prototype similarity.
    student_embeddings = []

    instructor_windows = []
    student_windows = []
    per_clip_limit = max(20, args.max_windows_per_role // max(1, len(instructor_clips)))
    for samples in instructor_clips:
        instructor_windows.extend(clip_windows(samples, per_clip_limit))
    per_clip_limit = max(20, args.max_windows_per_role // max(1, len(student_clips)))
    for samples in student_clips:
        student_windows.extend(clip_windows(samples, per_clip_limit))
    rng.shuffle(instructor_windows)
    rng.shuffle(student_windows)
    instructor_windows = instructor_windows[:args.max_windows_per_role]
    student_windows = student_windows[:args.max_windows_per_role]

    rows = []
    labels = []
    for frame, embedding in instructor_windows:
        rows.append(model_features(frame, embedding, instructor_embedding, student_embeddings, instructor_stats))
        labels.append("INSTRUCTOR")
    for frame, embedding in student_windows:
        rows.append(model_features(frame, embedding, instructor_embedding, student_embeddings, instructor_stats))
        labels.append("STUDENT")
    for _ in range(min(args.max_windows_per_role, len(instructor_windows), len(student_windows))):
        instructor_frame, _ = rng.choice(instructor_windows)
        student_frame, _ = rng.choice(student_windows)
        mixed = tuple(max(-32768, min(32767, int((a * 0.72) + (b * 0.72)))) for a, b in zip(instructor_frame, student_frame))
        embedding = build_embedding([frame_features(mixed)])
        rows.append(model_features(mixed, embedding, instructor_embedding, student_embeddings, instructor_stats))
        labels.append("BOTH")

    means, scales, weights, biases = train_softmax(rows, labels, args.epochs, args.learning_rate)
    payload = {
        "version": 1,
        "labels": list(LABELS),
        "featureNames": [
            "instructorSimilarity", "studentSimilarity", "similarityMargin", "rms", "zcr",
            "lowBandRatio", "highBandRatio", "vadConfidence", "rmsRatio", "lowBandDelta",
            "highBandDelta", "overlapEnergyJump", "overlapBandShift", "similarityUnstable",
            "studentClusterCount",
        ],
        "means": means,
        "scales": scales,
        "weights": weights,
        "biases": biases,
        "trainingSamples": len(rows),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"Wrote {args.out} with {len(rows)} samples.")


if __name__ == "__main__":
    main()
