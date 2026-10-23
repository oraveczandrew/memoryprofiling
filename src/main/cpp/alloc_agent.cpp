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
#include <fcntl.h>
#include <jni.h>
#include <jvmti.h>
#include <pthread.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#define LOG_TAG "AllocAgent"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr size_t kMaxBufferedEvents = 250'000;

#if defined(ALLOC_AGENT_STARTUP)
constexpr const char* kRawMagic = "AMPSRAW1";
constexpr uint32_t kRawVersion = 1;
constexpr const char* kDefaultOutput = "startup_capture.raw";
constexpr const char* kDefaultStop = "startup_capture.stop";
constexpr int kDefaultDurationMs = 30'000;
#endif

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

#if defined(ALLOC_AGENT_STARTUP)
std::atomic<bool> g_writer_stop{false};
int g_output_fd = -1;
pthread_t g_writer_thread{};
std::string g_data_dir;
std::string g_output_path;
std::string g_stop_path;
uint64_t g_start_time_ns = 0;
int g_duration_ms = kDefaultDurationMs;
uint32_t g_process_id = 0;
#endif

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
    clock_gettime(CLOCK_REALTIME, &ts);
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
                       jclass declaring_class,
                       jmethodID method,
                       const std::string& class_name,
                       std::vector<jvmtiLineNumberEntry>* out_lines) {
    char* raw_name = nullptr;
    char* raw_signature = nullptr;
    char* raw_generic = nullptr;
    std::string name;
    std::string signature;
    if (jvmti->GetMethodName(method, &raw_name, &raw_signature, &raw_generic) ==
        JVMTI_ERROR_NONE) {
        name = copy_and_free(jvmti, raw_name);
        signature = copy_and_free(jvmti, raw_signature);
    }
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(raw_generic));
    if (name.empty()) {
        name = "<unknown>";
    }

    const std::string key = class_name + '\x1f' + name + '\x1f' + signature;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_method_ids.find(key);
        if (it != g_method_ids.end()) {
            if (out_lines != nullptr) {
                *out_lines = g_methods.at(it->second).lines;
            }
            return it->second;
        }
    }

    char* raw_file = nullptr;
    std::string file;
    if (jvmti->GetSourceFileName(declaring_class, &raw_file) == JVMTI_ERROR_NONE) {
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

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_method_ids.find(key);
    if (it != g_method_ids.end()) {
        if (out_lines != nullptr) {
            *out_lines = g_methods.at(it->second).lines;
        }
        return it->second;
    }
    if (out_lines != nullptr) {
        *out_lines = lines;
    }
    const uint32_t id = g_next_method_id++;
    MethodRec record{id, intern_class(jvmti, class_name), name, file, std::move(lines)};
    g_method_ids.emplace(key, id);
    g_methods.emplace(id, record);
    g_pending.methods.push_back(std::move(record));
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
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_thread_ids.find(tid);
        if (it != g_thread_ids.end()) {
            return it->second;
        }
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

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_thread_ids.find(tid);
    if (it != g_thread_ids.end()) {
        return it->second;
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
            std::vector<jvmtiLineNumberEntry> lines;
            const uint32_t method_id = intern_method(
                    jvmti, declaring_class, frames[i].method, frame_class, &lines);
            if (env != nullptr) {
                env->DeleteLocalRef(declaring_class);
            }
            const MethodRec lookup{method_id, 0, {}, {}, lines};
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
    }

    const uint32_t thread_id = intern_thread(env, jvmti, thread);

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
                thread_id,
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

bool pending_is_empty(const Pending& pending) {
    return pending.classes.empty() && pending.methods.empty() && pending.stacks.empty() &&
           pending.threads.empty() && pending.allocs.empty();
}

std::string serialize_batch(Pending& pending, uint64_t dropped) {
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

    append_u64(out, dropped);
    return out;
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

#if defined(ALLOC_AGENT_STARTUP)
namespace {

std::string read_text_file(const std::string& path) {
    std::ifstream in(path);
    if (!in.is_open()) {
        return {};
    }
    std::string text;
    std::string line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        text += line;
        text += '\n';
    }
    return text;
}

std::string option_value(const std::string& text, const char* key) {
    const std::string prefix = std::string(key) + '=';
    size_t position = 0;
    while (position <= text.size()) {
        const size_t end = text.find('\n', position);
        const std::string line = text.substr(
                position, end == std::string::npos ? std::string::npos : end - position);
        if (line.rfind(prefix, 0) == 0) {
            return line.substr(prefix.size());
        }
        if (end == std::string::npos) {
            break;
        }
        position = end + 1;
    }
    return {};
}

int option_int(const std::string& text, const char* key, int fallback) {
    const std::string value = option_value(text, key);
    return value.empty() ? fallback : std::atoi(value.c_str());
}

bool write_all(int fd, const void* data, size_t length) {
    const char* current = static_cast<const char*>(data);
    while (length > 0) {
        const ssize_t written = write(fd, current, length);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        if (written == 0) {
            return false;
        }
        current += written;
        length -= static_cast<size_t>(written);
    }
    return true;
}

bool write_record(int fd, const std::string& payload) {
    std::string header;
    append_u32(header, static_cast<uint32_t>(payload.size()));
    return write_all(fd, header.data(), header.size()) &&
           write_all(fd, payload.data(), payload.size());
}

bool file_exists(const std::string& path) {
    return !path.empty() && access(path.c_str(), F_OK) == 0;
}

bool drain_to_file(int fd) {
    Pending pending;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        pending.classes.swap(g_pending.classes);
        pending.methods.swap(g_pending.methods);
        pending.stacks.swap(g_pending.stacks);
        pending.threads.swap(g_pending.threads);
        pending.allocs.swap(g_pending.allocs);
    }
    if (pending_is_empty(pending)) {
        return true;
    }
    return write_record(fd, serialize_batch(pending, g_dropped.load()));
}

void write_done_marker(uint64_t end_ns) {
    const std::string path = g_output_path + ".done";
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        return;
    }
    std::string marker;
    marker += "version=" + std::to_string(kRawVersion) + '\n';
    marker += "pid=" + std::to_string(g_process_id) + '\n';
    marker += "startNs=" + std::to_string(g_start_time_ns) + '\n';
    marker += "endNs=" + std::to_string(end_ns) + '\n';
    marker += "dropped=" + std::to_string(g_dropped.load()) + '\n';
    write_all(fd, marker.data(), marker.size());
    fsync(fd);
    close(fd);
}

