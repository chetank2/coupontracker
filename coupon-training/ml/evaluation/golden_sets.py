"""Golden and Regression test sets for model validation."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple

from ml.data.manifest import Manifest, load_manifest

LOGGER = logging.getLogger("golden_sets")


@dataclass
class GoldenSetItem:
    """An item in a golden set."""
    image_path: str
    expected_regions: List[Dict[str, Any]]
    expected_text: Dict[str, str]
    metadata: Dict[str, Any]
    difficulty_level: str  # 'easy', 'medium', 'hard'
    category: str  # 'detection', 'ocr', 'parsing'


@dataclass
class GoldenSet:
    """A golden test set."""
    name: str
    version: str
    description: str
    items: List[GoldenSetItem]
    created_at: str
    tags: List[str]


@dataclass
class RegressionTestResult:
    """Result of a regression test."""
    test_name: str
    passed: bool
    score: float
    threshold: float
    details: Dict[str, Any]
    error_message: Optional[str] = None


class GoldenSetManager:
    """Manages golden and regression test sets."""
    
    def __init__(self, golden_sets_dir: Path = Path("data/golden_sets")):
        self.golden_sets_dir = golden_sets_dir
        self.golden_sets_dir.mkdir(parents=True, exist_ok=True)
    
    def create_golden_set(
        self,
        name: str,
        description: str,
        items: List[GoldenSetItem],
        tags: Optional[List[str]] = None,
    ) -> GoldenSet:
        """Create a new golden set."""
        from datetime import datetime
        
        golden_set = GoldenSet(
            name=name,
            version="1.0.0",
            description=description,
            items=items,
            created_at=datetime.utcnow().isoformat() + "Z",
            tags=tags or [],
        )
        
        # Save golden set
        self._save_golden_set(golden_set)
        
        LOGGER.info(f"Created golden set: {name} with {len(items)} items")
        return golden_set
    
    def load_golden_set(self, name: str) -> Optional[GoldenSet]:
        """Load a golden set by name."""
        golden_set_file = self.golden_sets_dir / f"{name}.json"
        
        if not golden_set_file.exists():
            return None
        
        try:
            data = json.loads(golden_set_file.read_text())
            return GoldenSet(**data)
        except Exception as e:
            LOGGER.error(f"Failed to load golden set {name}: {e}")
            return None
    
    def list_golden_sets(self) -> List[str]:
        """List all available golden sets."""
        golden_set_files = self.golden_sets_dir.glob("*.json")
        return [f.stem for f in golden_set_files]
    
    def _save_golden_set(self, golden_set: GoldenSet) -> None:
        """Save a golden set to disk."""
        golden_set_file = self.golden_sets_dir / f"{golden_set.name}.json"
        golden_set_file.write_text(json.dumps(asdict(golden_set), indent=2))
    
    def create_from_manifest(
        self,
        manifest: Manifest,
        name: str,
        description: str,
        difficulty_distribution: Dict[str, float] = None,
    ) -> GoldenSet:
        """Create a golden set from an existing manifest."""
        if difficulty_distribution is None:
            difficulty_distribution = {"easy": 0.3, "medium": 0.5, "hard": 0.2}
        
        items = []
        total_items = len(manifest.records)
        
        # Calculate difficulty distribution
        easy_count = int(total_items * difficulty_distribution["easy"])
        medium_count = int(total_items * difficulty_distribution["medium"])
        hard_count = total_items - easy_count - medium_count
        
        # Assign difficulty levels
        for i, record in enumerate(manifest.records):
            if i < easy_count:
                difficulty = "easy"
            elif i < easy_count + medium_count:
                difficulty = "medium"
            else:
                difficulty = "hard"
            
            # Determine category based on annotation completeness
            annotations = record.get("annotations", [])
            has_detection = any(ann.get("category") in ["app_region", "store_region", "code_region", "benefit_region", "expiry_region", "terms_region"] for ann in annotations)
            has_text = any(ann.get("text") for ann in annotations)
            
            if has_detection and has_text:
                category = "parsing"
            elif has_detection:
                category = "detection"
            else:
                category = "ocr"
            
            item = GoldenSetItem(
                image_path=record["image_path"],
                expected_regions=annotations,
                expected_text={ann.get("category", ""): ann.get("text", "") for ann in annotations if ann.get("text")},
                metadata=record.get("metadata", {}),
                difficulty_level=difficulty,
                category=category,
            )
            items.append(item)
        
        return self.create_golden_set(name, description, items)
    
    def run_regression_test(
        self,
        golden_set_name: str,
        model_predictions: List[Dict[str, Any]],
        thresholds: Optional[Dict[str, float]] = None,
    ) -> RegressionTestResult:
        """Run a regression test against a golden set."""
        golden_set = self.load_golden_set(golden_set_name)
        if not golden_set:
            return RegressionTestResult(
                test_name=golden_set_name,
                passed=False,
                score=0.0,
                threshold=0.0,
                details={},
                error_message=f"Golden set {golden_set_name} not found"
            )
        
        if thresholds is None:
            thresholds = {
                "detection_iou": 0.5,
                "ocr_accuracy": 0.8,
                "parsing_accuracy": 0.7,
                "overall_score": 0.75,
            }
        
        # Run tests for each category
        detection_results = self._test_detection(golden_set, model_predictions, thresholds["detection_iou"])
        ocr_results = self._test_ocr(golden_set, model_predictions, thresholds["ocr_accuracy"])
        parsing_results = self._test_parsing(golden_set, model_predictions, thresholds["parsing_accuracy"])
        
        # Calculate overall score
        overall_score = (detection_results["score"] + ocr_results["score"] + parsing_results["score"]) / 3
        
        # Determine if test passed
        passed = overall_score >= thresholds["overall_score"]
        
        details = {
            "detection": detection_results,
            "ocr": ocr_results,
            "parsing": parsing_results,
            "overall_score": overall_score,
            "thresholds": thresholds,
        }
        
        return RegressionTestResult(
            test_name=golden_set_name,
            passed=passed,
            score=overall_score,
            threshold=thresholds["overall_score"],
            details=details,
        )
    
    def _test_detection(
        self,
        golden_set: GoldenSet,
        model_predictions: List[Dict[str, Any]],
        iou_threshold: float,
    ) -> Dict[str, Any]:
        """Test detection performance."""
        detection_items = [item for item in golden_set.items if item.category == "detection"]
        
        if not detection_items:
            return {"score": 1.0, "total": 0, "passed": 0, "details": "No detection items"}
        
        total_items = len(detection_items)
        passed_items = 0
        iou_scores = []
        
        for item in detection_items:
            # Find corresponding prediction
            prediction = next(
                (pred for pred in model_predictions if pred.get("image_path") == item.image_path),
                None
            )
            
            if not prediction:
                continue
            
            # Calculate IoU for each expected region
            predicted_regions = prediction.get("predicted_regions", [])
            item_iou_scores = []
            
            for expected_region in item.expected_regions:
                best_iou = 0.0
                for pred_region in predicted_regions:
                    iou = self._calculate_iou(expected_region.get("bbox", []), pred_region.get("bbox", []))
                    best_iou = max(best_iou, iou)
                item_iou_scores.append(best_iou)
            
            # Check if item passed
            avg_iou = sum(item_iou_scores) / len(item_iou_scores) if item_iou_scores else 0.0
            if avg_iou >= iou_threshold:
                passed_items += 1
            
            iou_scores.append(avg_iou)
        
        score = passed_items / total_items if total_items > 0 else 0.0
        
        return {
            "score": score,
            "total": total_items,
            "passed": passed_items,
            "avg_iou": sum(iou_scores) / len(iou_scores) if iou_scores else 0.0,
            "details": f"Detection test: {passed_items}/{total_items} passed"
        }
    
    def _test_ocr(
        self,
        golden_set: GoldenSet,
        model_predictions: List[Dict[str, Any]],
        accuracy_threshold: float,
    ) -> Dict[str, Any]:
        """Test OCR performance."""
        ocr_items = [item for item in golden_set.items if item.category == "ocr"]
        
        if not ocr_items:
            return {"score": 1.0, "total": 0, "passed": 0, "details": "No OCR items"}
        
        total_items = len(ocr_items)
        passed_items = 0
        accuracy_scores = []
        
        for item in ocr_items:
            # Find corresponding prediction
            prediction = next(
                (pred for pred in model_predictions if pred.get("image_path") == item.image_path),
                None
            )
            
            if not prediction:
                continue
            
            # Compare OCR text
            predicted_text = prediction.get("ocr_text", {})
            item_accuracy_scores = []
            
            for category, expected_text in item.expected_text.items():
                predicted_text_cat = predicted_text.get(category, "")
                accuracy = self._calculate_text_accuracy(expected_text, predicted_text_cat)
                item_accuracy_scores.append(accuracy)
            
            # Check if item passed
            avg_accuracy = sum(item_accuracy_scores) / len(item_accuracy_scores) if item_accuracy_scores else 0.0
            if avg_accuracy >= accuracy_threshold:
                passed_items += 1
            
            accuracy_scores.append(avg_accuracy)
        
        score = passed_items / total_items if total_items > 0 else 0.0
        
        return {
            "score": score,
            "total": total_items,
            "passed": passed_items,
            "avg_accuracy": sum(accuracy_scores) / len(accuracy_scores) if accuracy_scores else 0.0,
            "details": f"OCR test: {passed_items}/{total_items} passed"
        }
    
    def _test_parsing(
        self,
        golden_set: GoldenSet,
        model_predictions: List[Dict[str, Any]],
        accuracy_threshold: float,
    ) -> Dict[str, Any]:
        """Test parsing performance."""
        parsing_items = [item for item in golden_set.items if item.category == "parsing"]
        
        if not parsing_items:
            return {"score": 1.0, "total": 0, "passed": 0, "details": "No parsing items"}
        
        total_items = len(parsing_items)
        passed_items = 0
        accuracy_scores = []
        
        for item in parsing_items:
            # Find corresponding prediction
            prediction = next(
                (pred for pred in model_predictions if pred.get("image_path") == item.image_path),
                None
            )
            
            if not prediction:
                continue
            
            # Compare parsed fields
            predicted_fields = prediction.get("parsed_fields", {})
            item_accuracy_scores = []
            
            for category, expected_text in item.expected_text.items():
                predicted_text = predicted_fields.get(category, "")
                accuracy = self._calculate_text_accuracy(expected_text, predicted_text)
                item_accuracy_scores.append(accuracy)
            
            # Check if item passed
            avg_accuracy = sum(item_accuracy_scores) / len(item_accuracy_scores) if item_accuracy_scores else 0.0
            if avg_accuracy >= accuracy_threshold:
                passed_items += 1
            
            accuracy_scores.append(avg_accuracy)
        
        score = passed_items / total_items if total_items > 0 else 0.0
        
        return {
            "score": score,
            "total": total_items,
            "passed": passed_items,
            "avg_accuracy": sum(accuracy_scores) / len(accuracy_scores) if accuracy_scores else 0.0,
            "details": f"Parsing test: {passed_items}/{total_items} passed"
        }
    
    def _calculate_iou(self, bbox1: List[float], bbox2: List[float]) -> float:
        """Calculate Intersection over Union for two bounding boxes."""
        if len(bbox1) != 4 or len(bbox2) != 4:
            return 0.0
        
        # Calculate intersection
        x1 = max(bbox1[0], bbox2[0])
        y1 = max(bbox1[1], bbox2[1])
        x2 = min(bbox1[2], bbox2[2])
        y2 = min(bbox1[3], bbox2[3])
        
        if x2 <= x1 or y2 <= y1:
            return 0.0
        
        intersection = (x2 - x1) * (y2 - y1)
        
        # Calculate union
        area1 = (bbox1[2] - bbox1[0]) * (bbox1[3] - bbox1[1])
        area2 = (bbox2[2] - bbox2[0]) * (bbox2[3] - bbox2[1])
        union = area1 + area2 - intersection
        
        return intersection / union if union > 0 else 0.0
    
    def _calculate_text_accuracy(self, expected: str, predicted: str) -> float:
        """Calculate text accuracy between expected and predicted text."""
        if not expected and not predicted:
            return 1.0
        
        if not expected or not predicted:
            return 0.0
        
        # Simple character-level accuracy
        expected = expected.strip().lower()
        predicted = predicted.strip().lower()
        
        if expected == predicted:
            return 1.0
        
        # Calculate edit distance
        from difflib import SequenceMatcher
        similarity = SequenceMatcher(None, expected, predicted).ratio()
        
        return similarity
