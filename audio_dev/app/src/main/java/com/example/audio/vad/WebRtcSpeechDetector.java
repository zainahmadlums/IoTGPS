package com.example.audio.vad;

import com.example.audio.audio.AudioConfig;
import com.example.audio.util.Logger;
import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

public class WebRtcSpeechDetector implements SpeechDetector {

    private static final String TAG = "WebRtcSpeechDetector";

    private final VadWebRTC vadDelegate;
    private long lastLogTimestampMillis;

    public WebRtcSpeechDetector() {
        this.vadDelegate = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_320)
                .setMode(Mode.NORMAL)
                .setSilenceDurationMs(60)
                .setSpeechDurationMs(20)
                .build();
    }

    @Override
    public VadResult analyze(short[] frame, long timestampMillis) {
        if (frame == null || frame.length != 320) {
            return new VadResult(timestampMillis, false, null);
        }

        boolean isSpeech = vadDelegate.isSpeech(frame);
        maybeLog(timestampMillis, frame, isSpeech);
        return new VadResult(timestampMillis, isSpeech, null);
    }

    @Override
    public void close() {
        vadDelegate.close();
    }

    public static boolean supports(AudioConfig audioConfig) {
        return audioConfig.getSampleRateHz() == AudioConfig.DEFAULT_SAMPLE_RATE_HZ
                && audioConfig.getChannelCount() == 1
                && audioConfig.getFrameSizeSamples() == 320;
    }

    private void maybeLog(long timestampMillis, short[] frame, boolean isSpeech) {
        if (timestampMillis - lastLogTimestampMillis < 1000L) {
            return;
        }

        lastLogTimestampMillis = timestampMillis;
        Logger.d(TAG, "rms=" + computeRms(frame) + ", isSpeech=" + isSpeech);
    }

    private int computeRms(short[] frame) {
        long energy = 0L;
        for (short sample : frame) {
            energy += (long) sample * sample;
        }
        return (int) Math.sqrt((double) energy / frame.length);
    }
}
