#include <jni.h>
#include <string>
#include <android/log.h>
#include <memory>
#include <unordered_map>
#include <mutex>
#include <sstream>
#include <vector>            // ⭐ NEW: For image data
#include <atomic>

// Include llama.cpp headers
#include "llama/llama.h"

#define LOG_TAG "MLC_LLM_JNI_REAL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Model context structure
struct ModelContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    bool has_vision = false;
    std::string model_path;
    std::string grammar_str;     // ⭐ NEW: Loaded grammar string
    bool use_grammar = false;    // ⭐ NEW: Whether grammar enforcement is enabled
    int max_tokens = 512;
    float temperature = 0.0f;
    float top_p = 1.0f;
    std::atomic<bool> cancel_requested{false};
};

// Model handle management
static std::mutex g_model_mutex;
static std::unordered_map<jlong, ModelContext*> g_model_handles;
static jlong g_next_handle = 1;
static bool g_backend_initialized = false;

// Helper function to convert jstring to std::string
std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// Helper function to create jstring from std::string
jstring string_to_jstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

// ⭐ NEW: Helper function to load grammar file from assets
std::string load_grammar_file(const std::string& grammar_path) {
    FILE* file = fopen(grammar_path.c_str(), "r");
    if (!file) {
        LOGE("❌ Failed to open grammar file: %s", grammar_path.c_str());
        return "";
    }
    
    // Get file size
    fseek(file, 0, SEEK_END);
    long file_size = ftell(file);
    fseek(file, 0, SEEK_SET);
    
    // Read entire file
    std::string grammar_str(file_size, '\0');
    size_t bytes_read = fread(&grammar_str[0], 1, file_size, file);
    fclose(file);
    
    if (bytes_read != static_cast<size_t>(file_size)) {
        LOGE("❌ Failed to read grammar file completely");
        return "";
    }
    
    LOGI("✅ Loaded grammar file: %ld bytes", file_size);
    return grammar_str;
}

