# WAV Save Debug Report

Date: 2026-03-26

Scope:
- Debugged the WAV save and playback path only.
- Did not change the detector model.
- Did not change the active audio source.

## Findings

1. The same PCM frames used by the detector are the frames written to disk.

- In [AudioTrackingService.java](/Users/Raahim/Documents/LUMS/Junior/spring_semester/coursework/CS677/project/IoTGPS/audio_dev/app/src/main/java/com/example/audio/service/AudioTrackingService.java), `onFrame(short[] frame, long timestampMillis)` calls `writeAudioFrame(frame)` and then `audioPipelineCoordinator.process(frame, timestampMillis)`.
- This means the exact callback frame is written before analysis on the same thread.
- Temporary bridge logs were added to print frame identity, sample count, and timestamp for the first few frames.

2. `AudioRecord.read(...)` return values are respected correctly.

- In [AudioRecorderManager.java](/Users/Raahim/Documents/LUMS/Junior/spring_semester/coursework/CS677/project/IoTGPS/audio_dev/app/src/main/java/com/example/audio/audio/AudioRecorderManager.java), `samplesRead` is used to bound the copy loop:
  - `while (chunkOffset < samplesRead)`
  - `copyLength = min(frameBuffer.length - frameOffset, samplesRead - chunkOffset)`
- The code does not treat the whole temporary chunk buffer as valid if `read(...)` returned fewer samples.
- Temporary logs were added for the first few reads to print samples read and effective byte count.

3. Only valid emitted frame data is written, not the entire read buffer.

- The WAV writer does not write `chunkBuffer` directly.
- It writes only the full `frame` emitted by the recorder callback.
- That callback frame is created with `Arrays.copyOf(frameBuffer, frameBuffer.length)`, so the written data is exactly the completed frame passed to the detector.

4. PCM16 conversion is correct.

- In [WavSessionRecorder.java](/Users/Raahim/Documents/LUMS/Junior/spring_semester/coursework/CS677/project/IoTGPS/audio_dev/app/src/main/java/com/example/audio/data/WavSessionRecorder.java), each `short` sample is serialized as:
  - low byte first
  - high byte second
- This is the correct byte layout for PCM16 little-endian WAV data.
- Temporary logs were added to print min/max sample values and a short preview of the first few samples before write.

5. WAV writing is little-endian.

- `WavSessionRecorder.writeHeader(...)` uses `Integer.reverseBytes(...)` and `Short.reverseBytes(...)` for numeric header fields.
- PCM sample bytes are also written little-endian.

6. The WAV header is correct and finalized on stop.

- The file is opened, truncated, and given a placeholder 44-byte header at start.
- On stop, `finish()` rewrites the header using the final `pcmBytesWritten` length, then closes the file.
- Temporary logs were added to print total PCM bytes written, output path, and final file size after finalization.

7. Playback is pointed at the same saved file path.

- In [AudioDetailActivity.java](/Users/Raahim/Documents/LUMS/Junior/spring_semester/coursework/CS677/project/IoTGPS/audio_dev/app/src/main/java/com/example/audio/ui/detail/AudioDetailActivity.java), playback resolves the archived audio file via `AudioLibraryRepository.resolveAudioFile(...)`.
- Temporary logs were added to print:
  - resolved file path
  - existence
  - file size
  - prepared duration from `MediaPlayer`

## Temporary Debug Logs Added

- `AudioRecorderManager`
  - `samplesRead`
  - effective bytes per read
  - frame bytes dispatched to callback

- `AudioTrackingService`
  - callback frame identity, sample count, timestamp

- `WavSessionRecorder`
  - output file path
  - sample rate
  - channel count
  - encoding
  - frame size
  - frame bytes
  - total PCM bytes written
  - min/max sample values
  - preview sample values
  - final file size

- `AudioDetailActivity`
  - playback file path
  - file existence
  - file size
  - prepared duration

## Smallest Fix Applied

- In [AudioDetailActivity.java](/Users/Raahim/Documents/LUMS/Junior/spring_semester/coursework/CS677/project/IoTGPS/audio_dev/app/src/main/java/com/example/audio/ui/detail/AudioDetailActivity.java), playback initialization now explicitly sets:
  - `AudioAttributes.USAGE_MEDIA`
  - `AudioAttributes.CONTENT_TYPE_SPEECH`
  - full player volume `1.0f, 1.0f`

This is a playback-path-only change. No detector logic, recorder source selection, or model behavior was changed.

## Conclusion

From code inspection, the save path is structurally correct:
- callback frames are written directly
- `read(...)` bounds are respected
- PCM conversion is correct
- WAV endianness is correct
- the header is finalized on stop

The added logs are there to verify this on-device with one newly recorded session and to confirm that playback is opening the same finalized file.
