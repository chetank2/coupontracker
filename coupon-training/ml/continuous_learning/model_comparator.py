"""Compares model performance to determine if new model should be promoted."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple

LOGGER = logging.getLogger("model_comparator")


@dataclass
class ModelMetrics:
    """Model performance metrics."""
    model_id: str
    model_path: str
    map_score: float
    map50_score: float
    precision: float
    recall: float
    per_class_metrics: Dict[str, float]
    inference_time_ms: float
    model_size_mb: float
    created_at: str


@dataclass
class ComparisonResult:
    """Result of model comparison."""
    new_model_better: bool
    improvement_score: float
    metric_improvements: Dict[str, float]
    recommendation: str  # 'promote', 'reject', 'needs_more_data'
    confidence: float


class ModelComparator:
    """Compares model performance and makes promotion decisions."""
    
    def __init__(
        self,
        improvement_threshold: float = 0.02,  # 2% improvement required
        min_improvement_confidence: float = 0.8,
        critical_metrics: List[str] = None,
    ):
        self.improvement_threshold = improvement_threshold
        self.min_improvement_confidence = min_improvement_confidence
        self.critical_metrics = critical_metrics or ["map", "map50", "precision", "recall"]
    
    def load_model_metrics(self, metrics_path: Path) -> Optional[ModelMetrics]:
        """Load model metrics from file."""
        try:
            data = json.loads(metrics_path.read_text())
            
            # Extract metrics from evaluation data
            detector_metrics = data.get("detector", {})
            summary = detector_metrics.get("summary", {})
            per_class = detector_metrics.get("per_class", [])
            
            # Convert per-class metrics to dict
            per_class_dict = {}
            for item in per_class:
                category = item.get("category", "unknown")
                map50 = item.get("map50", 0.0)
                per_class_dict[category] = map50
            
            return ModelMetrics(
                model_id=data.get("model_id", "unknown"),
                model_path=data.get("model_path", ""),
                map_score=summary.get("map", 0.0),
                map50_score=summary.get("map50", 0.0),
                precision=summary.get("precision", 0.0),
                recall=summary.get("recall", 0.0),
                per_class_metrics=per_class_dict,
                inference_time_ms=data.get("inference_time_ms", 0.0),
                model_size_mb=data.get("model_size_mb", 0.0),
                created_at=data.get("created_at", ""),
            )
        except Exception as e:
            LOGGER.error(f"Failed to load model metrics from {metrics_path}: {e}")
            return None
    
    def compare_models(
        self,
        current_model: ModelMetrics,
        new_model: ModelMetrics,
    ) -> ComparisonResult:
        """Compare two models and determine if new model should be promoted."""
        
        # Calculate improvements for each metric
        metric_improvements = {}
        improvements = []
        
        for metric in self.critical_metrics:
            current_value = getattr(current_model, metric, 0.0)
            new_value = getattr(new_model, metric, 0.0)
            
            if current_value > 0:
                improvement = (new_value - current_value) / current_value
            else:
                improvement = new_value if new_value > 0 else 0
            
            metric_improvements[metric] = improvement
            improvements.append(improvement)
        
        # Calculate overall improvement score
        overall_improvement = sum(improvements) / len(improvements)
        
        # Check if any critical metrics regressed significantly
        significant_regression = False
        for metric in self.critical_metrics:
            if metric_improvements[metric] < -0.05:  # 5% regression threshold
                significant_regression = True
                break
        
        # Calculate confidence based on consistency of improvements
        positive_improvements = [imp for imp in improvements if imp > 0]
        confidence = len(positive_improvements) / len(improvements) if improvements else 0.0
        
        # Make recommendation
        if significant_regression:
            recommendation = "reject"
            new_model_better = False
        elif overall_improvement >= self.improvement_threshold and confidence >= self.min_improvement_confidence:
            recommendation = "promote"
            new_model_better = True
        elif overall_improvement > 0 and confidence >= 0.6:
            recommendation = "needs_more_data"
            new_model_better = True
        else:
            recommendation = "reject"
            new_model_better = False
        
        return ComparisonResult(
            new_model_better=new_model_better,
            improvement_score=overall_improvement,
            metric_improvements=metric_improvements,
            recommendation=recommendation,
            confidence=confidence,
        )
    
    def compare_with_baseline(
        self,
        new_model: ModelMetrics,
        baseline_metrics: Dict[str, float],
    ) -> ComparisonResult:
        """Compare new model with baseline metrics."""
        
        # Create a baseline model object
        baseline_model = ModelMetrics(
            model_id="baseline",
            model_path="",
            map_score=baseline_metrics.get("map", 0.0),
            map50_score=baseline_metrics.get("map50", 0.0),
            precision=baseline_metrics.get("precision", 0.0),
            recall=baseline_metrics.get("recall", 0.0),
            per_class_metrics=baseline_metrics.get("per_class", {}),
            inference_time_ms=baseline_metrics.get("inference_time_ms", 0.0),
            model_size_mb=baseline_metrics.get("model_size_mb", 0.0),
            created_at="baseline",
        )
        
        return self.compare_models(baseline_model, new_model)
    
    def evaluate_model_quality(self, model: ModelMetrics) -> Dict[str, Any]:
        """Evaluate overall model quality and provide recommendations."""
        quality_score = 0.0
        issues = []
        recommendations = []
        
        # Check mAP score
        if model.map_score >= 0.8:
            quality_score += 0.3
        elif model.map_score >= 0.6:
            quality_score += 0.2
        else:
            issues.append(f"Low mAP score: {model.map_score:.3f}")
            recommendations.append("Consider more training data or longer training")
        
        # Check precision-recall balance
        if model.precision > 0 and model.recall > 0:
            f1_score = 2 * (model.precision * model.recall) / (model.precision + model.recall)
            if f1_score >= 0.8:
                quality_score += 0.2
            elif f1_score >= 0.6:
                quality_score += 0.1
            else:
                issues.append(f"Low F1 score: {f1_score:.3f}")
                recommendations.append("Check class imbalance or annotation quality")
        
        # Check per-class performance
        poor_classes = [cls for cls, score in model.per_class_metrics.items() if score < 0.5]
        if poor_classes:
            issues.append(f"Poor performance on classes: {poor_classes}")
            recommendations.append("Add more training data for underperforming classes")
        else:
            quality_score += 0.2
        
        # Check inference time
        if model.inference_time_ms <= 100:
            quality_score += 0.15
        elif model.inference_time_ms <= 500:
            quality_score += 0.1
        else:
            issues.append(f"Slow inference time: {model.inference_time_ms:.1f}ms")
            recommendations.append("Consider model optimization or quantization")
        
        # Check model size
        if model.model_size_mb <= 50:
            quality_score += 0.15
        elif model.model_size_mb <= 100:
            quality_score += 0.1
        else:
            issues.append(f"Large model size: {model.model_size_mb:.1f}MB")
            recommendations.append("Consider model compression or pruning")
        
        return {
            "quality_score": quality_score,
            "issues": issues,
            "recommendations": recommendations,
            "overall_rating": self._get_quality_rating(quality_score),
        }
    
    def _get_quality_rating(self, score: float) -> str:
        """Get quality rating based on score."""
        if score >= 0.9:
            return "excellent"
        elif score >= 0.7:
            return "good"
        elif score >= 0.5:
            return "fair"
        else:
            return "poor"
    
    def generate_comparison_report(
        self,
        current_model: ModelMetrics,
        new_model: ModelMetrics,
        comparison_result: ComparisonResult,
    ) -> str:
        """Generate a human-readable comparison report."""
        report = f"""
Model Comparison Report
======================

Current Model: {current_model.model_id}
New Model: {new_model.model_id}

Performance Comparison:
----------------------
"""
        
        for metric in self.critical_metrics:
            current_value = getattr(current_model, metric, 0.0)
            new_value = getattr(new_model, metric, 0.0)
            improvement = comparison_result.metric_improvements.get(metric, 0.0)
            
            report += f"{metric.upper()}: {current_value:.3f} → {new_value:.3f} ({improvement:+.1%})\n"
        
        report += f"""
Overall Improvement: {comparison_result.improvement_score:+.1%}
Confidence: {comparison_result.confidence:.1%}
Recommendation: {comparison_result.recommendation.upper()}

Model Quality:
--------------
Current Model Quality: {self.evaluate_model_quality(current_model)['overall_rating']}
New Model Quality: {self.evaluate_model_quality(new_model)['overall_rating']}
"""
        
        return report
