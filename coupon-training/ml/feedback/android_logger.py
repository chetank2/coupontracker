"""Android-side failure logging and feedback collection."""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, asdict
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Any

import requests
from PIL import Image

LOGGER = logging.getLogger("android_logger")


@dataclass
class FailureReport:
    """Represents a failure report from Android app."""
    session_id: str
    timestamp: str
    app_version: str
    model_version: str
    image_path: str
    failure_type: str  # 'detection_failed', 'ocr_failed', 'parsing_failed', 'user_correction'
    confidence_scores: Dict[str, float]
    detected_regions: List[Dict[str, Any]]
    user_corrections: Optional[Dict[str, Any]] = None
    device_info: Optional[Dict[str, str]] = None
    error_message: Optional[str] = None


class AndroidFeedbackCollector:
    """Collects and uploads failure feedback from Android app."""
    
    def __init__(
        self,
        upload_endpoint: str = "http://localhost:5001/api/feedback/upload",
        local_storage_dir: Path = Path("data/feedback/android"),
        batch_size: int = 10,
    ):
        self.upload_endpoint = upload_endpoint
        self.local_storage_dir = local_storage_dir
        self.batch_size = batch_size
        self.local_storage_dir.mkdir(parents=True, exist_ok=True)
        
    def log_detection_failure(
        self,
        session_id: str,
        image: Image.Image,
        app_version: str,
        model_version: str,
        confidence_scores: Dict[str, float],
        error_message: str,
        device_info: Optional[Dict[str, str]] = None,
    ) -> str:
        """Log a detection failure."""
        return self._log_failure(
            session_id=session_id,
            image=image,
            app_version=app_version,
            model_version=model_version,
            failure_type="detection_failed",
            confidence_scores=confidence_scores,
            detected_regions=[],
            error_message=error_message,
            device_info=device_info,
        )
    
    def log_ocr_failure(
        self,
        session_id: str,
        image: Image.Image,
        app_version: str,
        model_version: str,
        detected_regions: List[Dict[str, Any]],
        confidence_scores: Dict[str, float],
        error_message: str,
        device_info: Optional[Dict[str, str]] = None,
    ) -> str:
        """Log an OCR failure."""
        return self._log_failure(
            session_id=session_id,
            image=image,
            app_version=app_version,
            model_version=model_version,
            failure_type="ocr_failed",
            confidence_scores=confidence_scores,
            detected_regions=detected_regions,
            error_message=error_message,
            device_info=device_info,
        )
    
    def log_parsing_failure(
        self,
        session_id: str,
        image: Image.Image,
        app_version: str,
        model_version: str,
        detected_regions: List[Dict[str, Any]],
        confidence_scores: Dict[str, float],
        raw_text: str,
        device_info: Optional[Dict[str, str]] = None,
    ) -> str:
        """Log a parsing failure."""
        return self._log_failure(
            session_id=session_id,
            image=image,
            app_version=app_version,
            model_version=model_version,
            failure_type="parsing_failed",
            confidence_scores=confidence_scores,
            detected_regions=detected_regions,
            error_message=f"Raw text: {raw_text}",
            device_info=device_info,
        )
    
    def log_user_correction(
        self,
        session_id: str,
        image: Image.Image,
        app_version: str,
        model_version: str,
        detected_regions: List[Dict[str, Any]],
        confidence_scores: Dict[str, float],
        user_corrections: Dict[str, Any],
        device_info: Optional[Dict[str, str]] = None,
    ) -> str:
        """Log a user correction."""
        return self._log_failure(
            session_id=session_id,
            image=image,
            app_version=app_version,
            model_version=model_version,
            failure_type="user_correction",
            confidence_scores=confidence_scores,
            detected_regions=detected_regions,
            user_corrections=user_corrections,
            device_info=device_info,
        )
    
    def _log_failure(
        self,
        session_id: str,
        image: Image.Image,
        app_version: str,
        model_version: str,
        failure_type: str,
        confidence_scores: Dict[str, float],
        detected_regions: List[Dict[str, Any]],
        error_message: Optional[str] = None,
        user_corrections: Optional[Dict[str, Any]] = None,
        device_info: Optional[Dict[str, str]] = None,
    ) -> str:
        """Internal method to log any type of failure."""
        timestamp = datetime.utcnow().isoformat() + "Z"
        
        # Save image locally
        image_filename = f"{session_id}_{int(time.time())}.jpg"
        image_path = self.local_storage_dir / image_filename
        image.save(image_path, "JPEG", quality=85)
        
        # Create failure report
        report = FailureReport(
            session_id=session_id,
            timestamp=timestamp,
            app_version=app_version,
            model_version=model_version,
            image_path=str(image_path),
            failure_type=failure_type,
            confidence_scores=confidence_scores,
            detected_regions=detected_regions,
            user_corrections=user_corrections,
            device_info=device_info,
            error_message=error_message,
        )
        
        # Save report locally
        report_filename = f"{session_id}_{int(time.time())}.json"
        report_path = self.local_storage_dir / report_filename
        report_path.write_text(json.dumps(asdict(report), indent=2))
        
        # Queue for upload
        self._queue_for_upload(report)
        
        LOGGER.info(f"Logged {failure_type} failure for session {session_id}")
        return str(report_path)
    
    def _queue_for_upload(self, report: FailureReport) -> None:
        """Queue a report for upload to the server."""
        queue_file = self.local_storage_dir / "upload_queue.json"
        
        if queue_file.exists():
            queue = json.loads(queue_file.read_text())
        else:
            queue = []
        
        queue.append(asdict(report))
        queue_file.write_text(json.dumps(queue, indent=2))
    
    def upload_pending_reports(self) -> Dict[str, Any]:
        """Upload all pending reports to the server."""
        queue_file = self.local_storage_dir / "upload_queue.json"
        
        if not queue_file.exists():
            return {"uploaded": 0, "failed": 0, "errors": []}
        
        queue = json.loads(queue_file.read_text())
        uploaded = 0
        failed = 0
        errors = []
        
        for report_data in queue:
            try:
                # Upload image
                image_path = Path(report_data["image_path"])
                with open(image_path, "rb") as f:
                    files = {"image": f}
                    data = {k: v for k, v in report_data.items() if k != "image_path"}
                    
                    response = requests.post(
                        self.upload_endpoint,
                        files=files,
                        data=data,
                        timeout=30
                    )
                    
                    if response.status_code == 200:
                        uploaded += 1
                    else:
                        failed += 1
                        errors.append(f"HTTP {response.status_code}: {response.text}")
                        
            except Exception as e:
                failed += 1
                errors.append(f"Upload error: {str(e)}")
        
        # Clear queue after upload attempt
        if uploaded > 0:
            queue_file.unlink()
        
        return {
            "uploaded": uploaded,
            "failed": failed,
            "errors": errors
        }
    
    def get_pending_count(self) -> int:
        """Get count of pending uploads."""
        queue_file = self.local_storage_dir / "upload_queue.json"
        if not queue_file.exists():
            return 0
        
        queue = json.loads(queue_file.read_text())
        return len(queue)