void destroy_model_context(ModelContext* ctx) {
    if (!ctx) {
        return;
    }

    if (ctx->sampler) {
        llama_sampler_free(ctx->sampler);
        ctx->sampler = nullptr;
    }

    if (ctx->ctx) {
        llama_free(ctx->ctx);
        ctx->ctx = nullptr;
    }

    if (ctx->model) {
        llama_model_free(ctx->model);
        ctx->model = nullptr;
    }

    delete ctx;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_runTextInference(
    JNIEnv* env,
    jobject /* this */,
    jlong model_handle,
    jstring ocr_text,
    jstring prompt
) {
    LOGI("========================================");
    LOGI("📝 TEXT-ONLY INFERENCE REQUEST");
    LOGI("========================================");
    
    // Validate model handle
    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGE("❌ Invalid model handle: %ld", model_handle);
        return string_to_jstring(env, "{\"error\": \"Invalid model handle\"}");
    }
    
    ModelContext* ctx = it->second;
    std::string ocr_text_str = jstring_to_string(env, ocr_text);
    std::string prompt_str = jstring_to_string(env, prompt);
    ctx->cancel_requested.store(false);
    
    LOGI("OCR text length: %zu chars", ocr_text_str.length());
    LOGD("OCR text preview: %.200s...", ocr_text_str.c_str());
    LOGD("Prompt: %.100s...", prompt_str.c_str());
    LOGI("========================================");
    
    try {
        // Use prompt as-is - Kotlin side already embedded OCR text in proper ChatML format
        // Adding more text here would break the assistant primer and conversation structure
        std::string full_prompt = prompt_str;
        
        LOGI("Step 1: Tokenizing prompt (%zu chars)...", full_prompt.length());
        const llama_vocab* vocab = llama_model_get_vocab(ctx->model);
        std::vector<llama_token> tokens;
        tokens.resize(full_prompt.size() + 512);
        
        int n_tokens = llama_tokenize(
            vocab,
            full_prompt.c_str(),
            full_prompt.size(),
            tokens.data(),
            tokens.size(),
            true,  // add_bos
            true   // special tokens
        );
        
        if (n_tokens < 0) {
            tokens.resize(-n_tokens);
            n_tokens = llama_tokenize(vocab, full_prompt.c_str(), full_prompt.size(),
                                     tokens.data(), tokens.size(), true, true);
        }
        tokens.resize(n_tokens);
        LOGI("  ✅ Tokenized: %d tokens", n_tokens);
        
        // CRITICAL: Check if token count exceeds batch size
        int n_batch = llama_n_batch(ctx->ctx);
        if (n_tokens > n_batch) {
            LOGE("❌ Token count (%d) exceeds batch size (%d)", n_tokens, n_batch);
            LOGE("   This will cause llama.cpp to abort!");
            LOGE("   Try using a shorter prompt or increase batch size in code");
            return string_to_jstring(env, "{\"error\": \"Prompt too long for batch size\"}");
        }
        LOGI("  ✅ Token count (%d) fits within batch size (%d)", n_tokens, n_batch);
        
        // Step 2: Run inference
        LOGI("Step 2: Running LLM inference...");
        llama_batch llm_batch = llama_batch_get_one(tokens.data(), n_tokens);
        int decode_result = llama_decode(ctx->ctx, llm_batch);
        
        if (decode_result != 0) {
            LOGE("❌ LLM decode failed with code: %d", decode_result);
            return string_to_jstring(env, "{\"error\": \"LLM decode failed\"}");
        }
        
        LOGI("  ✅ Context processed");
        
        // Step 3: Generate response
        LOGI("Step 3: Generating response...");
        std::vector<llama_token> output_tokens;
        int max_tokens = ctx->max_tokens;
        llama_token eos_token = llama_vocab_eos(vocab);
        llama_token new_token = llama_sampler_sample(ctx->sampler, ctx->ctx, -1);
        
        for (int i = 0; i < max_tokens && new_token != eos_token; i++) {
            if (ctx->cancel_requested.load()) {
                LOGW("⚠️  Inference cancel flag detected before decoding step");
                break;
            }
            output_tokens.push_back(new_token);
            llama_batch next_batch = llama_batch_get_one(&new_token, 1);
            int decode_status = llama_decode(ctx->ctx, next_batch);
            if (decode_status != 0) {
                LOGE("❌ LLM decode failed during generation: %d", decode_status);
                break;
            }
            if (ctx->cancel_requested.load()) {
                LOGW("⚠️  Inference cancel flag detected after decoding step");
                break;
            }
            new_token = llama_sampler_sample(ctx->sampler, ctx->ctx, -1);
        }
        
        bool cancelled = ctx->cancel_requested.exchange(false);
        if (cancelled) {
            LOGW("⚠️  Inference cancelled cooperatively by caller");
        }

        LOGI("  ✅ Generated %zu tokens", output_tokens.size());
        
        // Step 4: Detokenize response
        LOGI("Step 4: Detokenizing response...");
        std::string response_text;
        response_text.resize(output_tokens.size() * 8);
        int text_len = llama_detokenize(
            vocab,
            output_tokens.data(),
            output_tokens.size(),
            &response_text[0],
            response_text.size(),
            false,
            false
        );
        response_text.resize(text_len > 0 ? text_len : 0);
        
        LOGI("========================================");
        LOGI("✅ TEXT-ONLY INFERENCE COMPLETE!");
        LOGI("========================================");
        LOGI("Response (%zu chars): %.300s...", response_text.length(), response_text.c_str());
        LOGI("========================================");
        
        // CRITICAL: Clear KV cache and reset sampler for next inference
        llama_memory_t mem = llama_get_memory(ctx->ctx);
        llama_memory_seq_rm(mem, 0, -1, -1);  // Remove all tokens from sequence 0
        llama_sampler_reset(ctx->sampler);
        LOGI("🧹 KV cache cleared and sampler reset for next inference");
        
        if (cancelled) {
            return string_to_jstring(env, "{\"error\": \"inference_cancelled\"}");
        }

        return string_to_jstring(env, response_text);
        
    } catch (const std::exception& e) {
        LOGE("❌ Text inference failed: %s", e.what());
        
        // CRITICAL: Clear KV cache and reset sampler even on error
        auto it = g_model_handles.find(model_handle);
        if (it != g_model_handles.end() && it->second->ctx) {
            llama_memory_t mem = llama_get_memory(it->second->ctx);
            llama_memory_seq_rm(mem, 0, -1, -1);
            if (it->second->sampler) {
                llama_sampler_reset(it->second->sampler);
            }
            LOGI("🧹 KV cache cleared and sampler reset after error");
        }
        
        std::string error_json = "{\"error\": \"" + std::string(e.what()) + "\"}";
        return string_to_jstring(env, error_json);
    }
}

