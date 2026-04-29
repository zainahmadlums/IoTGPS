package com.example.audio.data;

import android.content.Context;

import com.example.audio.util.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public final class InstructorVoiceProfileStore {

    public static final String PROFILE_METADATA_FILE_NAME = "instructor_voice_profile.json";

    private static final String TAG = "InstructorVoiceProfile";
    private static final InstructorVoiceProfileStore INSTANCE = new InstructorVoiceProfileStore();

    private InstructorVoiceProfileStore() {
    }

    public static InstructorVoiceProfileStore getInstance() {
        return INSTANCE;
    }

    public boolean hasProfile(Context context) {
        InstructorVoiceProfile profile = readProfile(context);
        return profile != null
                && SessionAudioFileManager.resolveAudioFile(
                context.getApplicationContext(),
                profile.getAudioFileName()
        ).exists();
    }

    public InstructorVoiceProfile readProfile(Context context) {
        File inputFile = SessionMetadataFileManager.resolveMetadataFile(
                context.getApplicationContext(),
                PROFILE_METADATA_FILE_NAME
        );
        if (!inputFile.exists()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return InstructorVoiceProfile.fromJson(new JSONObject(builder.toString()));
        } catch (IOException | JSONException exception) {
            Logger.e(TAG, "Failed to read instructor voice profile.", exception);
            return null;
        }
    }

    public long writeProfile(Context context, InstructorVoiceProfile profile) {
        File outputFile = SessionMetadataFileManager.resolveMetadataFile(
                context.getApplicationContext(),
                PROFILE_METADATA_FILE_NAME
        );
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile, false))) {
            writer.write(profile.toJson().toString());
            return outputFile.length();
        } catch (IOException | JSONException exception) {
            throw new IllegalStateException("Failed to persist instructor voice profile.", exception);
        }
    }

    public boolean deleteProfile(Context context) {
        InstructorVoiceProfile profile = readProfile(context);
        boolean audioDeleted = true;
        if (profile != null) {
            audioDeleted = SessionAudioFileManager.deleteAudioFile(
                    context.getApplicationContext(),
                    profile.getAudioFileName()
            );
        }
        boolean metadataDeleted = SessionMetadataFileManager.deleteMetadataFile(
                context.getApplicationContext(),
                PROFILE_METADATA_FILE_NAME
        );
        return audioDeleted && metadataDeleted;
    }
}
