// Copyright 2024 Xiaomi Corporation

package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public class SpeakerEmbeddingExtractor {
    static {
        LibraryLoader.maybeLoad();
    }

    private long ptr = 0;

    public SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig config) {
        ptr = newFromFile(config);
    }

    public SpeakerEmbeddingExtractor(AssetManager assetManager, SpeakerEmbeddingExtractorConfig config) {
        ptr = newFromAsset(assetManager, config);
    }

    protected void finalize() throws Throwable {
        delete();
    }

    public void release() {
        delete();
    }

    public void delete() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    public OnlineStream createStream() {
        long streamPtr = createStream(ptr);
        return new OnlineStream(streamPtr);
    }

    public boolean isReady(OnlineStream stream) {
        return isReady(ptr, stream.getPtr());
    }

    public float[] compute(OnlineStream stream) {
        return compute(ptr, stream.getPtr());
    }

    public int dim() {
        return dim(ptr);
    }

    private native void delete(long ptr);

    private native long newFromFile(SpeakerEmbeddingExtractorConfig config);

    private native long newFromAsset(AssetManager assetManager, SpeakerEmbeddingExtractorConfig config);

    private native long createStream(long ptr);

    private native boolean isReady(long ptr, long streamPtr);

    private native float[] compute(long ptr, long streamPtr);

    private native int dim(long ptr);
}