JNIEXPORT jlong JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_initializeModel(
    JNIEnv* env, jobject /* this */, jstring model_path, jstring config_path) {
    
    std::string model_path_str = jstring_to_string(env, model_path);
    std::string config_path_str = jstring_to_string(env, config_path);
    
    LOGI("========================================");
    LOGI("🚀 Initializing REAL llama.cpp model");
    LOGI("========================================");
    LOGI("Model path: %s", model_path_str.c_str());
    
    try {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        
        // Initialize backend once
        if (!g_backend_initialized) {
            LOGI("Step 1: Initializing llama backend...");
            llama_backend_init();
            g_backend_initialized = true;
            LOGI("✅ Backend initialized");
        }
        
        // Create model context
        auto* ctx = new ModelContext();
        ctx->model_path = model_path_str;
        
        // Set model parameters
        LOGI("Step 2: Setting model parameters...");
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;     // CPU only on mobile
        model_params.use_mmap = true;      // Use mmap for efficiency
        model_params.use_mlock = false;    // Don't lock memory (Android constraint)
        model_params.check_tensors = false; // Skip tensor checks for speed
        
        LOGI("  - GPU layers: %d (CPU only)", model_params.n_gpu_layers);
        LOGI("  - Use mmap: %s", model_params.use_mmap ? "YES" : "NO");
        LOGI("✅ Parameters set");
        
        // Load the model
        LOGI("Step 3: Loading model file...");
        LOGI("  - File: %s", model_path_str.c_str());
        
        ctx->model = llama_model_load_from_file(model_path_str.c_str(), model_params);
        
        if (!ctx->model) {
            delete ctx;
            LOGE("========================================");
            LOGE("❌ FAILED: Could not load model");
            LOGE("========================================");
            return 0;
        }
        
        LOGI("✅ Model loaded successfully!");
        
        LOGI("Step 4: Configuring text-only Qwen runtime...");
        ctx->has_vision = false;
        LOGI("Vision: DISABLED (Qwen2.5 text-only model)");
        
        // Get model metadata
        int32_t n_params = llama_model_n_params(ctx->model);
        const llama_vocab* vocab = llama_model_get_vocab(ctx->model);
        int32_t n_vocab = llama_vocab_n_tokens(vocab);
        int32_t n_ctx_train = llama_model_n_ctx_train(ctx->model);
        
        LOGI("Step 5: Model metadata:");
        LOGI("  - Parameters: %d million", n_params / 1000000);
        LOGI("  - Vocab size: %d", n_vocab);
        LOGI("  - Context (train): %d", n_ctx_train);
        
        // Create context parameters
        LOGI("Step 6: Creating inference context...");
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = 1024;           // Increased: Prompt w/ JSON example = ~550 tokens
        ctx_params.n_batch = 1024;         // CRITICAL: Must be >= prompt tokens to avoid SIGABRT
        ctx_params.n_threads = 4;          // CPU threads
        ctx_params.n_threads_batch = 4;    // Batch threads
        
        LOGI("  - Context size: %d", ctx_params.n_ctx);
        LOGI("  - Batch size: %d", ctx_params.n_batch);
        LOGI("  - Threads: %d", ctx_params.n_threads);
        
        // Create inference context
        ctx->ctx = llama_init_from_model(ctx->model, ctx_params);
        
        if (!ctx->ctx) {
            llama_model_free(ctx->model);
            delete ctx;
            LOGE("========================================");
            LOGE("❌ FAILED: Could not create inference context");
            LOGE("========================================");
            return 0;
        }
        
        LOGI("✅ Inference context created");
        
        // ⭐ Step 6.5: Load grammar file (if available)
        std::string model_dir_str = model_path_str.substr(0, model_path_str.find_last_of("/"));
        std::string grammar_path = model_dir_str + "/coupon_schema.gbnf";
        
        LOGI("Step 6.5: Checking for grammar file...");
        LOGI("  - Looking for: %s", grammar_path.c_str());
        
        FILE* grammar_test = fopen(grammar_path.c_str(), "r");
        if (!grammar_test) {
            LOGE("❌ Grammar file not found at: %s", grammar_path.c_str());
            destroy_model_context(ctx);
            LOGE("========================================");
            LOGE("❌ FAILED: Missing grammar file");
            LOGE("========================================");
            return 0;
        }

        fclose(grammar_test);
        ctx->grammar_str = load_grammar_file(grammar_path);
        if (ctx->grammar_str.empty()) {
            LOGE("❌ Grammar file empty or unreadable: %s", grammar_path.c_str());
            destroy_model_context(ctx);
            LOGE("========================================");
            LOGE("❌ FAILED: Grammar load error");
            LOGE("========================================");
            return 0;
        }

        ctx->use_grammar = true;
        LOGI("✅ Grammar file loaded (%zu bytes)", ctx->grammar_str.size());
        LOGI("  🎯 JSON GRAMMAR ENFORCEMENT ENABLED!");
        
        // Create sampler (deterministic for JSON output, prevent echo)
        LOGI("Step 7: Initializing sampler...");
        auto sparams = llama_sampler_chain_default_params();
        ctx->sampler = llama_sampler_chain_init(sparams);
        if (!ctx->sampler) {
            LOGE("❌ Failed to initialize sampler chain");
            destroy_model_context(ctx);
            LOGE("========================================");
            LOGE("❌ FAILED: Sampler initialization error");
            LOGE("========================================");
            return 0;
        }
        
        // ⭐ If grammar is loaded, add grammar sampler FIRST (highest priority)
        if (ctx->use_grammar) {
            const llama_vocab* vocab = llama_model_get_vocab(ctx->model);
            llama_sampler* grammar_sampler = llama_sampler_init_grammar(
                vocab, 
                ctx->grammar_str.c_str(), 
                "root"  // Grammar root rule name
            );
            if (!grammar_sampler) {
                LOGE("❌ Failed to create grammar sampler for grammar root 'root'");
                destroy_model_context(ctx);
                LOGE("========================================");
                LOGE("❌ FAILED: Grammar sampler initialization error");
                LOGE("========================================");
                return 0;
            }
            llama_sampler_chain_add(ctx->sampler, grammar_sampler);
            LOGI("  ✅ Grammar sampler added (STRICT JSON enforcement)");
        }
        
        // Add standard samplers (grammar overrides these if enabled)
        llama_sampler_chain_add(ctx->sampler,
            llama_sampler_init_penalties(
                1024,      // penalty_last_n: large window to avoid repeating prompt tokens
                1.35f,     // penalty_repeat: VERY strong to prevent "Cashback Details:" echo
                0.0f,      // penalty_freq
                0.0f       // penalty_present
            ));
        llama_sampler_chain_add(ctx->sampler,
            llama_sampler_init_top_p(1.0f, 1));   // No top-p filtering (greedy mode)
        llama_sampler_chain_add(ctx->sampler,
            llama_sampler_init_temp(0.0f));       // temp=0 → GREEDY (most probable token only)
        llama_sampler_chain_add(ctx->sampler,
            llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        
        if (ctx->use_grammar) {
            LOGI("✅ Sampler initialized (GRAMMAR + temp=0.0, repeat_penalty=1.35)");
        } else {
            LOGI("✅ Sampler initialized (temp=0.0 GREEDY, top_p=1.0, repeat_penalty=1.35, last_n=1024)");
        }
        
        // Store context and return handle
        jlong handle = g_next_handle++;
        g_model_handles[handle] = ctx;
        
        LOGI("========================================");
        LOGI("🎉 MODEL INITIALIZATION COMPLETE!");
        LOGI("========================================");
        LOGI("Handle: %lld", (long long)handle);
        LOGI("Status: READY FOR INFERENCE");
        LOGI("Vision: DISABLED (text-only Qwen runtime)");
        LOGI("========================================");
        
        return handle;
        
    } catch (const std::exception& e) {
        LOGE("========================================");
        LOGE("❌ EXCEPTION: %s", e.what());
        LOGE("========================================");
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_runVisionInference(
    JNIEnv* env, jobject /* this */, jlong model_handle, jbyteArray image_data,
    jint width, jint height, jstring prompt) {
    
    (void)image_data;
    (void)width;
    (void)height;
    (void)prompt;

    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGE("❌ Invalid model handle: %lld", (long long)model_handle);
        return nullptr;
    }
    LOGW("Vision inference is disabled in the Qwen2.5 text-only backend");
    return string_to_jstring(env,
        "{\"error\":\"vision_disabled\",\"message\":\"Use OCR plus runTextInference for Qwen2.5\"}");
}

JNIEXPORT jstring JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_getModelInfo(
    JNIEnv* env, jobject /* this */, jlong model_handle) {
    
    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGE("Invalid model handle: %lld", (long long)model_handle);
        return nullptr;
    }
    
    ModelContext* ctx = it->second;
    
    // Get actual model info from llama.cpp
    int32_t n_params = llama_model_n_params(ctx->model);
    const llama_vocab* vocab = llama_model_get_vocab(ctx->model);
    int32_t n_vocab = llama_vocab_n_tokens(vocab);
    int32_t n_ctx_train = llama_model_n_ctx_train(ctx->model);
    
    std::stringstream info;
    info << "{";
    info << "\"name\": \"MiniCPM-Llama3-V2.5\",";
    info << "\"version\": \"2.5.0\",";
    info << "\"quantization\": \"Q4_K_M\",";
    info << "\"parameters\": \"" << (n_params / 1000000) << "M\",";
    info << "\"vocab_size\": " << n_vocab << ",";
    info << "\"context_length\": " << n_ctx_train << ",";
    info << "\"vision_enabled\": " << (ctx->has_vision ? "true" : "false") << ",";
    info << "\"model_path\": \"" << ctx->model_path << "\",";
    info << "\"status\": \"REAL_LLAMA_CPP\"";
    info << "}";
    
    return string_to_jstring(env, info.str());
}

JNIEXPORT jlong JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_getMemoryUsage(
    JNIEnv* env, jobject /* this */, jlong model_handle) {
    
    (void)env;
    
    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGE("Invalid model handle: %lld", (long long)model_handle);
        return -1;
    }
    
    ModelContext* ctx = it->second;
    
    // Get actual memory usage from llama.cpp
    size_t mem_size = llama_state_get_size(ctx->ctx);
    
    return static_cast<jlong>(mem_size);
}

