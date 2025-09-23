#!/usr/bin/env python3
"""CLI tool for managing the end-to-end workflow."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

# Add project root to path
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.append(str(PROJECT_ROOT))

from ml.workflow import EndToEndWorkflow, WorkflowConfig
from ml.feedback import PriorityQueue, UncertaintySampler
from ml.continuous_learning import RetrainOrchestrator, RetrainConfig
from ml.evaluation import GoldenSetManager
from scripts.release_playbook import ReleasePlaybook

LOGGER = logging.getLogger("workflow_cli")


def setup_logging(verbose: bool = False):
    """Setup logging configuration."""
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )


def create_workflow_config(args) -> WorkflowConfig:
    """Create workflow configuration from CLI arguments."""
    return WorkflowConfig(
        auto_collect_feedback=args.auto_collect_feedback,
        feedback_collection_interval=args.feedback_interval,
        enable_active_learning=args.enable_active_learning,
        uncertainty_threshold=args.uncertainty_threshold,
        min_samples_for_retrain=args.min_samples_retrain,
        enable_continuous_learning=args.enable_continuous_learning,
        retrain_interval_hours=args.retrain_interval,
        improvement_threshold=args.improvement_threshold,
        auto_release=args.auto_release,
        release_interval_days=args.release_interval,
        min_improvement_for_release=args.min_improvement_release,
        enable_monitoring=args.enable_monitoring,
        monitoring_interval=args.monitoring_interval,
        alert_webhook=args.alert_webhook,
    )


def cmd_start(args):
    """Start the end-to-end workflow."""
    config = create_workflow_config(args)
    workflow = EndToEndWorkflow(config, PROJECT_ROOT)
    
    print("Starting end-to-end workflow...")
    workflow.start_workflow()
    
    status = workflow.get_workflow_status()
    print(f"Workflow started: {status.is_running}")
    print(f"Health: {status.workflow_health}")


def cmd_stop(args):
    """Stop the end-to-end workflow."""
    config = create_workflow_config(args)
    workflow = EndToEndWorkflow(config, PROJECT_ROOT)
    
    print("Stopping end-to-end workflow...")
    workflow.stop_workflow()
    
    status = workflow.get_workflow_status()
    print(f"Workflow stopped: {not status.is_running}")


def cmd_status(args):
    """Get workflow status."""
    config = create_workflow_config(args)
    workflow = EndToEndWorkflow(config, PROJECT_ROOT)
    
    status = workflow.get_workflow_status()
    metrics = workflow.get_workflow_metrics()
    
    print("Workflow Status:")
    print(f"  Running: {status.is_running}")
    print(f"  Health: {status.workflow_health}")
    print(f"  Last Feedback Collection: {status.last_feedback_collection}")
    print(f"  Last Retrain: {status.last_retrain}")
    print(f"  Last Release: {status.last_release}")
    print(f"  Current Model Version: {status.current_model_version}")
    print(f"  Created: {status.created_at}")
    print(f"  Last Updated: {status.last_updated}")
    
    print("\nMetrics:")
    for key, value in metrics.items():
        print(f"  {key}: {value}")


def cmd_retrain(args):
    """Manually trigger a retrain cycle."""
    config = create_workflow_config(args)
    workflow = EndToEndWorkflow(config, PROJECT_ROOT)
    
    print("Triggering manual retrain...")
    retrain_id = workflow.trigger_manual_retrain()
    print(f"Retrain triggered: {retrain_id}")


def cmd_release(args):
    """Manually trigger a release."""
    config = create_workflow_config(args)
    workflow = EndToEndWorkflow(config, PROJECT_ROOT)
    
    print(f"Triggering manual release: {args.version}")
    release_id = workflow.trigger_manual_release(args.version, args.description)
    print(f"Release triggered: {release_id}")


def cmd_queue_status(args):
    """Get priority queue status."""
    priority_queue = PriorityQueue()
    stats = priority_queue.get_stats()
    
    print("Priority Queue Status:")
    print(f"  Total Items: {stats['total_items']}")
    print(f"  Processed: {stats['processed']}")
    print(f"  Unprocessed: {stats['unprocessed']}")
    print(f"  Reason Breakdown: {stats['reason_breakdown']}")


def cmd_create_golden_set(args):
    """Create a golden test set."""
    golden_set_manager = GoldenSetManager()
    
    # Load manifest
    from ml.data.manifest import load_manifest
    manifest = load_manifest(Path(args.manifest))
    
    print(f"Creating golden set from manifest: {args.manifest}")
    golden_set = golden_set_manager.create_from_manifest(
        manifest,
        name=args.name,
        description=args.description,
    )
    
    print(f"Golden set created: {golden_set.name}")
    print(f"Items: {len(golden_set.items)}")


def cmd_test_golden_set(args):
    """Test against a golden set."""
    golden_set_manager = GoldenSetManager()
    
    # Load mock predictions
    predictions = []
    if args.predictions_file:
        with open(args.predictions_file) as f:
            predictions = json.load(f)
    
    print(f"Testing against golden set: {args.golden_set}")
    result = golden_set_manager.run_regression_test(
        args.golden_set,
        predictions
    )
    
    print(f"Test Result: {'PASSED' if result.passed else 'FAILED'}")
    print(f"Score: {result.score:.3f}")
    print(f"Threshold: {result.threshold:.3f}")
    print(f"Details: {json.dumps(result.details, indent=2)}")


def cmd_release_status(args):
    """Get release status."""
    playbook = ReleasePlaybook(PROJECT_ROOT)
    
    if args.release_id:
        status = playbook.get_release_status(args.release_id)
        if status:
            print(json.dumps(status, indent=2))
        else:
            print(f"Release {args.release_id} not found")
    else:
        releases = playbook.list_releases()
        print(f"Found {len(releases)} releases:")
        for release in releases:
            print(f"  {release['release_id']}: {release['overall_status']} ({release['created_at']})")


def build_parser():
    """Build the argument parser."""
    parser = argparse.ArgumentParser(description="End-to-End Workflow CLI")
    parser.add_argument("--verbose", "-v", action="store_true", help="Verbose output")
    
    # Workflow configuration
    parser.add_argument("--auto-collect-feedback", action="store_true", default=True,
                       help="Auto collect feedback from Android")
    parser.add_argument("--feedback-interval", type=int, default=300,
                       help="Feedback collection interval (seconds)")
    parser.add_argument("--enable-active-learning", action="store_true", default=True,
                       help="Enable active learning")
    parser.add_argument("--uncertainty-threshold", type=float, default=0.3,
                       help="Uncertainty threshold for active learning")
    parser.add_argument("--min-samples-retrain", type=int, default=50,
                       help="Minimum samples for retraining")
    parser.add_argument("--enable-continuous-learning", action="store_true", default=True,
                       help="Enable continuous learning")
    parser.add_argument("--retrain-interval", type=int, default=24,
                       help="Retrain interval (hours)")
    parser.add_argument("--improvement-threshold", type=float, default=0.02,
                       help="Improvement threshold for promotion")
    parser.add_argument("--auto-release", action="store_true",
                       help="Enable automatic releases")
    parser.add_argument("--release-interval", type=int, default=7,
                       help="Release interval (days)")
    parser.add_argument("--min-improvement-release", type=float, default=0.05,
                       help="Minimum improvement for release")
    parser.add_argument("--enable-monitoring", action="store_true", default=True,
                       help="Enable monitoring")
    parser.add_argument("--monitoring-interval", type=int, default=60,
                       help="Monitoring interval (seconds)")
    parser.add_argument("--alert-webhook", help="Alert webhook URL")
    
    subparsers = parser.add_subparsers(dest="command", required=True)
    
    # Start command
    start_parser = subparsers.add_parser("start", help="Start the workflow")
    start_parser.set_defaults(func=cmd_start)
    
    # Stop command
    stop_parser = subparsers.add_parser("stop", help="Stop the workflow")
    stop_parser.set_defaults(func=cmd_stop)
    
    # Status command
    status_parser = subparsers.add_parser("status", help="Get workflow status")
    status_parser.set_defaults(func=cmd_status)
    
    # Retrain command
    retrain_parser = subparsers.add_parser("retrain", help="Trigger manual retrain")
    retrain_parser.set_defaults(func=cmd_retrain)
    
    # Release command
    release_parser = subparsers.add_parser("release", help="Trigger manual release")
    release_parser.add_argument("version", help="Release version")
    release_parser.add_argument("--description", help="Release description")
    release_parser.set_defaults(func=cmd_release)
    
    # Queue status command
    queue_parser = subparsers.add_parser("queue", help="Get queue status")
    queue_parser.set_defaults(func=cmd_queue_status)
    
    # Golden set commands
    golden_parser = subparsers.add_parser("golden", help="Golden set management")
    golden_subparsers = golden_parser.add_subparsers(dest="golden_command", required=True)
    
    create_golden_parser = golden_subparsers.add_parser("create", help="Create golden set")
    create_golden_parser.add_argument("--manifest", required=True, help="Manifest file")
    create_golden_parser.add_argument("--name", required=True, help="Golden set name")
    create_golden_parser.add_argument("--description", help="Description")
    create_golden_parser.set_defaults(func=cmd_create_golden_set)
    
    test_golden_parser = golden_subparsers.add_parser("test", help="Test golden set")
    test_golden_parser.add_argument("--golden-set", required=True, help="Golden set name")
    test_golden_parser.add_argument("--predictions-file", help="Predictions file")
    test_golden_parser.set_defaults(func=cmd_test_golden_set)
    
    # Release status command
    release_status_parser = subparsers.add_parser("release-status", help="Get release status")
    release_status_parser.add_argument("--release-id", help="Specific release ID")
    release_status_parser.set_defaults(func=cmd_release_status)
    
    return parser


def main():
    """Main CLI entry point."""
    parser = build_parser()
    args = parser.parse_args()
    
    setup_logging(args.verbose)
    
    try:
        args.func(args)
    except KeyboardInterrupt:
        print("\nOperation cancelled by user")
        sys.exit(1)
    except Exception as e:
        LOGGER.error(f"Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
