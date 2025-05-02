#!/usr/bin/env python3
import os
import cv2
import json
import numpy as np
import argparse
from pathlib import Path

# Define the regions of interest
REGIONS = [
    "store_name",
    "coupon_code",
    "expiry_date",
    "description",
    "amount"
]

class CouponAnnotator:
    def __init__(self, image_path, output_dir):
        self.image_path = image_path
        self.output_dir = output_dir
        self.image = cv2.imread(str(image_path))
        if self.image is None:
            raise ValueError(f"Could not read image: {image_path}")
        
        self.original_image = self.image.copy()
        self.height, self.width = self.image.shape[:2]
        
        # Initialize annotations
        self.annotations = {region: None for region in REGIONS}
        self.current_region = None
        self.drawing = False
        self.start_point = None
        self.end_point = None
        
        # Create window
        self.window_name = "Coupon Annotator"
        cv2.namedWindow(self.window_name, cv2.WINDOW_NORMAL)
        cv2.resizeWindow(self.window_name, 1200, 800)
        cv2.setMouseCallback(self.window_name, self.mouse_callback)
    
    def mouse_callback(self, event, x, y, flags, param):
        if self.current_region is None:
            return
        
        if event == cv2.EVENT_LBUTTONDOWN:
            self.drawing = True
            self.start_point = (x, y)
            self.end_point = (x, y)
        
        elif event == cv2.EVENT_MOUSEMOVE and self.drawing:
            self.end_point = (x, y)
            # Update the display
            self.display_image()
        
        elif event == cv2.EVENT_LBUTTONUP:
            self.drawing = False
            self.end_point = (x, y)
            
            # Save the region
            x1, y1 = min(self.start_point[0], self.end_point[0]), min(self.start_point[1], self.end_point[1])
            x2, y2 = max(self.start_point[0], self.end_point[0]), max(self.start_point[1], self.end_point[1])
            
            self.annotations[self.current_region] = [x1, y1, x2, y2]
            print(f"Region '{self.current_region}' set to: [{x1}, {y1}, {x2}, {y2}]")
            
            # Update the display
            self.display_image()
    
    def display_image(self):
        # Create a copy of the original image
        display = self.original_image.copy()
        
        # Draw all saved annotations
        for region, coords in self.annotations.items():
            if coords:
                x1, y1, x2, y2 = coords
                color = (0, 255, 0)  # Green for saved regions
                cv2.rectangle(display, (x1, y1), (x2, y2), color, 2)
                cv2.putText(display, region, (x1, y1 - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2)
        
        # Draw the current region being annotated
        if self.drawing and self.start_point and self.end_point:
            color = (0, 0, 255)  # Red for current region
            cv2.rectangle(display, self.start_point, self.end_point, color, 2)
            cv2.putText(display, self.current_region, (self.start_point[0], self.start_point[1] - 10), 
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2)
        
        # Display instructions
        instructions = [
            f"Current region: {self.current_region if self.current_region else 'None'}",
            "Press 1-5 to select region:",
            "1: Store Name, 2: Coupon Code, 3: Expiry Date, 4: Description, 5: Amount",
            "Press 'c' to clear current region",
            "Press 's' to save annotations",
            "Press 'q' to quit without saving"
        ]
        
        for i, line in enumerate(instructions):
            cv2.putText(display, line, (10, 30 + i * 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 0, 0), 2)
        
        # Show the image
        cv2.imshow(self.window_name, display)
    
    def run(self):
        while True:
            self.display_image()
            key = cv2.waitKey(1) & 0xFF
            
            # Region selection
            if key == ord('1'):
                self.current_region = "store_name"
            elif key == ord('2'):
                self.current_region = "coupon_code"
            elif key == ord('3'):
                self.current_region = "expiry_date"
            elif key == ord('4'):
                self.current_region = "description"
            elif key == ord('5'):
                self.current_region = "amount"
            
            # Clear current region
            elif key == ord('c'):
                if self.current_region:
                    self.annotations[self.current_region] = None
                    print(f"Cleared region: {self.current_region}")
            
            # Save annotations
            elif key == ord('s'):
                self.save_annotations()
                break
            
            # Quit without saving
            elif key == ord('q'):
                print("Quitting without saving")
                break
        
        cv2.destroyAllWindows()
    
    def save_annotations(self):
        # Create filename for annotations
        image_filename = os.path.basename(self.image_path)
        name, ext = os.path.splitext(image_filename)
        json_filename = f"{name}_annotations.json"
        json_path = os.path.join(self.output_dir, json_filename)
        
        # Save annotations to JSON file
        with open(json_path, 'w') as f:
            json.dump({
                "image_path": str(self.image_path),
                "image_width": self.width,
                "image_height": self.height,
                "regions": self.annotations
            }, f, indent=4)
        
        print(f"Annotations saved to: {json_path}")
        
        # Save annotated image
        annotated_image = self.original_image.copy()
        for region, coords in self.annotations.items():
            if coords:
                x1, y1, x2, y2 = coords
                color = (0, 255, 0)  # Green
                cv2.rectangle(annotated_image, (x1, y1), (x2, y2), color, 2)
                cv2.putText(annotated_image, region, (x1, y1 - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2)
        
        # Save the annotated image
        annotated_filename = f"{name}_annotated{ext}"
        annotated_path = os.path.join(self.output_dir, annotated_filename)
        cv2.imwrite(annotated_path, annotated_image)
        print(f"Annotated image saved to: {annotated_path}")

def main():
    parser = argparse.ArgumentParser(description="Annotate coupon images for training")
    parser.add_argument("--input-dir", default="../data/raw", help="Directory containing raw images")
    parser.add_argument("--output-dir", default="../data/annotated", help="Directory to save annotations")
    parser.add_argument("--image", help="Specific image to annotate (optional)")
    
    args = parser.parse_args()
    
    # Ensure output directory exists
    os.makedirs(args.output_dir, exist_ok=True)
    
    # Get image paths
    if args.image:
        image_paths = [Path(args.image)]
    else:
        image_extensions = ['.jpg', '.jpeg', '.png', '.bmp', '.tiff']
        image_paths = []
        for ext in image_extensions:
            image_paths.extend(list(Path(args.input_dir).glob(f"*{ext}")))
            image_paths.extend(list(Path(args.input_dir).glob(f"*{ext.upper()}")))
    
    if not image_paths:
        print(f"No images found in {args.input_dir}")
        return
    
    print(f"Found {len(image_paths)} images to annotate")
    
    # Process each image
    for i, image_path in enumerate(image_paths):
        print(f"\nAnnotating image {i+1}/{len(image_paths)}: {image_path}")
        try:
            annotator = CouponAnnotator(image_path, args.output_dir)
            annotator.run()
        except Exception as e:
            print(f"Error annotating {image_path}: {e}")
    
    print("Annotation complete!")

if __name__ == "__main__":
    main()
