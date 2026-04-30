#!/usr/bin/env python3
"""Score predicted role intervals against generated truth JSON."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


ROLES = ("SILENCE", "INSTRUCTOR", "STUDENT", "BOTH")
ROLE_SET = set(ROLES)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Score role interval predictions against truth files.")
    parser.add_argument("--truth", type=Path, default=Path("generated_dataset/truth"))
    parser.add_argument("--pred", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("generated_dataset/metrics.json"))
    parser.add_argument("--bin-ms", type=int, default=100)
    parser.add_argument("--allow-missing", action="store_true")
    return parser.parse_args()


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def labels_from_intervals(payload: dict, duration_ms: int, bin_ms: int) -> list[str]:
    bin_count = max(1, math.ceil(duration_ms / bin_ms))
    labels = ["SILENCE"] * bin_count
    for interval in payload.get("roleIntervals", []):
        role = interval.get("role", "SILENCE")
        if role not in ROLE_SET:
            role = "SILENCE"
        start_ms = max(0, int(interval.get("startOffsetMillis", 0)))
        end_ms = max(start_ms, min(duration_ms, int(interval.get("endOffsetMillis", start_ms))))
        start_bin = max(0, start_ms // bin_ms)
        end_bin = min(bin_count, math.ceil(end_ms / bin_ms))
        for index in range(start_bin, end_bin):
            labels[index] = role
    return labels


def safe_divide(numerator: float, denominator: float) -> float:
    return numerator / denominator if denominator else 0.0


def compute_metrics(confusion: dict[str, dict[str, int]]) -> dict:
    total = sum(sum(row.values()) for row in confusion.values())
    correct = sum(confusion[role][role] for role in ROLES)
    per_role = {}
    for role in ROLES:
        tp = confusion[role][role]
        fp = sum(confusion[truth][role] for truth in ROLES if truth != role)
        fn = sum(confusion[role][pred] for pred in ROLES if pred != role)
        precision = safe_divide(tp, tp + fp)
        recall = safe_divide(tp, tp + fn)
        f1 = safe_divide(2 * precision * recall, precision + recall)
        per_role[role] = {
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "supportBins": sum(confusion[role].values()),
        }
    return {
        "accuracy": safe_divide(correct, total),
        "totalBins": total,
        "correctBins": correct,
        "roles": per_role,
        "confusion": confusion,
    }


def main() -> None:
    args = parse_args()
    if args.bin_ms <= 0:
        raise ValueError("--bin-ms must be positive.")

    truth_paths = sorted(args.truth.glob("*.json"))
    if not truth_paths:
        raise ValueError(f"No truth JSON files found in {args.truth}.")

    confusion = {truth: {pred: 0 for pred in ROLES} for truth in ROLES}
    scored_files = 0
    missing_files = []

    for truth_path in truth_paths:
        pred_path = args.pred / truth_path.name
        if not pred_path.exists():
            missing_files.append(pred_path.as_posix())
            if args.allow_missing:
                continue
            raise FileNotFoundError(f"Missing prediction file: {pred_path}")

        truth_payload = load_json(truth_path)
        pred_payload = load_json(pred_path)
        duration_ms = int(truth_payload.get("durationMillis", pred_payload.get("durationMillis", 0)))
        truth_labels = labels_from_intervals(truth_payload, duration_ms, args.bin_ms)
        pred_labels = labels_from_intervals(pred_payload, duration_ms, args.bin_ms)
        if len(pred_labels) < len(truth_labels):
            pred_labels.extend(["SILENCE"] * (len(truth_labels) - len(pred_labels)))

        for truth_label, pred_label in zip(truth_labels, pred_labels):
            confusion[truth_label][pred_label] += 1
        scored_files += 1

    metrics = compute_metrics(confusion)
    metrics["scoredFiles"] = scored_files
    metrics["missingFiles"] = missing_files
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(metrics, indent=2), encoding="utf-8")

    print(f"Scored files: {scored_files}")
    print(f"Accuracy: {metrics['accuracy']:.4f}")
    for role in ROLES:
        role_metrics = metrics["roles"][role]
        print(
            f"{role}: precision={role_metrics['precision']:.4f} "
            f"recall={role_metrics['recall']:.4f} f1={role_metrics['f1']:.4f} "
            f"supportBins={role_metrics['supportBins']}"
        )
    print(f"Wrote metrics to {args.out}.")


if __name__ == "__main__":
    main()
