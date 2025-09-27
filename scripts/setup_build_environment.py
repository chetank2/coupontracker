#!/usr/bin/env python3
"""
MiniCPM Build Environment Setup
Sets up the Python environment and dependencies for MLC-LLM conversion
"""

import os
import sys
import subprocess
import platform
from pathlib import Path

def check_python_version():
    """Check if Python version is >= 3.8"""
    version = sys.version_info
    if version.major < 3 or (version.major == 3 and version.minor < 8):
        print(f"❌ Python {version.major}.{version.minor} detected. Need Python >= 3.8")
        return False
    
    print(f"✅ Python {version.major}.{version.minor}.{version.micro} detected")
    return True

def check_disk_space():
    """Check available disk space"""
    import shutil
    free_space_gb = shutil.disk_usage('.').free / (1024**3)
    
    if free_space_gb < 20:
        print(f"❌ Insufficient disk space: {free_space_gb:.1f}GB (need 20GB+)")
        return False
    
    print(f"✅ Disk space: {free_space_gb:.1f}GB available")
    return True

def check_memory():
    """Check available RAM"""
    try:
        import psutil
        memory_gb = psutil.virtual_memory().total / (1024**3)
        
        if memory_gb < 16:
            print(f"⚠️ Limited RAM: {memory_gb:.1f}GB (recommended: 16GB+)")
            print("   Model conversion may be slow or fail")
        else:
            print(f"✅ RAM: {memory_gb:.1f}GB available")
        
        return True
    except ImportError:
        print("⚠️ Cannot check RAM (psutil not available)")
        return True

def setup_virtual_environment():
    """Create and activate virtual environment"""
    venv_path = Path(".venv")
    
    if venv_path.exists():
        print("✅ Virtual environment already exists")
        return True
    
    try:
        print("🔧 Creating virtual environment...")
        subprocess.run([sys.executable, "-m", "venv", ".venv"], check=True)
        print("✅ Virtual environment created")
        return True
    except subprocess.CalledProcessError as e:
        print(f"❌ Failed to create virtual environment: {e}")
        return False

def install_dependencies():
    """Install required Python packages"""
    
    # Determine pip executable path
    if platform.system() == "Windows":
        pip_exe = Path(".venv/Scripts/pip.exe")
        python_exe = Path(".venv/Scripts/python.exe")
    else:
        pip_exe = Path(".venv/bin/pip")
        python_exe = Path(".venv/bin/python")
    
    if not pip_exe.exists():
        print(f"❌ Pip not found at {pip_exe}")
        return False
    
    # Required packages with specific versions
    packages = [
        "torch==2.3.1",
        "transformers==4.41.2", 
        "mlc-llm==0.13.0",
        "numpy",
        "pillow",
        "requests",
        "psutil"  # For memory checking
    ]
    
    try:
        print("📦 Installing dependencies...")
        
        # Upgrade pip first
        subprocess.run([str(pip_exe), "install", "--upgrade", "pip"], check=True)
        
        # Install packages
        cmd = [str(pip_exe), "install"] + packages
        subprocess.run(cmd, check=True)
        
        print("✅ Dependencies installed successfully")
        
        # Verify imports
        print("🔍 Verifying package imports...")
        test_imports = ["torch", "transformers", "mlc_llm", "numpy", "PIL"]
        
        for pkg in test_imports:
            try:
                subprocess.run([
                    str(python_exe), "-c", f"import {pkg}; print(f'✅ {pkg} imported successfully')"
                ], check=True, capture_output=True)
            except subprocess.CalledProcessError:
                print(f"❌ Failed to import {pkg}")
                return False
        
        print("✅ All packages verified")
        return True
        
    except subprocess.CalledProcessError as e:
        print(f"❌ Failed to install dependencies: {e}")
        return False

def check_system_tools():
    """Check for required system tools"""
    tools = ["cmake", "ninja", "clang"]
    missing_tools = []
    
    for tool in tools:
        try:
            subprocess.run([tool, "--version"], capture_output=True, check=True)
            print(f"✅ {tool} available")
        except (subprocess.CalledProcessError, FileNotFoundError):
            missing_tools.append(tool)
            print(f"❌ {tool} missing")
    
    if missing_tools:
        print(f"\n⚠️ Missing system tools: {', '.join(missing_tools)}")
        print("Install with:")
        
        if platform.system() == "Darwin":  # macOS
            print(f"   brew install {' '.join(missing_tools)}")
        elif platform.system() == "Linux":
            print(f"   sudo apt-get install -y {' '.join(missing_tools)}")
        elif platform.system() == "Windows":
            print("   Install Visual Studio Build Tools and LLVM")
        
        return False
    
    return True

def main():
    print("🚀 Setting up MiniCPM build environment...")
    print("=" * 50)
    
    # Check prerequisites
    checks = [
        ("Python version", check_python_version),
        ("Disk space", check_disk_space), 
        ("Memory", check_memory),
        ("System tools", check_system_tools)
    ]
    
    for name, check_func in checks:
        print(f"\n🔍 Checking {name}...")
        if not check_func():
            print(f"❌ {name} check failed")
            sys.exit(1)
    
    # Setup environment
    print(f"\n🔧 Setting up environment...")
    if not setup_virtual_environment():
        sys.exit(1)
    
    if not install_dependencies():
        sys.exit(1)
    
    print("\n" + "=" * 50)
    print("🎉 Build environment setup complete!")
    print("\nNext steps:")
    print("1. Activate virtual environment:")
    
    if platform.system() == "Windows":
        print("   .venv\\Scripts\\activate")
    else:
        print("   source .venv/bin/activate")
    
    print("2. Run model conversion:")
    print("   python scripts/build_real_minicpm.py")

if __name__ == "__main__":
    main()
