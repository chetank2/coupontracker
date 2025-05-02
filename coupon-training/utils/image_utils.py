#!/usr/bin/env python3
import cv2
import numpy as np

def resize_image(image, max_width=1600, max_height=1600):
    """Resize image while maintaining aspect ratio"""
    height, width = image.shape[:2]
    
    # Calculate new dimensions
    if width > max_width or height > max_height:
        if width > height:
            new_width = max_width
            new_height = int(height * (max_width / width))
        else:
            new_height = max_height
            new_width = int(width * (max_height / height))
        
        # Resize the image
        resized = cv2.resize(image, (new_width, new_height), interpolation=cv2.INTER_AREA)
        return resized
    
    return image

def enhance_contrast(image):
    """Enhance contrast using CLAHE"""
    # Convert to LAB color space
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    
    # Split the LAB image into L, A, and B channels
    l, a, b = cv2.split(lab)
    
    # Apply CLAHE to the L channel
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    cl = clahe.apply(l)
    
    # Merge the CLAHE enhanced L channel with the original A and B channels
    merged = cv2.merge((cl, a, b))
    
    # Convert back to BGR color space
    enhanced = cv2.cvtColor(merged, cv2.COLOR_LAB2BGR)
    
    return enhanced

def denoise_image(image):
    """Apply denoising to the image"""
    return cv2.fastNlMeansDenoisingColored(image, None, 10, 10, 7, 21)

def convert_to_grayscale(image):
    """Convert image to grayscale"""
    return cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

def adaptive_threshold(image):
    """Apply adaptive thresholding to grayscale image"""
    # Convert to grayscale if needed
    if len(image.shape) == 3:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    else:
        gray = image
    
    # Apply Gaussian blur
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    
    # Apply adaptive thresholding
    thresh = cv2.adaptiveThreshold(
        blurred, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 11, 2
    )
    
    return thresh

def deskew_image(image):
    """Deskew the image to straighten text"""
    # Convert to grayscale if needed
    if len(image.shape) == 3:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    else:
        gray = image
    
    # Apply threshold to get binary image
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    
    # Find all contours
    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    # Find the largest contour
    if contours:
        largest_contour = max(contours, key=cv2.contourArea)
        
        # Get the minimum area rectangle
        rect = cv2.minAreaRect(largest_contour)
        angle = rect[2]
        
        # Adjust the angle
        if angle < -45:
            angle = 90 + angle
        
        # Rotate the image
        (h, w) = image.shape[:2]
        center = (w // 2, h // 2)
        M = cv2.getRotationMatrix2D(center, angle, 1.0)
        rotated = cv2.warpAffine(image, M, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
        
        return rotated
    
    return image

def detect_text_regions(image):
    """Detect potential text regions in the image"""
    # Convert to grayscale if needed
    if len(image.shape) == 3:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    else:
        gray = image
    
    # Apply threshold to get binary image
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    
    # Apply morphological operations to find text regions
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    dilated = cv2.dilate(binary, kernel, iterations=3)
    
    # Find contours
    contours, _ = cv2.findContours(dilated, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    # Filter contours to find potential text regions
    text_regions = []
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        
        # Filter out very small regions
        if w < 20 or h < 20:
            continue
        
        # Filter out very large regions
        if w > image.shape[1] * 0.9 or h > image.shape[0] * 0.9:
            continue
        
        # Filter based on aspect ratio
        aspect_ratio = w / float(h)
        if aspect_ratio > 10 or aspect_ratio < 0.1:
            continue
        
        text_regions.append((x, y, x + w, y + h))
    
    return text_regions

def draw_regions(image, regions, color=(0, 255, 0), thickness=2):
    """Draw regions on an image"""
    result = image.copy()
    for region in regions:
        x1, y1, x2, y2 = region
        cv2.rectangle(result, (x1, y1), (x2, y2), color, thickness)
    return result
