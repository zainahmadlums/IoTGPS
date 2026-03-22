package com.example.audio.vad;

import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WebRtcSpeechDetectorConfigTest {

    @Test
    public void usesRecommendedWebRtcFrameConfigurationAndLatching() {
        assertEquals(SampleRate.SAMPLE_RATE_16K, WebRtcSpeechDetector.configuredSampleRate());
        assertEquals(FrameSize.FRAME_SIZE_320, WebRtcSpeechDetector.configuredFrameSize());
        assertEquals(Mode.VERY_AGGRESSIVE, WebRtcSpeechDetector.configuredMode());
        assertEquals(50, WebRtcSpeechDetector.configuredSpeechDurationMs());
        assertEquals(300, WebRtcSpeechDetector.configuredSilenceDurationMs());
    }
}
