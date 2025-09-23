"""End-to-end workflow orchestration integrating all components."""

from __future__ import annotations

import json
import logging
import threading
import time
from dataclasses import dataclass, asdict
from datetime import datetime, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Any

from ml.dataset_manager import DatasetManager
from ml.orchestrator import TrainingJobManager
from ml.packaging import ModelPackager
from ml.packaging.registry import PackagingRegistry
from ml.evaluation import GoldenSetManager
from ml.continuous_learning import RetrainOrchestrator, RetrainConfig, ModelComparator
from ml.feedback import PriorityQueue, UncertaintySampler, AndroidFeedbackCollector
from scripts.release_playbook import ReleasePlaybook, ReleaseConfig

LOGGER = logging.getLogger("end_to_end_workflow")


@dataclass
class WorkflowConfig:
    """Configuration for end-to-end workflow."""
    # Data management
    auto_collect_feedback: bool = True
    feedback_collection_interval: int = 300  # 5 minutes
    
    # Active learning
    enable_active_learning: bool = True
    uncertainty_threshold: float = 0.3
    min_samples_for_retrain: int = 50
    
    # Continuous learning
    enable_continuous_learning: bool = True
    retrain_interval_hours: int = 24
    improvement_threshold: float = 0.02
    
    # Release management
    auto_release: bool = False
    release_interval_days: int = 7
    min_improvement_for_release: float = 0.05
    
    # Monitoring
    enable_monitoring: bool = True
    monitoring_interval: int = 60  # 1 minute
    alert_webhook: Optional[str] = None


@dataclass
class WorkflowStatus:
    """Current status of the end-to-end workflow."""
    is_running: bool
    last_feedback_collection: Optional[str]
    last_retrain: Optional[str]
    last_release: Optional[str]
    active_learning_samples: int
    priority_queue_size: int
    current_model_version: Optional[str]
    workflow_health: str  # 'healthy', 'degraded', 'unhealthy'
    created_at: str
    last_updated: str


