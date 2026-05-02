package com.example.audio.data;

import com.example.audio.speaker.LegacySpeakerEmbeddingExtractor;

public final class InstructorVoiceProfileStats {

    private final LegacySpeakerEmbeddingExtractor embeddingExtractor = new LegacySpeakerEmbeddingExtractor();
    private final LegacySpeakerEmbeddingExtractor.EmbeddingAccumulator embeddingAccumulator =
            new LegacySpeakerEmbeddingExtractor.EmbeddingAccumulator();
    private int frameCount;
    private double rmsSum;
    private double zcrSum;
    private double lowBandRatioSum;
    private double highBandRatioSum;
    private float lowPassState;

    public void addFrame(short[] frame) {
        if (frame == null || frame.length == 0) {
            return;
        }

        double energy = 0.0d;
        double lowEnergy = 0.0d;
        double highEnergy = 0.0d;
        int crossings = 0;
        short previous = frame[0];
        for (short sample : frame) {
            energy += (double) sample * sample;
            lowPassState += 0.12f * (sample - lowPassState);
            float lowComponent = lowPassState;
            float highComponent = sample - lowComponent;
            lowEnergy += lowComponent * lowComponent;
            highEnergy += highComponent * highComponent;
            if ((previous >= 0 && sample < 0) || (previous < 0 && sample >= 0)) {
                crossings++;
            }
            previous = sample;
        }

        rmsSum += Math.sqrt(energy / frame.length) / Short.MAX_VALUE;
        zcrSum += (float) crossings / frame.length;
        if (energy > 0.0d) {
            lowBandRatioSum += lowEnergy / energy;
            highBandRatioSum += highEnergy / energy;
        }
        embeddingAccumulator.add(embeddingExtractor.extractFrameFeatures(frame));
        frameCount++;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public float getAverageRms() {
        return frameCount == 0 ? 0.0f : (float) (rmsSum / frameCount);
    }

    public float getAverageZcr() {
        return frameCount == 0 ? 0.0f : (float) (zcrSum / frameCount);
    }

    public float getAverageLowBandRatio() {
        return frameCount == 0 ? 0.0f : (float) (lowBandRatioSum / frameCount);
    }

    public float getAverageHighBandRatio() {
        return frameCount == 0 ? 0.0f : (float) (highBandRatioSum / frameCount);
    }

    public int getEmbeddingVersion() {
        return LegacySpeakerEmbeddingExtractor.EMBEDDING_VERSION;
    }

    public float[] getEmbedding() {
        return embeddingExtractor.buildEmbedding(embeddingAccumulator);
    }
}
