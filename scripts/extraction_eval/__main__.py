"""argparse entrypoint: python -m extraction_eval"""
from __future__ import annotations
import argparse
import json
from pathlib import Path

from extraction_eval.runner import run_eval

def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="extraction_eval")
    p.add_argument("--manifest", default="Coupons /manifest.json")
    p.add_argument("--manifest-root", default="Coupons ")
    p.add_argument("--eval-root", default="build/extraction-eval")
    p.add_argument("--jar", required=True)
    p.add_argument("--runtime-config", default="config/extraction/runtime.json")
    p.add_argument("--promote-baseline", action="store_true",
                   help="After this run, copy latest.json to baseline.json. Manual gate; never auto-set in CI.")
    args = p.parse_args(argv)

    cfg = json.loads(Path(args.runtime_config).read_text())
    out = run_eval(
        manifest_path=Path(args.manifest),
        manifest_root=Path(args.manifest_root),
        eval_root=Path(args.eval_root),
        jar=args.jar,
        runtime_config_path=Path(args.runtime_config),
        binary=cfg["macBinary"],
        gguf=cfg["qwenGgufPath"],
        mmproj=cfg["mmprojPath"],
        grammar_path=cfg.get("grammarPath"),
    )
    print(f"Run written to {out}")
    if args.promote_baseline:
        from extraction_eval.baseline import promote_baseline
        promote_baseline(latest=Path(args.eval_root) / "latest.json",
                         baseline=Path(args.eval_root) / "baseline.json")
        print(f"Baseline promoted from latest.json")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
