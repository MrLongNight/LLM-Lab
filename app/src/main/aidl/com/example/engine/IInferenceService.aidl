package com.example.engine;

interface IInferenceService {
    boolean loadModel(String path, int contextSize, boolean requireVulkan);
    String generate(String prompt, int maxTokens, int timeoutMs);
    void abort();
    void shutdown();

    // The client needs the remote PID so it can SIGKILL the isolated process
    // even when every Binder worker is blocked inside native code.
    int getProcessId();
}
