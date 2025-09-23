"""Uncertainty sampling for active learning."""

from __future__ import annotations

import json
import logging
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple

import numpy as np

LOGGER = logging.getLogger("uncertainty_sampler")


@dataclass
class UncertaintySample:
    """A sample with uncertainty metrics."""
    image_path: str
    uncertainty_score: float
    confidence_scores: Dict[str, float]
    predicted_regions: List[Dict[str, Any]]
    metadata: Dict[str, Any]


class UncertaintySampler:
    """Samples images based on uncertainty for active learning."""
    
    def __init__(self, min_uncertainty_threshold: float = 0.3):
        self.min_uncertainty_threshold = min_uncertainty_threshold
    
    def calculate_entropy(self, probabilities: List[float]) -> float:
        """Calculate entropy of probability distribution."""
        if not probabilities:
            return 0.0
        
        # Normalize probabilities
        probs = np.array(probabilities)
        probs = probs / (probs.sum() + 1e-10)
        
        # Calculate entropy
        entropy = -np.sum(probs * np.log(probs + 1e-10))
        return float(entropy)
    
    def calculate_confidence_variance(self, confidence_scores: Dict[str, float]) -> float:
        """Calculate variance of confidence scores across regions."""
        if not confidence_scores:
            return 0.0
        
        scores = list(confidence_scores.values())
        if len(scores) < 2:
            return 0.0
        
        return float(np.var(scores))
    
    def calculate_region_uncertainty(self, regions: List[Dict[str, Any]]) -> float:
        """Calculate uncertainty based on region detection quality."""
        if not regions:
            return 1.0  # High uncertainty if no regions detected
        
        # Check for overlapping regions (indicates uncertainty)
        overlap_penalty = 0.0
        for i, region1 in enumerate(regions):
            for j, region2 in enumerate(regions[i+1:], i+1):
                overlap = self._calculate_overlap(region1, region2)
                if overlap > 0.3:  # Significant overlap
                    overlap_penalty += overlap
        
        # Check for very small or very large regions (indicates uncertainty)
        size_penalty = 0.0
        for region in regions:
            bbox = region.get("bbox", [0, 0, 0, 0])
            width = bbox[2] - bbox[0]
            height = bbox[3] - bbox[1]
            area = width * height
            
            if area < 100:  # Very small region
                size_penalty += 0.5
            elif area > 100000:  # Very large region
                size_penalty += 0.3
        
        # Normalize penalties
        overlap_penalty = min(overlap_penalty, 1.0)
        size_penalty = min(size_penalty, 1.0)
        
        return overlap_penalty + size_penalty
    
    def _calculate_overlap(self, region1: Dict[str, Any], region2: Dict[str, Any]) -> float:
        """Calculate overlap ratio between two regions."""
        bbox1 = region1.get("bbox", [0, 0, 0, 0])
        bbox2 = region2.get("bbox", [0, 0, 0, 0])
        
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
    
    def calculate_uncertainty_score(
        self,
        confidence_scores: Dict[str, float],
        predicted_regions: List[Dict[str, Any]],
        probabilities: Optional[List[float]] = None,
    ) -> float:
        """Calculate overall uncertainty score for a sample."""
        # Entropy from class probabilities
        entropy_score = 0.0
        if probabilities:
            entropy_score = self.calculate_entropy(probabilities)
        
        # Confidence variance
        confidence_variance = self.calculate_confidence_variance(confidence_scores)
        
        # Region uncertainty
        region_uncertainty = self.calculate_region_uncertainty(predicted_regions)
        
        # Low confidence penalty
        avg_confidence = np.mean(list(confidence_scores.values())) if confidence_scores else 0.0
        low_confidence_penalty = 1.0 - avg_confidence
        
        # Combine scores (weighted average)
        uncertainty = (
            0.3 * entropy_score +
            0.2 * confidence_variance +
            0.3 * region_uncertainty +
            0.2 * low_confidence_penalty
        )
        
        return min(uncertainty, 1.0)  # Cap at 1.0
    
    def sample_uncertain_images(
        self,
        predictions: List[Dict[str, Any]],
        top_k: int = 20,
        min_uncertainty: Optional[float] = None,
    ) -> List[UncertaintySample]:
        """Sample the most uncertain images from predictions."""
        if min_uncertainty is None:
            min_uncertainty = self.min_uncertainty_threshold
        
        samples = []
        
        for pred in predictions:
            image_path = pred.get("image_path", "")
            confidence_scores = pred.get("confidence_scores", {})
            predicted_regions = pred.get("predicted_regions", [])
            probabilities = pred.get("probabilities")
            metadata = pred.get("metadata", {})
            
            uncertainty_score = self.calculate_uncertainty_score(
                confidence_scores, predicted_regions, probabilities
            )
            
            if uncertainty_score >= min_uncertainty:
                sample = UncertaintySample(
                    image_path=image_path,
                    uncertainty_score=uncertainty_score,
                    confidence_scores=confidence_scores,
                    predicted_regions=predicted_regions,
                    metadata=metadata,
                )
                samples.append(sample)
        
        # Sort by uncertainty score (highest first)
        samples.sort(key=lambda x: x.uncertainty_score, reverse=True)
        
        return samples[:top_k]
    
    def load_predictions_from_file(self, predictions_path: Path) -> List[Dict[str, Any]]:
        """Load predictions from a JSON file."""
        if not predictions_path.exists():
            LOGGER.warning(f"Predictions file not found: {predictions_path}")
            return []
        
        try:
            data = json.loads(predictions_path.read_text())
            if isinstance(data, dict):
                return data.get("predictions", [])
            elif isinstance(data, list):
                return data
            else:
                LOGGER.error(f"Invalid predictions file format: {predictions_path}")
                return []
        except Exception as e:
            LOGGER.error(f"Failed to load predictions: {e}")
            return []
    
    def save_uncertain_samples(
        self,
        samples: List[UncertaintySample],
        output_path: Path,
    ) -> None:
        """Save uncertain samples to a file."""
        output_path.parent.mkdir(parents=True, exist_ok=True)
        
        from datetime import datetime
        
        data = {
            "samples": [
                {
                    "image_path": sample.image_path,
                    "uncertainty_score": sample.uncertainty_score,
                    "confidence_scores": sample.confidence_scores,
                    "predicted_regions": sample.predicted_regions,
                    "metadata": sample.metadata,
                }
                for sample in samples
            ],
            "total_samples": len(samples),
            "min_uncertainty": self.min_uncertainty_threshold,
            "created_at": datetime.utcnow().isoformat() + "Z",
        }
        
        output_path.write_text(json.dumps(data, indent=2))
        LOGGER.info(f"Saved {len(samples)} uncertain samples to {output_path}")
