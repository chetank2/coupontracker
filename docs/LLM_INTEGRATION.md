# Qwen2.5 On-Device LLM OCR Integration

## Overview

This document describes the integration of the Qwen2.5-1.5B (text-only) model into the CouponTracker Android app for enhanced on-device extraction. Legacy MiniCPM hooks remain in the codebase for backward compatibility but are no longer the primary path.

## Architecture

### Core Components

1. **LlmRuntimeManager** (`app/src/main/kotlin/com/example/coupontracker/llm/LlmRuntimeManager.kt`)
   - Singleton manager for Qwen2.5 model lifecycle
   - Handles lazy loading with reference counting
   - Automatic model unloading after inactivity
   - Memory usage monitoring and optimization

2. **LocalLlmOcrService** (`app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt`)
   - Main OCR service using the Qwen2.5 text model
   - Structured JSON extraction from coupon OCR text
   - Graceful fallback to existing OCR pipeline
   - Quality validation and confidence scoring

3. **ModelDownloadManager** (`app/src/main/kotlin/com/example/coupontracker/llm/ModelDownloadManager.kt`)
   - Handles model download and verification
   - Progress tracking and WiFi-only options
   - Checksum verification for integrity
   - Storage management and cleanup

4. **SecurePreferencesManager** (Extended)
   - LLM-specific preference management
   - Model download status tracking
   - User configuration options

### Integration Points

- **ImageProcessor**: Updated to route to LLM OCR when enabled
- **Settings**: Extended with LLM preferences and controls
- **Fallback Chain**: LLM → ModelBased → Pattern → MLKit

## Model Specifications

### Qwen2.5-1.5B Configuration
- **Base Model**: `mlc-ai/Qwen2.5-1.5B-Instruct-q4f16_1`
- **Quantization**: 4-bit (q4f16_1)
- **Target Size**: ~2.0GB after quantization
- **Context Length**: 2048 tokens (mobile optimized)
- **Input Modality**: Text only (OCR output)
- **Runtime**: MLC-LLM with Vulkan/NNAPI acceleration

### Performance Expectations
- **Inference Time**: 1-2 seconds per request (average after warmup)
- **Cold Start**: 3-5 seconds (model loading)
- **Memory Usage**: ~2GB during inference
- **Minimum Requirements**: Android 8.0+, 4GB RAM

## Model Conversion Pipeline

### Prerequisites
```bash
pip install torch>=2.0.0 transformers>=4.30.0 mlc-llm>=0.12.0
```

### Conversion Process
1. **Download Base Model**: From the MLC-AI distribution of Qwen2.5
2. **Apply Mobile Optimizations**: Validate quantization and parameter configs
3. **4-bit Quantization**: (Provided by upstream build)
4. **MLC-LLM Export**: Convert/verify Android-compatible GGUF package
5. **Package for Deployment**: Create asset bundle with checksums

### Usage
```bash
# Test system readiness
python3 scripts/test_model_conversion.py

# Run conversion/export (when ready)
python3 scripts/mlc_model_builder.py --output-dir android_models
```

## Implementation Status

### ✅ Completed Components
- [x] LLM runtime management with resource control
- [x] OCR service with structured extraction
- [x] Model download and verification system
- [x] Preference management and storage
- [x] ImageProcessor integration with fallbacks
- [x] Model conversion scripts and validation
- [x] Comprehensive error handling

### 🔄 Pending Components
- [ ] UI controls for LLM settings
- [ ] Model download progress UI
- [ ] Performance monitoring dashboard
- [ ] End-to-end testing framework
- [ ] Production model deployment

## Prompt Engineering

### Coupon Extraction Prompt
The LLM uses a carefully crafted prompt for structured extraction:

```
You are an expert at extracting information from coupon images. Analyze this coupon image and extract the key information as JSON.

Return ONLY valid JSON in this exact format (no additional text):
{
    "storeName": "name of the store or brand",
    "description": "brief description of the offer including any savings/cashback details", 
    "code": "coupon or promo code if visible",
    "expiryDate": "expiry date if visible",
    "storeNameSource": "how the store name was determined (if provided)",
    "storeNameEvidence": ["OCR snippet(s) that justify the store name"],
    "needsAttention": true/false
}

Extraction rules:
- Use "Unknown" for fields that cannot be determined
- Include ₹ symbol for Indian rupee amounts
- Keep descriptions concise (max 80 characters) while preserving offer specifics
- Extract codes exactly as shown (preserve case and formatting)
- Format dates as DD/MM/YYYY when possible
- Look for common coupon elements: store logos, discount percentages, promo codes, expiry dates
- Pay attention to Indian brands: Myntra, Flipkart, Amazon, Swiggy, Zomato, PayTM, etc.

Focus on accuracy and completeness. If you're unsure about a field, use "Unknown" rather than guessing.
```

## Performance Optimizations

### Memory Management
- **Lazy Loading**: Model loads only when needed
- **Reference Counting**: Automatic unloading when unused
- **Memory Monitoring**: Track usage and prevent OOM
- **Bitmap Preprocessing**: Resize to optimal dimensions

### Inference Optimization
- **Low Temperature**: Consistent JSON output (0.1)
- **Token Limits**: Maximum 512 tokens for mobile
- **Timeout Handling**: 30-second inference limit
- **Progress Callbacks**: Non-blocking UI updates

### Fallback Strategy
```
LLM OCR (if enabled & downloaded)
    ↓ (on failure)
ModelBased OCR
    ↓ (on failure)  
Pattern Recognition
    ↓ (on failure)
ML Kit OCR
```

## Quality Validation

### Extraction Quality Scoring
- **Store Name**: 30 points (if not "Unknown Store")
- **Coupon Code**: 25 points (if present)
- **Amount**: 25 points (if present)
- **Expiry Date**: 10 points (if present)
- **Description**: 10 points (if not generic)

### Minimum Viability Check
Ensures extracted coupon has:
- Valid store name OR
- Coupon code OR
- Discount amount

## Security Considerations

### Model Integrity
- **Checksum Verification**: SHA-256 validation
- **Secure Download**: HTTPS with retry logic
- **Storage Protection**: App-private storage only

### Privacy Protection
- **On-Device Processing**: No data sent to servers
- **Local Storage**: All processing happens locally
- **No Telemetry**: Optional performance metrics only

## Development Workflow

### Testing LLM Integration
1. Enable LLM in settings (when UI is ready)
2. Download model (when download manager is ready)
3. Test with sample coupon images
4. Monitor performance and memory usage
5. Validate fallback behavior

### Debugging
- Check logs with tag `LlmRuntimeManager`
- Monitor memory usage via `getMemoryStats()`
- Validate model files with `ModelDownloadManager.getModelStatus()`
- Test fallback chain by disabling LLM

## Next Steps

1. **UI Implementation**: Add settings controls for LLM
2. **Model Deployment**: Complete actual MLC-LLM integration
3. **Performance Testing**: Benchmark on various devices
4. **User Experience**: Add progress indicators and status
5. **Production Rollout**: Feature flag and gradual deployment

## Resources

- [MiniCPM-Llama3-V2.5 Model Card](https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5)
- [MLC-LLM Documentation](https://mlc.ai/mlc-llm/)
- [Android Model Conversion Guide](android_models/SETUP_INSTRUCTIONS.md)
- [Conversion Plan](android_models/conversion_plan.json)

---

*This integration represents a significant advancement in on-device OCR capabilities, providing more accurate and contextual understanding of coupon images while maintaining complete privacy and offline functionality.*
