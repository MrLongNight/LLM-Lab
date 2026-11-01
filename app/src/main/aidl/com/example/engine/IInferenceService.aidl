package com.example.engine;

import android.os.Bundle;
import com.example.engine.IInferenceCallback;

interface IInferenceService {
    void prepareForOperation();
    Bundle loadModel(String path, in Bundle config);
    Bundle executeInference(String prompt, in Bundle config, IInferenceCallback callback);
    Bundle reconfigureContext(in Bundle config);
    void unloadModel();
    void abort();
    Bundle getDiagnostics();
    void shutdown();
    int getProcessId();
}
