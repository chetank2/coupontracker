package com.example.coupontracker.worker

internal object VisionVerificationPrompts {
    fun verification(): String {
        return "Read this coupon image. Return JSON only: " +
            "{\"storeName\":\"\",\"description\":\"\",\"redeemCode\":null,\"expiryDate\":null," +
            "\"storeNameSource\":\"vision\",\"storeNameEvidence\":[],\"needsAttention\":false}. " +
            "Use visible text only. Code must be exact. Missing fields are null."
    }

    fun layout(): String {
        return "JSON only. Layout only. Do not return store, offer, code, or expiry. " +
            "Return exactly {\"layoutState\":\"\",\"confidence\":0,\"cards\":[{\"active\":true,\"confidence\":0,\"bounds\":{\"x\":0,\"y\":0,\"w\":0,\"h\":0}}]}. " +
            "Bounds are normalized 0..1. Pick one active foreground coupon/card/modal. " +
            "layoutState: COMPLETE, PARTIAL, MODAL_FOREGROUND, MULTI_CARD, or LOW_CONFIDENCE."
    }

    fun fieldLabels(): String {
        return "JSON only, no markdown. Use visible crop/OCR text only. " +
            "Return tiny JSON with keys ls,s,d,cs,c,es,e,conf. " +
            "ls one of COMPLETE, PARTIAL, MODAL_FOREGROUND, MULTI_CARD, LOW_CONFIDENCE. " +
            "cs one of PRESENT, NO_CODE_NEEDED, NOT_VISIBLE, UNKNOWN. " +
            "es one of PRESENT, NOT_VISIBLE, UNKNOWN. " +
            "s=store, d=offer, c=exact code or null, e=expiry text or null. " +
            "Use null for absent text. Do not invent or copy example values."
    }
}