void* startup_writer_main(void*) {
    pthread_setname_np(pthread_self(), "alloc-startup");
    while (!g_writer_stop.load(std::memory_order_relaxed)) {
        if (!drain_to_file(g_output_fd)) {
            LOGW("Unable to write the startup capture file.");
            break;
        }
        const uint64_t now = now_ns();
        const bool stop_requested = file_exists(g_stop_path);
        const bool duration_reached =
                g_duration_ms > 0 &&
                (now - g_start_time_ns) / 1'000'000ULL >= static_cast<uint64_t>(g_duration_ms);
        if (stop_requested || duration_reached) {
            break;
        }
        usleep(50'000);
    }

    g_recording.store(false);
    drain_to_file(g_output_fd);
    const uint32_t end_marker = 0;
    write_all(g_output_fd, &end_marker, sizeof(end_marker));
    fsync(g_output_fd);
    close(g_output_fd);
    g_output_fd = -1;
    unlink(g_stop_path.c_str());
    write_done_marker(now_ns());
    LOGI("Startup capture finished (dropped=%llu).",
         static_cast<unsigned long long>(g_dropped.load()));
    return nullptr;
}

}  // namespace

static jint setup_startup_agent(JavaVM* vm, const char* options) {
    const std::string args = options != nullptr ? options : std::string();
    int sample_every = 1;
    int stack_depth = 32;
    int duration_ms = kDefaultDurationMs;
    std::string output = kDefaultOutput;
    std::string stop = kDefaultStop;

    const bool has_explicit_options =
            args.find("sampleEvery=") != std::string::npos ||
            args.find("depth=") != std::string::npos ||
            args.find("durationMs=") != std::string::npos ||
            args.find("output=") != std::string::npos ||
            args.find("stopFile=") != std::string::npos;
    if (!args.empty() && !has_explicit_options) {
        g_data_dir = args;
    }

    if (has_explicit_options) {
        sample_every = option_int(args, "sampleEvery", sample_every);
        stack_depth = option_int(args, "depth", stack_depth);
        duration_ms = option_int(args, "durationMs", duration_ms);
        const std::string explicit_output = option_value(args, "output");
        if (!explicit_output.empty()) {
            output = explicit_output;
        }
        const std::string explicit_stop = option_value(args, "stopFile");
        if (!explicit_stop.empty()) {
            stop = explicit_stop;
        }
    } else if (!g_data_dir.empty()) {
        const std::string config = read_text_file(
                g_data_dir + "/code_cache/startup_capture/config");
        sample_every = option_int(config, "sampleEvery", sample_every);
        stack_depth = option_int(config, "stackDepth", stack_depth);
        duration_ms = option_int(config, "durationMs", duration_ms);
        const std::string configured_output = option_value(config, "output");
        if (!configured_output.empty()) {
            output = configured_output;
        }
        const std::string configured_stop = option_value(config, "stopFile");
        if (!configured_stop.empty()) {
            stop = configured_stop;
        }
    }

    std::string capture_dir;
    if (!g_data_dir.empty()) {
        capture_dir = g_data_dir + "/code_cache/startup_capture";
        mkdir(capture_dir.c_str(), 0700);
    }

    if (!output.empty() && output[0] != '/') {
        if (capture_dir.empty()) {
            LOGW("Startup capture needs a data directory for a relative output path.");
            return JNI_ERR;
        }
        output = capture_dir + "/" + output;
    }
    if (!stop.empty() && stop[0] != '/') {
        if (capture_dir.empty()) {
            LOGW("Startup capture needs a data directory for a relative stop path.");
            return JNI_ERR;
        }
        stop = capture_dir + "/" + stop;
    }

    g_output_path = output;
    g_stop_path = stop;
    g_duration_ms = duration_ms;
    unlink((g_output_path + ".done").c_str());

    const int fd = open(g_output_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        LOGW("Unable to open the startup capture output %s (errno=%d).",
             g_output_path.c_str(), errno);
        return JNI_ERR;
    }
    g_output_fd = fd;
    g_start_time_ns = now_ns();
    g_process_id = static_cast<uint32_t>(getpid());

    std::string header;
    header.append(kRawMagic, std::strlen(kRawMagic));
    append_u32(header, kRawVersion);
    append_u32(header, sample_every);
    append_u32(header, stack_depth);
    append_u32(header, g_process_id);
    append_u64(header, g_start_time_ns);
    append_u32(header, 0);
    if (!write_all(fd, header.data(), header.size())) {
        LOGW("Unable to write the startup capture header.");
        close(fd);
        g_output_fd = -1;
        return JNI_ERR;
    }

    const jint result = setup_agent(vm, sample_every, stack_depth);
    if (result != JNI_OK) {
        close(fd);
        g_output_fd = -1;
        return result;
    }

    g_writer_stop.store(false);
    g_recording.store(true);
    if (pthread_create(&g_writer_thread, nullptr, startup_writer_main, nullptr) != 0) {
        LOGW("Unable to start the startup capture writer thread.");
        g_recording.store(false);
        close(fd);
        g_output_fd = -1;
        return JNI_ERR;
    }

    LOGI("Startup agent attached (output=%s, sampleEvery=%d, depth=%d, durationMs=%d).",
         g_output_path.c_str(), sample_every, stack_depth, duration_ms);
    return JNI_OK;
}
#endif

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void*) {
#if defined(ALLOC_AGENT_STARTUP)
    return setup_startup_agent(vm, options);
#else
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
#endif
}

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void*) {
    return Agent_OnAttach(vm, options, nullptr);
}

#if !defined(ALLOC_AGENT_STARTUP)
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

    if (pending_is_empty(pending)) {
        return nullptr;
    }

    std::string out = serialize_batch(pending, g_dropped.load());

    auto array = env->NewByteArray(static_cast<jsize>(out.size()));
    if (array != nullptr) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(out.size()),
                                reinterpret_cast<const jbyte*>(out.data()));
    }
    return array;
}
#endif
