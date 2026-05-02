// Copyright 2024 Xiaomi Corporation

package com.k2fsa.sherpa.onnx;

public class OnlineStream {
    static {
        LibraryLoader.maybeLoad();
    }

    private long ptr = 0;

    public OnlineStream(long ptr) {
        this.ptr = ptr;
    }

    public long getPtr() {
        return ptr;
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

    public void acceptWaveform(float[] samples, int sampleRate) {
        acceptWaveform(ptr, samples, sampleRate);
    }

    public void inputFinished() {
        inputFinished(ptr);
    }

    public boolean hasOption(String name) {
        return hasOption(ptr, name);
    }

    public void setOption(String name, String value) {
        setOption(ptr, name, value);
    }

    public String getOption(String name) {
        return getOption(ptr, name);
    }

    private native void delete(long ptr);

    private native void acceptWaveform(long ptr, float[] samples, int sampleRate);

    private native void inputFinished(long ptr);

    private native boolean hasOption(long ptr, String name);

    private native void setOption(long ptr, String name, String value);

    private native String getOption(long ptr, String name);
}
