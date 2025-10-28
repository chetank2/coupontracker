package com.example.coupontracker.schema

/**
 * Schema definition for coupon extraction.
 * Defines the canonical minimal fields extracted from coupon images.
 */
object CouponSchema {
    
    /**
     * Schema version - increment when making breaking changes
     */
    const val VERSION = "2.0.0"
    
    /**
     * The complete coupon extraction schema
     */
    val SCHEMA = Schema(
        name = "Coupon",
        version = VERSION,
        fields = listOf(
            SchemaField(
                name = "storeName",
                type = FieldType.StringType,
                required = true,
                metadata = FieldMetadata(
                    description = "Brand or store name that issued the coupon",
                    examples = listOf("Amazon", "Flipkart", "AJIO", "Myntra"),
                    hints = listOf(
                        "Extract brand name only, not full company name",
                        "Prefer the issuing brand over partner or watermark text",
                        "Use title case (e.g., 'Amazon' not 'AMAZON')",
                        "If any brand, merchant, or app name appears in OCR, choose the best match instead of returning null"
                    ),
                    extractionHints = "Usually appears at the top or as a logo. May be in large text or header.",
                    validationRules = listOf(
                        "Must not be empty or 'Unknown'",
                        "Must not contain generic terms like 'Store', 'Brand', 'Company'",
                        "Must be 2-50 characters",
                        "Return null only when the OCR text truly has no identifiable brand or merchant"
                    )
                )
            ),
            SchemaField(
                name = "description",
                type = FieldType.StringType,
                required = false,
                metadata = FieldMetadata(
                    description = "Verbatim offer text exactly as printed on the coupon",
                    examples = listOf(
                        "Flat ₹200 cashback on first order",
                        "Get 50% off on electronics",
                        "Buy 1 Get 1 free on select items"
                    ),
                    hints = listOf(
                        "Use the offer sentence as-is without adding math or commentary",
                        "Preserve currency symbols, plus signs, and formatting",
                        "Exclude generic UI chrome or timestamps",
                        "If multiple offer lines exist, choose the primary benefit"
                    ),
                    extractionHints = "Main offer text, usually prominent. Filter out status bar noise.",
                    validationRules = listOf(
                        "Must not invent combined totals (e.g., do not add ₹100 + ₹70)",
                        "Must not rewrite or paraphrase the coupon text",
                        "May be null when no readable description exists"
                    )
                )
            ),
            SchemaField(
                name = "redeemCode",
                type = FieldType.StringType,
                required = false,
                metadata = FieldMetadata(
                    description = "Coupon or promo code to be entered at checkout",
                    examples = listOf("SAVE50", "FLASH75", "NEWUSER100"),
                    hints = listOf(
                        "Extract only the code characters (no labels like 'Code:' or 'Use')",
                        "Treat 'couponCode' as an alias for this field",
                        "Strip whitespace and ensure uppercase alphanumerics with optional - or _"
                    ),
                    extractionHints = "Often appears in boxes, near 'Use code', or beside redemption instructions.",
                    validationRules = listOf(
                        "Must be alphanumeric (may include hyphens/underscores)",
                        "Must not include spaces or descriptive words",
                        "Return null when no explicit code is present"
                    )
                )
            ),
            SchemaField(
                name = "expiryDate",
                type = FieldType.StringType,
                required = false,
                metadata = FieldMetadata(
                    description = "Expiration date of the coupon",
                    examples = listOf("31 May, 2025", "2025-12-31", "15/12/2025"),
                    hints = listOf(
                        "Copy the displayed date exactly when present",
                        "If only a relative phrase like 'Expires in 5 days' exists, emit ISO date computed from screenshot timestamp",
                        "Do not hallucinate dates or infer months without evidence"
                    ),
                    extractionHints = "Look for keywords: 'expires', 'valid till', 'expiring in'.",
                    validationRules = listOf(
                        "Preserve original formatting when absolute date is present",
                        "Emit yyyy-MM-dd when converting relative day counts",
                        "Return null if no reliable expiry information is available"
                    )
                )
            )
        ),
        globalRules = listOf(
            "Output ONLY the keys: storeName, redeemCode, expiryDate, description",
            "Use null for any field that cannot be confidently extracted",
            "storeName must not be null when the OCR snippet includes any brand-like word, header text, or app name",
            "Preserve the offer description verbatim without arithmetic or rephrasing",
            "If the coupon only states it expires in N days and a capture timestamp is provided, convert it to an ISO date using that timestamp",
            "Do not hallucinate additional structured fields or metadata"
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

