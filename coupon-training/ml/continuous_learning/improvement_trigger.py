"""Triggers model improvements based on various conditions."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Any

from ml.feedback import PriorityQueue
from ml.continuous_learning.model_comparator import ModelComparator, ModelMetrics

LOGGER = logging.getLogger("improvement_trigger")


@dataclass
class TriggerCondition:
    """A condition that can trigger model improvement."""
    name: str
    enabled: bool
    threshold: float
    weight: float = 1.0


@dataclass
class ImprovementTrigger:
    """Triggers model improvements based on various conditions."""
    
    def __init__(
        self,
        priority_queue: PriorityQueue,
        model_comparator: ModelComparator,
        config: Optional[Dict[str, Any]] = None,
    ):
        self.priority_queue = priority_queue
        self.model_comparator = model_comparator
        
        # Default trigger conditions
        self.conditions = {
            "high_uncertainty_samples": TriggerCondition(
                name="High uncertainty samples",
                enabled=True,
                threshold=0.7,
                weight=1.0
            ),
            "user_corrections": TriggerCondition(
                name="User corrections",
                enabled=True,
                threshold=0.8,
                weight=1.5
            ),
            "parsing_failures": TriggerCondition(
                name="Parsing failures",
                enabled=True,
                threshold=0.6,
                weight=1.2
            ),
            "low_confidence_predictions": TriggerCondition(
                name="Low confidence predictions",
                enabled=True,
                threshold=0.4,
                weight=0.8
            ),
            "time_based": TriggerCondition(
                name="Time-based trigger",
                enabled=True,
                threshold=24,  # hours
                weight=0.5
            ),
        }
        
        # Load custom config if provided
        if config:
            self._load_config(config)
    
    def _load_config(self, config: Dict[str, Any]) -> None:
        """Load configuration for trigger conditions."""
        for condition_name, condition_config in config.get("conditions", {}).items():
            if condition_name in self.conditions:
                self.conditions[condition_name].enabled = condition_config.get("enabled", True)
                self.conditions[condition_name].threshold = condition_config.get("threshold", 0.5)
                self.conditions[condition_name].weight = condition_config.get("weight", 1.0)
    
    def check_trigger_conditions(self) -> Dict[str, Any]:
        """Check all trigger conditions and return results."""
        results = {}
        
        for condition_name, condition in self.conditions.items():
            if not condition.enabled:
                results[condition_name] = {
                    "triggered": False,
                    "score": 0.0,
                    "reason": "Condition disabled"
                }
                continue
            
            if condition_name == "high_uncertainty_samples":
                score, reason = self._check_uncertainty_condition(condition)
            elif condition_name == "user_corrections":
                score, reason = self._check_user_corrections_condition(condition)
            elif condition_name == "parsing_failures":
                score, reason = self._check_parsing_failures_condition(condition)
            elif condition_name == "low_confidence_predictions":
                score, reason = self._check_low_confidence_condition(condition)
            elif condition_name == "time_based":
                score, reason = self._check_time_based_condition(condition)
            else:
                score, reason = 0.0, "Unknown condition"
            
            results[condition_name] = {
                "triggered": score >= condition.threshold,
                "score": score,
                "reason": reason,
                "weight": condition.weight
            }
        
        return results
    
    def _check_uncertainty_condition(self, condition: TriggerCondition) -> tuple[float, str]:
        """Check high uncertainty samples condition."""
        queue_stats = self.priority_queue.get_stats()
        uncertainty_count = queue_stats.get("reason_breakdown", {}).get("uncertainty", 0)
        total_unprocessed = queue_stats.get("unprocessed", 0)
        
        if total_unprocessed == 0:
            return 0.0, "No unprocessed samples"
        
        uncertainty_ratio = uncertainty_count / total_unprocessed
        return uncertainty_ratio, f"Uncertainty ratio: {uncertainty_ratio:.2f}"
    
    def _check_user_corrections_condition(self, condition: TriggerCondition) -> tuple[float, str]:
        """Check user corrections condition."""
        queue_stats = self.priority_queue.get_stats()
        correction_count = queue_stats.get("reason_breakdown", {}).get("user_correction", 0)
        total_unprocessed = queue_stats.get("unprocessed", 0)
        
        if total_unprocessed == 0:
            return 0.0, "No unprocessed samples"
        
        correction_ratio = correction_count / total_unprocessed
        return correction_ratio, f"User correction ratio: {correction_ratio:.2f}"
    
    def _check_parsing_failures_condition(self, condition: TriggerCondition) -> tuple[float, str]:
        """Check parsing failures condition."""
        queue_stats = self.priority_queue.get_stats()
        parsing_failures = queue_stats.get("reason_breakdown", {}).get("parsing_failed", 0)
        total_unprocessed = queue_stats.get("unprocessed", 0)
        
        if total_unprocessed == 0:
            return 0.0, "No unprocessed samples"
        
        failure_ratio = parsing_failures / total_unprocessed
        return failure_ratio, f"Parsing failure ratio: {failure_ratio:.2f}"
    
    def _check_low_confidence_condition(self, condition: TriggerCondition) -> tuple[float, str]:
        """Check low confidence predictions condition."""
        # This would typically analyze recent predictions
        # For now, we'll use a simplified approach based on queue items
        queue_stats = self.priority_queue.get_stats()
        total_unprocessed = queue_stats.get("unprocessed", 0)
        
        # Estimate based on total unprocessed items
        # In a real implementation, this would analyze actual confidence scores
        estimated_low_confidence = total_unprocessed * 0.3  # Assume 30% are low confidence
        return min(estimated_low_confidence / 100, 1.0), f"Estimated low confidence: {estimated_low_confidence:.0f}"
    
    def _check_time_based_condition(self, condition: TriggerCondition) -> tuple[float, str]:
        """Check time-based condition."""
        # This would check the last retrain time
        # For now, we'll return a simple time-based score
        current_hour = datetime.utcnow().hour
        time_score = current_hour / 24.0  # Normalize to 0-1
        return time_score, f"Time-based score: {time_score:.2f}"
    
    def should_trigger_improvement(self) -> tuple[bool, Dict[str, Any]]:
        """Determine if model improvement should be triggered."""
        results = self.check_trigger_conditions()
        
        # Calculate weighted score
        total_score = 0.0
        total_weight = 0.0
        triggered_conditions = []
        
        for condition_name, result in results.items():
            if result["triggered"]:
                triggered_conditions.append(condition_name)
                total_score += result["score"] * result["weight"]
                total_weight += result["weight"]
        
        # Normalize score
        if total_weight > 0:
            normalized_score = total_score / total_weight
        else:
            normalized_score = 0.0
        
        # Trigger if we have enough triggered conditions or high normalized score
        should_trigger = (
            len(triggered_conditions) >= 2 or  # At least 2 conditions triggered
            normalized_score >= 0.7  # High overall score
        )
        
        return should_trigger, {
            "triggered": should_trigger,
            "normalized_score": normalized_score,
            "triggered_conditions": triggered_conditions,
            "condition_results": results,
        }
    
    def get_improvement_priority(self) -> str:
        """Get the priority level for improvement based on current conditions."""
        _, trigger_info = self.should_trigger_improvement()
        
        if not trigger_info["triggered"]:
            return "low"
        
        normalized_score = trigger_info["normalized_score"]
        triggered_count = len(trigger_info["triggered_conditions"])
        
        if normalized_score >= 0.9 or triggered_count >= 4:
            return "critical"
        elif normalized_score >= 0.7 or triggered_count >= 3:
            return "high"
        elif normalized_score >= 0.5 or triggered_count >= 2:
            return "medium"
        else:
            return "low"
    
    def get_improvement_recommendations(self) -> List[str]:
        """Get recommendations for model improvement based on current conditions."""
        recommendations = []
        results = self.check_trigger_conditions()
        
        if results.get("high_uncertainty_samples", {}).get("triggered", False):
            recommendations.append("Add more diverse training data to reduce uncertainty")
        
        if results.get("user_corrections", {}).get("triggered", False):
            recommendations.append("Focus on improving accuracy in areas where users make corrections")
        
        if results.get("parsing_failures", {}).get("triggered", False):
            recommendations.append("Improve text parsing and OCR accuracy")
        
        if results.get("low_confidence_predictions", {}).get("triggered", False):
            recommendations.append("Increase model confidence through better training data")
        
        if results.get("time_based", {}).get("triggered", False):
            recommendations.append("Schedule regular model updates")
        
        # Add general recommendations based on queue stats
        queue_stats = self.priority_queue.get_stats()
        total_unprocessed = queue_stats.get("unprocessed", 0)
        
        if total_unprocessed > 100:
            recommendations.append("Process backlog of feedback samples")
        elif total_unprocessed > 50:
            recommendations.append("Consider batch processing of feedback samples")
        
        return recommendations
    
    def save_trigger_state(self, state_path: Path) -> None:
        """Save current trigger state to file."""
        state_path.parent.mkdir(parents=True, exist_ok=True)
        
        should_trigger, trigger_info = self.should_trigger_improvement()
        
        state = {
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "should_trigger": should_trigger,
            "trigger_info": trigger_info,
            "priority": self.get_improvement_priority(),
            "recommendations": self.get_improvement_recommendations(),
            "queue_stats": self.priority_queue.get_stats(),
        }
        
        state_path.write_text(json.dumps(state, indent=2))
        LOGGER.info(f"Saved trigger state to {state_path}")
    
    def load_trigger_state(self, state_path: Path) -> Optional[Dict[str, Any]]:
        """Load trigger state from file."""
        if not state_path.exists():
            return None
        
        try:
            return json.loads(state_path.read_text())
        except Exception as e:
            LOGGER.error(f"Failed to load trigger state from {state_path}: {e}")
            return None
