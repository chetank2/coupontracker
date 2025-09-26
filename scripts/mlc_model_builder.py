#!/usr/bin/env python3
"""
MLC-LLM Model Builder for MiniCPM-Llama3-V2.5

This script handles the actual MLC-LLM model compilation and optimization
for Android deployment. It works with the existing conversion pipeline.
"""

import os
import sys
import json
import logging
import subprocess
from pathlib import Path
from typing import Dict, List, Optional

logger = logging.getLogger(__name__)

class MLCModelBuilder:
    """Build MLC-LLM model for Android deployment"""
    
    def __init__(self, work_dir: Path = Path("mlc_workspace")):
        self.work_dir = work_dir
        self.work_dir.mkdir(exist_ok=True)
        
        # MLC-LLM configuration
        self.model_config = {
            "model_type": "minicpm",
            "quantization": "q4f16_1",
            "max_seq_len": 4096,
            "vocab_size": 122753,
            "prefill_chunk_size": 4096,
            "tensor_parallel_shards": 1,
            "context_window_size": 4096
        }
        
        # Android specific settings
        self.android_config = {
            "target_abis": ["arm64-v8a", "armeabi-v7a"],
            "min_api": 26,
            "use_vulkan": True,
            "use_nnapi": True,
            "optimize_for_mobile": True
        }
    
    def check_mlc_installation(self) -> bool:
        """Check if MLC-LLM is properly installed"""
        try:
            result = subprocess.run(
                ["python", "-c", "import mlc_llm; print(mlc_llm.__version__)"],
                capture_output=True, text=True, check=True
            )
            version = result.stdout.strip()
            logger.info(f"MLC-LLM version: {version}")
            return True
        except (subprocess.CalledProcessError, FileNotFoundError):
            logger.error("MLC-LLM not found. Install with: pip install mlc-llm")
            return False
    
    def create_mlc_config(self, model_path: Path) -> Path:
        """Create MLC-LLM configuration file"""
        config_path = self.work_dir / "mlc-chat-config.json"
        
        config = {
            "model_lib": f"MiniCPM-Llama3-V-2_5-{self.model_config['quantization']}",
            "local_id": "MiniCPM-Llama3-V-2_5-q4f16_1-MLC",
            "conv_template": "minicpm",
            "temperature": 0.3,
            "repetition_penalty": 1.0,
            "top_p": 0.9,
            "mean_gen_len": 512,
            "max_gen_len": 1024,
            "shift_fill_factor": 0.3,
            "tokenizer_files": [
                "tokenizer.json",
                "tokenizer_config.json",
                "vocab.json",
                "merges.txt"
            ],
            "model_category": "vision-language",
            "model_name": "MiniCPM-Llama3-V-2.5",
            "quantization": self.model_config["quantization"]
        }
        
        with open(config_path, 'w') as f:
            json.dump(config, f, indent=2)
        
        logger.info(f"Created MLC config: {config_path}")
        return config_path
    
    def compile_model(self, model_path: Path, output_dir: Path) -> Dict:
        """Compile model using MLC-LLM"""
        if not self.check_mlc_installation():
            raise RuntimeError("MLC-LLM not available")
        
        logger.info("Compiling model with MLC-LLM...")
        
        # Create MLC configuration
        config_path = self.create_mlc_config(model_path)
        
        # Prepare compilation command
        compile_cmd = [
            "python", "-m", "mlc_llm.build",
            "--model", str(model_path),
            "--target", "android",
            "--quantization", self.model_config["quantization"],
            "--max-seq-len", str(self.model_config["max_seq_len"]),
            "--output", str(output_dir),
            "--use-cache=0"  # Disable cache for clean build
        ]
        
        # Add Android-specific flags
        if self.android_config["use_vulkan"]:
            compile_cmd.extend(["--use-vulkan", "1"])
        
        if self.android_config["optimize_for_mobile"]:
            compile_cmd.extend(["--opt-level", "3"])
        
        logger.info(f"Running compilation: {' '.join(compile_cmd)}")
        
        try:
            result = subprocess.run(
                compile_cmd,
                cwd=self.work_dir,
                capture_output=True,
                text=True,
                check=True,
                timeout=3600  # 1 hour timeout
            )
            
            logger.info("Model compilation completed successfully")
            logger.debug(f"Compilation output: {result.stdout}")
            
            return {
                "status": "success",
                "output_dir": str(output_dir),
                "model_lib": f"MiniCPM-Llama3-V-2_5-{self.model_config['quantization']}",
                "config_file": str(config_path),
                "compilation_log": result.stdout
            }
            
        except subprocess.TimeoutExpired:
            logger.error("Model compilation timed out (1 hour)")
            raise RuntimeError("Compilation timeout")
            
        except subprocess.CalledProcessError as e:
            logger.error(f"Model compilation failed: {e}")
            logger.error(f"Error output: {e.stderr}")
            raise RuntimeError(f"Compilation failed: {e.stderr}")
    
    def create_android_package(self, compiled_dir: Path, package_dir: Path) -> Dict:
        """Package compiled model for Android deployment"""
        logger.info("Creating Android deployment package...")
        
        package_dir.mkdir(parents=True, exist_ok=True)
        
        # Required files for Android deployment
        required_files = [
            "mlc-chat-config.json",
            "tokenizer.json",
            "tokenizer_config.json",
            "params_shard_*.bin",  # Model weights
            "*.so"  # Compiled libraries
        ]
        
        packaged_files = []
        
        for pattern in required_files:
            if "*" in pattern:
                # Handle glob patterns
                import glob
                matches = glob.glob(str(compiled_dir / pattern))
                for match in matches:
                    src = Path(match)
                    dst = package_dir / src.name
                    shutil.copy2(src, dst)
                    packaged_files.append(dst.name)
            else:
                src = compiled_dir / pattern
                if src.exists():
                    dst = package_dir / pattern
                    shutil.copy2(src, dst)
                    packaged_files.append(pattern)
                else:
                    logger.warning(f"Required file not found: {src}")
        
        # Create deployment manifest
        manifest = {
            "model_name": "MiniCPM-Llama3-V-2.5",
            "version": "1.0.0",
            "quantization": self.model_config["quantization"],
            "target_platform": "android",
            "min_api_level": self.android_config["min_api"],
            "supported_abis": self.android_config["target_abis"],
            "files": packaged_files,
            "estimated_size_mb": self._calculate_package_size(package_dir),
            "checksum": self._calculate_package_checksum(package_dir)
        }
        
        manifest_path = package_dir / "deployment_manifest.json"
        with open(manifest_path, 'w') as f:
            json.dump(manifest, f, indent=2)
        
        logger.info(f"Android package created: {package_dir}")
        logger.info(f"Package size: {manifest['estimated_size_mb']:.1f} MB")
        
        return manifest
    
    def _calculate_package_size(self, package_dir: Path) -> float:
        """Calculate total package size in MB"""
        total_size = sum(
            f.stat().st_size for f in package_dir.rglob('*') if f.is_file()
        )
        return total_size / (1024 * 1024)  # Convert to MB
    
    def _calculate_package_checksum(self, package_dir: Path) -> str:
        """Calculate SHA-256 checksum of package contents"""
        import hashlib
        
        hasher = hashlib.sha256()
        
        # Sort files for consistent checksum
        files = sorted(package_dir.rglob('*'))
        
        for file_path in files:
            if file_path.is_file() and file_path.name != "deployment_manifest.json":
                with open(file_path, 'rb') as f:
                    for chunk in iter(lambda: f.read(4096), b""):
                        hasher.update(chunk)
        
        return hasher.hexdigest()

def main():
    """Main function for standalone usage"""
    import argparse
    
    parser = argparse.ArgumentParser(description="Build MLC-LLM model for Android")
    parser.add_argument("--model-path", required=True, help="Path to base model")
    parser.add_argument("--output-dir", required=True, help="Output directory")
    parser.add_argument("--work-dir", default="mlc_workspace", help="Working directory")
    
    args = parser.parse_args()
    
    # Setup logging
    logging.basicConfig(level=logging.INFO)
    
    builder = MLCModelBuilder(Path(args.work_dir))
    
    try:
        # Compile model
        result = builder.compile_model(Path(args.model_path), Path(args.output_dir))
        
        # Create Android package
        package_dir = Path(args.output_dir) / "android_package"
        manifest = builder.create_android_package(Path(args.output_dir), package_dir)
        
        print(f"✅ Model compilation successful!")
        print(f"📦 Package size: {manifest['estimated_size_mb']:.1f} MB")
        print(f"📁 Output: {package_dir}")
        print(f"🔑 Checksum: {manifest['checksum'][:16]}...")
        
    except Exception as e:
        print(f"❌ Model compilation failed: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
