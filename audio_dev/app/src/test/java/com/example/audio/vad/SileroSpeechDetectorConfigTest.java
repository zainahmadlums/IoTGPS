package com.example.audio.vad;

import com.konovalov.vad.silero.config.FrameSize;
import com.konovalov.vad.silero.config.Mode;
import com.konovalov.vad.silero.config.SampleRate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SileroSpeechDetectorConfigTest {

    @Test
    public void usesRecommendedSileroFrameConfigurationAndLatching() {
        assertEquals(SampleRate.SAMPLE_RATE_16K, SileroSpeechDetector.configuredSampleRate());
        assertEquals(FrameSize.FRAME_SIZE_512, SileroSpeechDetector.configuredFrameSize());
        assertEquals(Mode.NORMAL, SileroSpeechDetector.configuredMode());
        assertEquals(50, SileroSpeechDetector.configuredSpeechDurationMs());
        assertEquals(300, SileroSpeechDetector.configuredSilenceDurationMs());
    }

    @Test
    public void factoryExposesSileroAsActiveDetector() {
        assertEquals("Silero", SpeechDetectorFactory.activeDetectorName());
        assertTrue(SileroSpeechDetector.supports(SpeechDetectorFactory.activeAudioConfig()));
        assertEquals(512, SpeechDetectorFactory.activeAudioConfig().getFrameSizeSamples());
    }
}
