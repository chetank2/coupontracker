#!/usr/bin/env python3
"""Select uncertain samples based on model predictions."""

from __future__ import annotations

import argparse
import json
import math
import pathlib
from typing import Dict, List


def load_predictions(path: pathlib.Path) -> List[Dict]:
    data = json.loads(path.read_text())
    if isinstance(data, dict):
        data = data.get("predictions", [])
    return data


def entropy(probabilities: List[float]) -> float:
    return -sum(p * math.log(p + 1e-10, 2) for p in probabilities)


def main() -> None:
    parser = argparse.ArgumentParser(description="Rank samples by uncertainty")
    parser.add_argument("predictions", help="JSON file with model predictions + confidences")
    parser.add_argument("--top-k", type=int, default=20)
    args = parser.parse_args()

    preds = load_predictions(pathlib.Path(args.predictions))
    ranked = []
    for item in preds:
        probs = item.get("probabilities") or []
        if not probs:
            continue
        score = entropy(probs)
        ranked.append((score, item))

    ranked.sort(key=lambda tup: tup[0], reverse=True)
    for score, item in ranked[: args.top_k]:
        print(json.dumps({"score": score, **item}, ensure_ascii=False))


if __name__ == "__main__":
    main()
