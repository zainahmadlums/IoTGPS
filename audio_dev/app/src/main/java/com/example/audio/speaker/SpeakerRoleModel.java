package com.example.audio.speaker;

import android.content.Context;

import com.example.audio.data.SpeakerRole;
import com.example.audio.util.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class SpeakerRoleModel {

    private static final String TAG = "SpeakerRoleModel";
    private static final String ASSET_FILE_NAME = "speaker_role_model.model";

    private final String[] labels;
    private final float[] means;
    private final float[] scales;
    private final float[][] weights;
    private final float[] biases;

    private SpeakerRoleModel(String[] labels, float[] means, float[] scales, float[][] weights, float[] biases) {
        this.labels = labels;
        this.means = means;
        this.scales = scales;
        this.weights = weights;
        this.biases = biases;
    }

    public static SpeakerRoleModel load(Context context) {
        if (context == null) {
            return null;
        }
        try (InputStream inputStream = context.getAssets().open(ASSET_FILE_NAME);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            JSONObject root = new JSONObject(builder.toString());
            String[] labels = readStringArray(root.getJSONArray("labels"));
            float[] means = readFloatArray(root.getJSONArray("means"));
            float[] scales = readFloatArray(root.getJSONArray("scales"));
            JSONArray weightsJson = root.getJSONArray("weights");
            float[][] weights = new float[weightsJson.length()][];
            for (int index = 0; index < weightsJson.length(); index++) {
                weights[index] = readFloatArray(weightsJson.getJSONArray(index));
            }
            float[] biases = readFloatArray(root.getJSONArray("biases"));
            return new SpeakerRoleModel(labels, means, scales, weights, biases);
        } catch (Exception exception) {
            Logger.e(TAG, "Speaker role model unavailable; using heuristic classifier.", exception);
            return null;
        }
    }

    public SpeakerRole predict(float[] features) {
        if (features == null || features.length != means.length) {
            return SpeakerRole.STUDENT;
        }

        int bestIndex = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int classIndex = 0; classIndex < labels.length; classIndex++) {
            float score = biases[classIndex];
            for (int featureIndex = 0; featureIndex < features.length; featureIndex++) {
                float scale = Math.max(1.0e-6f, scales[featureIndex]);
                float normalized = (features[featureIndex] - means[featureIndex]) / scale;
                score += weights[classIndex][featureIndex] * normalized;
            }
            if (score > bestScore) {
                bestScore = score;
                bestIndex = classIndex;
            }
        }
        return toRole(labels[bestIndex]);
    }

    private static SpeakerRole toRole(String value) {
        try {
            return SpeakerRole.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return SpeakerRole.STUDENT;
        }
    }

    private static String[] readStringArray(JSONArray jsonArray) throws Exception {
        String[] values = new String[jsonArray.length()];
        for (int index = 0; index < jsonArray.length(); index++) {
            values[index] = jsonArray.getString(index);
        }
        return values;
    }

    private static float[] readFloatArray(JSONArray jsonArray) throws Exception {
        float[] values = new float[jsonArray.length()];
        for (int index = 0; index < jsonArray.length(); index++) {
            values[index] = (float) jsonArray.getDouble(index);
        }
        return values;
    }
}
