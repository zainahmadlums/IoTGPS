# Dataset Source Clips

Put short source recordings here before generating a synthetic evaluation set.

Accepted input formats:

- WAV
- M4A/AAC
- MP3
- CAF/AIFF
- FLAC
- MP4 audio

The generator automatically normalizes supported input clips to the app format:

- 16 kHz
- mono
- 16-bit PCM WAV

Normalized copies are cached under `generated_dataset/normalized_sources/`. Your original clips are not modified.

Folder layout:

- `instructor/`: instructor enrollment/source clips.
- `students/<student_id>/`: student clips, grouped by speaker folder.
- `noise/background/`: optional background noise clips.
- `noise/pocket_rub/`: optional pocket rubbing/handling noise clips.

The generator treats instructor and student clips as labeled speech events. Noise clips are mixed into the audio but do not create speech labels.
