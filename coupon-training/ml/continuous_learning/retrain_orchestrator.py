"""Orchestrates continuous retraining based on feedback and uncertainty."""

from __future__ import annotations

import json
import logging
import subprocess
import threading
import time
from dataclasses import dataclass, asdict
from datetime import datetime, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple

from ml.feedback import PriorityQueue, UncertaintySampler
from ml.dataset_manager import DatasetManager
from ml.orchestrator import TrainingJobManager, JobRequest

LOGGER = logging.getLogger("retrain_orchestrator")


@dataclass
class RetrainConfig:
    """Configuration for retraining."""
    min_samples_for_retrain: int = 50
    max_samples_per_retrain: int = 200
    retrain_interval_hours: int = 24
    improvement_threshold: float = 0.02  # 2% improvement required
    max_retrain_attempts: int = 3
    enable_active_learning: bool = True
    uncertainty_threshold: float = 0.3


@dataclass
class RetrainResult:
    """Result of a retraining operation."""
    success: bool
    new_model_path: Optional[str]
    metrics: Dict[str, float]
    improvement_score: float
    samples_used: int
    retrain_id: str
    created_at: str
    error_message: Optional[str] = None


class RetrainOrchestrator:
    """Orchestrates continuous retraining based on feedback."""
    
    def __init__(
        self,
        config: RetrainConfig,
        priority_queue: PriorityQueue,
        dataset_manager: DatasetManager,
        training_job_manager: TrainingJobManager,
        project_root: Path,
    ):
        self.config = config
        self.priority_queue = priority_queue
        self.dataset_manager = dataset_manager
        self.training_job_manager = training_job_manager
        self.project_root = project_root
        self.uncertainty_sampler = UncertaintySampler(config.uncertainty_threshold)
        
        # State tracking
        self.last_retrain_time = None
        self.retrain_attempts = 0
        self.is_retraining = False
        
        # Results storage
        self.results_dir = project_root / "data" / "continuous_learning" / "results"
        self.results_dir.mkdir(parents=True, exist_ok=True)
    
    def should_retrain(self) -> bool:
        """Check if conditions are met for retraining."""
        # Check if already retraining
        if self.is_retraining:
            return False
        
        # Check retrain attempts limit
        if self.retrain_attempts >= self.config.max_retrain_attempts:
            LOGGER.warning("Max retrain attempts reached")
            return False
        
        # Check time interval
        if self.last_retrain_time:
            time_since_last = datetime.utcnow() - self.last_retrain_time
            if time_since_last < timedelta(hours=self.config.retrain_interval_hours):
                return False
        
        # Check if enough samples in queue
        queue_stats = self.priority_queue.get_stats()
        if queue_stats["unprocessed"] < self.config.min_samples_for_retrain:
            return False
        
        return True
    
    def collect_training_samples(self) -> List[Dict[str, Any]]:
        """Collect samples for retraining from priority queue."""
        # Get top priority samples
        items = self.priority_queue.get_top_items(self.config.max_samples_per_retrain)
        
        samples = []
        for item in items:
            # Convert priority queue item to training sample
            sample = {
                "image_path": item.image_path,
                "priority_score": item.priority_score,
                "reason": item.reason,
                "metadata": item.metadata,
                "created_at": item.created_at,
            }
            samples.append(sample)
        
        LOGGER.info(f"Collected {len(samples)} samples for retraining")
        return samples
    
    def create_retrain_dataset(self, samples: List[Dict[str, Any]]) -> Path:
        """Create a new dataset from retraining samples."""
        retrain_dir = self.project_root / "data" / "continuous_learning" / "retrain_data"
        retrain_dir.mkdir(parents=True, exist_ok=True)
        
        # Create annotation files for each sample
        annotations = []
        for i, sample in enumerate(samples):
            # Create annotation record
            annotation = {
                "image_path": sample["image_path"],
                "annotations": sample["metadata"].get("detected_regions", []),
                "metadata": {
                    "source": "continuous_learning",
                    "priority_score": sample["priority_score"],
                    "reason": sample["reason"],
                    "original_metadata": sample["metadata"],
                },
                "created_at": sample["created_at"],
            }
            annotations.append(annotation)
        
        # Save annotations
        annotations_file = retrain_dir / f"retrain_annotations_{int(time.time())}.json"
        annotations_file.write_text(json.dumps(annotations, indent=2))
        
        # Create dataset manifest
        from datetime import datetime
        
        dataset_summary = self.dataset_manager.create_dataset(
            description=f"Continuous learning retrain dataset - {len(samples)} samples",
            tags=["continuous_learning", "retrain"],
            train_ratio=0.8,
            val_ratio=0.2,
        )
        
        LOGGER.info(f"Created retrain dataset: {dataset_summary.version}")
        return Path(dataset_summary.manifest_path)
    
    def trigger_retrain(self, manifest_path: Path) -> str:
        """Trigger a retraining job."""
        # Create training config
        config_path = self.project_root / "ml" / "config" / "continuous_learning.yaml"
        config_path.parent.mkdir(parents=True, exist_ok=True)
        
        config_data = {
            "manifest_path": str(manifest_path),
            "output_dir": str(self.results_dir / f"retrain_{int(time.time())}"),
            "detector": {
                "enabled": True,
                "model": "yolov8n.pt",
                "epochs": 30,  # Shorter epochs for continuous learning
                "batch": 16,
                "imgsz": 1024,
            },
            "recognizer": {
                "enabled": False,
            },
            "augmentation_profile": "continuous_learning",
            "use_mlflow": True,
            "experiment_name": "continuous_learning",
        }
        
        config_path.write_text(json.dumps(config_data, indent=2))
        
        # Submit training job
        job_request = JobRequest(
            config_path=str(config_path),
            notes=f"Continuous learning retrain - {len(samples)} samples"
        )
        
        job = self.training_job_manager.submit(job_request)
        LOGGER.info(f"Submitted retrain job: {job.job_id}")
        
        return job.job_id
    
    def monitor_retrain_job(self, job_id: str) -> RetrainResult:
        """Monitor a retraining job and return results."""
        max_wait_time = 3600  # 1 hour max wait
        check_interval = 30  # Check every 30 seconds
        start_time = time.time()
        
        while time.time() - start_time < max_wait_time:
            job = self.training_job_manager.get_job(job_id)
            if not job:
                return RetrainResult(
                    success=False,
                    new_model_path=None,
                    metrics={},
                    improvement_score=0.0,
                    samples_used=0,
                    retrain_id=job_id,
                    created_at=datetime.utcnow().isoformat() + "Z",
                    error_message="Job not found"
                )
            
            if job.status == "succeeded":
                # Job completed successfully
                return self._process_successful_job(job)
            elif job.status == "failed":
                # Job failed
                return RetrainResult(
                    success=False,
                    new_model_path=None,
                    metrics={},
                    improvement_score=0.0,
                    samples_used=0,
                    retrain_id=job_id,
                    created_at=datetime.utcnow().isoformat() + "Z",
                    error_message=f"Training job failed with exit code {job.exit_code}"
                )
            
            time.sleep(check_interval)
        
        # Timeout
        return RetrainResult(
            success=False,
            new_model_path=None,
            metrics={},
            improvement_score=0.0,
            samples_used=0,
            retrain_id=job_id,
            created_at=datetime.utcnow().isoformat() + "Z",
            error_message="Job monitoring timeout"
        )
    
    def _process_successful_job(self, job) -> RetrainResult:
        """Process a successful training job."""
        # Load evaluation results
        output_dir = Path(job.config_path).parent / "output_dir"
        evaluation_file = output_dir / "evaluation.json"
        
        if evaluation_file.exists():
            evaluation_data = json.loads(evaluation_file.read_text())
            metrics = evaluation_data.get("detector", {})
        else:
            metrics = {}
        
        # Find the best model
        best_model_path = None
        for file_path in output_dir.rglob("best.pt"):
            best_model_path = str(file_path)
            break
        
        # Calculate improvement score (simplified)
        improvement_score = metrics.get("map", 0.0) - 0.5  # Assume baseline of 0.5
        
        return RetrainResult(
            success=True,
            new_model_path=best_model_path,
            metrics=metrics,
            improvement_score=improvement_score,
            samples_used=0,  # Will be updated by caller
            retrain_id=job.job_id,
            created_at=datetime.utcnow().isoformat() + "Z",
        )
    
    def run_continuous_learning_cycle(self) -> RetrainResult:
        """Run a complete continuous learning cycle."""
        if not self.should_retrain():
            return RetrainResult(
                success=False,
                new_model_path=None,
                metrics={},
                improvement_score=0.0,
                samples_used=0,
                retrain_id="",
                created_at=datetime.utcnow().isoformat() + "Z",
                error_message="Retrain conditions not met"
            )
        
        self.is_retraining = True
        self.retrain_attempts += 1
        
        try:
            # Collect samples
            samples = self.collect_training_samples()
            if not samples:
                return RetrainResult(
                    success=False,
                    new_model_path=None,
                    metrics={},
                    improvement_score=0.0,
                    samples_used=0,
                    retrain_id="",
                    created_at=datetime.utcnow().isoformat() + "Z",
                    error_message="No samples available for retraining"
                )
            
            # Create dataset
            manifest_path = self.create_retrain_dataset(samples)
            
            # Trigger retrain
            job_id = self.trigger_retrain(manifest_path)
            
            # Monitor job
            result = self.monitor_retrain_job(job_id)
            result.samples_used = len(samples)
            
            # Mark samples as processed if successful
            if result.success:
                for sample in samples:
                    self.priority_queue.mark_processed(sample["image_path"])
            
            # Update state
            self.last_retrain_time = datetime.utcnow()
            
            # Save result
            self._save_retrain_result(result)
            
            return result
            
        except Exception as e:
            LOGGER.error(f"Error in continuous learning cycle: {e}")
            return RetrainResult(
                success=False,
                new_model_path=None,
                metrics={},
                improvement_score=0.0,
                samples_used=0,
                retrain_id="",
                created_at=datetime.utcnow().isoformat() + "Z",
                error_message=str(e)
            )
        finally:
            self.is_retraining = False
    
    def _save_retrain_result(self, result: RetrainResult) -> None:
        """Save retrain result to disk."""
        result_file = self.results_dir / f"retrain_result_{result.retrain_id}.json"
        result_file.write_text(json.dumps(asdict(result), indent=2))
    
    def get_retrain_history(self) -> List[RetrainResult]:
        """Get history of retrain results."""
        results = []
        for result_file in self.results_dir.glob("retrain_result_*.json"):
            try:
                data = json.loads(result_file.read_text())
                results.append(RetrainResult(**data))
            except Exception as e:
                LOGGER.warning(f"Failed to load result file {result_file}: {e}")
        
        # Sort by creation time
        results.sort(key=lambda x: x.created_at, reverse=True)
        return results
    
    def start_background_learning(self) -> None:
        """Start background continuous learning thread."""
        def learning_loop():
            while True:
                try:
                    if self.should_retrain():
                        LOGGER.info("Starting continuous learning cycle")
                        result = self.run_continuous_learning_cycle()
                        LOGGER.info(f"Continuous learning cycle completed: {result.success}")
                    else:
                        LOGGER.debug("Retrain conditions not met, waiting...")
                    
                    # Wait before next check
                    time.sleep(300)  # Check every 5 minutes
                    
                except Exception as e:
                    LOGGER.error(f"Error in background learning loop: {e}")
                    time.sleep(60)  # Wait 1 minute on error
        
        thread = threading.Thread(target=learning_loop, daemon=True)
        thread.start()
        LOGGER.info("Started background continuous learning")
