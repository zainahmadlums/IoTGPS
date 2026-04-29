package com.example.audio.pipeline;

import com.example.audio.audio.FrameProcessor;
import com.example.audio.disturbance.DisturbanceDetector;
import com.example.audio.reverb.ReverbEstimator;
import com.example.audio.speaker.SpeakerRoleClassifier;
import com.example.audio.vad.SpeechDetector;
import com.example.audio.vad.VadResult;

public class AudioPipelineCoordinator implements FrameProcessor {

    private final SpeechDetector speechDetector;
    private final DisturbanceDetector disturbanceDetector;
    private final ReverbEstimator reverbEstimator;
    private final SpeakerRoleClassifier speakerRoleClassifier;

    public AudioPipelineCoordinator(
            SpeechDetector speechDetector,
            DisturbanceDetector disturbanceDetector,
            ReverbEstimator reverbEstimator
    ) {
        this(speechDetector, disturbanceDetector, reverbEstimator, null);
    }

    public AudioPipelineCoordinator(
            SpeechDetector speechDetector,
            DisturbanceDetector disturbanceDetector,
            ReverbEstimator reverbEstimator,
            SpeakerRoleClassifier speakerRoleClassifier
    ) {
        this.speechDetector = speechDetector;
        this.disturbanceDetector = disturbanceDetector;
        this.reverbEstimator = reverbEstimator;
        this.speakerRoleClassifier = speakerRoleClassifier;
    }

    @Override
    public FrameAnalysisResult process(short[] frame) {
        long timestampMillis = System.currentTimeMillis();
        VadResult vadResult = speechDetector.analyze(frame, timestampMillis);
        vadResult = withSpeakerRole(frame, vadResult);
        return new FrameAnalysisResult(
                vadResult,
                disturbanceDetector.analyze(frame, timestampMillis, vadResult),
                reverbEstimator.estimate(frame, timestampMillis, vadResult)
        );
    }

    public FrameAnalysisResult process(short[] frame, long timestampMillis) {
        VadResult vadResult = speechDetector.analyze(frame, timestampMillis);
        vadResult = withSpeakerRole(frame, vadResult);
        return new FrameAnalysisResult(
                vadResult,
                disturbanceDetector.analyze(frame, timestampMillis, vadResult),
                reverbEstimator.estimate(frame, timestampMillis, vadResult)
        );
    }

    public void close() {
        speechDetector.close();
    }

    private VadResult withSpeakerRole(short[] frame, VadResult vadResult) {
        if (vadResult == null || speakerRoleClassifier == null) {
            return vadResult;
        }
        return new VadResult(
                vadResult.getTimestampMillis(),
                vadResult.isSpeech(),
                vadResult.getConfidence(),
                vadResult.getConditionedFrame(),
                vadResult.getPlaybackFrame(),
                speakerRoleClassifier.classify(frame, vadResult)
        );
    }
}
