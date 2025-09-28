# Outlier Handling Improvements for CouponTracker

This document describes the improvements made to handle outliers in the CouponTracker training data based on the outlier analysis findings.

## Overview of Improvements

We've implemented several improvements to address the outliers identified in both the image dataset and Reddit coupon links:

1. **Balanced Dataset Creation**: Added synthetic coupons to balance underrepresented coupon types
2. **Enhanced Data Augmentation**: Implemented advanced augmentation techniques with special handling for outliers
3. **Feature Engineering**: Added specialized features for dates, prices, and promotion types
4. **Weighted Training**: Implemented a weighted training approach to handle outliers appropriately
5. **Integrated Pipeline**: Created a comprehensive pipeline that combines all these improvements

## 1. Balanced Dataset Creation

The `balance_reddit_dataset.py` script creates synthetic coupons to balance the dataset:

- **Standard Coupons**: Regular discount coupons with percentage-based offers
- **Price-Specific Coupons**: Coupons with specific price amounts (e.g., ₹500 OFF)
- **Dated Coupons**: Coupons with explicit expiry dates

This addresses the imbalance in the dataset where certain coupon types (like dated coupons) were underrepresented and considered outliers.

### Usage:

```bash
python scripts/balance_reddit_dataset.py \
  --output-dir ../data/raw/balanced \
  --annotation-dir ../data/annotated/balanced \
  --standard-count 10 \
  --price-specific-count 10 \
  --dated-count 10
```

## 2. Enhanced Data Augmentation

The `enhanced_augmentation.py` script implements advanced augmentation techniques:

- **Basic Augmentations**: Brightness, contrast, rotation, blur
- **Advanced Augmentations**: Shadow, perspective transform, ISO noise, JPEG compression
- **Realistic Augmentations**: Glare, moiré pattern, poor lighting, creases/folds

It also includes special handling for outliers with a configurable weight parameter to control their influence.

### Usage:

```bash
python scripts/enhanced_augmentation.py \
  --input-dir ../data/raw/balanced \
  --annotation-dir ../data/annotated/balanced \
  --output-dir ../data/augmented \
  --output-annotation-dir ../data/annotated/augmented \
  --augmentation-types basic advanced realistic \
  --include-outliers \
  --outlier-weight 0.5
```

## 3. Feature Engineering

The `feature_engineering.py` script implements specialized feature extraction:

- **Date Features**: Extracts and validates dates in various formats
- **Price Features**: Detects currency amounts and percentage discounts
- **Promotion Type Features**: Identifies different types of promotions (e.g., "Buy 1 Get 1")

These features help the model better understand the different types of coupons, including those that were previously considered outliers.

### Usage:

```bash
python scripts/feature_engineering.py \
  --image-dir ../data/augmented \
  --annotation-dir ../data/annotated/augmented \
  --output-path ../data/features/coupon_features.csv \
  --analyze
```

## 4. Weighted Training

The `weighted_training.py` script implements a weighted training approach:

- Assigns different weights to normal and outlier samples
- Uses a configurable outlier weight parameter (0-1)
- Extracts rich features from images and annotations
- Trains a robust model that handles outliers appropriately

### Usage:

```bash
python scripts/weighted_training.py \
  --image-dir ../data/augmented \
  --annotation-dir ../data/annotated/augmented \
  --features-path ../data/features/coupon_features.csv \
  --model-path ../data/models/coupon_classifier.joblib \
  --outlier-weight 0.5
```

## 5. Integrated Pipeline

The `improved_training_pipeline.py` script integrates all these improvements into a single pipeline:

1. Copies input data to a structured directory
2. Balances the dataset with synthetic coupons
3. Applies enhanced data augmentation
4. Performs feature engineering
5. Trains a weighted model
6. Creates a comprehensive report

### Usage:

```bash
python scripts/improved_training_pipeline.py \
  --base-dir ../data/improved_pipeline \
  --input-dir ../data/raw \
  --annotation-dir ../data/annotated \
  --outlier-weight 0.5 \
  --standard-count 10 \
  --price-specific-count 10 \
  --dated-count 10
```

## Benefits of These Improvements

1. **Better Handling of Diverse Coupon Types**: The model can now recognize a wider variety of coupon formats, including those that were previously considered outliers.

2. **Balanced Dataset**: The synthetic coupon generation ensures that all coupon types are well-represented in the training data.

3. **Realistic Augmentations**: The enhanced augmentation techniques simulate real-world conditions, making the model more robust.

4. **Specialized Feature Extraction**: The feature engineering extracts meaningful information from dates, prices, and promotion types.

5. **Appropriate Outlier Influence**: The weighted training approach ensures that outliers contribute to the model without dominating it.

## Conclusion

These improvements address all the recommendations from the outlier analysis:

1. ✅ Keep most outliers for diversity
2. ✅ Remove the request link that might confuse the model
3. ✅ Balance the dataset with underrepresented coupon types
4. ✅ Augment the dataset with realistic variations
5. ✅ Implement weighted training to control outlier influence
6. ✅ Add feature engineering for dates, prices, and promotion types

By implementing these improvements, we've created a more robust and accurate coupon recognition system that can handle a wide variety of coupon formats, including those that were previously considered outliers.
