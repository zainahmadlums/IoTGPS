#!/usr/bin/env python3
"""Continuously mirror app audio/metadata files from Android for live pyannote."""

from __future__ import annotations

import argparse
import subprocess
import tarfile
import tempfile
import time
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Pull live app chunks from Android run-as storage.")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--package", default="com.example.audio")
    parser.add_argument("--adb", default="/Users/Raahim/Library/Android/sdk/platform-tools/adb")
    parser.add_argument("--poll-sec", type=float, default=10.0)
    parser.add_argument("--once", action="store_true")
    return parser.parse_args()


def sync_once(args: argparse.Namespace) -> None:
    args.out.mkdir(parents=True, exist_ok=True)
    command = [
        args.adb,
        "exec-out",
        "run-as",
        args.package,
        "tar",
        "-C",
        "files",
        "-cf",
        "-",
        "archived_audio",
        "session_metadata",
    ]
    with tempfile.NamedTemporaryFile(suffix=".tar") as temp_file:
        subprocess.run(command, check=True, stdout=temp_file)
        temp_file.flush()
        temp_file.seek(0)
        with tarfile.open(fileobj=temp_file, mode="r:") as archive:
            archive.extractall(args.out)
    print(f"Synced Android live files to {args.out}")


def main() -> None:
    args = parse_args()
    while True:
        sync_once(args)
        if args.once:
            break
        time.sleep(args.poll_sec)


if __name__ == "__main__":
    main()
