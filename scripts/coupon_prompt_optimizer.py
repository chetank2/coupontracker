#!/usr/bin/env python3
"""
Coupon Prompt Optimizer for MiniCPM-Llama3-V2.5

Optimizes prompts for structured coupon information extraction.
Includes few-shot examples and validation logic.
"""

import json
import logging
from typing import Dict, List, Optional, Any
from dataclasses import dataclass
from datetime import datetime, timedelta

logger = logging.getLogger(__name__)

@dataclass
class CouponPromptTemplate:
    """Template for coupon extraction prompts"""
    system_prompt: str
    user_prompt: str
    few_shot_examples: List[Dict]
    output_schema: Dict
    validation_rules: Dict

class CouponPromptOptimizer:
    """Optimize prompts for accurate coupon extraction"""
    
    def __init__(self):
        self.base_schema = {
            "storeName": "string (required) - Name of the store/brand",
            "description": "string (required) - Offer description including savings details",
            "redeemCode": "string (optional) - Coupon/promo code",
            "expiryDate": "string (optional) - Expiry date in YYYY-MM-DD format",
            "storeNameSource": "string (optional) - How the store name was inferred",
            "storeNameEvidence": "array of strings (optional) - OCR snippets backing the store name",
            "needsAttention": "boolean (required) - true if extraction looks uncertain"
        }
        
        self.few_shot_examples = self._create_few_shot_examples()
    
    def _create_few_shot_examples(self) -> List[Dict]:
        """Create few-shot examples for better extraction"""
        return [
            {
                "description": "Amazon coupon with 20% off electronics",
                "expected_output": {
                    "storeName": "Amazon",
                    "description": "Get 20% off electronics",
                    "redeemCode": None,
                    "expiryDate": None,
                    "storeNameSource": "heading",
                    "storeNameEvidence": ["Amazon"],
                    "needsAttention": False
                }
            },
            {
                "description": "Flipkart promo code SAVE500 for ₹500 off on orders above ₹2000, valid till Dec 31",
                "expected_output": {
                    "storeName": "Flipkart", 
                    "description": "Use code SAVE500 for ₹500 off on orders above ₹2000",
                    "redeemCode": "SAVE500",
                    "expiryDate": "2024-12-31",
                    "storeNameSource": "heading",
                    "storeNameEvidence": ["Flipkart"],
                    "needsAttention": False
                }
            },
            {
                "description": "Zomato: Get 50% off up to ₹150 on your first order. Use code FIRST50",
                "expected_output": {
                    "storeName": "Zomato",
                    "description": "Get 50% off up to ₹150 on your first order",
                    "redeemCode": "FIRST50",
                    "expiryDate": None,
                    "storeNameSource": "logo",
                    "storeNameEvidence": ["Zomato"],
                    "needsAttention": False
                }
            }
        ]
    
    def create_extraction_prompt(self, include_examples: bool = True) -> CouponPromptTemplate:
        """Create optimized prompt for coupon extraction"""
        
        system_prompt = """You are a precise coupon information extractor. Analyze coupon images and extract structured information in JSON format.

CRITICAL RULES:
1. Extract ONLY information visible in the image
2. Return valid JSON with the exact schema provided
3. Use null for missing optional fields (never invent values)
4. Keep all savings/cashback specifics inside `description`
5. Preserve original currency symbols and formatting
6. For dates, prefer YYYY-MM-DD when possible, otherwise use `DD/MM/YYYY`

SCHEMA:
{
  "storeName": "string (required) - Store/brand name",
  "description": "string (required) - Offer description including savings details", 
  "redeemCode": "string|null - Promo/coupon code",
  "expiryDate": "string|null - Expiry in YYYY-MM-DD format",
  "storeNameSource": "string|null - Indicator of how the store was inferred",
  "storeNameEvidence": "array|null - OCR snippet(s) backing the store name",
  "needsAttention": "boolean (required) - true if output looks uncertain"
}"""

        user_prompt = """Analyze this coupon image and extract the information in the exact JSON format specified.

Look for:
- Store/brand name (usually prominent)
- Offer details (discount amount, percentage)
- Promo codes (alphanumeric codes like SAVE20, FIRST50)
- Expiry dates (look for "valid till", "expires", dates)
- Mention minimum order requirements, categories, or terms inside the description when they appear.

Return ONLY the JSON object, no additional text."""

        if include_examples:
            examples_text = "\n\nFEW-SHOT EXAMPLES:\n"
            for i, example in enumerate(self.few_shot_examples, 1):
                examples_text += f"\nExample {i}:\n"
                examples_text += f"Input: {example['description']}\n"
                examples_text += f"Output: {json.dumps(example['expected_output'], indent=2)}\n"
            
            user_prompt += examples_text

        return CouponPromptTemplate(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            few_shot_examples=self.few_shot_examples,
            output_schema=self.base_schema,
            validation_rules=self._create_validation_rules()
        )
    
    def _create_validation_rules(self) -> Dict:
        """Create validation rules for extracted data"""
        return {
            "required_fields": ["storeName", "description", "needsAttention"],
            "field_types": {
                "storeName": str,
                "description": str,
                "redeemCode": [str, type(None)],
                "expiryDate": [str, type(None)],
                "storeNameSource": [str, type(None)],
                "storeNameEvidence": [list, type(None)],
                "needsAttention": bool
            },
            "date_formats": [
                "%Y-%m-%d",
                "%d/%m/%Y", 
                "%m/%d/%Y",
                "%d-%m-%Y"
            ],
            "currency_patterns": [
                r"₹\d+",
                r"\$\d+", 
                r"€\d+",
                r"\d+%"
            ]
        }
    
    def validate_extraction(self, extracted_data: Dict) -> Dict:
        """Validate extracted coupon data"""
        validation_result = {
            "is_valid": True,
            "errors": [],
            "warnings": [],
            "confidence_score": 100
        }
        
        # Check required fields
        for field in self.validation_rules["required_fields"]:
            if field not in extracted_data or not extracted_data[field]:
                validation_result["errors"].append(f"Missing required field: {field}")
                validation_result["is_valid"] = False
                validation_result["confidence_score"] -= 30
        
        # Validate field types
        for field, expected_types in self.validation_rules["field_types"].items():
            if field in extracted_data and extracted_data[field] is not None:
                field_type = type(extracted_data[field])
                if isinstance(expected_types, list):
                    if field_type not in expected_types:
                        validation_result["errors"].append(
                            f"Invalid type for {field}: expected {expected_types}, got {field_type}"
                        )
                        validation_result["confidence_score"] -= 10
                else:
                    if field_type != expected_types:
                        validation_result["errors"].append(
                            f"Invalid type for {field}: expected {expected_types}, got {field_type}"
                        )
                        validation_result["confidence_score"] -= 10
        
        # Validate date format
        if extracted_data.get("expiryDate"):
            if not self._validate_date_format(extracted_data["expiryDate"]):
                validation_result["warnings"].append(
                    f"Date format may be incorrect: {extracted_data['expiryDate']}"
                )
                validation_result["confidence_score"] -= 5
        
        # Check for suspicious patterns
        if extracted_data.get("storeName"):
            if len(extracted_data["storeName"]) > 100:
                validation_result["warnings"].append("Store name seems too long")
                validation_result["confidence_score"] -= 5
        
        if extracted_data.get("redeemCode"):
            code = extracted_data["redeemCode"]
            if len(code) > 20 or len(code) < 3:
                validation_result["warnings"].append(
                    f"Promo code length unusual: {len(code)} characters"
                )
                validation_result["confidence_score"] -= 5
        
        # Ensure confidence score doesn't go below 0
        validation_result["confidence_score"] = max(0, validation_result["confidence_score"])
        
        return validation_result
    
    def _validate_date_format(self, date_str: str) -> bool:
        """Validate if date string matches expected formats"""
        from datetime import datetime
        
        for fmt in self.validation_rules["date_formats"]:
            try:
                datetime.strptime(date_str, fmt)
                return True
            except ValueError:
                continue
        return False
    
    def create_optimized_prompts(self) -> Dict[str, CouponPromptTemplate]:
        """Create multiple optimized prompt variants"""
        prompts = {}
        
        # Standard prompt with examples
        prompts["standard"] = self.create_extraction_prompt(include_examples=True)
        
        # Concise prompt without examples (for faster inference)
        prompts["concise"] = self.create_extraction_prompt(include_examples=False)
        
        # High-precision prompt (more strict)
        prompts["precise"] = self._create_precise_prompt()
        
        # Fast prompt (minimal instructions)
        prompts["fast"] = self._create_fast_prompt()
        
        return prompts
    
    def _create_precise_prompt(self) -> CouponPromptTemplate:
        """Create high-precision prompt with detailed instructions"""
        system_prompt = """You are an expert coupon analyzer with 99% accuracy. Extract coupon information with maximum precision.

EXTRACTION RULES:
1. Store Name: Look for logos, brand names, website URLs
2. Description: Summarize the main offer in 1-2 sentences  
3. Cashback Amount: Extract exact discount (20%, ₹100, $50 off)
4. Redeem Code: Find alphanumeric codes (usually 4-12 characters)
5. Expiry Date: Look for "valid till", "expires on", date stamps
6. Min Order: Find "minimum order", "above ₹X", "orders over"
7. Category: Infer from context (electronics, food, fashion, etc.)
8. Terms: Extract key conditions (first order, new users, etc.)

QUALITY CHECKS:
- Verify store name appears in image
- Confirm discount amount is realistic (1-90%)
- Validate promo codes don't contain spaces
- Check expiry dates are future dates
- Ensure minimum orders are reasonable

Return JSON only. Be conservative if uncertain."""

        user_prompt = """Extract coupon information with maximum precision. Double-check each field before including it in the output."""
        
        return CouponPromptTemplate(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            few_shot_examples=self.few_shot_examples,
            output_schema=self.base_schema,
            validation_rules=self._create_validation_rules()
        )
    
    def _create_fast_prompt(self) -> CouponPromptTemplate:
        """Create minimal prompt for fast inference"""
        system_prompt = """Extract coupon info as JSON. Required fields: storeName, description, needsAttention. Optional: redeemCode, expiryDate, storeNameSource, storeNameEvidence. Use null for missing optional data."""
        
        user_prompt = """Return ONLY this JSON structure:
{
  "storeName": "?",
  "description": "?",
  "redeemCode": "?",
  "expiryDate": "?",
  "storeNameSource": "?",
  "storeNameEvidence": ["?"],
  "needsAttention": false
}

Ensure all savings and cashback information stays inside `description`."""
        
        return CouponPromptTemplate(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            few_shot_examples=[],
            output_schema=self.base_schema,
            validation_rules=self._create_validation_rules()
        )

def main():
    """Test prompt generation"""
    optimizer = CouponPromptOptimizer()
    prompts = optimizer.create_optimized_prompts()
    
    print("Generated prompt variants:")
    for name, template in prompts.items():
        print(f"\n=== {name.upper()} PROMPT ===")
        print(f"System: {template.system_prompt[:100]}...")
        print(f"User: {template.user_prompt[:100]}...")
        print(f"Examples: {len(template.few_shot_examples)}")

if __name__ == "__main__":
    main()
