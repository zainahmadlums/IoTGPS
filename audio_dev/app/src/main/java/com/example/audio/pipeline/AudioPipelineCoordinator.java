package com.example.audio.pipeline;

import com.example.audio.audio.FrameProcessor;
import com.example.audio.disturbance.DisturbanceDetector;
import com.example.audio.reverb.ReverbEstimator;
import com.example.audio.speaker.SpeakerDiarizer;
import com.example.audio.vad.SpeechDetector;
import com.example.audio.vad.VadResult;

public class AudioPipelineCoordinator implements FrameProcessor {

    private final SpeechDetector speechDetector;
    private final DisturbanceDetector disturbanceDetector;
    private final ReverbEstimator reverbEstimator;
    private final SpeakerDiarizer speakerDiarizer;

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
            SpeakerDiarizer speakerDiarizer
    ) {
        this.speechDetector = speechDetector;
        this.disturbanceDetector = disturbanceDetector;
        this.reverbEstimator = reverbEstimator;
        this.speakerDiarizer = speakerDiarizer;
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
        if (speakerDiarizer != null) {
            speakerDiarizer.close();
        }

    }

    private VadResult withSpeakerRole(short[] frame, VadResult vadResult) {
        if (vadResult == null || speakerDiarizer == null) {
            return vadResult;
        }
        return new VadResult(
                vadResult.getTimestampMillis(),
                vadResult.isSpeech(),
                vadResult.getConfidence(),
                vadResult.getConditionedFrame(),
                vadResult.getPlaybackFrame(),
                speakerDiarizer.classify(frame, vadResult)
        );
    }
}