JNIEXPORT jboolean JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_warmupModel(
    JNIEnv* env, jobject /* this */, jlong model_handle) {
    
    (void)env;
    
    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGE("Invalid model handle: %lld", (long long)model_handle);
        return JNI_FALSE;
    }
    
    LOGI("Model warmup - context is ready");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_setInferenceParams(
    JNIEnv* env, jobject /* this */, jlong model_handle,
    jfloat temperature, jint max_tokens, jfloat top_p) {

    (void)env;

    std::lock_guard<std::mutex> lock(g_model_mutex);

    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGE("Invalid model handle: %lld", (long long)model_handle);
        return JNI_FALSE;
    }

    ModelContext* ctx = it->second;
    if (max_tokens > 0) {
        ctx->max_tokens = max_tokens;
    }
    ctx->temperature = temperature;
    ctx->top_p = top_p;

    LOGI("Inference params set: temp=%.2f, max_tokens=%d, top_p=%.2f",
         ctx->temperature, ctx->max_tokens, ctx->top_p);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_cancelInference(
    JNIEnv* env, jobject /* this */, jlong model_handle) {

    (void)env;

    std::lock_guard<std::mutex> lock(g_model_mutex);

    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGW("cancelInference called with unknown handle: %lld", (long long)model_handle);
        return;
    }

    it->second->cancel_requested.store(true);
    LOGW("cancelInference requested for handle %lld – cooperative flag set", (long long)model_handle);
}

