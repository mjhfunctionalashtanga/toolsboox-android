package com.onyx.android.sdk.hwr.service;

import android.os.ParcelFileDescriptor;
import com.onyx.android.sdk.hwr.service.HWROutputCallback;
import com.onyx.android.sdk.hwr.service.HWRInputArgs;
import com.onyx.android.sdk.hwr.service.HWRCommandArgs;

// The Boox firmware's handwriting-recognition service (MyScript under the hood).
// METHOD ORDER IS THE WIRE: AIDL transaction codes come from declaration order and must
// match the service exactly — init=1 … closeRecognizer=6. All methods oneway, as shipped.
oneway interface IHWRService {
    void init(in HWRInputArgs args, boolean forceReinit, HWROutputCallback callback);
    void compileRecognizeText(String text, String language, HWROutputCallback callback);
    void batchRecognize(in ParcelFileDescriptor pfd, HWROutputCallback callback);
    void openIncrementalRecognizer(in HWRInputArgs args, HWROutputCallback callback);
    void execCommand(in HWRInputArgs args, in HWRCommandArgs cmdArgs, HWROutputCallback callback);
    void closeRecognizer();
}
