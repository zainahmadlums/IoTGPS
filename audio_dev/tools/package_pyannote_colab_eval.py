#!/usr/bin/env python3
"""Package Android-conditioned pyannote evaluation inputs for Colab.

The zip layout intentionally mirrors generated_dataset/ so the same notebook
command can score either the 100-file smoke set or a larger generated set.
"""

from __future__ import annotations

import argparse
import shutil
import zipfile
from pathlib import Path


REQUIRED_TOOL_FILES = (
    "run_pyannote_baseline.py",
    "score_dataset.py",
    "pyannote_requirements.txt",
    "PYANNOTE_BASELINE.md",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build a Colab pyannote eval zip.")
    parser.add_argument(
        "--android-root",
        type=Path,
        required=True,
        help="Directory containing filtered_audio/ and vad/ exported from Android.",
    )
    parser.add_argument("--truth", type=Path, required=True, help="Truth JSON directory.")
    parser.add_argument("--out", type=Path, required=True, help="Output zip path.")
    parser.add_argument(
        "--workdir",
        type=Path,
        default=Path("build/pyannote_colab_package"),
        help="Temporary staging directory.",
    )
    return parser.parse_args()


def copy_tree_contents(source: Path, destination: Path, suffix: str) -> int:
    if not source.exists():
        raise FileNotFoundError(f"Missing source directory: {source}")
    destination.mkdir(parents=True, exist_ok=True)
    count = 0
    for path in sorted(source.glob(f"*{suffix}")):
        if path.name.startswith("."):
            continue
        shutil.copy2(path, destination / path.name)
        count += 1
    if count == 0:
        raise ValueError(f"No {suffix} files found in {source}")
    return count


def zip_directory(source: Path, output_zip: Path) -> None:
    output_zip.parent.mkdir(parents=True, exist_ok=True)
    if output_zip.exists():
        output_zip.unlink()
    with zipfile.ZipFile(output_zip, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_dir() or path.name.startswith("."):
                continue
            archive.write(path, path.relative_to(source))


def main() -> None:
    args = parse_args()
    android_root = args.android_root
    filtered_audio_dir = android_root / "filtered_audio"
    vad_dir = android_root / "vad"

    if args.workdir.exists():
        shutil.rmtree(args.workdir)
    package_root = args.workdir / "colab_pyannote_eval"
    dataset_root = package_root / "generated_dataset"
    tools_root = package_root / "tools"

    audio_count = copy_tree_contents(filtered_audio_dir, dataset_root / "audio", ".wav")
    vad_count = copy_tree_contents(vad_dir, dataset_root / "vad", ".json")
    truth_count = copy_tree_contents(args.truth, dataset_root / "truth", ".json")
    if not (audio_count == vad_count == truth_count):
        raise ValueError(
            "Mismatched package counts: "
            f"audio={audio_count}, vad={vad_count}, truth={truth_count}"
        )

    tools_root.mkdir(parents=True, exist_ok=True)
    for file_name in REQUIRED_TOOL_FILES:
        shutil.copy2(Path("tools") / file_name, tools_root / file_name)
    shutil.copy2(Path("tools/iot_pyannote.ipynb"), package_root / "iot_pyannote.ipynb")

    zip_directory(package_root, args.out)
    print(f"Packaged {audio_count} Android-conditioned items into {args.out}")


if __name__ == "__main__":
    main()
