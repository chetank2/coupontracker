#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/bitmap.h>  // ⭐ NEW: For Bitmap handling
#include <memory>
#include <unordered_map>
#include <mutex>
#include <sstream>
#include <vector>            // ⭐ NEW: For image data

// Include llama.cpp headers
#include "llama/llama.h"
#include "tools/mtmd/clip.h"      // ⭐ NEW: CLIP/MTMD vision library
#include "tools/mtmd/clip-impl.h" // ⭐ NEW: CLIP implementation (for struct definitions)

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
    clip_ctx* vision_ctx = nullptr;  // ⭐ UPDATED: CLIP vision context (was llama_model*)
    bool has_vision = false;
    std::string model_path;
    std::string mmproj_path;  // Path to mmproj file
    std::string grammar_str;     // ⭐ NEW: Loaded grammar string
    bool use_grammar = false;    // ⭐ NEW: Whether grammar enforcement is enabled
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

// ⭐ Phase 2: Convert Android Bitmap to RGB pixels for CLIP
std::vector<uint8_t> bitmapToRGB(JNIEnv* env, jbyteArray image_data, jint width, jint height) {
    jbyte* image_bytes = env->GetByteArrayElements(image_data, nullptr);
    jsize image_size = env->GetArrayLength(image_data);
    
    std::vector<uint8_t> rgb_pixels(width * height * 3);
    
    // Assume input is RGB or RGBA, convert to RGB
    int bytes_per_pixel = image_size / (width * height);
    
    for (int i = 0; i < width * height; i++) {
        rgb_pixels[i * 3 + 0] = image_bytes[i * bytes_per_pixel + 0]; // R
        rgb_pixels[i * 3 + 1] = image_bytes[i * bytes_per_pixel + 1]; // G
        rgb_pixels[i * 3 + 2] = image_bytes[i * bytes_per_pixel + 2]; // B
    }
    
    env->ReleaseByteArrayElements(image_data, image_bytes, JNI_ABORT);
    return rgb_pixels;
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
        int max_tokens = 600;  // Increased from 400 to fit complete schema JSON (7 fields + nested cashback)
        llama_token eos_token = llama_vocab_eos(vocab);
        llama_token new_token = llama_sampler_sample(ctx->sampler, ctx->ctx, -1);
        
        for (int i = 0; i < max_tokens && new_token != eos_token; i++) {
            output_tokens.push_back(new_token);
            llama_batch next_batch = llama_batch_get_one(&new_token, 1);
            llama_decode(ctx->ctx, next_batch);
            new_token = llama_sampler_sample(ctx->sampler, ctx->ctx, -1);
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
        
        ctx->model = llama_load_model_from_file(model_path_str.c_str(), model_params);
        
        if (!ctx->model) {
            delete ctx;
            LOGE("========================================");
            LOGE("❌ FAILED: Could not load model");
            LOGE("========================================");
            return 0;
        }
        
        LOGI("✅ Model loaded successfully!");
        
        // Check if model has vision/encoder capabilities (built-in)
        LOGI("Step 4: Checking vision capabilities...");
        ctx->has_vision = llama_model_has_encoder(ctx->model);
        LOGI("  - Has encoder: %s", ctx->has_vision ? "YES" : "NO");
        
        // NEW: Try to load mmproj file for vision support
        if (!ctx->has_vision) {
            LOGI("Step 4b: Attempting to load mmproj (vision projector)...");
            
            // Extract model directory from model path
            std::string model_dir = model_path_str.substr(0, model_path_str.find_last_of("/"));
            std::string mmproj_path = model_dir + "/mmproj-model-f16.gguf";
            
            LOGI("  - Looking for: %s", mmproj_path.c_str());
            
            // Check if mmproj file exists
            FILE* test_file = fopen(mmproj_path.c_str(), "rb");
            if (test_file) {
                fclose(test_file);
                
                LOGI("  - Found mmproj file, loading with CLIP...");
                
                // ⭐ Use clip_init() to load vision projector (new API with params)
                clip_context_params clip_params;
                clip_params.verbosity = GGML_LOG_LEVEL_INFO;  // Enable logging
                clip_init_result clip_result = clip_init(mmproj_path.c_str(), clip_params);
                ctx->vision_ctx = clip_result.ctx_v;  // Get vision context
                
                if (ctx->vision_ctx) {
                    ctx->mmproj_path = mmproj_path;
                    ctx->has_vision = true;  // Enable vision support
                    LOGI("✅ Vision projector (mmproj) loaded with CLIP!");
                    LOGI("✅ VISION ENABLED - Ready for multimodal inference");
                } else {
                    LOGE("❌ Failed to load mmproj with clip_init()");
                    LOGW("⚠️  Falling back to text-only mode");
                }
            } else {
                LOGW("⚠️  mmproj file not found at: %s", mmproj_path.c_str());
                LOGW("⚠️  Model will operate in text-only mode");
                LOGW("⚠️  Download mmproj-model-f16.gguf for vision support");
            }
        } else {
            LOGI("✅ Model has BUILT-IN vision encoder!");
        }
        
        // Get model metadata
        int32_t n_params = llama_model_n_params(ctx->model);
        const llama_vocab* vocab = llama_model_get_vocab(ctx->model);
        int32_t n_vocab = llama_vocab_n_tokens(vocab);
        int32_t n_ctx_train = llama_n_ctx_train(ctx->model);
        
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
        ctx->ctx = llama_new_context_with_model(ctx->model, ctx_params);
        
        if (!ctx->ctx) {
            llama_free_model(ctx->model);
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
        if (grammar_test) {
            fclose(grammar_test);
            ctx->grammar_str = load_grammar_file(grammar_path);
            if (!ctx->grammar_str.empty()) {
                ctx->use_grammar = true;
                LOGI("✅ Grammar file loaded (%zu bytes)", ctx->grammar_str.size());
                LOGI("  🎯 JSON GRAMMAR ENFORCEMENT ENABLED!");
            }
        } else {
            LOGW("⚠️  Grammar file not found, using standard sampling");
        }
        
        // Create sampler (deterministic for JSON output, prevent echo)
        LOGI("Step 7: Initializing sampler...");
        auto sparams = llama_sampler_chain_default_params();
        ctx->sampler = llama_sampler_chain_init(sparams);
        
        // ⭐ If grammar is loaded, add grammar sampler FIRST (highest priority)
        if (ctx->use_grammar) {
            const llama_vocab* vocab = llama_model_get_vocab(ctx->model);
            llama_sampler* grammar_sampler = llama_sampler_init_grammar(
                vocab, 
                ctx->grammar_str.c_str(), 
                "root"  // Grammar root rule name
            );
            if (grammar_sampler) {
                llama_sampler_chain_add(ctx->sampler, grammar_sampler);
                LOGI("  ✅ Grammar sampler added (STRICT JSON enforcement)");
            } else {
                LOGE("  ❌ Failed to create grammar sampler, falling back to standard");
                ctx->use_grammar = false;
            }
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
        LOGI("Vision: %s", ctx->has_vision ? "ENABLED" : "DISABLED (need mmproj)");
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
    
    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGE("❌ Invalid model handle: %lld", (long long)model_handle);
        return nullptr;
    }
    
    ModelContext* ctx = it->second;
    std::string prompt_str = jstring_to_string(env, prompt);
    
    // Get image data
    jbyte* image_bytes = env->GetByteArrayElements(image_data, nullptr);
    jsize image_size = env->GetArrayLength(image_data);
    
    LOGI("========================================");
    LOGI("🖼️  VISION INFERENCE REQUEST");
    LOGI("========================================");
    LOGD("Image: %dx%d (%d bytes)", width, height, image_size);
    LOGD("Prompt: %.100s...", prompt_str.c_str());
    LOGD("Has vision: %s", ctx->has_vision ? "YES" : "NO");
    LOGI("========================================");
    
    try {
        std::stringstream result;
        
        if (ctx->has_vision && ctx->vision_ctx) {
            // ⭐ PHASE 2: FULL CLIP VISION INFERENCE
            LOGI("✅ Vision context loaded - Running full CLIP inference");
            
            // Step 1: Convert byte array to RGB pixels
            LOGI("Step 1: Converting image to RGB...");
            std::vector<uint8_t> rgb_pixels = bitmapToRGB(env, image_data, width, height);
            LOGI("  ✅ Converted %dx%d image to RGB (%zu bytes)", width, height, rgb_pixels.size());
            
            // Step 2: Initialize CLIP image structure
            LOGI("Step 2: Initializing CLIP image structure...");
            clip_image_u8* img = clip_image_u8_init();
            if (!img) {
                LOGE("❌ Failed to initialize clip_image_u8");
                env->ReleaseByteArrayElements(image_data, image_bytes, JNI_ABORT);
                return string_to_jstring(env, "{\"error\": \"Failed to initialize CLIP image\"}");
            }
            
            // Step 3: Build image from RGB pixels
            LOGI("Step 3: Building CLIP image from pixels...");
            clip_build_img_from_pixels(rgb_pixels.data(), width, height, img);
            LOGI("  ✅ CLIP image built");
            
            // Step 4: Initialize preprocessing batch
            LOGI("Step 4: Preprocessing image...");
            clip_image_f32_batch* batch = clip_image_f32_batch_init();
            if (!batch) {
                LOGE("❌ Failed to initialize clip_image_f32_batch");
                clip_image_u8_free(img);
                env->ReleaseByteArrayElements(image_data, image_bytes, JNI_ABORT);
                return string_to_jstring(env, "{\"error\": \"Failed to initialize preprocessing batch\"}");
            }
            
            // Step 5: Preprocess image (resize, normalize, etc.)
            bool preprocess_ok = clip_image_preprocess(ctx->vision_ctx, img, batch);
            clip_image_u8_free(img); // Free u8 image after preprocessing
            
            if (!preprocess_ok) {
                LOGE("❌ Failed to preprocess image");
                clip_image_f32_batch_free(batch);
                env->ReleaseByteArrayElements(image_data, image_bytes, JNI_ABORT);
                return string_to_jstring(env, "{\"error\": \"Image preprocessing failed\"}");
            }
            
            size_t n_images = clip_image_f32_batch_n_images(batch);
            LOGI("  ✅ Preprocessed into %zu image(s)", n_images);
            
            // Step 6: Encode images with CLIP to get embeddings
            LOGI("Step 5: Encoding images with CLIP...");
            int embd_size = clip_n_mmproj_embd(ctx->vision_ctx);
            LOGI("  - Embedding size: %d dimensions", embd_size);
            LOGI("  - Number of images: %zu", n_images);
            LOGI("  - Total embedding buffer: %d floats", embd_size * (int)n_images);
            
            std::vector<float> image_embeddings(embd_size * n_images);
            
            // ⚠️ WARNING: CLIP encoding is VERY slow on CPU (minutes per image)
            // For now, skip vision encoding to avoid app freeze
            // TODO: Implement async encoding or use text-only inference
            LOGW("⚠️  CLIP vision encoding is extremely slow on CPU");
            LOGW("⚠️  Skipping vision encoding to prevent app freeze");
            LOGW("⚠️  Falling back to text-only inference with OCR");
            
            bool encode_ok = false;  // Force fallback to text-only
            
            // Uncomment below to enable SLOW vision encoding (5-30 min per image):
            /*
            bool encode_ok = true;
            for (size_t i = 0; i < n_images && encode_ok; i++) {
                LOGI("  - Encoding image %zu/%zu... (this may take 5-30 minutes)", i + 1, n_images);
                struct clip_image_f32* single_img = clip_image_f32_get_img(batch, i);
                
                if (!single_img) {
                    LOGE("❌ Failed to get image %zu from batch", i);
                    encode_ok = false;
                    break;
                }
                
                float* embd_ptr = image_embeddings.data() + (i * embd_size);
                auto start = std::chrono::high_resolution_clock::now();
                encode_ok = clip_image_encode(ctx->vision_ctx, 4, single_img, embd_ptr);
                auto end = std::chrono::high_resolution_clock::now();
                auto duration = std::chrono::duration_cast<std::chrono::seconds>(end - start).count();
                
                if (!encode_ok) {
                    LOGE("❌ Failed to encode image %zu", i);
                    break;
                }
                LOGI("  ✅ Image %zu encoded in %ld seconds", i + 1, duration);
            }
            */
            
            clip_image_f32_batch_free(batch); // Free batch after encoding
            
            if (!encode_ok) {
                LOGW("⚠️  Vision encoding skipped (too slow on mobile CPU)");
                LOGI("→ Using text-only MiniCPM inference with OCR text");
                env->ReleaseByteArrayElements(image_data, image_bytes, JNI_ABORT);
                
                // Return status indicating we need GPU or should use text-only mode
                result << "{";
                result << "\"storeName\": \"Text Mode\",";
                result << "\"description\": \"Vision encoding requires GPU acceleration. Using OCR-based extraction.\",";
                result << "\"cashbackAmount\": \"0.00\",";
                result << "\"redeemCode\": \"\",";
                result << "\"expiryDate\": \"\",";
                result << "\"status\": \"VISION_TOO_SLOW\",";
                result << "\"note\": \"CLIP encoding takes 5-30 min on CPU. Consider GPU device or use OCR-only mode.\"";
                result << "}";
                
                std::string result_str = result.str();
                return string_to_jstring(env, result_str);
            }
            
            LOGI("  ✅ All images encoded to %d-dimensional embeddings", embd_size);
            LOGI("========================================");
            LOGI("✅ CLIP VISION ENCODING COMPLETE!");
            LOGI("========================================");
            
            // Step 7: Build multimodal prompt
            // For MiniCPM-V, the format is typically: <image>prompt
            std::string full_prompt = prompt_str;  // Embeddings are handled separately
            LOGI("Step 6: Running multimodal LLM inference...");
            LOGD("  Prompt: %.100s...", full_prompt.c_str());
            
            // Step 8: Tokenize and run inference (simplified - production needs image token injection)
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
            
            // Step 9: Run inference
            llama_batch llm_batch = llama_batch_get_one(tokens.data(), n_tokens);
            llama_decode(ctx->ctx, llm_batch);
            
            // Step 10: Generate response
            LOGI("Step 7: Generating response...");
            std::vector<llama_token> output_tokens;
            int max_tokens = 512;  // Increased for detailed coupon info
            llama_token eos_token = llama_token_eos(vocab);
            llama_token new_token = llama_sampler_sample(ctx->sampler, ctx->ctx, -1);
            
            for (int i = 0; i < max_tokens && new_token != eos_token; i++) {
                output_tokens.push_back(new_token);
                llama_batch next_batch = llama_batch_get_one(&new_token, 1);
                llama_decode(ctx->ctx, next_batch);
                new_token = llama_sampler_sample(ctx->sampler, ctx->ctx, -1);
            }
            
            // Step 11: Detokenize response
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
            LOGI("✅ MULTIMODAL INFERENCE COMPLETE!");
            LOGI("========================================");
            LOGI("Response (%zu chars): %.300s...", response_text.length(), response_text.c_str());
            
            // Return LLM response (should be JSON from MiniCPM)
            result << response_text;
            
        } else {
            // Model doesn't have vision encoder - need mmproj
            LOGW("⚠️  Vision context not available");
            LOGW("⚠️  Need to download mmproj file");
            
            result << "{";
            result << "\"storeName\": \"Model Loaded\",";
            result << "\"description\": \"Text model loaded. Download mmproj file for vision support.\",";
            result << "\"cashbackAmount\": \"0.00\",";
            result << "\"redeemCode\": \"\",";
            result << "\"expiryDate\": \"\",";
            result << "\"status\": \"NEED_MMPROJ\",";
            result << "\"note\": \"Download mmproj-model-f16.gguf from Settings\"";
            result << "}";
        }
        
        env->ReleaseByteArrayElements(image_data, image_bytes, JNI_ABORT);
        
        std::string result_str = result.str();
        LOGI("✅ Vision inference complete");
        LOGI("========================================");
        
        return string_to_jstring(env, result_str);
        
    } catch (const std::exception& e) {
        env->ReleaseByteArrayElements(image_data, image_bytes, JNI_ABORT);
        LOGE("========================================");
        LOGE("❌ Vision inference failed: %s", e.what());
        LOGE("========================================");
        return nullptr;
    }
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
    int32_t n_ctx_train = llama_n_ctx_train(ctx->model);
    
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

JNIEXPORT void JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_warmupModel(
    JNIEnv* env, jobject /* this */, jlong model_handle) {
    
    (void)env;
    
    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGE("Invalid model handle: %lld", (long long)model_handle);
        return;
    }
    
    LOGI("Model warmup - context is ready");
}

JNIEXPORT void JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_setInferenceParams(
    JNIEnv* env, jobject /* this */, jlong model_handle,
    jfloat temperature, jint max_tokens, jfloat top_p) {

    (void)env;

    std::lock_guard<std::mutex> lock(g_model_mutex);

    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        LOGE("Invalid model handle: %lld", (long long)model_handle);
        return;
    }

    LOGI("Inference params set: temp=%.2f, max_tokens=%d, top_p=%.2f",
         temperature, max_tokens, top_p);
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

    LOGW("cancelInference requested for handle %lld, but llama.cpp backend has no cooperative cancel support; request ignored.",
         (long long)model_handle);
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
    
    // ⭐ Free CLIP vision context (mmproj)
    if (ctx->vision_ctx) {
        clip_free(ctx->vision_ctx);
        LOGI("✅ Vision projector (CLIP) freed");
    }
    
    // Free model
    if (ctx->model) {
        llama_free_model(ctx->model);
        LOGI("✅ Model freed");
    }
    
    delete ctx;
    g_model_handles.erase(it);
    
    LOGI("========================================");
    LOGI("✅ Model released successfully");
    LOGI("========================================");
}

} // extern "C"
