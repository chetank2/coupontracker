#!/usr/bin/env python3
"""
Script to create sample CRED coupon images for training the CouponTracker model.
"""

import os
import random
from PIL import Image, ImageDraw, ImageFont
import datetime

# Directory to save the images
SAVE_DIR = "data/cred_coupons"
os.makedirs(SAVE_DIR, exist_ok=True)

# Define coupon types and their properties
COUPON_TYPES = [
    {
        "brand": "CRED",
        "partner": "Swiggy",
        "discount": "₹150 OFF",
        "code": "CREDSWIGGY150",
        "min_order": "₹300",
        "expiry_days": 30,
        "bg_color": (0, 0, 0),
        "text_color": (255, 255, 255),
        "accent_color": (255, 59, 48)
    },
    {
        "brand": "CRED",
        "partner": "Myntra",
        "discount": "40% OFF",
        "code": "CREDMYNTRA40",
        "min_order": "₹1000",
        "expiry_days": 15,
        "bg_color": (0, 0, 0),
        "text_color": (255, 255, 255),
        "accent_color": (255, 149, 0)
    },
    {
        "brand": "CRED",
        "partner": "Zomato",
        "discount": "₹200 OFF",
        "code": "CREDZOMATO200",
        "min_order": "₹500",
        "expiry_days": 7,
        "bg_color": (0, 0, 0),
        "text_color": (255, 255, 255),
        "accent_color": (255, 45, 85)
    },
    {
        "brand": "CRED",
        "partner": "Citizen",
        "discount": "40% OFF",
        "code": "CREDCITIZEN40",
        "min_order": "₹5000",
        "expiry_days": 45,
        "bg_color": (0, 0, 0),
        "text_color": (255, 255, 255),
        "accent_color": (52, 199, 89)
    },
    {
        "brand": "CRED",
        "partner": "Zepto",
        "discount": "₹100 OFF",
        "code": "CREDZEPTO100",
        "min_order": "₹250",
        "expiry_days": 10,
        "bg_color": (0, 0, 0),
        "text_color": (255, 255, 255),
        "accent_color": (0, 122, 255)
    }
]

def create_coupon_image(coupon_data, index):
    """Create a coupon image with the given data."""
    # Set up the image
    width, height = 800, 400
    image = Image.new('RGB', (width, height), coupon_data["bg_color"])
    draw = ImageDraw.Draw(image)
    
    try:
        # Try to load fonts (fallback to default if not available)
        try:
            title_font = ImageFont.truetype("Arial Bold.ttf", 36)
            code_font = ImageFont.truetype("Arial.ttf", 32)
            body_font = ImageFont.truetype("Arial.ttf", 24)
        except IOError:
            # Use default font
            title_font = ImageFont.load_default()
            code_font = ImageFont.load_default()
            body_font = ImageFont.load_default()
        
        # Calculate expiry date
        today = datetime.datetime.now()
        expiry_date = today + datetime.timedelta(days=coupon_data["expiry_days"])
        expiry_str = expiry_date.strftime("%d %b %Y")
        
        # Draw brand logo
        draw.rectangle([40, 40, 160, 80], fill=coupon_data["accent_color"])
        draw.text((50, 45), coupon_data["brand"], font=title_font, fill=coupon_data["text_color"])
        
        # Draw discount
        draw.text((40, 100), coupon_data["discount"], font=title_font, fill=coupon_data["accent_color"])
        
        # Draw partner name
        draw.text((40, 150), f"on {coupon_data['partner']}", font=body_font, fill=coupon_data["text_color"])
        
        # Draw minimum order
        draw.text((40, 190), f"Min order: {coupon_data['min_order']}", font=body_font, fill=coupon_data["text_color"])
        
        # Draw expiry
        draw.text((40, 230), f"Valid till: {expiry_str}", font=body_font, fill=coupon_data["text_color"])
        
        # Draw coupon code
        draw.rectangle([40, 280, 760, 340], outline=coupon_data["accent_color"], width=2)
        draw.text((50, 290), f"CODE: {coupon_data['code']}", font=code_font, fill=coupon_data["text_color"])
        
        # Save the image
        filename = f"cred_coupon_{index}_{coupon_data['partner'].lower()}.png"
        save_path = os.path.join(SAVE_DIR, filename)
        image.save(save_path)
        print(f"Created {save_path}")
        
        return save_path
    except Exception as e:
        print(f"Error creating coupon image: {e}")
        return None

def create_variations(base_coupon, num_variations=3):
    """Create variations of a base coupon by changing some properties."""
    variations = []
    
    for i in range(num_variations):
        # Create a copy of the base coupon
        variation = base_coupon.copy()
        
        # Modify some properties
        if random.random() > 0.5:
            # Change discount amount
            discount_value = int(random.choice([50, 75, 100, 125, 150, 200, 250, 300]))
            variation["discount"] = f"₹{discount_value} OFF"
            variation["code"] = f"{variation['brand']}{variation['partner']}{discount_value}"
        else:
            # Change discount percentage
            discount_percent = int(random.choice([10, 15, 20, 25, 30, 35, 40, 50]))
            variation["discount"] = f"{discount_percent}% OFF"
            variation["code"] = f"{variation['brand']}{variation['partner']}{discount_percent}"
        
        # Change minimum order
        min_order_value = int(random.choice([100, 200, 300, 500, 750, 1000, 1500, 2000]))
        variation["min_order"] = f"₹{min_order_value}"
        
        # Change expiry days
        variation["expiry_days"] = random.choice([3, 5, 7, 10, 15, 30, 45, 60])
        
        variations.append(variation)
    
    return variations

def main():
    """Main function to create sample CRED coupon images."""
    # Clear existing files
    for file in os.listdir(SAVE_DIR):
        if file.startswith("cred_coupon_") and file.endswith((".png", ".jpg", ".jpeg")):
            os.remove(os.path.join(SAVE_DIR, file))
    
    # Create base coupons
    created_images = []
    for i, coupon_data in enumerate(COUPON_TYPES):
        image_path = create_coupon_image(coupon_data, i)
        if image_path:
            created_images.append(image_path)
    
    # Create variations
    all_variations = []
    for coupon_data in COUPON_TYPES:
        variations = create_variations(coupon_data)
        all_variations.extend(variations)
    
    # Create variation images
    for i, variation in enumerate(all_variations):
        image_path = create_coupon_image(variation, i + len(COUPON_TYPES))
        if image_path:
            created_images.append(image_path)
    
    print(f"Created {len(created_images)} sample coupon images in {SAVE_DIR}")

if __name__ == "__main__":
    main()
