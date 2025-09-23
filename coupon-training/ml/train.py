"""Neural training entrypoint for coupon detection/recognition."""

from __future__ import annotations

import argparse
import json
import logging
import pathlib
import tempfile
from datetime import datetime
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Any, Tuple

import yaml

from ml.data.manifest import Manifest, load_manifest
from ml.ocr.evaluator import evaluate_with_tesseract

LOGGER = logging.getLogger("ml.train")

try:
    from ultralytics import YOLO
except ModuleNotFoundError:  # pragma: no cover
    YOLO = None

try:
    import mlflow
except ModuleNotFoundError:  # pragma: no cover
    mlflow = None


@dataclass
class DetectorConfig:
    enabled: bool = True
    model: str = "yolov8n.pt"
    epochs: int = 50
    batch: int = 16
    imgsz: int = 1024


@dataclass
class RecognizerConfig:
    enabled: bool = False
    model: str = "microsoft/trocr-base-printed"
    epochs: int = 3


@dataclass
class TrainingConfig:
    manifest_path: pathlib.Path
    output_dir: pathlib.Path
    experiment_name: str = "coupon_unified_model"
    detector: DetectorConfig = field(default_factory=DetectorConfig)
    recognizer: RecognizerConfig = field(default_factory=RecognizerConfig)
    augmentation_profile: str = "default"
    use_mlflow: bool = True


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train coupon detector/recognizer")
    parser.add_argument("--config", required=True, help="YAML config path")
    return parser.parse_args()


def load_config(path: pathlib.Path) -> TrainingConfig:
    data = yaml.safe_load(path.read_text())
    detector = DetectorConfig(**data.get("detector", {}))
    recognizer = RecognizerConfig(**data.get("recognizer", {}))
    return TrainingConfig(
        manifest_path=pathlib.Path(data["manifest"]),
        output_dir=pathlib.Path(data.get("output_dir", "artifacts")),
        experiment_name=data.get("experiment", "coupon_unified_model"),
        detector=detector,
        recognizer=recognizer,
        augmentation_profile=data.get("augmentation_profile", "default"),
        use_mlflow=data.get("use_mlflow", True),
    )


def prepare_yolo_dataset(manifest: Manifest, root: pathlib.Path) -> Dict[str, pathlib.Path]:
    images_dir = root / "images"
    labels_dir = root / "labels"
    for split in ("train", "val", "test"):
        (images_dir / split).mkdir(parents=True, exist_ok=True)
        (labels_dir / split).mkdir(parents=True, exist_ok=True)

    categories = manifest.categories
    cat_to_idx = {name: idx for idx, name in enumerate(categories)}

    for ann in manifest.annotations:
        if not ann.image_path.exists():
            LOGGER.warning("Missing image %s", ann.image_path)
            continue
        split = ann.split.lower()
        split = "train" if split not in {"train", "val", "test"} else split
        target_image = images_dir / split / ann.image_path.name
        if not target_image.exists():
            target_image.symlink_to(ann.image_path)
        label_path = labels_dir / split / (ann.image_path.stem + ".txt")
        lines = []
        for box in ann.bboxes:
            idx = cat_to_idx[box.category]
            lines.append(box.as_yolo(ann.width, ann.height, idx))
        label_path.write_text("\n".join(lines))

    data_yaml = {
        "path": str(root.resolve()),
        "train": "images/train",
        "val": "images/val",
        "test": "images/test",
        "names": categories,
    }
    yaml_path = root / "dataset.yaml"
    yaml_path.write_text(yaml.safe_dump(data_yaml))
    return {"yaml": yaml_path, "categories": categories}