class EndToEndWorkflow:
    """Orchestrates the complete end-to-end ML pipeline."""
    
    def __init__(
        self,
        config: WorkflowConfig,
        project_root: Path,
    ):
        self.config = config
        self.project_root = project_root
        
        # Initialize all components
        self.dataset_manager = DatasetManager()
        self.training_job_manager = TrainingJobManager()
        self.packaging_registry = PackagingRegistry()
        self.golden_set_manager = GoldenSetManager()
        self.priority_queue = PriorityQueue()
        self.uncertainty_sampler = UncertaintySampler(config.uncertainty_threshold)
        self.feedback_collector = AndroidFeedbackCollector()
        
        # Initialize continuous learning
        retrain_config = RetrainConfig(
            min_samples_for_retrain=config.min_samples_for_retrain,
            retrain_interval_hours=config.retrain_interval_hours,
            improvement_threshold=config.improvement_threshold,
        )
        self.retrain_orchestrator = RetrainOrchestrator(
            retrain_config,
            self.priority_queue,
            self.dataset_manager,
            self.training_job_manager,
            project_root
        )
        
        # Initialize model comparator
        self.model_comparator = ModelComparator(
            improvement_threshold=config.improvement_threshold
        )
        
        # Initialize release playbook
        self.release_playbook = ReleasePlaybook(project_root)
        
        # Workflow state
        self.is_running = False
        self.workflow_thread = None
        self.status = WorkflowStatus(
            is_running=False,
            last_feedback_collection=None,
            last_retrain=None,
            last_release=None,
            active_learning_samples=0,
            priority_queue_size=0,
            current_model_version=None,
            workflow_health="healthy",
            created_at=datetime.utcnow().isoformat() + "Z",
            last_updated=datetime.utcnow().isoformat() + "Z",
        )
        
        # State storage
        self.state_dir = project_root / "data" / "workflow_state"
        self.state_dir.mkdir(parents=True, exist_ok=True)
    
    def start_workflow(self) -> None:
        """Start the end-to-end workflow."""
        if self.is_running:
            LOGGER.warning("Workflow is already running")
            return
        
        self.is_running = True
        self.status.is_running = True
        self.status.last_updated = datetime.utcnow().isoformat() + "Z"
        
        # Start background thread
        self.workflow_thread = threading.Thread(target=self._workflow_loop, daemon=True)
        self.workflow_thread.start()
        
        LOGGER.info("End-to-end workflow started")
    
    def stop_workflow(self) -> None:
        """Stop the end-to-end workflow."""
        if not self.is_running:
            LOGGER.warning("Workflow is not running")
            return
        
        self.is_running = False
        self.status.is_running = False
        self.status.last_updated = datetime.utcnow().isoformat() + "Z"
        
        if self.workflow_thread:
            self.workflow_thread.join(timeout=30)
        
        LOGGER.info("End-to-end workflow stopped")
    
    def _workflow_loop(self) -> None:
        """Main workflow loop."""
        while self.is_running:
            try:
                # Update status
                self._update_workflow_status()
                
                # Step 1: Collect feedback from Android
                if self.config.auto_collect_feedback:
                    self._collect_feedback()
                
                # Step 2: Process active learning
                if self.config.enable_active_learning:
                    self._process_active_learning()
                
                # Step 3: Run continuous learning
                if self.config.enable_continuous_learning:
                    self._run_continuous_learning()
                
                # Step 4: Check for release
                if self.config.auto_release:
                    self._check_for_release()
                
                # Step 5: Monitor workflow health
                if self.config.enable_monitoring:
                    self._monitor_workflow_health()
                
                # Save state
                self._save_workflow_state()
                
                # Wait before next iteration
                time.sleep(self.config.monitoring_interval)
                
            except Exception as e:
                LOGGER.error(f"Error in workflow loop: {e}")
                self.status.workflow_health = "unhealthy"
                time.sleep(60)  # Wait 1 minute on error
    
    def _collect_feedback(self) -> None:
        """Collect feedback from Android app."""
        try:
            # Upload pending reports
            upload_result = self.feedback_collector.upload_pending_reports()
            
            if upload_result["uploaded"] > 0:
                LOGGER.info(f"Uploaded {upload_result['uploaded']} feedback reports")
                self.status.last_feedback_collection = datetime.utcnow().isoformat() + "Z"
            
            if upload_result["failed"] > 0:
                LOGGER.warning(f"Failed to upload {upload_result['failed']} feedback reports")
                
        except Exception as e:
            LOGGER.error(f"Error collecting feedback: {e}")
    
    def _process_active_learning(self) -> None:
        """Process active learning samples."""
        try:
            # Get uncertain samples from recent predictions
            # This would typically analyze recent model predictions
            # For now, we'll use a simplified approach
            
            # Check if we have enough samples for active learning
            queue_stats = self.priority_queue.get_stats()
            uncertainty_samples = queue_stats.get("reason_breakdown", {}).get("uncertainty", 0)
            
            if uncertainty_samples >= self.config.min_samples_for_retrain:
                LOGGER.info(f"Found {uncertainty_samples} uncertain samples for active learning")
                self.status.active_learning_samples = uncertainty_samples
            
        except Exception as e:
            LOGGER.error(f"Error processing active learning: {e}")
    
    def _run_continuous_learning(self) -> None:
        """Run continuous learning cycle."""
        try:
            # Check if we should retrain
            if self.retrain_orchestrator.should_retrain():
                LOGGER.info("Starting continuous learning cycle")
                
                # Run retrain cycle
                result = self.retrain_orchestrator.run_continuous_learning_cycle()
                
                if result.success:
                    LOGGER.info(f"Continuous learning completed successfully: {result.retrain_id}")
                    self.status.last_retrain = datetime.utcnow().isoformat() + "Z"
                    
                    # Compare with current model
                    self._compare_and_promote_model(result)
                else:
                    LOGGER.warning(f"Continuous learning failed: {result.error_message}")
                    
        except Exception as e:
            LOGGER.error(f"Error in continuous learning: {e}")
    
    def _compare_and_promote_model(self, retrain_result) -> None:
        """Compare new model with current model and promote if better."""
        try:
            # Load current model metrics (simplified)
            current_metrics = {
                "map": 0.8,  # This would be loaded from actual model
                "map50": 0.85,
                "precision": 0.82,
                "recall": 0.78,
            }
            
            # Load new model metrics
            new_metrics = retrain_result.metrics.get("summary", {})
            
            # Compare models
            comparison = self.model_comparator.compare_with_baseline(
                retrain_result.metrics,  # This would be a ModelMetrics object
                current_metrics
            )
            
            if comparison.recommendation == "promote":
                LOGGER.info("New model is better, promoting...")
                # Promote model in registry
                # This would update the current model version
                self.status.current_model_version = retrain_result.retrain_id
            else:
                LOGGER.info(f"New model not better: {comparison.recommendation}")
                
        except Exception as e:
            LOGGER.error(f"Error comparing models: {e}")
    
    def _check_for_release(self) -> None:
        """Check if conditions are met for a new release."""
        try:
            # Check if enough time has passed since last release
            if self.status.last_release:
                from datetime import datetime
                last_release_time = datetime.fromisoformat(self.status.last_release.replace("Z", "+00:00"))
                time_since_release = datetime.utcnow() - last_release_time
                
                if time_since_release < timedelta(days=self.config.release_interval_days):
                    return
            
            # Check if we have significant improvements
            # This would analyze recent model improvements
            has_significant_improvement = True  # Simplified
            
            if has_significant_improvement:
                LOGGER.info("Conditions met for new release")
                self._create_release()
                
        except Exception as e:
            LOGGER.error(f"Error checking for release: {e}")
    
    def _create_release(self) -> None:
        """Create a new release."""
        try:
            # Create release config
            release_config = ReleaseConfig(
                model_name="coupon_detector",
                version=f"v{int(time.time())}",
                description="Automated release from continuous learning",
                train_epochs=50,  # Shorter for continuous releases
                auto_promote=True,
            )
            
            # Create release
            result = self.release_playbook.create_release(release_config, dry_run=False)
            
            if result.overall_status == "completed":
                LOGGER.info(f"Release created successfully: {result.release_id}")
                self.status.last_release = datetime.utcnow().isoformat() + "Z"
            else:
                LOGGER.warning(f"Release failed: {result.overall_status}")
                
        except Exception as e:
            LOGGER.error(f"Error creating release: {e}")
    
    def _monitor_workflow_health(self) -> None:
        """Monitor workflow health and update status."""
        try:
            # Check various health indicators
            health_issues = []
            
            # Check feedback collection
            if self.status.last_feedback_collection:
                last_collection = datetime.fromisoformat(self.status.last_feedback_collection.replace("Z", "+00:00"))
                if datetime.utcnow() - last_collection > timedelta(hours=1):
                    health_issues.append("Feedback collection stale")
            
            # Check priority queue size
            queue_stats = self.priority_queue.get_stats()
            if queue_stats["unprocessed"] > 1000:
                health_issues.append("Priority queue backlog")
            
            # Check continuous learning
            if self.status.last_retrain:
                last_retrain = datetime.fromisoformat(self.status.last_retrain.replace("Z", "+00:00"))
                if datetime.utcnow() - last_retrain > timedelta(days=7):
                    health_issues.append("No recent retraining")
            
            # Update health status
            if not health_issues:
                self.status.workflow_health = "healthy"
            elif len(health_issues) <= 2:
                self.status.workflow_health = "degraded"
            else:
                self.status.workflow_health = "unhealthy"
            
            # Send alerts if needed
            if health_issues and self.config.alert_webhook:
                self._send_alert(health_issues)
                
        except Exception as e:
            LOGGER.error(f"Error monitoring workflow health: {e}")
    
    def _send_alert(self, issues: List[str]) -> None:
        """Send alert about workflow issues."""
        try:
            import requests
            
            alert_data = {
                "workflow_health": self.status.workflow_health,
                "issues": issues,
                "timestamp": datetime.utcnow().isoformat() + "Z",
            }
            
            requests.post(self.config.alert_webhook, json=alert_data, timeout=10)
            
        except Exception as e:
            LOGGER.error(f"Error sending alert: {e}")
    
    def _update_workflow_status(self) -> None:
        """Update workflow status."""
        try:
            # Update queue size
            queue_stats = self.priority_queue.get_stats()
            self.status.priority_queue_size = queue_stats["unprocessed"]
            
            # Update active learning samples
            uncertainty_samples = queue_stats.get("reason_breakdown", {}).get("uncertainty", 0)
            self.status.active_learning_samples = uncertainty_samples
            
            # Update last updated time
            self.status.last_updated = datetime.utcnow().isoformat() + "Z"
            
        except Exception as e:
            LOGGER.error(f"Error updating workflow status: {e}")
    
    def _save_workflow_state(self) -> None:
        """Save workflow state to disk."""
        try:
            state_file = self.state_dir / "workflow_state.json"
            state_file.write_text(json.dumps(asdict(self.status), indent=2))
        except Exception as e:
            LOGGER.error(f"Error saving workflow state: {e}")
    
    def load_workflow_state(self) -> None:
        """Load workflow state from disk."""
        try:
            state_file = self.state_dir / "workflow_state.json"
            if state_file.exists():
                data = json.loads(state_file.read_text())
                self.status = WorkflowStatus(**data)
        except Exception as e:
            LOGGER.error(f"Error loading workflow state: {e}")
    
    def get_workflow_status(self) -> WorkflowStatus:
        """Get current workflow status."""
        return self.status
    
    def get_workflow_metrics(self) -> Dict[str, Any]:
        """Get workflow metrics."""
        return {
            "is_running": self.is_running,
            "workflow_health": self.status.workflow_health,
            "priority_queue_size": self.status.priority_queue_size,
            "active_learning_samples": self.status.active_learning_samples,
            "last_feedback_collection": self.status.last_feedback_collection,
            "last_retrain": self.status.last_retrain,
            "last_release": self.status.last_release,
            "current_model_version": self.status.current_model_version,
        }
    
    def trigger_manual_retrain(self) -> str:
        """Manually trigger a retrain cycle."""
        try:
            result = self.retrain_orchestrator.run_continuous_learning_cycle()
            return result.retrain_id
        except Exception as e:
            LOGGER.error(f"Error triggering manual retrain: {e}")
            raise
    
    def trigger_manual_release(self, version: str, description: str) -> str:
        """Manually trigger a release."""
        try:
            config = ReleaseConfig(
                model_name="coupon_detector",
                version=version,
                description=description,
                auto_promote=True,
            )
            
            result = self.release_playbook.create_release(config, dry_run=False)
            return result.release_id
        except Exception as e:
            LOGGER.error(f"Error triggering manual release: {e}")
            raise
