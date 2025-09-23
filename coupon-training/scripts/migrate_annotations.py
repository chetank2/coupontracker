#!/usr/bin/env python3
"""Normalize annotation JSON files with metadata and canvas/original coordinates."""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image

DEFAULT_METADATA = {
    "source_type": None,
    "theme": None,
    "language": None,
}


def migrate_annotation(path: Path) -> bool:
    with path.open('r') as f:
        data = json.load(f)
    changed = False

    image_field = data.get('image_path') or data.get('image')
    image_path = Path(image_field) if image_field else None
    image_width = data.get('width') or data.get('image_width')
    image_height = data.get('height') or data.get('image_height')

    if image_path and (not image_width or not image_height):
        abs_path = (path.parent.parent / image_path).resolve()
        if abs_path.exists():
            with Image.open(abs_path) as img:
                image_width, image_height = img.size
                data['width'] = image_width
                data['height'] = image_height
                changed = True

    metadata = data.get('metadata') or {}
    for key, default_val in DEFAULT_METADATA.items():
        if key not in metadata:
            metadata[key] = default_val
            changed = True
    data['metadata'] = metadata

    annotations = data.get('annotations')
    if isinstance(annotations, list):
        for ann in annotations:
            if not isinstance(ann, dict):
                continue
            if 'canvas_left' not in ann:
                ann['canvas_left'] = ann.get('canvas_left') or ann.get('left')
                changed = True
            if 'canvas_top' not in ann:
                ann['canvas_top'] = ann.get('canvas_top') or ann.get('top')
                changed = True
            if 'canvas_width' not in ann:
                ann['canvas_width'] = ann.get('canvas_width') or ann.get('width')
                changed = True
            if 'canvas_height' not in ann:
                ann['canvas_height'] = ann.get('canvas_height') or ann.get('height')
                changed = True
            if 'left' not in ann and ann.get('canvas_left') is not None:
                ann['left'] = ann['canvas_left']
                changed = True
            if 'top' not in ann and ann.get('canvas_top') is not None:
                ann['top'] = ann['canvas_top']
                changed = True
            if 'width' not in ann and ann.get('canvas_width') is not None:
                ann['width'] = ann['canvas_width']
                changed = True
            if 'height' not in ann and ann.get('canvas_height') is not None:
                ann['height'] = ann['canvas_height']
                changed = True

    if changed:
        path.write_text(json.dumps(data, indent=2))
    return changed


def main() -> None:
    annotated_dir = Path('data/annotated')
    updated = 0
    for json_file in annotated_dir.glob('*.json'):
        if migrate_annotation(json_file):
            updated += 1
    print(f'Migrated {updated} annotation files')


if __name__ == '__main__':
    main()
