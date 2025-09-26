#!/bin/bash

# Setup script for MLC-LLM Android integration
# This script prepares the environment for real MLC-LLM compilation

set -e

echo "🚀 Setting up MLC-LLM for Android development..."

# Check if MLC-LLM is installed
if ! python -c "import mlc_llm" 2>/dev/null; then
    echo "❌ MLC-LLM not found. Installing..."
    pip install mlc-llm
    echo "✅ MLC-LLM installed successfully"
else
    echo "✅ MLC-LLM already installed"
fi

# Create Android model directory
ANDROID_MODELS_DIR="android_models"
mkdir -p "$ANDROID_MODELS_DIR"

echo "📁 Created output directory: $ANDROID_MODELS_DIR"

# Download MiniCPM model if not present
MODEL_CACHE_DIR="$HOME/.cache/huggingface/hub"
MINICPM_MODEL="models--openbmb--MiniCPM-Llama3-V-2_5"

if [ ! -d "$MODEL_CACHE_DIR/$MINICPM_MODEL" ]; then
    echo "📥 Downloading MiniCPM-Llama3-V2.5 model..."
    python -c "
from transformers import AutoModel, AutoTokenizer
model = AutoModel.from_pretrained('openbmb/MiniCPM-Llama3-V-2_5', trust_remote_code=True)
tokenizer = AutoTokenizer.from_pretrained('openbmb/MiniCPM-Llama3-V-2_5')
print('Model downloaded successfully')
"
    echo "✅ Model downloaded to cache"
else
    echo "✅ MiniCPM model already cached"
fi

# Check Android NDK availability
if [ -z "$ANDROID_NDK_ROOT" ] && [ -z "$ANDROID_NDK_HOME" ]; then
    echo "⚠️  Android NDK not found in environment"
    echo "   Please set ANDROID_NDK_ROOT or ANDROID_NDK_HOME"
    echo "   Example: export ANDROID_NDK_ROOT=\$HOME/Library/Android/sdk/ndk/27.0.12077973"
else
    echo "✅ Android NDK found"
fi

# Enable MLC-LLM compilation in CMake
echo "🔧 Enabling MLC-LLM compilation..."
CMAKE_FILE="app/src/main/cpp/CMakeLists.txt"

if grep -q "# add_compile_definitions(MLC_LLM_AVAILABLE)" "$CMAKE_FILE"; then
    sed -i '' 's/# add_compile_definitions(MLC_LLM_AVAILABLE)/add_compile_definitions(MLC_LLM_AVAILABLE)/' "$CMAKE_FILE"
    echo "✅ Enabled MLC_LLM_AVAILABLE flag in CMakeLists.txt"
else
    echo "✅ MLC_LLM_AVAILABLE flag already enabled"
fi

# Uncomment MLC-LLM headers (when available)
JNI_REAL_FILE="app/src/main/cpp/mlc_llm_jni_real.cpp"
if grep -q "// #include.*mlc/" "$JNI_REAL_FILE"; then
    echo "⚠️  MLC-LLM headers still commented out in $JNI_REAL_FILE"
    echo "   Uncomment when MLC-LLM libraries are available"
fi

echo ""
echo "🎯 Setup complete! Next steps:"
echo "1. Run: python scripts/convert_minicpm_to_mobile.py"
echo "2. This will generate real Android artifacts in $ANDROID_MODELS_DIR"
echo "3. Copy generated .so files to app/src/main/jniLibs/"
echo "4. Uncomment MLC-LLM headers in $JNI_REAL_FILE"
echo "5. Build with: ./gradlew assembleDebug"
echo ""
echo "🔧 For production deployment:"
echo "   - Update ModelDownloadManager checksums with real artifact hashes"
echo "   - Test on physical Android devices with 4GB+ RAM"
echo "   - Monitor thermal performance during inference"
echo ""
echo "✅ MLC-LLM Android environment ready!"