def _extract_detector_metrics(results: Any, categories: List[str]) -> Dict[str, Any]:
    summary: Dict[str, Any] = {}
    per_class: List[Dict[str, Any]] = []
    try:
        metrics_obj = getattr(results, "metrics", None)
        if metrics_obj and hasattr(metrics_obj, "box"):
            box = metrics_obj.box
            summary = {
                "map": float(getattr(box, "map", 0.0) or 0.0),
                "map50": float(getattr(box, "map50", 0.0) or 0.0),
                "precision": float(getattr(box, "mp", 0.0) or 0.0),
                "recall": float(getattr(box, "mr", 0.0) or 0.0),
            }
            maps = getattr(box, "maps", None)
            if maps:
                for idx, cat in enumerate(categories):
                    value = float(maps[idx]) if idx < len(maps) else None
                    per_class.append({"category": cat, "map50": value})
    except Exception as exc:  # pragma: no cover - best effort extraction
        LOGGER.warning("Failed to parse detector metrics: %s", exc)

    try:
        results_dict = getattr(results, "results_dict", None)
        if results_dict:
            summary.setdefault("box_loss", results_dict.get("train/box_loss"))
            summary.setdefault("cls_loss", results_dict.get("train/cls_loss"))
            summary.setdefault("dfl_loss", results_dict.get("train/dfl_loss"))
    except Exception:  # pragma: no cover
        pass

    return {
        "summary": summary,
        "per_class": per_class,
    }


def train_detector(manifest: Manifest, cfg: TrainingConfig) -> Tuple[Optional[pathlib.Path], Dict[str, Any]]:
    if not cfg.detector.enabled:
        LOGGER.info("Detector training disabled")
        return None, {}
    if YOLO is None:
        raise RuntimeError("Ultralytics YOLO not installed. See requirements-ml.txt")

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_root = pathlib.Path(tmpdir)
        data_assets = prepare_yolo_dataset(manifest, tmp_root)
        model = YOLO(cfg.detector.model)
        results = model.train(
            data=str(data_assets["yaml"]),
            epochs=cfg.detector.epochs,
            batch=cfg.detector.batch,
            imgsz=cfg.detector.imgsz,
            project=str(cfg.output_dir / "detector"),
            name="yolo",
        )
        best_model = pathlib.Path(results.save_dir) / "weights" / "best.pt"
        metrics = _extract_detector_metrics(results, manifest.categories)
        return (best_model if best_model.exists() else None, metrics)


def log_mlflow(
    config: TrainingConfig,
    artifacts: Dict[str, Optional[pathlib.Path]],
    metrics: Dict[str, float],
    extras: Dict[str, str | None],
) -> None:
    if not config.use_mlflow:
        return
    if mlflow is None:
        raise RuntimeError("MLflow not installed. See requirements-ml.txt")

    mlflow.set_experiment(config.experiment_name)
    with mlflow.start_run(run_name="detector"):
        mlflow.log_params(
            {
                "detector_model": config.detector.model,
                "detector_epochs": config.detector.epochs,
                "detector_batch": config.detector.batch,
                "augmentation": config.augmentation_profile,
            }
        )
        for name, path in artifacts.items():
            if path and path.exists():
                mlflow.log_artifact(str(path), artifact_path=name)
        for key, value in metrics.items():
            mlflow.log_metric(key, value)
        mlflow.log_dict(extras, artifact_file="metadata.json")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = parse_args()
    cfg = load_config(pathlib.Path(args.config))

    manifest = load_manifest(cfg.manifest_path)
    cfg.output_dir.mkdir(parents=True, exist_ok=True)

    detector_path, detector_metrics = train_detector(manifest, cfg)

    ocr_metrics = evaluate_with_tesseract(manifest)

    trained_at = datetime.utcnow().isoformat() + "Z"
    meta = {
        "detector_checkpoint": str(detector_path) if detector_path else None,
        "categories": manifest.categories,
        "ocr_metrics": ocr_metrics,
        "detector_metrics": detector_metrics,
        "trained_at": trained_at,
        "manifest_path": str(cfg.manifest_path),
        "output_dir": str(cfg.output_dir.resolve()),
    }
    (cfg.output_dir / "metadata.json").write_text(json.dumps(meta, indent=2))
    evaluation_payload = {
        "detector": detector_metrics,
        "ocr": ocr_metrics,
    }
    (cfg.output_dir / "evaluation.json").write_text(json.dumps(evaluation_payload, indent=2))

    artifacts = {"detector": detector_path}
    metrics = {f"ocr_{key}": value for key, value in ocr_metrics.items()}
    summary_metrics = detector_metrics.get("summary", {})
    if summary_metrics:
        for key, value in summary_metrics.items():
            if value is not None:
                metrics[f"detector_{key}"] = float(value)
    log_mlflow(cfg, artifacts, metrics, meta)
    LOGGER.info("Training complete. Artifacts stored in %s", cfg.output_dir)


if __name__ == "__main__":
    main()
