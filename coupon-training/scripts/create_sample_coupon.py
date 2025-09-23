#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Create a sample coupon image for demonstration.
This script creates a synthetic coupon image for testing.
"""

import os
import sys
import argparse
import json
from PIL import Image, ImageDraw, ImageFont
import random

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

def create_sample_coupon(output_path, annotation_path, width=720, height=1600):
    """
    Create a sample coupon image

    Args:
        output_path: Path to save the image
        annotation_path: Path to save the annotation file
        width: Image width
        height: Image height

    Returns:
        bool: True if successful, False otherwise
    """
    try:
        # Create a blank image with a light background
        bg_color = (255, 255, 255)
        image = Image.new('RGB', (width, height), bg_color)
        draw = ImageDraw.Draw(image)

        # Try to load fonts
        try:
            title_font = ImageFont.truetype('Arial Bold.ttf', 60)
            code_font = ImageFont.truetype('Courier New.ttf', 48)
            normal_font = ImageFont.truetype('Arial.ttf', 36)
            small_font = ImageFont.truetype('Arial.ttf', 24)
        except:
            # Fallback to default font
            title_font = ImageFont.load_default()
            code_font = ImageFont.load_default()
            normal_font = ImageFont.load_default()
            small_font = ImageFont.load_default()

        # Add a header background - Myntra pink
        header_color = (255, 63, 108)
        draw.rectangle([0, 0, width, 200], fill=header_color)

        # Add store name
        store_name = "MYNTRA"
        store_text_width = draw.textlength(store_name, font=title_font)
        store_x = (width - store_text_width) // 2
        store_y = 70
        draw.text((store_x, store_y), store_name, font=title_font, fill=(255, 255, 255))

        # Add a border
        border_color = (255, 63, 108)
        border_width = 5
        draw.rectangle([border_width, 200 + border_width, width - border_width, height - border_width],
                      outline=border_color, width=border_width)

        # Add insider badge
        insider_text = "MYNTRA INSIDER"
        insider_text_width = draw.textlength(insider_text, font=small_font)
        insider_x = (width - insider_text_width) // 2
        insider_y = 220
        draw.rectangle([insider_x - 10, insider_y - 5, insider_x + insider_text_width + 10, insider_y + 30],
                      fill=(255, 63, 108))
        draw.text((insider_x, insider_y), insider_text, font=small_font, fill=(255, 255, 255))

        # Add discount amount
        amount_text = "FLAT ₹500 OFF"
        amount_text_width = draw.textlength(amount_text, font=title_font)
        amount_x = (width - amount_text_width) // 2
        amount_y = 300
        draw.text((amount_x, amount_y), amount_text, font=title_font, fill=(255, 63, 108))

        # Add minimum order value
        min_order_text = "On orders above ₹1999"
        min_order_text_width = draw.textlength(min_order_text, font=normal_font)
        min_order_x = (width - min_order_text_width) // 2
        min_order_y = 380
        draw.text((min_order_x, min_order_y), min_order_text, font=normal_font, fill=(100, 100, 100))

        # Add description
        desc_text = "Valid on select brands only"
        desc_text_width = draw.textlength(desc_text, font=normal_font)
        desc_x = (width - desc_text_width) // 2
        desc_y = 450
        draw.text((desc_x, desc_y), desc_text, font=normal_font, fill=(100, 100, 100))

        # Add a box for the coupon code
        code_box_y = 600
        code_box_height = 150
        draw.rectangle([50, code_box_y, width - 50, code_box_y + code_box_height],
                      fill=(245, 245, 250), outline=(255, 63, 108), width=3)

        # Add "USE CODE" text
        use_code_text = "USE CODE:"
        use_code_text_width = draw.textlength(use_code_text, font=small_font)
        use_code_x = (width - use_code_text_width) // 2
        use_code_y = code_box_y + 20
        draw.text((use_code_x, use_code_y), use_code_text, font=small_font, fill=(100, 100, 100))

        # Add coupon code
        code_text = "MYNTRA500"
        code_text_width = draw.textlength(code_text, font=code_font)
        code_x = (width - code_text_width) // 2
        code_y = code_box_y + 60
        draw.text((code_x, code_y), code_text, font=code_font, fill=(0, 0, 0))

        # Add "TAP TO COPY" button
        copy_text = "TAP TO COPY"
        copy_text_width = draw.textlength(copy_text, font=small_font)
        copy_x = (width - copy_text_width) // 2
        copy_y = code_box_y + code_box_height + 20
        draw.rectangle([copy_x - 20, copy_y - 5, copy_x + copy_text_width + 20, copy_y + 30],
                      fill=(255, 63, 108))
        draw.text((copy_x, copy_y), copy_text, font=small_font, fill=(255, 255, 255))

        # Add expiry date
        expiry_text = "Valid till: 30/06/2024"
        expiry_text_width = draw.textlength(expiry_text, font=normal_font)
        expiry_x = (width - expiry_text_width) // 2
        expiry_y = 850
        draw.text((expiry_x, expiry_y), expiry_text, font=normal_font, fill=(100, 100, 100))

        # Add exclusions
        exclusion_text = "Not valid on Myntra Luxe"
        exclusion_text_width = draw.textlength(exclusion_text, font=small_font)
        exclusion_x = (width - exclusion_text_width) // 2
        exclusion_y = 950
        draw.text((exclusion_x, exclusion_y), exclusion_text, font=small_font, fill=(150, 150, 150))

        # Add terms and conditions
        terms_text = "Terms & Conditions Apply"
        terms_text_width = draw.textlength(terms_text, font=normal_font)
        terms_x = (width - terms_text_width) // 2
        terms_y = 1400
        draw.text((terms_x, terms_y), terms_text, font=normal_font, fill=(150, 150, 150))

        # Save the image
        image.save(output_path)

        # Create annotation
        annotation = {
            'store': [
                {
                    'left': store_x,
                    'top': store_y,
                    'right': store_x + int(store_text_width),
                    'bottom': store_y + 70
                }
            ],
            'code': [
                {
                    'left': 50,
                    'top': code_box_y,
                    'right': width - 50,
                    'bottom': code_box_y + code_box_height
                }
            ],
            'amount': [
                {
                    'left': amount_x,
                    'top': amount_y,
                    'right': amount_x + int(amount_text_width),
                    'bottom': amount_y + 70
                }
            ],
            'expiry': [
                {
                    'left': expiry_x,
                    'top': expiry_y,
                    'right': expiry_x + int(expiry_text_width),
                    'bottom': expiry_y + 40
                }
            ],
            'description': [
                {
                    'left': desc_x,
                    'top': desc_y,
                    'right': desc_x + int(desc_text_width),
                    'bottom': desc_y + 40
                }
            ]
        }

        # Save the annotation
        with open(annotation_path, 'w') as f:
            json.dump(annotation, f, indent=2)

        return True

    except Exception as e:
        print(f"Error creating sample coupon: {e}")
        return False

def create_swiggy_coupon(output_path, annotation_path, width=720, height=1600):
    """
    Create a Swiggy coupon image

    Args:
        output_path: Path to save the image
        annotation_path: Path to save the annotation file
        width: Image width
        height: Image height

    Returns:
        bool: True if successful, False otherwise
    """
    try:
        # Create a blank image with a light background
        bg_color = (255, 248, 240)
        image = Image.new('RGB', (width, height), bg_color)
        draw = ImageDraw.Draw(image)

        # Try to load fonts
        try:
            title_font = ImageFont.truetype('Arial Bold.ttf', 60)
            code_font = ImageFont.truetype('Courier New.ttf', 48)
            normal_font = ImageFont.truetype('Arial.ttf', 36)
        except:
            # Fallback to default font
            title_font = ImageFont.load_default()
            code_font = ImageFont.load_default()
            normal_font = ImageFont.load_default()

        # Add a header background
        header_color = (252, 128, 25)  # Swiggy orange
        draw.rectangle([0, 0, width, 200], fill=header_color)

        # Add store name
        store_name = "SWIGGY"
        store_text_width = draw.textlength(store_name, font=title_font)
        store_x = (width - store_text_width) // 2
        store_y = 70
        draw.text((store_x, store_y), store_name, font=title_font, fill=(255, 255, 255))

        # Add a border
        border_color = (252, 128, 25)  # Swiggy orange
        border_width = 5
        draw.rectangle([border_width, 200 + border_width, width - border_width, height - border_width],
                      outline=border_color, width=border_width)

        # Add discount amount
        amount_text = "₹100 OFF"
        amount_text_width = draw.textlength(amount_text, font=title_font)
        amount_x = (width - amount_text_width) // 2
        amount_y = 300
        draw.text((amount_x, amount_y), amount_text, font=title_font, fill=(252, 128, 25))

        # Add description
        desc_text = "On orders above ₹300"
        desc_text_width = draw.textlength(desc_text, font=normal_font)
        desc_x = (width - desc_text_width) // 2
        desc_y = 400
        draw.text((desc_x, desc_y), desc_text, font=normal_font, fill=(100, 100, 100))

        # Add a box for the coupon code
        code_box_y = 700
        code_box_height = 150
        draw.rectangle([50, code_box_y, width - 50, code_box_y + code_box_height],
                      outline=(252, 128, 25), width=3)

        # Add coupon code
        code_text = "SWIGGY100"
        code_text_width = draw.textlength(code_text, font=code_font)
        code_x = (width - code_text_width) // 2
        code_y = code_box_y + 50
        draw.text((code_x, code_y), code_text, font=code_font, fill=(0, 0, 0))

        # Add expiry date
        expiry_text = "Valid till: 15/01/2024"
        expiry_text_width = draw.textlength(expiry_text, font=normal_font)
        expiry_x = (width - expiry_text_width) // 2
        expiry_y = 1000
        draw.text((expiry_x, expiry_y), expiry_text, font=normal_font, fill=(100, 100, 100))

        # Add terms and conditions
        terms_text = "Terms & Conditions Apply"
        terms_text_width = draw.textlength(terms_text, font=normal_font)
        terms_x = (width - terms_text_width) // 2
        terms_y = 1400
        draw.text((terms_x, terms_y), terms_text, font=normal_font, fill=(150, 150, 150))

        # Save the image
        image.save(output_path)

        # Create annotation
        annotation = {
            'store': [
                {
                    'left': store_x,
                    'top': store_y,
                    'right': store_x + int(store_text_width),
                    'bottom': store_y + 70
                }
            ],
            'code': [
                {
                    'left': 50,
                    'top': code_box_y,
                    'right': width - 50,
                    'bottom': code_box_y + code_box_height
                }
            ],
            'amount': [
                {
                    'left': amount_x,
                    'top': amount_y,
                    'right': amount_x + int(amount_text_width),
                    'bottom': amount_y + 70
                }
            ],
            'expiry': [
                {
                    'left': expiry_x,
                    'top': expiry_y,
                    'right': expiry_x + int(expiry_text_width),
                    'bottom': expiry_y + 40
                }
            ],
            'description': [
                {
                    'left': desc_x,
                    'top': desc_y,
                    'right': desc_x + int(desc_text_width),
                    'bottom': desc_y + 40
                }
            ]
        }

        # Save the annotation
        with open(annotation_path, 'w') as f:
            json.dump(annotation, f, indent=2)

        return True

    except Exception as e:
        print(f"Error creating Swiggy coupon: {e}")
        return False

def create_citizen_coupon(output_path, annotation_path, width=720, height=1600):
    """
    Create a Citizen watch coupon image

    Args:
        output_path: Path to save the image
        annotation_path: Path to save the annotation file
        width: Image width
        height: Image height

    Returns:
        bool: True if successful, False otherwise
    """
    try:
        # Create a blank image with a dark background
        bg_color = (0, 30, 60)
        image = Image.new('RGB', (width, height), bg_color)
        draw = ImageDraw.Draw(image)

        # Try to load fonts
        try:
            title_font = ImageFont.truetype('Arial Bold.ttf', 60)
            code_font = ImageFont.truetype('Courier New.ttf', 48)
            normal_font = ImageFont.truetype('Arial.ttf', 36)
        except:
            # Fallback to default font
            title_font = ImageFont.load_default()
            code_font = ImageFont.load_default()
            normal_font = ImageFont.load_default()

        # Add a header background
        header_color = (0, 50, 100)
        draw.rectangle([0, 0, width, 200], fill=header_color)

        # Add store name
        store_name = "CITIZEN"
        store_text_width = draw.textlength(store_name, font=title_font)
        store_x = (width - store_text_width) // 2
        store_y = 70
        draw.text((store_x, store_y), store_name, font=title_font, fill=(255, 255, 255))

        # Add a border
        border_color = (200, 200, 200)
        border_width = 5
        draw.rectangle([border_width, 200 + border_width, width - border_width, height - border_width],
                      outline=border_color, width=border_width)

        # Add discount amount
        amount_text = "40% OFF"
        amount_text_width = draw.textlength(amount_text, font=title_font)
        amount_x = (width - amount_text_width) // 2
        amount_y = 300
        draw.text((amount_x, amount_y), amount_text, font=title_font, fill=(255, 215, 0))  # Gold color

        # Add description
        desc_text = "On all Eco-Drive watches"
        desc_text_width = draw.textlength(desc_text, font=normal_font)
        desc_x = (width - desc_text_width) // 2
        desc_y = 400
        draw.text((desc_x, desc_y), desc_text, font=normal_font, fill=(200, 200, 200))

        # Add a box for the coupon code
        code_box_y = 700
        code_box_height = 150
        draw.rectangle([50, code_box_y, width - 50, code_box_y + code_box_height],
                      outline=(255, 215, 0), width=3)

        # Add coupon code
        code_text = "CITIZEN40"
        code_text_width = draw.textlength(code_text, font=code_font)
        code_x = (width - code_text_width) // 2
        code_y = code_box_y + 50
        draw.text((code_x, code_y), code_text, font=code_font, fill=(255, 255, 255))

        # Add expiry date
        expiry_text = "Valid till: 28/02/2024"
        expiry_text_width = draw.textlength(expiry_text, font=normal_font)
        expiry_x = (width - expiry_text_width) // 2
        expiry_y = 1000
        draw.text((expiry_x, expiry_y), expiry_text, font=normal_font, fill=(200, 200, 200))

        # Add terms and conditions
        terms_text = "Terms & Conditions Apply"
        terms_text_width = draw.textlength(terms_text, font=normal_font)
        terms_x = (width - terms_text_width) // 2
        terms_y = 1400
        draw.text((terms_x, terms_y), terms_text, font=normal_font, fill=(150, 150, 150))

        # Save the image
        image.save(output_path)

        # Create annotation
        annotation = {
            'store': [
                {
                    'left': store_x,
                    'top': store_y,
                    'right': store_x + int(store_text_width),
                    'bottom': store_y + 70
                }
            ],
            'code': [
                {
                    'left': 50,
                    'top': code_box_y,
                    'right': width - 50,
                    'bottom': code_box_y + code_box_height
                }
            ],
            'amount': [
                {
                    'left': amount_x,
                    'top': amount_y,
                    'right': amount_x + int(amount_text_width),
                    'bottom': amount_y + 70
                }
            ],
            'expiry': [
                {
                    'left': expiry_x,
                    'top': expiry_y,
                    'right': expiry_x + int(expiry_text_width),
                    'bottom': expiry_y + 40
                }
            ],
            'description': [
                {
                    'left': desc_x,
                    'top': desc_y,
                    'right': desc_x + int(desc_text_width),
                    'bottom': desc_y + 40
                }
            ]
        }

        # Save the annotation
        with open(annotation_path, 'w') as f:
            json.dump(annotation, f, indent=2)

        return True

    except Exception as e:
        print(f"Error creating Citizen coupon: {e}")
        return False

def create_phonepe_coupon(output_path, annotation_path, width=720, height=1600):
    """
    Create a PhonePe coupon image

    Args:
        output_path: Path to save the image
        annotation_path: Path to save the annotation file
        width: Image width
        height: Image height

    Returns:
        bool: True if successful, False otherwise
    """
    try:
        # Create a blank image with a light background
        bg_color = (245, 250, 255)
        image = Image.new('RGB', (width, height), bg_color)
        draw = ImageDraw.Draw(image)

        # Try to load fonts
        try:
            title_font = ImageFont.truetype('Arial Bold.ttf', 60)
            code_font = ImageFont.truetype('Courier New.ttf', 48)
            normal_font = ImageFont.truetype('Arial.ttf', 36)
        except:
            # Fallback to default font
            title_font = ImageFont.load_default()
            code_font = ImageFont.load_default()
            normal_font = ImageFont.load_default()

        # Add a header background
        header_color = (86, 44, 168)  # PhonePe purple
        draw.rectangle([0, 0, width, 200], fill=header_color)

        # Add store name
        store_name = "PHONEPE"
        store_text_width = draw.textlength(store_name, font=title_font)
        store_x = (width - store_text_width) // 2
        store_y = 70
        draw.text((store_x, store_y), store_name, font=title_font, fill=(255, 255, 255))

        # Add a border
        border_color = (86, 44, 168)  # PhonePe purple
        border_width = 5
        draw.rectangle([border_width, 200 + border_width, width - border_width, height - border_width],
                      outline=border_color, width=border_width)

        # Add brand logo/name
        brand_name = "MINIMALIST"
        brand_text_width = draw.textlength(brand_name, font=normal_font)
        brand_x = (width - brand_text_width) // 2
        brand_y = 250
        draw.text((brand_x, brand_y), brand_name, font=normal_font, fill=(86, 44, 168))

        # Add discount amount
        amount_text = "₹200 OFF"
        amount_text_width = draw.textlength(amount_text, font=title_font)
        amount_x = (width - amount_text_width) // 2
        amount_y = 350
        draw.text((amount_x, amount_y), amount_text, font=title_font, fill=(86, 44, 168))

        # Add description
        desc_text = "On Skincare & Haircare Products"
        desc_text_width = draw.textlength(desc_text, font=normal_font)
        desc_x = (width - desc_text_width) // 2
        desc_y = 450
        draw.text((desc_x, desc_y), desc_text, font=normal_font, fill=(100, 100, 100))

        # Add a box for the coupon code
        code_box_y = 700
        code_box_height = 150
        draw.rectangle([50, code_box_y, width - 50, code_box_y + code_box_height],
                      outline=(86, 44, 168), width=3)

        # Add coupon code
        code_text = "MNPP200"
        code_text_width = draw.textlength(code_text, font=code_font)
        code_x = (width - code_text_width) // 2
        code_y = code_box_y + 50
        draw.text((code_x, code_y), code_text, font=code_font, fill=(0, 0, 0))

        # Add expiry date
        expiry_text = "Expires on: 31/07/2024"
        expiry_text_width = draw.textlength(expiry_text, font=normal_font)
        expiry_x = (width - expiry_text_width) // 2
        expiry_y = 1000
        draw.text((expiry_x, expiry_y), expiry_text, font=normal_font, fill=(100, 100, 100))

        # Add terms and conditions
        terms_text = "Terms & Conditions Apply"
        terms_text_width = draw.textlength(terms_text, font=normal_font)
        terms_x = (width - terms_text_width) // 2
        terms_y = 1400
        draw.text((terms_x, terms_y), terms_text, font=normal_font, fill=(150, 150, 150))

        # Save the image
        image.save(output_path)

        # Create annotation
        annotation = {
            'store': [
                {
                    'left': store_x,
                    'top': store_y,
                    'right': store_x + int(store_text_width),
                    'bottom': store_y + 70
                }
            ],
            'code': [
                {
                    'left': 50,
                    'top': code_box_y,
                    'right': width - 50,
                    'bottom': code_box_y + code_box_height
                }
            ],
            'amount': [
                {
                    'left': amount_x,
                    'top': amount_y,
                    'right': amount_x + int(amount_text_width),
                    'bottom': amount_y + 70
                }
            ],
            'expiry': [
                {
                    'left': expiry_x,
                    'top': expiry_y,
                    'right': expiry_x + int(expiry_text_width),
                    'bottom': expiry_y + 40
                }
            ],
            'description': [
                {
                    'left': desc_x,
                    'top': desc_y,
                    'right': desc_x + int(desc_text_width),
                    'bottom': desc_y + 40
                }
            ]
        }

        # Save the annotation
        with open(annotation_path, 'w') as f:
            json.dump(annotation, f, indent=2)

        return True

    except Exception as e:
        print(f"Error creating PhonePe coupon: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Create sample coupon images")
    parser.add_argument("--output-dir", default="../data/raw", help="Directory to save the images")
    parser.add_argument("--annotation-dir", default="../data/annotated", help="Directory to save the annotation files")

    args = parser.parse_args()

    # Create output directories if they don't exist
    os.makedirs(args.output_dir, exist_ok=True)
    os.makedirs(args.annotation_dir, exist_ok=True)

    # Create Myntra coupon
    myntra_output_path = os.path.join(args.output_dir, "sample_myntra_coupon.jpg")
    myntra_annotation_path = os.path.join(args.annotation_dir, "sample_myntra_coupon_annotations.json")

    if create_sample_coupon(myntra_output_path, myntra_annotation_path):
        print(f"Successfully created Myntra coupon image: {myntra_output_path}")
    else:
        print("Failed to create Myntra coupon image")

    # Create Swiggy coupon
    swiggy_output_path = os.path.join(args.output_dir, "sample_swiggy_coupon.jpg")
    swiggy_annotation_path = os.path.join(args.annotation_dir, "sample_swiggy_coupon_annotations.json")

    if create_swiggy_coupon(swiggy_output_path, swiggy_annotation_path):
        print(f"Successfully created Swiggy coupon image: {swiggy_output_path}")
    else:
        print("Failed to create Swiggy coupon image")

    # Create Citizen coupon
    citizen_output_path = os.path.join(args.output_dir, "sample_citizen_coupon.jpg")
    citizen_annotation_path = os.path.join(args.annotation_dir, "sample_citizen_coupon_annotations.json")

    if create_citizen_coupon(citizen_output_path, citizen_annotation_path):
        print(f"Successfully created Citizen coupon image: {citizen_output_path}")
    else:
        print("Failed to create Citizen coupon image")

    # Create PhonePe coupon
    phonepe_output_path = os.path.join(args.output_dir, "sample_phonepe_coupon.jpg")
    phonepe_annotation_path = os.path.join(args.annotation_dir, "sample_phonepe_coupon_annotations.json")

    if create_phonepe_coupon(phonepe_output_path, phonepe_annotation_path):
        print(f"Successfully created PhonePe coupon image: {phonepe_output_path}")
    else:
        print("Failed to create PhonePe coupon image")

    print(f"\nAll annotation files saved to: {args.annotation_dir}")
    print("\nYou can now use these sample images for training and testing.")

if __name__ == "__main__":
    main()
