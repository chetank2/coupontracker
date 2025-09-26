
# MiniCPM-Llama3-V2.5 Android Conversion Setup

## Prerequisites Installation

1. Install Python dependencies:
```bash
pip install torch>=2.0.0 transformers>=4.30.0 numpy pillow requests
```

2. Install MLC-LLM (for actual conversion):
```bash
pip install mlc-llm>=0.12.0
```

## Conversion Process

1. Run the conversion script:
```bash
python scripts/convert_minicpm_to_mobile.py --output-dir android_models
```

2. For dry-run (generate scripts only):
```bash
python scripts/convert_minicpm_to_mobile.py --dry-run --output-dir android_models
```

## Expected Output Structure

```
android_models/
├── cache/
│   └── base_model/          # Downloaded base model
├── quantized/               # 4-bit quantized model
├── mlc_model/              # MLC-LLM format
└── android_package/        # Final Android package
    ├── assets/models/      # Model files for Android
    ├── jni/               # JNI bindings
    └── model_manifest.json # Deployment metadata
```

## Integration with Android App

1. Copy model files to `app/src/main/assets/models/`
2. Update `LlmRuntimeManager` with actual MLC-LLM JNI calls
3. Test on device with 4GB+ RAM
4. Monitor memory usage and performance

## Troubleshooting

- **Out of memory**: Increase system RAM or use smaller batch sizes
- **Slow conversion**: Use GPU acceleration if available
- **Android integration**: Ensure MLC-LLM Android libraries are included
