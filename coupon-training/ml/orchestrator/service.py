"""FastAPI service to orchestrate training jobs."""

from __future__ import annotations

import json
import os
import subprocess
import threading
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

PROJECT_ROOT = Path(__file__).resolve().parents[1]
RUNS_DIR = PROJECT_ROOT / "runs" / "orchestrator"
LOGS_DIR = RUNS_DIR / "logs"
STATE_FILE = RUNS_DIR / "jobs.json"

RUNS_DIR.mkdir(parents=True, exist_ok=True)
LOGS_DIR.mkdir(parents=True, exist_ok=True)


@dataclass
class Job:
    job_id: str
    status: str
    config_path: str
    created_at: str
    started_at: Optional[str] = None
    finished_at: Optional[str] = None
    exit_code: Optional[int] = None
    log_path: Optional[str] = None
    notes: Optional[str] = None


class JobRequest(BaseModel):
    config_path: str = Field(..., description="Path to YAML config passed to ml/train.py")
    notes: Optional[str] = Field(None, description="Optional human-readable description")


class TrainingJobManager:
    def __init__(self) -> None:
        self._jobs: Dict[str, Job] = {}
        self._lock = threading.Lock()
        self._load_state()

    # ------------------------------------------------------------------
    # Persistence helpers
    # ------------------------------------------------------------------
    def _load_state(self) -> None:
        if STATE_FILE.exists():
            try:
                data = json.loads(STATE_FILE.read_text())
                for entry in data.get("jobs", []):
                    job = Job(**entry)
                    self._jobs[job.job_id] = job
            except json.JSONDecodeError:
                pass

    def _save_state(self) -> None:
        STATE_FILE.write_text(json.dumps({"jobs": [asdict(job) for job in self._jobs.values()]}, indent=2))

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------
    def list_jobs(self) -> Dict[str, Job]:
        with self._lock:
            return dict(self._jobs)

    def get_job(self, job_id: str) -> Optional[Job]:
        with self._lock:
            return self._jobs.get(job_id)

    def submit(self, request: JobRequest) -> Job:
        config_path = self._resolve_config(request.config_path)
        job_id = uuid.uuid4().hex
        log_path = LOGS_DIR / f"{job_id}.log"

        job = Job(
            job_id=job_id,
            status="queued",
            config_path=str(config_path),
            created_at=datetime.utcnow().isoformat() + "Z",
            log_path=str(log_path),
            notes=request.notes,
        )
        with self._lock:
            self._jobs[job_id] = job
            self._save_state()

        thread = threading.Thread(target=self._run_job, args=(job_id,), daemon=True)
        thread.start()
        return job

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------
    def _resolve_config(self, config_path: str) -> Path:
        path = Path(config_path)
        if not path.is_absolute():
            path = PROJECT_ROOT / path
        if not path.exists():
            raise ValueError(f"Config file not found: {path}")
        return path.resolve()

    def _update_job(self, job: Job) -> None:
        with self._lock:
            self._jobs[job.job_id] = job
            self._save_state()

    def _run_job(self, job_id: str) -> None:
        job = self.get_job(job_id)
        if not job:
            return
        job.status = "running"
        job.started_at = datetime.utcnow().isoformat() + "Z"
        self._update_job(job)

        log_path = Path(job.log_path)
        env = os.environ.copy()
        env.setdefault("PYTHONPATH", str(PROJECT_ROOT))
        cmd = [
            "python3",
            "ml/train.py",
            "--config",
            job.config_path,
        ]

        with log_path.open("w") as log_file:
            log_file.write(f"# Job {job.job_id} started at {job.started_at}\n")
            log_file.write(f"# Command: {' '.join(cmd)}\n\n")
            log_file.flush()
            process = subprocess.Popen(
                cmd,
                cwd=str(PROJECT_ROOT),
                stdout=log_file,
                stderr=subprocess.STDOUT,
                env=env,
            )
            exit_code = process.wait()

        job.exit_code = exit_code
        job.finished_at = datetime.utcnow().isoformat() + "Z"
        job.status = "succeeded" if exit_code == 0 else "failed"
        self._update_job(job)


def create_app() -> FastAPI:
    job_manager = TrainingJobManager()
    app = FastAPI(title="Coupon Training Orchestrator")

    @app.get("/health")
    def health() -> Dict[str, str]:
        return {"status": "ok"}

    @app.get("/jobs")
    def list_jobs():
        jobs = job_manager.list_jobs()
        return {"jobs": [asdict(job) for job in jobs.values()]}

    @app.get("/jobs/{job_id}")
    def get_job(job_id: str):
        job = job_manager.get_job(job_id)
        if not job:
            raise HTTPException(status_code=404, detail="Job not found")
        return {"job": asdict(job)}

    @app.post("/jobs", status_code=201)
    def submit_job(payload: JobRequest):
        job = job_manager.submit(payload)
        return {"job": asdict(job)}

    return app
