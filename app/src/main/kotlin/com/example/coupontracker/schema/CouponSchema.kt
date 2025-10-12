package com.example.coupontracker.schema

/**
 * Schema definition for coupon extraction.
 * Defines the 7 core fields extracted from coupon images.
 */
object CouponSchema {
    
    /**
     * Schema version - increment when making breaking changes
     */
    const val VERSION = "1.0.0"
    
    /**
     * The complete coupon extraction schema
     */
    val SCHEMA = Schema(
        name = "Coupon",
        version = VERSION,
        fields = listOf(
            // Field 1: Store Name
            SchemaField(
                name = "storeName",
                type = FieldType.StringType,
                required = true,
                metadata = FieldMetadata(
                    description = "Brand or store name that issued the coupon",
                    examples = listOf(
                        "Amazon",
                        "Flipkart",
                        "AJIO",
                        "Myntra",
                        "Swiggy",
                        "Zomato",
                        "Tata CLiQ"
                    ),
                    hints = listOf(
                        "Extract brand name only, not full company name",
                        "Use title case (e.g., 'Amazon' not 'AMAZON')",
                        "If multiple brands mentioned, use the issuing brand"
                    ),
                    extractionHints = "Usually appears at the top or as a logo. May be in large text or header.",
                    validationRules = listOf(
                        "Must not be empty or 'Unknown'",
                        "Must not contain generic terms like 'Store', 'Brand', 'Company'",
                        "Must be 2-50 characters"
                    )
                )
            ),
            
            // Field 2: Description
            SchemaField(
                name = "description",
                type = FieldType.StringType,
                required = false,  // FIXED: Allow null when LLM can't extract description
                metadata = FieldMetadata(
                    description = "Brief summary of the coupon offer",
                    examples = listOf(
                        "Get 50% off on electronics",
                        "Free delivery on orders above ₹499",
                        "Flat ₹200 off on fashion items",
                        "Buy 1 Get 1 free on select items"
                    ),
                    hints = listOf(
                        "Keep it concise (under 100 characters)",
                        "Focus on the main benefit",
                        "Exclude terms & conditions",
                        "Clean up OCR noise (timestamps, single letters)"
                    ),
                    extractionHints = "Main offer text, usually prominent. Filter out timestamps, navigation elements.",
                    validationRules = listOf(
                        "Must not be empty or generic placeholder",
                        "Must not contain timestamps (e.g., '10:23')",
                        "Must not be single letters or noise",
                        "Should be under 100 characters"
                    )
                )
            ),
            
            // Field 3: Cashback (structured object)
            SchemaField(
                name = "cashback",
                type = FieldType.ObjectType(
                    properties = mapOf(
                        "type" to SchemaField(
                            name = "type",
                            type = FieldType.EnumType(listOf("percent", "amount", "text")),
                            required = true,
                            metadata = FieldMetadata(
                                description = "Type of cashback/discount",
                                examples = listOf("percent", "amount", "text"),
                                hints = listOf(
                                    "Use 'percent' for percentage discounts (e.g., 50% off)",
                                    "Use 'amount' for fixed rupee discounts (e.g., ₹200 off)",
                                    "Use 'text' only as fallback for complex offers"
                                )
                            )
                        ),
                        "valueNum" to SchemaField(
                            name = "valueNum",
                            type = FieldType.NumberType,
                            required = true,
                            metadata = FieldMetadata(
                                description = "Numeric value of the discount",
                                examples = listOf("50", "200", "11", "75"),
                                hints = listOf(
                                    "For 'percent': just the number (e.g., 50 for '50% off')",
                                    "For 'amount': just the number (e.g., 200 for '₹200 off')",
                                    "Do not include currency symbols or percent signs"
                                )
                            )
                        ),
                        "currency" to SchemaField(
                            name = "currency",
                            type = FieldType.StringType,
                            required = false,
                            metadata = FieldMetadata(
                                description = "Currency code (only for amount type)",
                                examples = listOf("INR", "USD"),
                                hints = listOf(
                                    "Use 'INR' for Indian rupees (₹)",
                                    "Use null for percent type",
                                    "Default to 'INR' if currency is unclear"
                                )
                            )
                        )
                    )
                ),
                required = false,
                metadata = FieldMetadata(
                    description = "Structured cashback/discount information",
                    examples = listOf(
                        """{"type":"percent","valueNum":50,"currency":null}""",
                        """{"type":"amount","valueNum":200,"currency":"INR"}""",
                        """{"type":"percent","valueNum":11,"currency":null}"""
                    ),
                    hints = listOf(
                        "Parse discount amount and type separately",
                        "Distinguish between percentage and fixed amount",
                        "Handle variations: 'Flat 75% Off', '₹500 cashback', '50% discount'"
                    ),
                    extractionHints = "Look for '%', '₹', 'Rs', 'off', 'discount', 'cashback' keywords.",
                    validationRules = listOf(
                        "If type is 'percent', valueNum should be 0-100",
                        "If type is 'amount', valueNum should be > 0",
                        "Currency should be null for percent type",
                        "Currency should be 'INR' for amount type (default)"
                    )
                )
            ),
            
            // Field 4: Redeem Code
            SchemaField(
                name = "redeemCode",
                type = FieldType.StringType,
                required = false,
                metadata = FieldMetadata(
                    description = "Coupon code to be entered at checkout",
                    examples = listOf(
                        "SAVE50",
                        "TBNEIZNOL5FSUZY",
                        "NEWUSER100",
                        "FLASH75"
                    ),
                    hints = listOf(
                        "Extract ONLY the code itself",
                        "Strip ALL extra text and spaces",
                        "Example: 'Code: SAVE50' → extract 'SAVE50'",
                        "Do NOT include labels like 'Code:', 'Promo:', 'Use code'",
                        "Do NOT include expiry information"
                    ),
                    extractionHints = "Often in a box, dashed border, or labeled 'Code:', 'Promo Code:', 'Coupon Code:'.",
                    validationRules = listOf(
                        "Must be alphanumeric (may include hyphens/underscores)",
                        "Must not contain spaces",
                        "Must not include prefix text (Code:, Use:, etc.)",
                        "Typically 4-20 characters"
                    )
                )
            ),
            
            // Field 5: Expiry Date
            SchemaField(
                name = "expiryDate",
                type = FieldType.StringType, // Keep as string to preserve original format
                required = false,
                metadata = FieldMetadata(
                    description = "Expiration date of the coupon",
                    examples = listOf(
                        "31 May, 2025",
                        "2025-12-31",
                        "Dec 15, 2025",
                        "15/12/2025"
                    ),
                    hints = listOf(
                        "Extract date EXACTLY as shown",
                        "Do NOT reformat or change the date",
                        "Do NOT hallucinate dates",
                        "Example: 'Expires on 31 May, 2025, 11:59 PM' → extract '31 May, 2025'",
                        "Example: 'Valid till 2025-12-31' → extract '2025-12-31'"
                    ),
                    extractionHints = "Look for keywords: 'expires', 'valid till', 'valid until', 'expiry'.",
                    validationRules = listOf(
                        "Must preserve original format from coupon",
                        "Must not include time component (11:59 PM, etc.)",
                        "Must not be hallucinated or fabricated"
                    )
                )
            ),
            
            // Field 6: Minimum Order Amount
            SchemaField(
                name = "minOrderAmount",
                type = FieldType.StringType, // String to preserve formatting like "₹999"
                required = false,
                metadata = FieldMetadata(
                    description = "Minimum purchase amount required to use the coupon",
                    examples = listOf(
                        "₹999",
                        "₹500",
                        "₹1,499",
                        "Rs 299"
                    ),
                    hints = listOf(
                        "Include currency symbol if present",
                        "Preserve original formatting",
                        "Look for keywords: 'minimum', 'min', 'above', 'orders over'"
                    ),
                    extractionHints = "Often in conditions section: 'Valid on orders above', 'Minimum purchase'.",
                    validationRules = listOf(
                        "Should include currency indicator",
                        "Numeric value should be > 0"
                    )
                )
            )
        ),
        
        globalRules = listOf(
            "All keys are required in JSON output, use null if data is missing",
            "Output ONLY valid JSON, no explanatory text before or after",
            "Start with '{' and end with '}'",
            "Do not hallucinate data not present in the OCR text",
            "If a field cannot be extracted, use null",
            "Preserve original formatting where specified (dates, amounts)",
            "Filter out OCR noise (timestamps, single letters, navigation elements)"
        )
    )
    
    // Helper methods
    
    /**
     * Get a field by name
     */
    fun getField(name: String): SchemaField? = SCHEMA.getField(name)
    
    /**
     * Check if a field is required
     */
    fun isFieldRequired(name: String): Boolean = SCHEMA.isFieldRequired(name)
    
    /**
     * Get all required field names
     */
    fun getRequiredFieldNames(): List<String> = SCHEMA.getRequiredFields().map { it.name }
    
    /**
     * Get all field names
     */
    fun getAllFieldNames(): List<String> = SCHEMA.fields.map { it.name }
}

