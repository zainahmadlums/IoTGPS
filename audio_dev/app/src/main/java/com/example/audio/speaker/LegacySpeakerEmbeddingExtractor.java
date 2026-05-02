package com.example.audio.speaker;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public final class LegacySpeakerEmbeddingExtractor {

    public static final int EMBEDDING_VERSION = 1;

    private static final int BAND_COUNT = 24;
    private static final int MAX_DFT_BIN = 96;
    private static final int MIN_DFT_BIN = 2;
    private static final int FEATURE_COUNT = BAND_COUNT + 4;
    private static final float LOG_EPSILON = 1.0e-6f;

    public float[] extractFrameFeatures(short[] frame) {
        float[] features = new float[FEATURE_COUNT];
        if (frame == null || frame.length == 0) {
            return features;
        }

        double energy = 0.0d;
        int crossings = 0;
        short previous = frame[0];
        for (short sample : frame) {
            energy += (double) sample * sample;
            if ((previous >= 0 && sample < 0) || (previous < 0 && sample >= 0)) {
                crossings++;
            }
            previous = sample;
        }

        float rms = (float) (Math.sqrt(energy / frame.length) / Short.MAX_VALUE);
        float zcr = (float) crossings / frame.length;
        float pitchCorrelation = estimatePitchCorrelation(frame);
        float spectralCentroid = fillLogBands(frame, features);

        features[BAND_COUNT] = rms;
        features[BAND_COUNT + 1] = zcr;
        features[BAND_COUNT + 2] = pitchCorrelation;
        features[BAND_COUNT + 3] = spectralCentroid;
        return features;
    }

    public int embeddingSize() {
        return FEATURE_COUNT * 2;
    }

    public float[] buildEmbedding(EmbeddingAccumulator accumulator) {
        if (accumulator == null || accumulator.getFrameCount() == 0) {
            return new float[embeddingSize()];
        }

        float[] embedding = new float[embeddingSize()];
        int frameCount = accumulator.getFrameCount();
        for (int index = 0; index < FEATURE_COUNT; index++) {
            float mean = accumulator.getSum(index) / frameCount;
            float meanSquare = accumulator.getSumSquares(index) / frameCount;
            float variance = Math.max(0.0f, meanSquare - (mean * mean));
            embedding[index] = mean;
            embedding[index + FEATURE_COUNT] = (float) Math.sqrt(variance);
        }
        return l2Normalize(embedding);
    }

    public float cosineSimilarity(float[] first, float[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) {
            return 0.0f;
        }

        float dotProduct = 0.0f;
        float firstNorm = 0.0f;
        float secondNorm = 0.0f;
        for (int index = 0; index < first.length; index++) {
            dotProduct += first[index] * second[index];
            firstNorm += first[index] * first[index];
            secondNorm += second[index] * second[index];
        }
        if (firstNorm <= 0.0f || secondNorm <= 0.0f) {
            return 0.0f;
        }
        return dotProduct / ((float) Math.sqrt(firstNorm) * (float) Math.sqrt(secondNorm));
    }

    public float[] l2Normalize(float[] values) {
        if (values == null || values.length == 0) {
            return new float[0];
        }

        float norm = 0.0f;
        for (float value : values) {
            norm += value * value;
        }
        if (norm <= 0.0f) {
            return Arrays.copyOf(values, values.length);
        }

        float scale = 1.0f / (float) Math.sqrt(norm);
        float[] normalized = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            normalized[index] = values[index] * scale;
        }
        return normalized;
    }

    private float fillLogBands(short[] frame, float[] output) {
        double totalPower = 0.0d;
        double weightedBinSum = 0.0d;
        double[] bandPower = new double[BAND_COUNT];
        int frameLength = frame.length;
        for (int bin = MIN_DFT_BIN; bin <= MAX_DFT_BIN; bin++) {
            double real = 0.0d;
            double imaginary = 0.0d;
            for (int index = 0; index < frameLength; index++) {
                double window = 0.5d - (0.5d * Math.cos((2.0d * Math.PI * index) / (frameLength - 1)));
                double angle = (2.0d * Math.PI * bin * index) / frameLength;
                double sample = frame[index] * window;
                real += sample * Math.cos(angle);
                imaginary -= sample * Math.sin(angle);
            }
            double power = (real * real) + (imaginary * imaginary);
            int band = Math.min(BAND_COUNT - 1, (bin - MIN_DFT_BIN) * BAND_COUNT / (MAX_DFT_BIN - MIN_DFT_BIN + 1));
            bandPower[band] += power;
            totalPower += power;
            weightedBinSum += power * bin;
        }

        double logPowerSum = 0.0d;
        for (int band = 0; band < BAND_COUNT; band++) {
            output[band] = (float) Math.log(LOG_EPSILON + (bandPower[band] / Math.max(LOG_EPSILON, totalPower)));
            logPowerSum += output[band];
        }
        float logPowerMean = (float) (logPowerSum / BAND_COUNT);
        for (int band = 0; band < BAND_COUNT; band++) {
            output[band] -= logPowerMean;
        }
        return totalPower <= 0.0d ? 0.0f : (float) (weightedBinSum / (totalPower * MAX_DFT_BIN));
    }

    private float estimatePitchCorrelation(short[] frame) {
        if (frame.length < 128) {
            return 0.0f;
        }

        double totalEnergy = 0.0d;
        for (short sample : frame) {
            totalEnergy += (double) sample * sample;
        }
        if (totalEnergy <= 0.0d) {
            return 0.0f;
        }

        double bestCorrelation = 0.0d;
        int maxLag = Math.min(160, frame.length / 2);
        for (int lag = 32; lag <= maxLag; lag += 4) {
            double correlation = 0.0d;
            for (int index = lag; index < frame.length; index++) {
                correlation += (double) frame[index] * frame[index - lag];
            }
            bestCorrelation = Math.max(bestCorrelation, correlation);
        }
        return Math.max(0.0f, Math.min(1.0f, (float) (bestCorrelation / totalEnergy)));
    }

    public static final class EmbeddingAccumulator {
        private final float[] sum = new float[FEATURE_COUNT];
        private final float[] sumSquares = new float[FEATURE_COUNT];
        private int frameCount;

        public void add(float[] features) {
            if (features == null || features.length != FEATURE_COUNT) {
                return;
            }
            for (int index = 0; index < FEATURE_COUNT; index++) {
                sum[index] += features[index];
                sumSquares[index] += features[index] * features[index];
            }
            frameCount++;
        }

        int getFrameCount() {
            return frameCount;
        }

        float getSum(int index) {
            return sum[index];
        }

        float getSumSquares(int index) {
            return sumSquares[index];
        }
    }

    public final class RollingWindow {
        private final int maxFrames;
        private final Deque<float[]> features = new ArrayDeque<>();

        public RollingWindow(int maxFrames) {
            this.maxFrames = Math.max(1, maxFrames);
        }

        public void add(short[] frame) {
            features.addLast(extractFrameFeatures(frame));
            while (features.size() > maxFrames) {
                features.removeFirst();
            }
        }

        public void clear() {
            features.clear();
        }

        public int size() {
            return features.size();
        }

        public float[] buildEmbedding() {
            EmbeddingAccumulator accumulator = new EmbeddingAccumulator();
            for (float[] frameFeatures : features) {
                accumulator.add(frameFeatures);
            }
            return SpeakerEmbeddingExtractor.this.buildEmbedding(accumulator);
        }
    }
}
