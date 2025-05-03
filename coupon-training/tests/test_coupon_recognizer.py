#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Unit tests for the testable coupon recognizer
"""

import os
import sys
import unittest
from unittest.mock import Mock, patch, MagicMock
import numpy as np
from PIL import Image
import json

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Import the module to test
from scripts.testable_coupon_recognizer import (
    CouponRecognizer,
    ImageLoader,
    TextExtractor,
    ImagePreprocessor,
    TextPostprocessor,
    ConfidenceCalculator
)

class TestCouponRecognizer(unittest.TestCase):
    """Test cases for CouponRecognizer"""
    
    def setUp(self):
        """Set up test fixtures"""
        # Create mock dependencies
        self.mock_image_loader = Mock(spec=ImageLoader)
        self.mock_text_extractor = Mock(spec=TextExtractor)
        self.mock_image_preprocessor = Mock(spec=ImagePreprocessor)
        self.mock_text_postprocessor = Mock(spec=TextPostprocessor)
        self.mock_confidence_calculator = Mock(spec=ConfidenceCalculator)
        
        # Create test image
        self.test_image = np.zeros((100, 100, 3), dtype=np.uint8)
        
        # Configure mocks
        self.mock_image_loader.load_image.return_value = self.test_image
        self.mock_image_preprocessor.preprocess.return_value = self.test_image
        
        # Create recognizer with mock dependencies
        self.recognizer = CouponRecognizer(
            image_loader=self.mock_image_loader,
            text_extractor=self.mock_text_extractor,
            image_preprocessor=self.mock_image_preprocessor,
            text_postprocessor=self.mock_text_postprocessor,
            confidence_calculator=self.mock_confidence_calculator
        )
    
    def test_process_region_valid(self):
        """Test processing a valid region"""
        # Configure mocks
        self.mock_text_extractor.extract_text.return_value = "TEST123"
        self.mock_text_postprocessor.postprocess.return_value = "TEST123"
        self.mock_confidence_calculator.calculate_confidence.return_value = 0.8
        
        # Define test region
        region = {
            'left': 10,
            'top': 10,
            'right': 50,
            'bottom': 50
        }
        
        # Process region
        result = self.recognizer.process_region(self.test_image, region, 'code')
        
        # Verify result
        self.assertEqual(result['text'], "TEST123")
        self.assertEqual(result['confidence'], 0.8)
        
        # Verify mock calls
        self.mock_image_preprocessor.preprocess.assert_called_once()
        self.mock_text_extractor.extract_text.assert_called_once()
        self.mock_text_postprocessor.postprocess.assert_called_once_with("TEST123", 'code')
        self.mock_confidence_calculator.calculate_confidence.assert_called_once_with("TEST123", 'code')
    
    def test_process_region_invalid(self):
        """Test processing an invalid region"""
        # Define invalid region (left > right)
        region = {
            'left': 50,
            'top': 10,
            'right': 10,
            'bottom': 50
        }
        
        # Process region
        result = self.recognizer.process_region(self.test_image, region, 'code')
        
        # Verify result
        self.assertEqual(result['text'], "")
        self.assertEqual(result['confidence'], 0.0)
        
        # Verify mock calls
        self.mock_image_preprocessor.preprocess.assert_not_called()
        self.mock_text_extractor.extract_text.assert_not_called()
        self.mock_text_postprocessor.postprocess.assert_not_called()
        self.mock_confidence_calculator.calculate_confidence.assert_not_called()
    
    def test_process_image(self):
        """Test processing a complete image"""
        # Configure mocks
        self.mock_text_extractor.extract_text.side_effect = ["MYNTRA", "CODE123", "50% OFF"]
        self.mock_text_postprocessor.postprocess.side_effect = ["Myntra", "CODE123", "50% OFF"]
        self.mock_confidence_calculator.calculate_confidence.side_effect = [0.9, 0.8, 0.7]
        
        # Define test regions
        regions = {
            'store': [
                {
                    'left': 10,
                    'top': 10,
                    'right': 50,
                    'bottom': 30
                }
            ],
            'code': [
                {
                    'left': 10,
                    'top': 40,
                    'right': 50,
                    'bottom': 60
                }
            ],
            'amount': [
                {
                    'left': 10,
                    'top': 70,
                    'right': 50,
                    'bottom': 90
                }
            ]
        }
        
        # Process image
        result = self.recognizer.process_image("test_image.jpg", regions)
        
        # Verify result
        self.assertEqual(result['results']['store'], "Myntra")
        self.assertEqual(result['results']['code'], "CODE123")
        self.assertEqual(result['results']['amount'], "50% OFF")
        self.assertEqual(result['confidence']['store'], 0.9)
        self.assertEqual(result['confidence']['code'], 0.8)
        self.assertEqual(result['confidence']['amount'], 0.7)
        
        # Verify mock calls
        self.mock_image_loader.load_image.assert_called_once_with("test_image.jpg")
        self.assertEqual(self.mock_image_preprocessor.preprocess.call_count, 3)
        self.assertEqual(self.mock_text_extractor.extract_text.call_count, 3)
        self.assertEqual(self.mock_text_postprocessor.postprocess.call_count, 3)
        self.assertEqual(self.mock_confidence_calculator.calculate_confidence.call_count, 3)
    
    def test_process_image_with_error(self):
        """Test processing an image with an error"""
        # Configure mock to raise an exception
        self.mock_image_loader.load_image.side_effect = ValueError("Image not found")
        
        # Define test regions
        regions = {
            'store': [
                {
                    'left': 10,
                    'top': 10,
                    'right': 50,
                    'bottom': 30
                }
            ]
        }
        
        # Process image
        result = self.recognizer.process_image("nonexistent.jpg", regions)
        
        # Verify result
        self.assertEqual(result['results'], {})
        self.assertEqual(result['confidence'], {})
        
        # Verify mock calls
        self.mock_image_loader.load_image.assert_called_once_with("nonexistent.jpg")
        self.mock_image_preprocessor.preprocess.assert_not_called()
        self.mock_text_extractor.extract_text.assert_not_called()
        self.mock_text_postprocessor.postprocess.assert_not_called()
        self.mock_confidence_calculator.calculate_confidence.assert_not_called()
    
    def test_best_result_selection(self):
        """Test selection of best result from multiple regions"""
        # Configure mocks
        self.mock_text_extractor.extract_text.side_effect = ["CODE1", "CODE123", "CODE3"]
        self.mock_text_postprocessor.postprocess.side_effect = ["CODE1", "CODE123", "CODE3"]
        self.mock_confidence_calculator.calculate_confidence.side_effect = [0.5, 0.9, 0.7]
        
        # Define test regions with multiple options for code
        regions = {
            'code': [
                {
                    'left': 10,
                    'top': 10,
                    'right': 50,
                    'bottom': 30
                },
                {
                    'left': 10,
                    'top': 40,
                    'right': 50,
                    'bottom': 60
                },
                {
                    'left': 10,
                    'top': 70,
                    'right': 50,
                    'bottom': 90
                }
            ]
        }
        
        # Process image
        result = self.recognizer.process_image("test_image.jpg", regions)
        
        # Verify result - should select CODE123 with highest confidence
        self.assertEqual(result['results']['code'], "CODE123")
        self.assertEqual(result['confidence']['code'], 0.9)
        
        # Verify mock calls
        self.mock_image_loader.load_image.assert_called_once()
        self.assertEqual(self.mock_image_preprocessor.preprocess.call_count, 3)
        self.assertEqual(self.mock_text_extractor.extract_text.call_count, 3)
        self.assertEqual(self.mock_text_postprocessor.postprocess.call_count, 3)
        self.assertEqual(self.mock_confidence_calculator.calculate_confidence.call_count, 3)

class TestDefaultImplementations(unittest.TestCase):
    """Test cases for default implementations"""
    
    def test_default_text_postprocessor(self):
        """Test the default text postprocessor"""
        from scripts.testable_coupon_recognizer import DefaultTextPostprocessor
        
        postprocessor = DefaultTextPostprocessor()
        
        # Test store name postprocessing
        self.assertEqual(postprocessor.postprocess("myntra", "store"), "Myntra")
        self.assertEqual(postprocessor.postprocess("MYNTRA STORE", "store"), "Myntra Store")
        
        # Test code postprocessing
        self.assertEqual(postprocessor.postprocess("CODE-123", "code"), "CODE123")
        self.assertEqual(postprocessor.postprocess("code 123", "code"), "CODE123")
        
        # Test amount postprocessing
        self.assertEqual(postprocessor.postprocess("50%off", "amount"), "50% off")
        self.assertEqual(postprocessor.postprocess("₹500", "amount"), "₹ 500")
        
        # Test expiry postprocessing
        self.assertEqual(postprocessor.postprocess("valid till 31/12/2023", "expiry"), "31/12/2023")
        self.assertEqual(postprocessor.postprocess("expires on 01-01-2024", "expiry"), "01-01-2024")
        
        # Test description postprocessing
        self.assertEqual(postprocessor.postprocess("on all products", "description"), "On all products")
    
    def test_default_confidence_calculator(self):
        """Test the default confidence calculator"""
        from scripts.testable_coupon_recognizer import DefaultConfidenceCalculator
        
        calculator = DefaultConfidenceCalculator()
        
        # Test empty text
        self.assertEqual(calculator.calculate_confidence("", "code"), 0.0)
        
        # Test code confidence
        self.assertGreater(calculator.calculate_confidence("CODE123", "code"), 0.7)
        self.assertLess(calculator.calculate_confidence("C@DE", "code"), 0.7)
        
        # Test amount confidence
        self.assertGreater(calculator.calculate_confidence("50% OFF", "amount"), 0.7)
        self.assertGreater(calculator.calculate_confidence("₹500", "amount"), 0.7)
        
        # Test expiry confidence
        self.assertGreater(calculator.calculate_confidence("31/12/2023", "expiry"), 0.7)
        self.assertLess(calculator.calculate_confidence("December", "expiry"), 0.7)

if __name__ == "__main__":
    unittest.main()
