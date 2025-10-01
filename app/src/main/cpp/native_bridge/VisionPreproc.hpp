#pragma once
#include <vector>
#include <cstdint>
#include <algorithm>

/**
 * Vision preprocessing utilities
 * 
 * Provides letterbox resizing and other image preprocessing
 * for vision models that expect fixed input sizes.
 */

/**
 * Letterbox resize RGB image to target size
 * 
 * Maintains aspect ratio by padding with gray background.
 * Uses simple nearest-neighbor interpolation.
 * 
 * @param src Source RGB bytes (w * h * 3)
 * @param w Source width
 * @param h Source height
 * @param target Target size (e.g., 336, 768)
 * @param dst Output RGB bytes (target * target * 3)
 * @param ow Output width after resize (before padding)
 * @param oh Output height after resize (before padding)
 */
inline void letterbox_rgb(const uint8_t* src, int w, int h,
                          int target, std::vector<uint8_t>& dst, int& ow, int& oh) {
    // Calculate resize ratio (maintain aspect ratio)
    float r = std::min((float)target / w, (float)target / h);
    ow = std::max(1, (int)(w * r));
    oh = std::max(1, (int)(h * r));
    
    // Initialize output with gray background
    dst.assign(target * target * 3, 240);
    
    // Simple nearest-neighbor resize
    for (int y = 0; y < oh; ++y) {
        for (int x = 0; x < ow; ++x) {
            // Map to source coordinates
            int sx = x * w / ow;
            int sy = y * h / oh;
            
            // Get source pixel
            const uint8_t* sp = src + (sy * w + sx) * 3;
            
            // Calculate output position (centered)
            int ox = (target - ow) / 2 + x;
            int oy = (target - oh) / 2 + y;
            
            // Set output pixel
            uint8_t* dp = dst.data() + (oy * target + ox) * 3;
            dp[0] = sp[0]; // R
            dp[1] = sp[1]; // G
            dp[2] = sp[2]; // B
        }
    }
}

/**
 * Convert RGB to normalized float (for some models)
 * 
 * @param rgb Input RGB bytes
 * @param size Number of bytes (w * h * 3)
 * @param out Output float array
 * @param mean Channel means [R, G, B]
 * @param std Channel std devs [R, G, B]
 */
inline void normalize_rgb(const uint8_t* rgb, int size,
                          std::vector<float>& out,
                          const float mean[3] = nullptr,
                          const float std[3] = nullptr) {
    out.resize(size);
    
    // Default ImageNet normalization if not provided
    float default_mean[3] = {0.485f, 0.456f, 0.406f};
    float default_std[3] = {0.229f, 0.224f, 0.225f};
    
    const float* m = mean ? mean : default_mean;
    const float* s = std ? std : default_std;
    
    for (int i = 0; i < size; i += 3) {
        out[i + 0] = (rgb[i + 0] / 255.0f - m[0]) / s[0]; // R
        out[i + 1] = (rgb[i + 1] / 255.0f - m[1]) / s[1]; // G
        out[i + 2] = (rgb[i + 2] / 255.0f - m[2]) / s[2]; // B
    }
}

