#!/usr/bin/env python3
"""
Mock MLC-LLM Module
Simulates MLC-LLM functionality for testing the conversion pipeline
when real MLC-LLM compilation tools are not available
"""

import os
import json
import time
import hashlib
import logging
from pathlib import Path
from typing import Dict, Any

logger = logging.getLogger("mock_mlc_llm")

class MockMLCLLM:
    """Mock MLC-LLM class that simulates model conversion"""
    
    def __init__(self):
        self.version = "0.13.0-mock"
    
    def quantize_model(self, model_path: str, output_path: str, config: Dict[str, Any]) -> str:
        """Mock model quantization"""
        logger.info(f"🔧 Mock quantizing model from {model_path}")
        logger.info(f"   - Quantization: {config.get('quantization', 'q4f16_1')}")
        logger.info(f"   - Max sequence length: {config.get('max_seq_len', 2048)}")
        logger.info(f"   - Max image size: {config.get('max_image_size', (768, 768))}")
        
        # Simulate quantization time
        time.sleep(2)
        
        # Create output directory
        output_dir = Path(output_path)
        output_dir.mkdir(parents=True, exist_ok=True)
        
        # Create mock quantized model files
        mock_files = {
            "config.json": {
                "model_type": "minicpm",
                "quantization": config.get('quantization', 'q4f16_1'),
                "max_seq_len": config.get('max_seq_len', 2048),
                "max_image_size": config.get('max_image_size', [768, 768]),
                "vocab_size": 32000,
                "hidden_size": 2048
            },
            "tokenizer.json": {
                "version": "1.0",
                "truncation": None,
                "padding": None,
                "added_tokens": [],
                "normalizer": {"type": "NFC"},
                "pre_tokenizer": {"type": "ByteLevel"},
                "post_processor": {"type": "ByteLevel"},
                "decoder": {"type": "ByteLevel"},
                "model": {"type": "BPE", "vocab": {}, "merges": []}
            }
        }
        
        for filename, content in mock_files.items():
            file_path = output_dir / filename
            with open(file_path, 'w') as f:
                json.dump(content, f, indent=2)
        
        logger.info(f"✅ Mock quantization completed: {output_path}")
        return str(output_path)
    
    def export_to_mlc(self, quantized_path: str, output_path: str) -> str:
        """Mock MLC export"""
        logger.info(f"📦 Mock exporting to MLC format: {output_path}")
        
        # Simulate export time
        time.sleep(3)
        
        # Create output directory
        output_dir = Path(output_path)
        output_dir.mkdir(parents=True, exist_ok=True)
        
        # Create mock MLC model files with realistic sizes
        mock_mlc_files = {
            "minicpm_llm_q4f16_1.so": b"MOCK_SHARED_LIBRARY_CONTENT" * 50000,  # ~1.2MB
            "model.bin": b"MOCK_MODEL_PARAMETERS" * 150000,  # ~2.7MB  
            "mlc-chat-config.json": {
                "model_type": "minicpm-llama3-v2.5",
                "quantization": "q4f16_1",
                "context_window_size": 2048,
                "prefill_chunk_size": 2048,
                "tensor_parallel_shards": 1,
                "max_batch_size": 1,
                "temperature": 0.3,
                "top_p": 0.9,
                "conv_template": "minicpm"
            },
            "vision_config.json": {
                "vision_model": "clip",
                "image_size": 768,
                "patch_size": 14,
                "num_channels": 3,
                "hidden_size": 1024,
                "intermediate_size": 4096,
                "num_hidden_layers": 24,
                "num_attention_heads": 16
            },
            "tokenizer.model": b"MOCK_TOKENIZER_MODEL_DATA" * 8000,  # ~144KB
        }
        
        total_size = 0
        for filename, content in mock_mlc_files.items():
            file_path = output_dir / filename
            
            if isinstance(content, dict):
                # JSON files
                json_content = json.dumps(content, indent=2)
                file_path.write_text(json_content)
                total_size += len(json_content.encode())
            else:
                # Binary files
                file_path.write_bytes(content)
                total_size += len(content)
        
        logger.info(f"✅ Mock MLC export completed: {total_size / (1024*1024):.1f}MB")
        return str(output_path)
    
    def get_version(self) -> str:
        """Get mock MLC-LLM version"""
        return self.version

# Mock module-level functions
def quantize_model(model_path: str, output_path: str, config: Dict[str, Any]) -> str:
    """Mock quantize_model function"""
    mock_mlc = MockMLCLLM()
    return mock_mlc.quantize_model(model_path, output_path, config)

def export_to_mlc(quantized_path: str, output_path: str) -> str:
    """Mock export_to_mlc function"""
    mock_mlc = MockMLCLLM()
    return mock_mlc.export_to_mlc(quantized_path, output_path)

def get_version() -> str:
    """Mock get_version function"""
    return "0.13.0-mock"

# Mock __version__ attribute
__version__ = "0.13.0-mock"

if __name__ == "__main__":
    # Test the mock functionality
    print("🧪 Testing Mock MLC-LLM...")
    
    mock = MockMLCLLM()
    
    # Test quantization
    config = {
        'quantization': 'q4f16_1',
        'max_seq_len': 2048,
        'max_image_size': (768, 768)
    }
    
    quantized_path = mock.quantize_model("mock_model", "test_quantized", config)
    mlc_path = mock.export_to_mlc(quantized_path, "test_mlc")
    
    print(f"✅ Mock MLC-LLM test completed")
    print(f"   - Version: {mock.get_version()}")
    print(f"   - Quantized path: {quantized_path}")
    print(f"   - MLC path: {mlc_path}")
