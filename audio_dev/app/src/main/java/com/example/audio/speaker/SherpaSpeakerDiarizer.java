package com.example.audio.speaker;

import android.content.Context;
import android.util.Log;

import com.example.audio.data.SpeakerRole;
import com.example.audio.vad.VadResult;
import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class SherpaSpeakerDiarizer implements SpeakerDiarizer {
    private static final String TAG = "SherpaSpeakerDiarizer";
    private static final String SEGMENTATION_MODEL = "sherpa-onnx/segmentation.onnx";
    private static final String EMBEDDING_MODEL = "sherpa-onnx/embedding.onnx";

    private final OfflineSpeakerDiarization diarizer;

    public SherpaSpeakerDiarizer(Context context) {
        String segmentationPath = copyAssetToFile(context, SEGMENTATION_MODEL);
        String embeddingPath = copyAssetToFile(context, EMBEDDING_MODEL);

        OfflineSpeakerDiarizationConfig config = OfflineSpeakerDiarizationConfig.builder()
                .setSegmentation(OfflineSpeakerSegmentationModelConfig.builder()
                        .setPyannote(OfflineSpeakerSegmentationPyannoteModelConfig.builder()
                                .setModel(segmentationPath)
                                .build())
                        .setNumThreads(4)
                        .setDebug(true)
                        .build())
                .setEmbedding(SpeakerEmbeddingExtractorConfig.builder()
                        .setModel(embeddingPath)
                        .setNumThreads(4)
                        .setDebug(true)
                        .build())
                .setClustering(FastClusteringConfig.builder()
                        .setNumClusters(-1)
                        .setThreshold(0.5f)
                        .build())
                .build();

        this.diarizer = new OfflineSpeakerDiarization(config);
        Log.i(TAG, "SherpaSpeakerDiarizer initialized with Pyannote models.");
    }

    @Override
    public SpeakerRole classify(short[] frame, VadResult vadResult) {
        // Frame-by-frame classification is not natively supported by offline diarizer.
        return (vadResult != null && vadResult.isSpeech()) ? SpeakerRole.STUDENT : SpeakerRole.SILENCE;
    }

    @Override
    public SpeakerRole[] processAll(short[] samples) {
        if (samples == null || samples.length == 0) {
            return new SpeakerRole[0];
        }

        float[] floatSamples = shortToFloat(samples);
        OfflineSpeakerDiarizationSegment[] segments = diarizer.process(floatSamples);

        int frameSize = 160; // 10ms frames at 16kHz
        int frameCount = samples.length / frameSize;
        SpeakerRole[] roles = new SpeakerRole[frameCount];
        Arrays.fill(roles, SpeakerRole.SILENCE);

        if (segments != null) {
            for (OfflineSpeakerDiarizationSegment segment : segments) {
                // Convert seconds to frame index
                int startFrame = (int) (segment.getStart() * 100);
                int endFrame = (int) (segment.getEnd() * 100);
                SpeakerRole role = mapSpeaker(segment.getSpeaker());
                
                for (int i = Math.max(0, startFrame); i < Math.min(frameCount, endFrame); i++) {
                    roles[i] = role;
                }
            }
        }
        return roles;
    }

    private SpeakerRole mapSpeaker(int speakerId) {
        // Heuristic: First speaker (0) is Instructor.
        return speakerId == 0 ? SpeakerRole.INSTRUCTOR : SpeakerRole.STUDENT;
    }

    private float[] shortToFloat(short[] samples) {
        float[] floatSamples = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            floatSamples[i] = samples[i] / 32768.0f;
        }
        return floatSamples;
    }

    private String copyAssetToFile(Context context, String assetPath) {
        File file = new File(context.getFilesDir(), assetPath);
        if (file.exists()) {
            return file.getAbsolutePath();
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy asset: " + assetPath, e);
            return "";
        }
    }

    public void release() {
        diarizer.release();
    }
}
