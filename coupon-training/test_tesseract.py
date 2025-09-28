#!/usr/bin/env python3
import sys

try:  # pragma: no cover - optional dependency for OCR validation
    import pytesseract  # type: ignore
except ImportError:  # pragma: no cover - handled gracefully during runtime
    pytesseract = None  # type: ignore[assignment]

def main():
    if pytesseract is None:
        print("pytesseract is not installed. Install it with 'pip install pytesseract'.")
        return 1

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