JNIEXPORT jboolean JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_isInferenceRunning(
    JNIEnv* env, jobject /* this */, jlong model_handle) {

    (void)env;
    (void)model_handle;

    // The llama.cpp backend processes requests synchronously; no async state to report.
    return JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_getInferenceProgress(
    JNIEnv* env, jobject /* this */, jlong model_handle) {

    (void)env;
    (void)model_handle;

    // Progress reporting is unsupported for synchronous text inference; return 0.0.
    return 0.0f;
}

JNIEXPORT void JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_releaseModel(
    JNIEnv* env, jobject /* this */, jlong model_handle) {
    
    (void)env;
    
    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGE("Invalid model handle: %lld", (long long)model_handle);
        return;
    }
    
    ModelContext* ctx = it->second;
    
    LOGI("========================================");
    LOGI("🔄 Releasing model (handle: %lld)", (long long)model_handle);
    LOGI("========================================");
    
    // Free sampler
    if (ctx->sampler) {
        llama_sampler_free(ctx->sampler);
        LOGI("✅ Sampler freed");
    }
    
    // Free context
    if (ctx->ctx) {
        llama_free(ctx->ctx);
        LOGI("✅ Context freed");
    }
    
    // Free model
    if (ctx->model) {
        llama_model_free(ctx->model);
        LOGI("✅ Model freed");
    }
    
    delete ctx;
    g_model_handles.erase(it);
    
    LOGI("========================================");
    LOGI("✅ Model released successfully");
    LOGI("========================================");
}

} // extern "C"
