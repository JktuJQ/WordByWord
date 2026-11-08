#include <jni.h>
#include <onnxruntime_c_api.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

static const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
static void check(OrtStatus* status) {
  if (!status) return;
  std::string message = api->GetErrorMessage(status);
  api->ReleaseStatus(status);
  throw std::runtime_error(message);
}
struct Session {
  OrtEnv* env = nullptr;
  OrtSessionOptions* options = nullptr;
  OrtSession* session = nullptr;
  OrtMemoryInfo* memory = nullptr;
  OrtAllocator* allocator = nullptr;
  char* input = nullptr;
  char* output = nullptr;
  std::vector<int64_t> shape;
  ~Session() {
    if (input) allocator->Free(allocator, input);
    if (output) allocator->Free(allocator, output);
    if (session) api->ReleaseSession(session);
    if (memory) api->ReleaseMemoryInfo(memory);
    if (options) api->ReleaseSessionOptions(options);
    if (env) api->ReleaseEnv(env);
  }
};
static void fail(JNIEnv* jni, const std::exception& error) {
  jni->ThrowNew(jni->FindClass("java/lang/IllegalStateException"), error.what());
}
extern "C" JNIEXPORT jlong JNICALL
Java_dev_wordbyword_evaluation_NativeSegmentation_openModel(JNIEnv* jni, jclass, jstring model) {
  try {
    if (!api) throw std::runtime_error("Unsupported ONNX Runtime API version");
    auto owner = std::make_unique<Session>();
    check(api->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "segmentation-probe", &owner->env));
    check(api->CreateSessionOptions(&owner->options));
    check(api->SetIntraOpNumThreads(owner->options, 1));
    check(api->SetInterOpNumThreads(owner->options, 1));
    const char* text = jni->GetStringUTFChars(model, nullptr);
    std::string path(text); jni->ReleaseStringUTFChars(model, text);
    check(api->CreateSession(owner->env, path.c_str(), owner->options, &owner->session));
    check(api->GetAllocatorWithDefaultOptions(&owner->allocator));
    check(api->SessionGetInputName(owner->session, 0, owner->allocator, &owner->input));
    check(api->SessionGetOutputName(owner->session, 0, owner->allocator, &owner->output));
    check(api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &owner->memory));
    return reinterpret_cast<jlong>(owner.release());
  } catch (const std::exception& error) { fail(jni, error); return 0; }
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_wordbyword_evaluation_NativeSegmentation_readMetadata(JNIEnv* jni, jclass, jlong handle) {
  OrtModelMetadata* metadata = nullptr;
  try {
    auto owner = reinterpret_cast<Session*>(handle);
    check(api->SessionGetModelMetadata(owner->session, &metadata));
    const char* keys[] = {"sample_rate", "window_size", "receptive_field_size", "receptive_field_shift", "num_classes", "num_speakers", "powerset_max_classes"};
    auto result = jni->NewObjectArray(7, jni->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < 7; i++) {
      char* value = nullptr;
      check(api->ModelMetadataLookupCustomMetadataMap(metadata, owner->allocator, keys[i], &value));
      if (!value) throw std::runtime_error(std::string("Missing model metadata: ") + keys[i]);
      std::string text = std::string(keys[i]) + "=" + value;
      owner->allocator->Free(owner->allocator, value);
      auto item = jni->NewStringUTF(text.c_str()); jni->SetObjectArrayElement(result, i, item); jni->DeleteLocalRef(item);
    }
    api->ReleaseModelMetadata(metadata);
    return result;
  } catch (const std::exception& error) {
    if (metadata) api->ReleaseModelMetadata(metadata);
    fail(jni, error); return nullptr;
  }
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_wordbyword_evaluation_NativeSegmentation_inferModel(JNIEnv* jni, jclass, jlong handle, jfloatArray waveform) {
  OrtValue* input = nullptr; OrtValue* output = nullptr; OrtTensorTypeAndShapeInfo* info = nullptr;
  try {
    auto owner = reinterpret_cast<Session*>(handle);
    int n = jni->GetArrayLength(waveform);
    std::vector<float> samples(n); jni->GetFloatArrayRegion(waveform, 0, n, samples.data());
    int64_t dimensions[] = {1, 1, n};
    check(api->CreateTensorWithDataAsOrtValue(owner->memory, samples.data(), n * sizeof(float), dimensions, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &input));
    const OrtValue* inputs[] = {input};
    check(api->Run(owner->session, nullptr, &owner->input, inputs, 1, &owner->output, 1, &output));
    check(api->GetTensorTypeAndShape(output, &info));
    size_t rank = 0, count = 0;
    check(api->GetDimensionsCount(info, &rank)); owner->shape.resize(rank);
    check(api->GetDimensions(info, owner->shape.data(), rank));
    check(api->GetTensorShapeElementCount(info, &count));
    if (count > 10000000) throw std::runtime_error("Unexpectedly large segmentation output");
    void* data = nullptr; check(api->GetTensorMutableData(output, &data));
    auto result = jni->NewFloatArray(static_cast<jsize>(count));
    jni->SetFloatArrayRegion(result, 0, static_cast<jsize>(count), static_cast<float*>(data));
    api->ReleaseTensorTypeAndShapeInfo(info); api->ReleaseValue(output); api->ReleaseValue(input);
    return result;
  } catch (const std::exception& error) {
    if (info) api->ReleaseTensorTypeAndShapeInfo(info);
    if (output) api->ReleaseValue(output);
    if (input) api->ReleaseValue(input);
    fail(jni, error); return nullptr;
  }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_wordbyword_evaluation_NativeSegmentation_lastShape(JNIEnv* jni, jclass, jlong handle) {
  auto owner = reinterpret_cast<Session*>(handle);
  auto result = jni->NewLongArray(owner->shape.size());
  std::vector<jlong> dimensions(owner->shape.begin(), owner->shape.end());
  jni->SetLongArrayRegion(result, 0, dimensions.size(), dimensions.data()); return result;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_wordbyword_evaluation_NativeSegmentation_closeModel(JNIEnv*, jclass, jlong handle) {
  delete reinterpret_cast<Session*>(handle);
}
