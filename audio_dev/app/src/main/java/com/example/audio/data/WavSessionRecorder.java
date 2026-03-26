//package com.example.audio.data;
//
//import com.example.audio.audio.AudioConfig;
//import com.example.audio.util.Logger;
//
//import java.io.File;
//import java.io.IOException;
//import java.io.RandomAccessFile;
//import java.util.Arrays;
//
//public final class WavSessionRecorder {
//
//    private static final String TAG = "WavSessionRecorder";
//    private static final int WAV_HEADER_SIZE = 44;
//    private static final int DEBUG_FRAME_LOG_LIMIT = 6;
//
//    private final File outputFile;
//    private final AudioConfig audioConfig;
//    private final RandomAccessFile randomAccessFile;
//    private long pcmBytesWritten;
//    private int debugFrameLogs;
//
//    public WavSessionRecorder(File outputFile, AudioConfig audioConfig) throws IOException {
//        this.outputFile = outputFile;
//        this.audioConfig = audioConfig;
//        this.randomAccessFile = new RandomAccessFile(outputFile, "rw");
//        randomAccessFile.setLength(0L);
//        writeHeader(0L);
//        Logger.d(
//                TAG,
//                "Opened WAV output path="
//                        + outputFile.getAbsolutePath()
//                        + ", sampleRateHz="
//                        + audioConfig.getSampleRateHz()
//                        + ", channelCount="
//                        + audioConfig.getChannelCount()
//                        + ", encoding="
//                        + audioConfig.getAudioEncoding()
//                        + ", frameSizeSamples="
//                        + audioConfig.getFrameSizeSamples()
//                        + ", frameSizeBytes="
//                        + audioConfig.getFrameSizeBytes()
//        );
//    }
//
//    public synchronized void writeFrame(short[] frame) throws IOException {
//        byte[] frameBytes = new byte[frame.length * 2];
//        int byteIndex = 0;
//        short minSample = Short.MAX_VALUE;
//        short maxSample = Short.MIN_VALUE;
//        for (short sample : frame) {
//            if (sample < minSample) {
//                minSample = sample;
//            }
//            if (sample > maxSample) {
//                maxSample = sample;
//            }
//            frameBytes[byteIndex++] = (byte) (sample & 0xFF);
//            frameBytes[byteIndex++] = (byte) ((sample >> 8) & 0xFF);
//        }
//        randomAccessFile.seek(WAV_HEADER_SIZE + pcmBytesWritten);
//        randomAccessFile.write(frameBytes);
//        pcmBytesWritten += frameBytes.length;
//        if (debugFrameLogs < DEBUG_FRAME_LOG_LIMIT) {
//            debugFrameLogs++;
//            int previewLength = Math.min(8, frame.length);
//            short[] previewSamples = Arrays.copyOf(frame, previewLength);
//            Logger.d(
//                    TAG,
//                    "write frameBytes="
//                            + frameBytes.length
//                            + ", totalPcmBytesWritten="
//                            + pcmBytesWritten
//                            + ", minSample="
//                            + minSample
//                            + ", maxSample="
//                            + maxSample
//                            + ", previewSamples="
//                            + Arrays.toString(previewSamples)
//            );
//        }
//    }
//
//    public synchronized long finish() throws IOException {
//        writeHeader(pcmBytesWritten);
//        randomAccessFile.close();
//        long finalFileSize = outputFile.length();
//        Logger.d(
//                TAG,
//                "Finalized WAV path="
//                        + outputFile.getAbsolutePath()
//                        + ", totalPcmBytesWritten="
//                        + pcmBytesWritten
//                        + ", finalFileSize="
//                        + finalFileSize
//        );
//        return finalFileSize;
//    }
//
//    public synchronized void abort() {
//        try {
//            randomAccessFile.close();
//        } catch (IOException ignored) {
//        }
//        if (outputFile.exists()) {
//            outputFile.delete();
//        }
//    }
//
//    public File getOutputFile() {
//        return outputFile;
//    }
//
//    private void writeHeader(long audioDataBytes) throws IOException {
//        randomAccessFile.seek(0L);
//        randomAccessFile.writeBytes("RIFF");
//        randomAccessFile.writeInt(Integer.reverseBytes((int) (36L + audioDataBytes)));
//        randomAccessFile.writeBytes("WAVE");
//        randomAccessFile.writeBytes("fmt ");
//        randomAccessFile.writeInt(Integer.reverseBytes(16));
//        randomAccessFile.writeShort(Short.reverseBytes((short) 1));
//        randomAccessFile.writeShort(Short.reverseBytes((short) audioConfig.getChannelCount()));
//        randomAccessFile.writeInt(Integer.reverseBytes(audioConfig.getSampleRateHz()));
//        int byteRate = audioConfig.getSampleRateHz()
//                * audioConfig.getChannelCount()
//                * audioConfig.getBytesPerSample();
//        randomAccessFile.writeInt(Integer.reverseBytes(byteRate));
//        short blockAlign = (short) (audioConfig.getChannelCount() * audioConfig.getBytesPerSample());
//        randomAccessFile.writeShort(Short.reverseBytes(blockAlign));
//        randomAccessFile.writeShort(Short.reverseBytes((short) (audioConfig.getBytesPerSample() * 8)));
//        randomAccessFile.writeBytes("data");
//        randomAccessFile.writeInt(Integer.reverseBytes((int) audioDataBytes));
//    }
//}

package com.example.audio.data;

import com.example.audio.audio.AudioConfig;
import com.example.audio.util.Logger;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

public final class WavSessionRecorder {

    private static final String TAG = "WavSessionRecorder";
    private static final int WAV_HEADER_SIZE = 44;
    private static final int DEBUG_FRAME_LOG_LIMIT = 6;

