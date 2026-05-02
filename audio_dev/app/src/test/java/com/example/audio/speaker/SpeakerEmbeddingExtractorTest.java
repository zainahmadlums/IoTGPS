package com.example.audio.speaker;

import com.example.audio.testsupport.SyntheticAudioFactory;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SpeakerEmbeddingExtractorTest {

    @Test
    public void givesHigherSimilarityToSameSpectralSpeakerWindow() {
        LegacySpeakerEmbeddingExtractor extractor = new LegacySpeakerEmbeddingExtractor();
        LegacySpeakerEmbeddingExtractor.EmbeddingAccumulator instructor =
                new LegacySpeakerEmbeddingExtractor.EmbeddingAccumulator();
        LegacySpeakerEmbeddingExtractor.EmbeddingAccumulator sameSpeaker =
                new LegacySpeakerEmbeddingExtractor.EmbeddingAccumulator();
        LegacySpeakerEmbeddingExtractor.EmbeddingAccumulator differentSpeaker =
                new LegacySpeakerEmbeddingExtractor.EmbeddingAccumulator();

        for (int frameIndex = 0; frameIndex < 16; frameIndex++) {
            instructor.add(extractor.extractFrameFeatures(
                    SyntheticAudioFactory.currentSineFrame(6000, 180.0f)
            ));
            sameSpeaker.add(extractor.extractFrameFeatures(
                    SyntheticAudioFactory.currentSineFrame(5200, 180.0f)
            ));
            differentSpeaker.add(extractor.extractFrameFeatures(
                    SyntheticAudioFactory.currentAlternatingFrame(5200)
            ));
        }

        float[] instructorEmbedding = extractor.buildEmbedding(instructor);
        float sameSimilarity = extractor.cosineSimilarity(
                instructorEmbedding,
                extractor.buildEmbedding(sameSpeaker)
        );
        float differentSimilarity = extractor.cosineSimilarity(
                instructorEmbedding,
                extractor.buildEmbedding(differentSpeaker)
        );

        assertTrue(sameSimilarity > differentSimilarity);
    }
}
