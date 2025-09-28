#!/usr/bin/env python3
"""
Test script for MiniCPM model conversion setup
Validates dependencies and generates conversion plan without actual conversion
"""

import os
import sys
import json
import subprocess
from pathlib import Path

def check_python_version():
    """Check if Python version is compatible"""
    version = sys.version_info
    if version.major < 3 or (version.major == 3 and version.minor < 8):
        print(f"❌ Python 3.8+ required, found {version.major}.{version.minor}")
        return False
    print(f"✅ Python version: {version.major}.{version.minor}.{version.micro}")
    return True

def check_package_availability():
    """Check if required packages are available"""
    packages = {
        'torch': 'PyTorch for model loading',
        'transformers': 'Hugging Face Transformers',
        'numpy': 'Numerical computing',
        'pillow': 'Image processing',
        'requests': 'HTTP requests for downloads'
    }
    
    available = {}
    for package, description in packages.items():
        try:
            result = subprocess.run([sys.executable, '-c', f'import {package}; print({package}.__version__)'], 
                                  capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                version = result.stdout.strip()
                available[package] = version
                print(f"✅ {package} {version} - {description}")
            else:
                available[package] = None
                print(f"❌ {package} not available - {description}")
        except Exception as e:
            available[package] = None
            print(f"❌ {package} error: {e}")
    
    return available

def check_disk_space():
    """Check available disk space"""
    try:
        statvfs = os.statvfs('.')
        free_bytes = statvfs.f_frsize * statvfs.f_bavail
        free_gb = free_bytes / (1024**3)
        
        required_gb = 10  # Minimum required space
        
        if free_gb >= required_gb:
            print(f"✅ Disk space: {free_gb:.1f} GB available")
            return True
        else:
            print(f"❌ Disk space: {free_gb:.1f} GB available, {required_gb} GB required")
            return False
    except Exception as e:
        print(f"⚠️  Could not check disk space: {e}")
        return True

def generate_conversion_plan():
    """Generate a detailed conversion plan"""
    plan = {
        "conversion_steps": [
            {
                "step": 1,
                "name": "Download Base Model",
                "description": "Download MiniCPM-Llama3-V-2.5 from Hugging Face",
                "estimated_time": "10-30 minutes",
                "estimated_size": "8 GB",
                "command": "python -c \"from transformers import AutoModel; AutoModel.from_pretrained('openbmb/MiniCPM-Llama3-V-2_5')\""
            },
            {
                "step": 2,
                "name": "Apply Mobile Optimizations",
                "description": "Configure model for mobile deployment",
                "estimated_time": "5 minutes",
                "modifications": [
                    "Reduce context length to 2048 tokens",
                    "Cap image resolution to 768x768",
                    "Enable memory-efficient attention",
                    "Configure KV cache for mobile"
                ]
            },
            {
                "step": 3,
                "name": "4-bit Quantization",
                "description": "Quantize model weights to 4-bit precision",
                "estimated_time": "30-60 minutes",
                "estimated_size_reduction": "75% (8GB -> 2GB)",
                "method": "q4f16_1 quantization"
            },
            {
                "step": 4,
                "name": "MLC-LLM Export",
                "description": "Export to MLC-LLM format for Android",
                "estimated_time": "15-30 minutes",
                "outputs": [
                    "minicpm_llm_q4f16_1.so",
                    "mlc-chat-config.json",
                    "tokenizer.json",
                    "params_shard_*.bin"
                ]
            },
            {
                "step": 5,
                "name": "Android Packaging",
                "description": "Package for Android deployment",
                "estimated_time": "5 minutes",
                "outputs": [
                    "Android asset files",
                    "JNI library bindings",
                    "Model manifest with checksums"
                ]
            }
        ],
        "requirements": {
            "python_version": "3.8+",
            "packages": [
                "torch>=2.0.0",
                "transformers>=4.30.0",
                "mlc-llm>=0.12.0",
                "numpy>=1.21.0"
            ],
            "disk_space_gb": 20,
            "ram_gb": 16,
            "estimated_total_time": "1-3 hours"
        },
        "output_specifications": {
            "final_model_size_mb": 2400,
            "target_devices": "Android 8.0+, 4GB+ RAM",
            "inference_time": "2-4 seconds per image",
            "supported_image_size": "768x768 max"
        }
    }
    
    return plan

def create_setup_instructions():
    """Create setup instructions"""
    instructions = """
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
"""
    
    return instructions

def main():
    """Main test function"""
    print("🚀 MiniCPM-Llama3-V2.5 Android Conversion Test\n")
    
    # Check system requirements
    print("📋 Checking System Requirements:")
    python_ok = check_python_version()
    disk_ok = check_disk_space()
    print()
    
    # Check package availability
    print("📦 Checking Package Availability:")
    packages = check_package_availability()
    print()
    
    # Generate conversion plan
    print("📝 Generating Conversion Plan:")
    plan = generate_conversion_plan()
    
    # Save plan to file
    plan_file = Path("android_models") / "conversion_plan.json"
    plan_file.parent.mkdir(exist_ok=True)
    
    with open(plan_file, 'w') as f:
        json.dump(plan, f, indent=2)
    
    print(f"✅ Conversion plan saved to: {plan_file}")
    
    # Create setup instructions
    instructions = create_setup_instructions()
    instructions_file = plan_file.parent / "SETUP_INSTRUCTIONS.md"
    
    with open(instructions_file, 'w') as f:
        f.write(instructions)
    
    print(f"✅ Setup instructions saved to: {instructions_file}")
    print()
    
    # Summary
    print("📊 Summary:")
    ready_packages = sum(1 for v in packages.values() if v is not None)
    total_packages = len(packages)
    
    print(f"   System Requirements: {'✅' if python_ok and disk_ok else '❌'}")
    print(f"   Package Availability: {ready_packages}/{total_packages} packages ready")
    print(f"   Estimated Conversion Time: {plan['requirements']['estimated_total_time']}")
    print(f"   Final Model Size: {plan['output_specifications']['final_model_size_mb']} MB")
    
    if ready_packages == total_packages and python_ok and disk_ok:
        print("\n🎉 System is ready for MiniCPM conversion!")
        print("   Run: python scripts/convert_minicpm_to_mobile.py --dry-run")
    else:
        print("\n⚠️  System needs additional setup before conversion")
        print("   Install missing packages and check requirements")

if __name__ == "__main__":
    main()
