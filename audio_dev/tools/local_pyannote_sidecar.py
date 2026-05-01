#!/usr/bin/env python3
"""Local-only pyannote sidecar for live chunk diarization.

Runtime contract:
- no remote APIs
- no Hugging Face login
- models must already exist on disk
- Java/Android app talks to localhost
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import tempfile
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


GLOBAL_INSTRUCTOR = "GLOBAL_INSTRUCTOR"
GLOBAL_PREFIX = "GLOBAL_SPEAKER_"
ROLE_UNKNOWN = "UNKNOWN"
DEFAULT_INSTRUCTOR_THRESHOLD = 0.68
DEFAULT_SPEAKER_THRESHOLD = 0.62
DEFAULT_OVERLAP_THRESHOLD_MS = 600
MIN_SEGMENT_MS = 250


def cosine(a: list[float], b: list[float]) -> float:
    numerator = sum(x * y for x, y in zip(a, b))
    a_norm = math.sqrt(sum(x * x for x in a))
    b_norm = math.sqrt(sum(y * y for y in b))
    return numerator / (a_norm * b_norm) if a_norm and b_norm else 0.0


def flatten_embedding(value) -> list[float]:
    import numpy as np
    return np.asarray(value, dtype=float).reshape(-1).tolist()


@dataclass
class GlobalSpeaker:
    label: str
    centroid: list[float]
    update_count: int = 1

    def update(self, embedding: list[float], weight: float = 1.0) -> None:
        total = self.update_count + weight
        self.centroid = [
            ((old * self.update_count) + (new * weight)) / total
            for old, new in zip(self.centroid, embedding)
        ]
        self.update_count += 1


@dataclass
class ChunkPrediction:
    chunk_index: int
    chunk_start_ms: int
    chunk_end_ms: int
    segments: list[dict]


@dataclass
class SessionState:
    session_id: str
    audio_duration_ms: int = 0
    chunk_duration_ms: int = 40_000
    overlap_ms: int = 5_000
    instructor_embedding: list[float] | None = None
    global_speakers: dict[str, GlobalSpeaker] = field(default_factory=dict)
    chunk_predictions: list[ChunkPrediction] = field(default_factory=list)
    raw_to_global: dict[str, str] = field(default_factory=dict)
    next_speaker_index: int = 1

    def create_global_speaker(self, embedding: list[float]) -> str:
        label = f"{GLOBAL_PREFIX}{self.next_speaker_index:02d}"
        self.next_speaker_index += 1
        self.global_speakers[label] = GlobalSpeaker(label, embedding)
        return label


class LocalPyannoteSidecar:

    def __init__(
            self,
            diarization_model: Path,
            embedding_model: Path,
            work_dir: Path,
            device: str | None,
            instructor_threshold: float,
            speaker_threshold: float,
    ) -> None:
        self.diarization_model = diarization_model
        self.embedding_model = embedding_model
        self.work_dir = work_dir
        self.device = device
        self.instructor_threshold = instructor_threshold
        self.speaker_threshold = speaker_threshold
        self.sessions: dict[str, SessionState] = {}
        self.pipeline = None
        self.embedding_inference = None

    def load(self) -> None:
        if not self.diarization_model.exists():
            raise FileNotFoundError(f"Missing local diarization model: {self.diarization_model}")
        if not self.embedding_model.exists():
            raise FileNotFoundError(f"Missing local embedding model: {self.embedding_model}")
        from pyannote.audio import Inference, Model, Pipeline
        self.pipeline = Pipeline.from_pretrained(str(self.diarization_model))
        embedding_model = Model.from_pretrained(str(self.embedding_model))
        if self.device:
            import torch
            try:
                self.pipeline.to(torch.device(self.device))
                embedding_model.to(torch.device(self.device))
            except Exception:
                if self.device != "cpu":
                    self.pipeline.to(torch.device("cpu"))
                    embedding_model.to(torch.device("cpu"))
                else:
                    raise
        self.embedding_inference = Inference(embedding_model, window="whole")

    def start_session(self, payload: dict) -> dict:
        session_id = payload["session_id"]
        state = SessionState(
            session_id=session_id,
            chunk_duration_ms=int(payload.get("chunk_duration_ms", 40_000)),
            overlap_ms=int(payload.get("overlap_ms", 5_000)),
        )
        instructor_audio = payload.get("instructor_audio_path")
        if instructor_audio:
            state.instructor_embedding = self.load_or_create_instructor_embedding(session_id, Path(instructor_audio))
            state.global_speakers[GLOBAL_INSTRUCTOR] = GlobalSpeaker(
                GLOBAL_INSTRUCTOR,
                state.instructor_embedding,
                update_count=1000,
            )
        self.sessions[session_id] = state
        return {"ok": True, "session_id": session_id, "privacy_mode": "local_only"}

    def process_chunk(self, payload: dict, audio_bytes: bytes | None = None) -> dict:
        session_id = payload["session_id"]
        state = self.sessions.setdefault(session_id, SessionState(session_id=session_id))
        audio_path = self.resolve_audio_path(payload, audio_bytes)
        chunk_index = int(payload["chunk_index"])
        chunk_start_ms = int(payload["chunk_start_ms"])
        chunk_end_ms = int(payload["chunk_end_ms"])
        vad_segments = payload.get("vad_speech_segments", [])

        diarization = self.pipeline(str(audio_path))
        raw_segments = self.diarization_segments(diarization, chunk_start_ms)
        if vad_segments:
            raw_segments = self.filter_by_vad(raw_segments, vad_segments, chunk_start_ms)

        speaker_embeddings = self.speaker_embeddings(audio_path, raw_segments, chunk_start_ms)
        raw_mapping = self.map_chunk_speakers(state, chunk_index, raw_segments, speaker_embeddings)
        stable_segments = []
        for segment in raw_segments:
            global_label = raw_mapping.get(segment["raw_speaker"])
            if not global_label:
                continue
            stable_segments.append({
                "start_ms": segment["start_ms"],
                "end_ms": segment["end_ms"],
                "speaker": global_label,
                "source_chunk_index": chunk_index,
                "confidence": segment.get("confidence"),
                "processing_stage": "live_chunk",
            })

        prediction = ChunkPrediction(chunk_index, chunk_start_ms, chunk_end_ms, stable_segments)
        state.chunk_predictions = [
            item for item in state.chunk_predictions if item.chunk_index != chunk_index
        ]
        state.chunk_predictions.append(prediction)
        state.audio_duration_ms = max(state.audio_duration_ms, chunk_end_ms)
        self.write_session_state(state)
        return {
            "ok": True,
            "session_id": session_id,
            "chunk_index": chunk_index,
            "privacy_mode": "local_only",
            "segments": stable_segments,
        }

    def finish_session(self, payload: dict) -> dict:
        session_id = payload["session_id"]
        state = self.sessions[session_id]
        if "audio_duration_ms" in payload:
            state.audio_duration_ms = int(payload["audio_duration_ms"])
        final_json = self.final_session_json(state)
        output_path = self.session_dir(session_id) / "final_diarization.json"
        output_path.write_text(json.dumps(final_json, indent=2), encoding="utf-8")
        return {"ok": True, "session_id": session_id, "output_path": str(output_path), **final_json}

    def load_or_create_instructor_embedding(self, session_id: str, audio_path: Path) -> list[float]:
        cache_path = self.session_dir(session_id) / "instructor_anchor_embedding.json"
        key = self.file_key(audio_path)
        if cache_path.exists():
            payload = json.loads(cache_path.read_text(encoding="utf-8"))
            if payload.get("source") == key:
                return [float(item) for item in payload["embedding"]]
        embedding = flatten_embedding(self.embedding_inference(str(audio_path)))
        cache_path.write_text(json.dumps({"source": key, "embedding": embedding}), encoding="utf-8")
        return embedding

    def map_chunk_speakers(
            self,
            state: SessionState,
            chunk_index: int,
            segments: list[dict],
            speaker_embeddings: dict[str, list[float]],
    ) -> dict[str, str]:
        mapping = {}
        overlap_votes = self.overlap_votes(state, chunk_index, segments)
        for raw_speaker, embedding in speaker_embeddings.items():
            instructor_score = (
                cosine(embedding, state.instructor_embedding)
                if state.instructor_embedding is not None
                else -1.0
            )
            if instructor_score >= self.instructor_threshold:
                mapping[raw_speaker] = GLOBAL_INSTRUCTOR
                continue

            overlap_label, overlap_ms = overlap_votes.get(raw_speaker, (None, 0))
            centroid_label, centroid_score = self.best_centroid_match(state, embedding)
            if overlap_label and overlap_ms >= DEFAULT_OVERLAP_THRESHOLD_MS:
                mapping[raw_speaker] = overlap_label
            elif centroid_label and centroid_score >= self.speaker_threshold:
                mapping[raw_speaker] = centroid_label
            elif centroid_label and centroid_score >= self.speaker_threshold - 0.05:
                mapping[raw_speaker] = centroid_label
            else:
                mapping[raw_speaker] = state.create_global_speaker(embedding)

            label = mapping[raw_speaker]
            if label != GLOBAL_INSTRUCTOR and label in state.global_speakers:
                state.global_speakers[label].update(embedding)
        return mapping

    def overlap_votes(self, state: SessionState, chunk_index: int, segments: list[dict]) -> dict[str, tuple[str, int]]:
        previous = [item for item in state.chunk_predictions if item.chunk_index == chunk_index - 1]
        if not previous:
            return {}
        previous_segments = previous[0].segments
        votes: dict[str, dict[str, int]] = {}
        for current in segments:
            for old in previous_segments:
                overlap = max(0, min(current["end_ms"], old["end_ms"]) - max(current["start_ms"], old["start_ms"]))
                if overlap <= 0:
                    continue
                votes.setdefault(current["raw_speaker"], {})
                votes[current["raw_speaker"]][old["speaker"]] = votes[current["raw_speaker"]].get(old["speaker"], 0) + overlap
        result = {}
        for raw_speaker, labels in votes.items():
            result[raw_speaker] = max(labels.items(), key=lambda item: item[1])
        return result

    def best_centroid_match(self, state: SessionState, embedding: list[float]) -> tuple[str | None, float]:
        best_label = None
        best_score = -1.0
        for label, speaker in state.global_speakers.items():
            if label == GLOBAL_INSTRUCTOR:
                continue
            score = cosine(embedding, speaker.centroid)
            if score > best_score:
                best_label = label
                best_score = score
        return best_label, best_score

    def speaker_embeddings(self, audio_path: Path, segments: list[dict], chunk_start_ms: int) -> dict[str, list[float]]:
        grouped: dict[str, list[list[float]]] = {}
        for segment in segments:
            if segment["end_ms"] - segment["start_ms"] < 500:
                continue
            embedding = self.segment_embedding(audio_path, segment["start_ms"], segment["end_ms"], chunk_start_ms)
            if embedding:
                grouped.setdefault(segment["raw_speaker"], []).append(embedding)
        return {
            speaker: [sum(values) / len(values) for values in zip(*embeddings)]
            for speaker, embeddings in grouped.items()
        }

    def segment_embedding(self, audio_path: Path, start_ms: int, end_ms: int, chunk_start_ms: int) -> list[float] | None:
        from pyannote.core import Segment
        local_start = max(0.0, (start_ms - chunk_start_ms) / 1000.0)
        local_end = max(local_start, (end_ms - chunk_start_ms) / 1000.0)
        try:
            return flatten_embedding(self.embedding_inference.crop(str(audio_path), Segment(local_start, local_end)))
        except Exception:
            return None

    def diarization_segments(self, diarization, chunk_start_ms: int) -> list[dict]:
        segments = []
        for turn, _, speaker in diarization.itertracks(yield_label=True):
            start_ms = chunk_start_ms + int(round(float(turn.start) * 1000.0))
            end_ms = chunk_start_ms + int(round(float(turn.end) * 1000.0))
            if end_ms - start_ms >= MIN_SEGMENT_MS:
                segments.append({
                    "start_ms": start_ms,
                    "end_ms": end_ms,
                    "raw_speaker": str(speaker),
                    "confidence": None,
                })
        return sorted(segments, key=lambda item: (item["start_ms"], item["end_ms"], item["raw_speaker"]))

    def filter_by_vad(self, segments: list[dict], vad_segments: list[dict], chunk_start_ms: int) -> list[dict]:
        filtered = []
        for segment in segments:
            for vad in vad_segments:
                vad_start = chunk_start_ms + int(vad.get("start_ms", vad.get("startOffsetMillis", 0)))
                vad_end = chunk_start_ms + int(vad.get("end_ms", vad.get("endOffsetMillis", 0)))
                start_ms = max(segment["start_ms"], vad_start)
                end_ms = min(segment["end_ms"], vad_end)
                if end_ms - start_ms >= MIN_SEGMENT_MS:
                    clipped = dict(segment)
                    clipped["start_ms"] = start_ms
                    clipped["end_ms"] = end_ms
                    filtered.append(clipped)
        return filtered

    def final_session_json(self, state: SessionState) -> dict:
        segments = []
        for prediction in sorted(state.chunk_predictions, key=lambda item: item.chunk_index):
            keep_start = prediction.chunk_start_ms if prediction.chunk_index == 0 else prediction.chunk_start_ms + state.overlap_ms
            keep_end = prediction.chunk_end_ms
            for segment in prediction.segments:
                start_ms = max(keep_start, int(segment["start_ms"]))
                end_ms = min(keep_end, int(segment["end_ms"]))
                if end_ms - start_ms < MIN_SEGMENT_MS:
                    continue
                cleaned = dict(segment)
                cleaned["start_ms"] = start_ms
                cleaned["end_ms"] = end_ms
                cleaned["processing_stage"] = "final_cleanup"
                segments.append(cleaned)
        return {
            "session_id": state.session_id,
            "audio_duration_ms": state.audio_duration_ms,
            "chunk_duration_ms": state.chunk_duration_ms,
            "overlap_ms": state.overlap_ms,
            "privacy_mode": "local_only",
            "speakers": sorted({segment["speaker"] for segment in segments}),
            "segments": self.smooth_segments(sorted(segments, key=lambda item: (item["start_ms"], item["end_ms"]))),
        }

    def smooth_segments(self, segments: list[dict]) -> list[dict]:
        if not segments:
            return []
        merged = []
        for segment in segments:
            if merged and merged[-1]["speaker"] == segment["speaker"] and segment["start_ms"] <= merged[-1]["end_ms"] + 200:
                merged[-1]["end_ms"] = max(merged[-1]["end_ms"], segment["end_ms"])
            else:
                merged.append(segment)
        return [segment for segment in merged if segment["end_ms"] - segment["start_ms"] >= MIN_SEGMENT_MS]

    def resolve_audio_path(self, payload: dict, audio_bytes: bytes | None) -> Path:
        if "audio_path" in payload:
            return Path(payload["audio_path"])
        if audio_bytes is None:
            raise ValueError("Either audio_path or audio bytes are required.")
        session_id = payload["session_id"]
        chunk_index = int(payload["chunk_index"])
        path = self.session_dir(session_id) / f"chunk_{chunk_index:04d}.wav"
        path.write_bytes(audio_bytes)
        return path

    def write_session_state(self, state: SessionState) -> None:
        payload = self.final_session_json(state)
        payload["processing_stage"] = "live_partial"
        (self.session_dir(state.session_id) / "partial_diarization.json").write_text(
            json.dumps(payload, indent=2),
            encoding="utf-8",
        )

    def session_dir(self, session_id: str) -> Path:
        path = self.work_dir / session_id
        path.mkdir(parents=True, exist_ok=True)
        return path

    @staticmethod
    def file_key(path: Path) -> dict:
        stat = path.stat()
        return {"path": str(path.resolve()), "size": stat.st_size, "mtime_ns": stat.st_mtime_ns}


class SidecarHandler(BaseHTTPRequestHandler):
    sidecar: LocalPyannoteSidecar

    def do_GET(self) -> None:
        if urlparse(self.path).path == "/health":
            self.write_json({"ok": True, "privacy_mode": "local_only"})
            return
        self.send_error(404)

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        try:
            payload = self.read_json()
            if path == "/session/start":
                self.write_json(self.sidecar.start_session(payload))
            elif path == "/chunk":
                self.write_json(self.sidecar.process_chunk(payload))
            elif path == "/session/end":
                self.write_json(self.sidecar.finish_session(payload))
            else:
                self.send_error(404)
        except Exception as exception:
            self.write_json({"ok": False, "error": str(exception)}, status=500)

    def read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def write_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args) -> None:
        print(f"{self.address_string()} {format % args}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run local-only pyannote localhost sidecar.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--diarization-model", type=Path, required=True)
    parser.add_argument("--embedding-model", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, default=Path("live_sidecar_state"))
    parser.add_argument("--device", default="mps")
    parser.add_argument("--instructor-threshold", type=float, default=DEFAULT_INSTRUCTOR_THRESHOLD)
    parser.add_argument("--speaker-threshold", type=float, default=DEFAULT_SPEAKER_THRESHOLD)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    sidecar = LocalPyannoteSidecar(
        args.diarization_model,
        args.embedding_model,
        args.work_dir,
        args.device,
        args.instructor_threshold,
        args.speaker_threshold,
    )
    sidecar.load()
    SidecarHandler.sidecar = sidecar
    server = ThreadingHTTPServer((args.host, args.port), SidecarHandler)
    print(f"Local pyannote sidecar listening on http://{args.host}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
