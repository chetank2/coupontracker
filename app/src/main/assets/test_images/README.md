# Test Coupon Image Requirement

## Required File
`test_coupon.jpg` - A real coupon image for self-testing

## Requirements
- **Format**: JPG or PNG
- **Size**: 200-500 KB
- **Dimensions**: 800x600 to 1920x1080 pixels
- **Content Must Include**:
  - Store name (e.g., "MYNTRA", "AMAZON", "FLIPKART")
  - Coupon code (e.g., "SAVE200", "DEAL50")
  - Expiry date (e.g., "Valid till 30 Sep 2025")
  - Cashback/discount amount (e.g., "₹200 off" or "50% OFF")

## Purpose
This image is used by the self-test to verify that the MiniCPM model:
1. Can perform inference within 2 seconds
2. Extracts real coupon fields (not mock data)
3. Returns valid JSON structure

## How to Add
1. Find or create a real Indian coupon image meeting the requirements above
2. Save it as `test_coupon.jpg` in this directory
3. Verify it's not a placeholder/mock image

## Example Sources
- Screenshot of a real coupon from your test data
- Use one from `coupon-training/data_collection/` if available
- Create using design tools with realistic coupon layout

