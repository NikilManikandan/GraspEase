#include <jni.h>
#include "TFLiteEngine.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_graspease_dev_tflite_TFLiteEngineNative_createEngine(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new TFLiteEngine());
}

JNIEXPORT jint JNICALL
Java_com_graspease_dev_tflite_TFLiteEngineNative_initVocab(JNIEnv *, jobject, jlong nativePtr, jboolean isMultilingual) {
    auto *engine = reinterpret_cast<TFLiteEngine *>(nativePtr);
    return static_cast<jint>(engine->initVocab(isMultilingual));
}

JNIEXPORT jfloatArray JNICALL
Java_com_graspease_dev_tflite_TFLiteEngineNative_computeMel(JNIEnv *env, jobject, jlong nativePtr, jfloatArray samples) {
    auto *engine = reinterpret_cast<TFLiteEngine *>(nativePtr);
    jsize len = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    std::vector<float> sampleVector(data, data + len);
    env->ReleaseFloatArrayElements(samples, data, 0);

    std::vector<float> mel = engine->computeMel(sampleVector);
    jfloatArray out = env->NewFloatArray(mel.size());
    env->SetFloatArrayRegion(out, 0, mel.size(), mel.data());
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_graspease_dev_tflite_TFLiteEngineNative_decodeTokens(JNIEnv *env, jobject, jlong nativePtr, jintArray tokens) {
    auto *engine = reinterpret_cast<TFLiteEngine *>(nativePtr);
    jsize len = env->GetArrayLength(tokens);
    jint *data = env->GetIntArrayElements(tokens, nullptr);
    std::vector<int> tokenVector(data, data + len);
    env->ReleaseIntArrayElements(tokens, data, 0);
    std::string result = engine->decodeTokens(tokenVector);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_graspease_dev_tflite_TFLiteEngineNative_free(JNIEnv *, jobject, jlong nativePtr) {
    auto *engine = reinterpret_cast<TFLiteEngine *>(nativePtr);
    delete engine;
}

}
