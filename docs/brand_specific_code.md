# Brand-specific code audit

## Keyword footprint
- A quick search for brand names such as Myntra, Mivi, ABHIBUS, XYXX, NEWMEE, Ixigo, boAt, Mamaearth, Zomato, Swiggy, Flipkart, Amazon, and CRED reveals **more than 300 explicit matches** across the Kotlin source tree (e.g., Myntra alone appears 100 times, Mivi 58 times, CRED 47 times).【fafab7†L1-L14】

## Concentrated brand heuristics
- **Template pipeline:** `CouponTemplateExtractor` carries dedicated flows for Mivi, Myntra, ABHIBUS, and CRED, including custom template detection triggers, code patterns, fallback descriptions, and even color heuristics for pink/purple Myntra headers or red ABHIBUS headers.【F:app/src/main/kotlin/com/example/coupontracker/util/CouponTemplateExtractor.kt†L18-L450】
- **OCR post-processing:** `EnhancedOCRHelper` embeds Myntra-specific regexes for store detection, code length, amount phrasing ("up to ₹…"), and voucher descriptions, forcing "Myntra" as the store when those phrases appear.【F:app/src/main/kotlin/com/example/coupontracker/util/EnhancedOCRHelper.kt†L26-L256】
- **Fallback OCR wrapper:** `OCREngineImpl` repeats Myntra gatekeeping to coerce store name, description, and long-form codes whenever the text hints at "you won a voucher."【F:app/src/main/kotlin/com/example/coupontracker/util/OCREngineImpl.kt†L76-L138】
- **Rule-based extraction:** `TextExtractor` layers brand checks for Myntra (amount and code parsing) plus forced categories for Myntra, XYXX, and ABHIBUS, which propagates into the general extraction flow.【F:app/src/main/kotlin/com/example/coupontracker/util/TextExtractor.kt†L866-L1073】
- **Regional enrichment:** `RegionBasedExtractor` maintains a known store list and explicit overrides for Mivi prompts, including forcing store names, codes, descriptions, and fixed 80% amounts.【F:app/src/main/kotlin/com/example/coupontracker/util/RegionBasedExtractor.kt†L390-L455】
- **Validation layer:** `BrandAwareCouponValidator` bundles brand-specific regexes and brand dictionaries for Myntra, Ixigo, ABHIBUS, boAt, Mamaearth, and others, enriching cashback extraction and brand identification beyond generic rules.【F:app/src/main/kotlin/com/example/coupontracker/util/BrandAwareCouponValidator.kt†L67-L138】

## Takeaways
- Brand tailoring is not limited to a single module; it spans OCR helpers, core extractors, regional enrichers, and validators, reinforcing the dependency on hard-coded brand knowledge.
- Removing or generalizing these behaviors would require coordinated changes across the extraction pipeline rather than a single entry point.
