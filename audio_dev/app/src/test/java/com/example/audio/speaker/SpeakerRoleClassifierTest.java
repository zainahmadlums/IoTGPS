package com.example.audio.speaker;

import com.example.audio.data.InstructorVoiceProfile;
import com.example.audio.data.SpeakerRole;
import com.example.audio.testsupport.SyntheticAudioFactory;
import com.example.audio.vad.VadResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpeakerRoleClassifierTest {

    @Test
    public void warmupSpeechDoesNotAssumeInstructor() {
        SpeakerRoleClassifier classifier = new SpeakerRoleClassifier(buildInstructorProfile());

        SpeakerRole role = classifier.classify(
                SyntheticAudioFactory.currentSineFrame(6000, 220.0f),
                new VadResult(0L, true, 0.80f)
        );

        assertEquals(SpeakerRole.SILENCE, role);
    }

    @Test
    public void weakSpeechConfidenceIsSilenceForRoleClassifier() {
        SpeakerRoleClassifier classifier = new SpeakerRoleClassifier(buildInstructorProfile());

        SpeakerRole role = classifier.classify(
                SyntheticAudioFactory.currentConstantFrame(20),
                new VadResult(0L, true, 0.20f)
        );

        assertEquals(SpeakerRole.SILENCE, role);
    }

    @Test
    public void switchesToStudentAfterRollingEmbeddingWindow() {
        SpeakerRoleClassifier classifier = new SpeakerRoleClassifier(buildInstructorProfile());
        SpeakerRole role = SpeakerRole.INSTRUCTOR;

        for (int index = 0; index < 64; index++) {
            role = classifier.classify(
                    SyntheticAudioFactory.currentAlternatingFrame(6000),
                    new VadResult(index * 32L, true, 0.80f)
            );
        }

        assertEquals(SpeakerRole.STUDENT, role);
    }

    private InstructorVoiceProfile buildInstructorProfile() {
        SpeakerEmbeddingExtractor extractor = new SpeakerEmbeddingExtractor();
        SpeakerEmbeddingExtractor.EmbeddingAccumulator accumulator =
                new SpeakerEmbeddingExtractor.EmbeddingAccumulator();
        for (int index = 0; index < 16; index++) {
            accumulator.add(extractor.extractFrameFeatures(
                    SyntheticAudioFactory.currentSineFrame(6000, 180.0f)
            ));
        }
        return new InstructorVoiceProfile(
                "test-profile",
                "Instructor Voice Setup",
                "instructor_voice_profile.json",
                "instructor.wav",
                "prompt",
                0L,
                30000L,
                30000L,
                16000,
                1,
                512,
                16,
                2048L,
                0.10f,
                0.05f,
                0.40f,
                0.60f,
                SpeakerEmbeddingExtractor.EMBEDDING_VERSION,
                extractor.buildEmbedding(accumulator)
        );
    }
}
