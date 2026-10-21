/*
 *    Copyright 2026 András Oravecz <info@oandras.hu>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

#include <android/log.h>
#include <jni.h>
#include <jvmti.h>
#include <pthread.h>
#include <time.h>

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#define LOG_TAG "AllocAgent"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr size_t kMaxBufferedEvents = 2'000'000;

struct ClassRec {
    uint32_t id;
    std::string name;
};

struct MethodRec {
    uint32_t id;
    uint32_t class_id;
    std::string name;
    std::string file;
    std::vector<jvmtiLineNumberEntry> lines;
};

struct Frame {
    uint32_t method_id;
    int32_t line;
};

struct StackRec {
    uint32_t id;
    std::vector<Frame> frames;
};

struct ThreadRec {
    uint32_t id;
    uint64_t tid;
    std::string name;
};

struct AllocRec {
    uint32_t class_id;
    uint64_t size;
    uint32_t length;
    uint32_t thread_id;
    uint32_t stack_id;
    uint64_t timestamp_ns;
};

struct Pending {
    std::vector<ClassRec> classes;
    std::vector<MethodRec> methods;
    std::vector<StackRec> stacks;
    std::vector<ThreadRec> threads;
    std::vector<AllocRec> allocs;
};

std::mutex g_mutex;
Pending g_pending;

std::unordered_map<std::string, uint32_t> g_class_ids;
std::unordered_map<std::string, uint32_t> g_method_ids;
std::unordered_map<uint32_t, MethodRec> g_methods;
std::unordered_map<std::string, uint32_t> g_stack_ids;
std::unordered_map<uint64_t, uint32_t> g_thread_ids;

uint32_t g_next_class_id = 1;
uint32_t g_next_method_id = 1;
uint32_t g_next_stack_id = 1;
uint32_t g_next_thread_id = 1;

jvmtiEnv* g_jvmti = nullptr;
std::atomic<bool> g_attached{false};
std::atomic<bool> g_recording{false};
std::atomic<int> g_sample_every{1};
std::atomic<int> g_stack_depth{32};
std::atomic<uint64_t> g_dropped{0};

thread_local bool tls_ignore = false;
thread_local bool tls_in_callback = false;
thread_local int tls_sample_countdown = 0;
thread_local int tls_sample_every = 1;

struct CallbackGuard {
    CallbackGuard() { tls_in_callback = true; }
    ~CallbackGuard() { tls_in_callback = false; }
};

uint64_t now_ns() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1'000'000'000ULL
           + static_cast<uint64_t>(ts.tv_nsec);
}

std::string copy_and_free(jvmtiEnv* jvmti, char* value) {
    if (value == nullptr) {
        return {};
    }
    std::string result(value);
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(value));
    return result;
}

int line_for(const MethodRec& method, jlocation location) {
    int line = 0;
    for (const auto& entry : method.lines) {
        if (entry.start_location <= location) {
            line = static_cast<int>(entry.line_number);
        } else {
            break;
        }
    }
    return line;
}

uint32_t intern_class(jvmtiEnv* jvmti, const std::string& name) {
    auto it = g_class_ids.find(name);
    if (it != g_class_ids.end()) {
        return it->second;
    }
    const uint32_t id = g_next_class_id++;
    g_class_ids.emplace(name, id);
    g_pending.classes.push_back(ClassRec{id, name});
    return id;
}

uint32_t intern_method(jvmtiEnv* jvmti,
                       jclass klass,
                       jmethodID method,
                       const std::string& class_name,
                       std::string* out_name,
                       std::string* out_file,
                       std::vector<jvmtiLineNumberEntry>* out_lines) {
    char* raw_name = nullptr;
    char* raw_signature = nullptr;
    char* raw_generic = nullptr;
    std::string name;
    if (jvmti->GetMethodName(method, &raw_name, &raw_signature, &raw_generic) ==
        JVMTI_ERROR_NONE) {
        name = copy_and_free(jvmti, raw_name);
    }
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(raw_signature));
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(raw_generic));
    if (name.empty()) {
        name = "<unknown>";
    }

    const std::string key = class_name + '\x1f' + name;
    auto it = g_method_ids.find(key);
    if (it != g_method_ids.end()) {
        const uint32_t id = it->second;
        const auto& rec = g_methods.at(id);
        *out_name = rec.name;
        *out_file = rec.file;
        *out_lines = rec.lines;
        return id;
    }

    char* raw_file = nullptr;
    std::string file;
    if (jvmti->GetSourceFileName(klass, &raw_file) == JVMTI_ERROR_NONE) {
        file = copy_and_free(jvmti, raw_file);
    }

    std::vector<jvmtiLineNumberEntry> lines;
    jint line_count = 0;
    jvmtiLineNumberEntry* raw_lines = nullptr;
    if (jvmti->GetLineNumberTable(method, &line_count, &raw_lines) == JVMTI_ERROR_NONE) {
        if (raw_lines != nullptr && line_count > 0) {
            lines.assign(raw_lines, raw_lines + line_count);
        }
        if (raw_lines != nullptr) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(raw_lines));
        }
    }

    const uint32_t id = g_next_method_id++;
    g_method_ids.emplace(key, id);
    MethodRec record{id, intern_class(jvmti, class_name), name, file, lines};
    g_methods.emplace(id, record);
    g_pending.methods.push_back(std::move(record));
    *out_name = name;
    *out_file = file;
    *out_lines = lines;
    return id;
}

uint32_t intern_stack(const std::vector<Frame>& frames) {
    std::string key;
    key.reserve(frames.size() * sizeof(Frame));
    for (const auto& frame : frames) {
        key.append(reinterpret_cast<const char*>(&frame.method_id), sizeof(frame.method_id));
        key.append(reinterpret_cast<const char*>(&frame.line), sizeof(frame.line));
    }
    auto it = g_stack_ids.find(key);
    if (it != g_stack_ids.end()) {
        return it->second;
    }
    const uint32_t id = g_next_stack_id++;
    g_stack_ids.emplace(key, id);
    g_pending.stacks.push_back(StackRec{id, frames});
    return id;
}

uint32_t intern_thread(JNIEnv* env, jvmtiEnv* jvmti, jthread thread) {
    const uint64_t tid = static_cast<uint64_t>(pthread_self());
    auto it = g_thread_ids.find(tid);
    if (it != g_thread_ids.end()) {
        return it->second;
    }

    std::string name;
    jvmtiThreadInfo info{};
    if (jvmti->GetThreadInfo(thread, &info) == JVMTI_ERROR_NONE) {
        if (info.name != nullptr) {
            name = info.name;
        }
        jvmti->Deallocate(reinterpret_cast<unsigned char*>(info.name));
        if (env != nullptr) {
            if (info.thread_group != nullptr) {
                env->DeleteLocalRef(info.thread_group);
            }
            if (info.context_class_loader != nullptr) {
                env->DeleteLocalRef(info.context_class_loader);
            }
        }
    }
    if (name.empty()) {
        name = "<unnamed>";
    }

    const uint32_t id = g_next_thread_id++;
    g_thread_ids.emplace(tid, id);
    g_pending.threads.push_back(ThreadRec{id, tid, name});
    return id;
}

void JNICALL on_vm_object_alloc(jvmtiEnv* jvmti,
                                JNIEnv* env,
                                jthread thread,
                                jobject object,
                                jclass klass,
                                jlong size) {
    if (!g_recording.load(std::memory_order_relaxed) || tls_ignore || tls_in_callback) {
        return;
    }
    CallbackGuard callback_guard;

    const int every = g_sample_every.load(std::memory_order_relaxed);
    if (every != tls_sample_every) {
        tls_sample_countdown = 0;
        tls_sample_every = every;
    }
    if (every > 1) {
        if (++tls_sample_countdown < every) {
            return;
        }
        tls_sample_countdown = 0;
    }

    char* raw_signature = nullptr;
    std::string class_name;
    if (klass != nullptr &&
        jvmti->GetClassSignature(klass, &raw_signature, nullptr) == JVMTI_ERROR_NONE) {
        class_name = copy_and_free(jvmti, raw_signature);
    } else {
        class_name = "<unknown>";
    }

    const int depth_limit = g_stack_depth.load(std::memory_order_relaxed);
    std::vector<jvmtiFrameInfo> frames(static_cast<size_t>(depth_limit > 0 ? depth_limit : 1));
    jint frame_count = 0;
    std::vector<Frame> stack;
    if (depth_limit > 0 &&
        jvmti->GetStackTrace(thread, 0, depth_limit, frames.data(), &frame_count) ==
                JVMTI_ERROR_NONE) {
        stack.reserve(static_cast<size_t>(frame_count));
        for (jint i = 0; i < frame_count; ++i) {
            jclass declaring_class = nullptr;
            if (jvmti->GetMethodDeclaringClass(frames[i].method, &declaring_class) !=
                JVMTI_ERROR_NONE) {
                continue;
            }
            char* raw_class = nullptr;
            std::string frame_class;
            if (jvmti->GetClassSignature(declaring_class, &raw_class, nullptr) ==
                JVMTI_ERROR_NONE) {
                frame_class = copy_and_free(jvmti, raw_class);
            }
            std::string method_name;
            std::string file;
            std::vector<jvmtiLineNumberEntry> lines;
            uint32_t method_id = 0;
            {
                std::lock_guard<std::mutex> lock(g_mutex);
                method_id = intern_method(jvmti, declaring_class, frames[i].method, frame_class,
                                          &method_name, &file, &lines);
            }
            if (env != nullptr) {
                env->DeleteLocalRef(declaring_class);
            }
            const MethodRec lookup{method_id, 0, method_name, file, lines};
            stack.push_back(Frame{method_id, line_for(lookup, frames[i].location)});
        }
    }

    uint32_t length = 0;
    if (env != nullptr && object != nullptr && !class_name.empty() && class_name[0] == '[') {
        length = static_cast<uint32_t>(env->GetArrayLength(reinterpret_cast<jarray>(object)));
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_pending.allocs.size() >= kMaxBufferedEvents) {
            g_dropped.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        g_pending.allocs.push_back(AllocRec{
                intern_class(jvmti, class_name),
                static_cast<uint64_t>(size),
                length,
                intern_thread(env, jvmti, thread),
                intern_stack(stack),
                now_ns(),
        });
    }
}

void append_u32(std::string& out, uint32_t value) {
    out.push_back(static_cast<char>(value & 0xFF));
    out.push_back(static_cast<char>((value >> 8) & 0xFF));
    out.push_back(static_cast<char>((value >> 16) & 0xFF));
    out.push_back(static_cast<char>((value >> 24) & 0xFF));
}

void append_u64(std::string& out, uint64_t value) {
    for (int i = 0; i < 8; ++i) {
        out.push_back(static_cast<char>((value >> (i * 8)) & 0xFF));
    }
}

void append_bytes(std::string& out, const void* data, size_t length) {
    out.append(static_cast<const char*>(data), length);
}

void append_string(std::string& out, const std::string& value) {
    append_u32(out, static_cast<uint32_t>(value.size()));
    append_bytes(out, value.data(), value.size());
}

}  // namespace

static jint setup_agent(JavaVM* vm, int sample_every, int stack_depth) {
    jvmtiEnv* jvmti = nullptr;
    jint result = vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_1);
    if (result != JNI_OK || jvmti == nullptr) {
        LOGW("JVMTI is not available (code %d).", result);
        return JNI_ERR;
    }

    jvmtiCapabilities caps{};
    caps.can_generate_vm_object_alloc_events = 1;
    caps.can_get_line_numbers = 1;
    caps.can_get_source_file_name = 1;
    if (jvmti->AddCapabilities(&caps) != JVMTI_ERROR_NONE) {
        LOGW("Unable to acquire JVMTI capabilities.");
        return JNI_ERR;
    }

    static jvmtiEventCallbacks callbacks{};
    callbacks.VMObjectAlloc = on_vm_object_alloc;
    if (jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) {
        LOGW("Unable to set JVMTI callbacks.");
        return JNI_ERR;
    }
    if (jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_OBJECT_ALLOC, nullptr) !=
        JVMTI_ERROR_NONE) {
        LOGW("Unable to enable VMObjectAlloc.");
        return JNI_ERR;
    }

    g_jvmti = jvmti;
    g_sample_every.store(sample_every > 0 ? sample_every : 1);
    g_stack_depth.store(stack_depth >= 0 ? stack_depth : 0);
    g_attached.store(true);
    LOGI("Agent attached (sampleEvery=%d, depth=%d).", sample_every, stack_depth);
    return JNI_OK;
}

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void*) {
    int sample_every = 1;
    int stack_depth = 32;
    if (options != nullptr) {
        std::string args(options);
        size_t pos = args.find("sampleEvery=");
        if (pos != std::string::npos) {
            sample_every = std::atoi(args.c_str() + pos + 12);
        }
        pos = args.find("depth=");
        if (pos != std::string::npos) {
            stack_depth = std::atoi(args.c_str() + pos + 6);
        }
    }
    return setup_agent(vm, sample_every, stack_depth);
}

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void*) {
    return Agent_OnAttach(vm, options, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_hu_oandras_memoryprofiling_internal_NativeAllocAgent_nativeConfigure(JNIEnv*,
                                                                         jclass,
                                                                         jint sample_every,
                                                                         jint stack_depth) {
    g_sample_every.store(sample_every > 0 ? sample_every : 1);
    g_stack_depth.store(stack_depth >= 0 ? stack_depth : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_hu_oandras_memoryprofiling_internal_NativeAllocAgent_nativeStart(JNIEnv*, jclass) {
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_class_ids.clear();
        g_method_ids.clear();
        g_methods.clear();
        g_stack_ids.clear();
        g_thread_ids.clear();
        g_next_class_id = 1;
        g_next_method_id = 1;
        g_next_stack_id = 1;
        g_next_thread_id = 1;
        g_pending = Pending{};
        g_dropped.store(0, std::memory_order_relaxed);
    }
    g_recording.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_hu_oandras_memoryprofiling_internal_NativeAllocAgent_nativeStop(JNIEnv*, jclass) {
    g_recording.store(false);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_hu_oandras_memoryprofiling_internal_NativeAllocAgent_nativeIsAttached(JNIEnv*, jclass) {
    return g_attached.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_hu_oandras_memoryprofiling_internal_NativeAllocAgent_nativeIsRecording(JNIEnv*, jclass) {
    return g_recording.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_hu_oandras_memoryprofiling_internal_NativeAllocAgent_nativeDropped(JNIEnv*, jclass) {
    return static_cast<jlong>(g_dropped.load());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_hu_oandras_memoryprofiling_internal_NativeAllocAgent_nativeDrain(JNIEnv* env, jclass) {
    tls_ignore = true;

    Pending pending;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        pending.classes.swap(g_pending.classes);
        pending.methods.swap(g_pending.methods);
        pending.stacks.swap(g_pending.stacks);
        pending.threads.swap(g_pending.threads);
        pending.allocs.swap(g_pending.allocs);
    }

    if (pending.classes.empty() && pending.methods.empty() && pending.stacks.empty() &&
        pending.threads.empty() && pending.allocs.empty()) {
        return nullptr;
    }

    std::string out;
    out.reserve(pending.allocs.size() * 32 + 1024);

    append_u32(out, static_cast<uint32_t>(pending.classes.size()));
    for (const auto& rec : pending.classes) {
        append_u32(out, rec.id);
        append_string(out, rec.name);
    }

    append_u32(out, static_cast<uint32_t>(pending.methods.size()));
    for (const auto& rec : pending.methods) {
        append_u32(out, rec.id);
        append_u32(out, rec.class_id);
        append_string(out, rec.name);
        append_string(out, rec.file);
    }

    append_u32(out, static_cast<uint32_t>(pending.stacks.size()));
    for (const auto& rec : pending.stacks) {
        append_u32(out, rec.id);
        append_u32(out, static_cast<uint32_t>(rec.frames.size()));
        for (const auto& frame : rec.frames) {
            append_u32(out, frame.method_id);
            append_u32(out, static_cast<uint32_t>(frame.line));
        }
    }

    append_u32(out, static_cast<uint32_t>(pending.threads.size()));
    for (const auto& rec : pending.threads) {
        append_u32(out, rec.id);
        append_u64(out, rec.tid);
        append_string(out, rec.name);
    }

    append_u32(out, static_cast<uint32_t>(pending.allocs.size()));
    for (const auto& rec : pending.allocs) {
        append_u32(out, rec.class_id);
        append_u64(out, rec.size);
        append_u32(out, rec.length);
        append_u32(out, rec.thread_id);
        append_u32(out, rec.stack_id);
        append_u64(out, rec.timestamp_ns);
    }

    append_u64(out, g_dropped.load());

    auto array = env->NewByteArray(static_cast<jsize>(out.size()));
    if (array != nullptr) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(out.size()),
                                reinterpret_cast<const jbyte*>(out.data()));
    }
    return array;
}
