#!/usr/bin/env python3
import pytesseract
import sys

def main():
    try:
        # Print Tesseract version
        print(f"Tesseract Version (via pytesseract): {pytesseract.get_tesseract_version()}")
        
        # Print available languages
        langs = pytesseract.get_languages()
        print(f"Available languages: {langs}")
        
        print("Tesseract is properly installed and configured!")
        return 0
    except Exception as e:
        print(f"Error: {e}")
        return 1

if __name__ == "__main__":
    sys.exit(main())
