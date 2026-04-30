# Generated Dataset Output

`tools/generate_synthetic_dataset.py` writes generated files here by default.

Expected output layout:

- `audio/`: generated one-minute WAV files.
- `truth/`: ground-truth role interval JSON files.
- `manifest.jsonl`: one JSON object per generated item.

Generated files are ignored by git. Keep only this README and `.gitignore` committed.