    // ADDED: Gain multiplier to boost the heavily AGC'd VOICE_RECOGNITION signal
    // 15.0f to 20.0f is a safe range based on your previous max amplitude logs.
    private static final float EXPORT_GAIN_MULTIPLIER = 15.0f;

    private final File outputFile;
    private final AudioConfig audioConfig;
    private final RandomAccessFile randomAccessFile;
    private long pcmBytesWritten;
    private int debugFrameLogs;

    public WavSessionRecorder(File outputFile, AudioConfig audioConfig) throws IOException {
        this.outputFile = outputFile;
        this.audioConfig = audioConfig;
        this.randomAccessFile = new RandomAccessFile(outputFile, "rw");
        randomAccessFile.setLength(0L);
        writeHeader(0L);
        Logger.d(
                TAG,
                "Opened WAV output path="
                        + outputFile.getAbsolutePath()
                        + ", sampleRateHz="
                        + audioConfig.getSampleRateHz()
                        + ", channelCount="
                        + audioConfig.getChannelCount()
                        + ", encoding="
                        + audioConfig.getAudioEncoding()
                        + ", frameSizeSamples="
                        + audioConfig.getFrameSizeSamples()
                        + ", frameSizeBytes="
                        + audioConfig.getFrameSizeBytes()
        );
    }

    public synchronized void writeFrame(short[] frame) throws IOException {
        byte[] frameBytes = new byte[frame.length * 2];
        int byteIndex = 0;
        short minSample = Short.MAX_VALUE;
        short maxSample = Short.MIN_VALUE;

        for (short sample : frame) {
            // 1. Apply software gain
            int boosted = (int) (sample * EXPORT_GAIN_MULTIPLIER);

            // 2. Hard-clip to 16-bit boundaries to prevent integer overflow wraparound
            if (boosted > Short.MAX_VALUE) {
                boosted = Short.MAX_VALUE;
            } else if (boosted < Short.MIN_VALUE) {
                boosted = Short.MIN_VALUE;
            }

            short boostedSample = (short) boosted;

            // 3. Track min/max using the BOOSTED sample so your logs reflect the saved file
            if (boostedSample < minSample) {
                minSample = boostedSample;
            }
            if (boostedSample > maxSample) {
                maxSample = boostedSample;
            }

            // 4. Write the BOOSTED sample to the byte array (little-endian)
            // Note: The original 'frame' array is NOT modified.
            frameBytes[byteIndex++] = (byte) (boostedSample & 0xFF);
            frameBytes[byteIndex++] = (byte) ((boostedSample >> 8) & 0xFF);
        }

        randomAccessFile.seek(WAV_HEADER_SIZE + pcmBytesWritten);
        randomAccessFile.write(frameBytes);
        pcmBytesWritten += frameBytes.length;

        if (debugFrameLogs < DEBUG_FRAME_LOG_LIMIT) {
            debugFrameLogs++;
            int previewLength = Math.min(8, frame.length);
            short[] previewSamples = Arrays.copyOf(frame, previewLength);
            // Optional: You could multiply the previewSamples by the gain here if you want
            // the log array to perfectly match the file, but leaving it as-is shows the RAW detector input.
            Logger.d(
                    TAG,
                    "write frameBytes="
                            + frameBytes.length
                            + ", totalPcmBytesWritten="
                            + pcmBytesWritten
                            + ", minSample="
                            + minSample // This will now show the amplified min
                            + ", maxSample="
                            + maxSample // This will now show the amplified max
                            + ", previewSamples="
                            + Arrays.toString(previewSamples)
            );
        }
    }

    public synchronized long finish() throws IOException {
        writeHeader(pcmBytesWritten);
        randomAccessFile.close();
        long finalFileSize = outputFile.length();
        Logger.d(
                TAG,
                "Finalized WAV path="
                        + outputFile.getAbsolutePath()
                        + ", totalPcmBytesWritten="
                        + pcmBytesWritten
                        + ", finalFileSize="
                        + finalFileSize
        );
        return finalFileSize;
    }

    public synchronized void abort() {
        try {
            randomAccessFile.close();
        } catch (IOException ignored) {
        }
        if (outputFile.exists()) {
            outputFile.delete();
        }
    }

    public File getOutputFile() {
        return outputFile;
    }

    private void writeHeader(long audioDataBytes) throws IOException {
        randomAccessFile.seek(0L);
        randomAccessFile.writeBytes("RIFF");
        randomAccessFile.writeInt(Integer.reverseBytes((int) (36L + audioDataBytes)));
        randomAccessFile.writeBytes("WAVE");
        randomAccessFile.writeBytes("fmt ");
        randomAccessFile.writeInt(Integer.reverseBytes(16));
        randomAccessFile.writeShort(Short.reverseBytes((short) 1));
        randomAccessFile.writeShort(Short.reverseBytes((short) audioConfig.getChannelCount()));
        randomAccessFile.writeInt(Integer.reverseBytes(audioConfig.getSampleRateHz()));
        int byteRate = audioConfig.getSampleRateHz()
                * audioConfig.getChannelCount()
                * audioConfig.getBytesPerSample();
        randomAccessFile.writeInt(Integer.reverseBytes(byteRate));
        short blockAlign = (short) (audioConfig.getChannelCount() * audioConfig.getBytesPerSample());
        randomAccessFile.writeShort(Short.reverseBytes(blockAlign));
        randomAccessFile.writeShort(Short.reverseBytes((short) (audioConfig.getBytesPerSample() * 8)));
        randomAccessFile.writeBytes("data");
        randomAccessFile.writeInt(Integer.reverseBytes((int) audioDataBytes));
    }
}