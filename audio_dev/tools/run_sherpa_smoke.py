#!/usr/bin/env python3
import json
import os
import wave
import argparse
from pathlib import Path
import numpy as np

try:
    import onnxruntime as ort
    import librosa
except ImportError:
    print("Please install onnxruntime and librosa: pip install onnxruntime librosa")
    exit(1)

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=Path("generated_dataset_100/results/sherpa_smoke_10"))
    parser.add_argument("--embedding-model", type=Path, default=Path("app/src/main/assets/sherpa-onnx/embedding.onnx"))
    parser.add_argument("--instructor-profile", type=Path, default=Path("generated_dataset_100/results/sherpa_smoke_10/profile/instructor_profile.wav"))
    parser.add_argument("--out", type=Path, default=Path("generated_dataset_100/results/sherpa_smoke_10/predictions"))
    return parser.parse_args()

def load_wav(path):
    # Librosa handles resampling if needed, ensuring 16kHz for Sherpa
    samples, sr = librosa.load(str(path), sr=16000)
    return samples, sr

def compute_features(samples, sr=16000):
    # Sherpa-ONNX uses 80-bin Fbank features. 
    # Wespeaker/CAM++ typically use 25ms window, 10ms shift.
    fbank = librosa.feature.melspectrogram(
        y=samples, sr=sr, n_fft=400, hop_length=160, n_mels=80, 
        window='hamming', center=False, power=2.0
    )
    # Convert to log-power and normalize
    log_fbank = librosa.power_to_db(fbank, ref=np.max)
    # Swap axes to [Time, Feat]
    return log_fbank.T.astype(np.float32)

def get_embedding(sess, samples, sr=16000):
    features = compute_features(samples, sr)
    # Add Batch dimension: [1, T, 80]
    input_name = sess.get_inputs()[0].name
    ort_inputs = {input_name: features[None, :, :]}
    ort_outs = sess.run(None, ort_inputs)
    return ort_outs[0][0]

def cosine_similarity(a, b):
    dot = np.dot(a, b)
    norm = np.linalg.norm(a) * np.linalg.norm(b)
    return dot / norm if norm > 0 else 0.0

def main():
    args = parse_args()
    sess = ort.InferenceSession(str(args.embedding_model))
    
    # 1. Get Instructor Embedding
    print(f"Loading instructor profile: {args.instructor_profile}")
    instr_samples, sr = load_wav(args.instructor_profile)
    # Use middle 10 seconds for profile if long
    if len(instr_samples) > 10 * sr:
        mid = len(instr_samples) // 2
        instr_samples = instr_samples[mid - 5*sr : mid + 5*sr]
    instr_emb = get_embedding(sess, instr_samples, sr)
    
    audio_dir = args.dataset / "filtered_audio"
    vad_dir = args.dataset / "vad"
    
    for wav_path in sorted(audio_dir.glob("*.wav")):
        print(f"Processing {wav_path.name}...")
        samples, sr = load_wav(wav_path)
        vad_path = vad_dir / (wav_path.stem + ".json")
        with open(vad_path) as f:
            vad_data = json.load(f)
        
        role_intervals = []
        for interval in vad_data.get("speechIntervals", []):
            start_ms = interval["startOffsetMillis"]
            end_ms = interval["endOffsetMillis"]
            
            start_idx = int(start_ms * sr / 1000)
            end_idx = int(end_ms * sr / 1000)
            seg_samples = samples[start_idx:end_idx]
            
            # Minimum 0.5s for embedding
            if len(seg_samples) < 0.5 * sr:
                role = "STUDENT"
            else:
                try:
                    seg_emb = get_embedding(sess, seg_samples, sr)
                    sim = cosine_similarity(seg_emb, instr_emb)
                    # Use a slightly more conservative threshold for the simulated batch run
                    role = "INSTRUCTOR" if sim > 0.975 else "STUDENT"
                except Exception as e:
                    print(f"  Error processing segment: {e}")
                    role = "STUDENT"
            
            role_intervals.append({
                "role": role,
                "startOffsetMillis": start_ms,
                "endOffsetMillis": end_ms
            })
            
        out_data = {
            "id": wav_path.stem,
            "durationMillis": vad_data["durationMillis"],
            "roleIntervals": role_intervals
        }
        with open(args.out / (wav_path.stem + ".json"), "w") as f:
            json.dump(out_data, f, indent=2)

    print("\nScoring results...")
    import subprocess
    subprocess.run([
        "python3", "tools/score_dataset.py",
        "--truth", str(args.dataset / "truth"),
        "--pred", str(args.out),
        "--out", str(args.dataset.parent / "sherpa_smoke_metrics.json")
    ])

if __name__ == "__main__":
    main()
