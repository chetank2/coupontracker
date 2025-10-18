#!/usr/bin/env python3
"""Guardrail checks for AI-related diffs.

This script is intended to run in pre-commit and CI contexts. It inspects the
staged diff to detect changes that could weaken safety guardrails around store
name validation, CTA stop-word filtering, or provenance tracking. If a
potentially dangerous change is detected the script prints a descriptive error
message and exits with a non-zero status so the change cannot be merged by
mistake.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List, Optional, Sequence, Set

REPO_ROOT = Path(__file__).resolve().parent.parent
CTA_FILE = Path(
    "app/src/main/kotlin/com/example/coupontracker/extraction/validation/FieldValidators.kt"
)
FIELD_CANDIDATE_PATTERN = re.compile(r"FieldCandidate\s*\(([^)]*)\)", re.DOTALL)


class GuardrailViolation(RuntimeError):
    """Custom exception used for early exits."""


@dataclass
class DiffFile:
    path: Path
    diff_text: str


_DIFF_HEADER_RE = re.compile(r"^diff --git a/(.*?) b/(.*?)$", re.MULTILINE)
def run_git_command(args: Sequence[str]) -> str:
    result = subprocess.run(args, cwd=REPO_ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(
            f"Failed to run {' '.join(args)}: {result.stderr.strip() or result.stdout.strip()}"
        )
    return result.stdout


def load_staged_diff() -> List[DiffFile]:
    diff_output = run_git_command(["git", "diff", "--cached", "--no-color"])
    if not diff_output.strip():
        return []

    files: List[DiffFile] = []
    for match in _DIFF_HEADER_RE.finditer(diff_output):
        start = match.start()
        end = diff_output.find("diff --git", start + 1)
        if end == -1:
            end = len(diff_output)
        block = diff_output[start:end]
        path = Path(match.group(2))
        files.append(DiffFile(path=path, diff_text=block))
    return files


def parse_cta_stopwords(text: str) -> Set[str]:
    match = re.search(r"DEFAULT_CTA_STOPWORDS\s*=\s*setOf\((.*?)\)", text, re.DOTALL)
    if not match:
        return set()
    body = match.group(1)
    entries = []
    for line in body.splitlines():
        line = line.strip().strip(",")
        if not line:
            continue
        entry = line.strip().strip("\"")
        if entry:
            entries.append(entry.lower())
    return set(entries)


def check_cta_stopwords() -> None:
    if not CTA_FILE.exists():
        return

    status_output = run_git_command(["git", "diff", "--cached", "--name-only", "--", str(CTA_FILE)])
    if not status_output.strip():
        return

    try:
        old_text = run_git_command(["git", "show", f"HEAD:{CTA_FILE.as_posix()}"])
    except RuntimeError:
        old_text = ""
    try:
        new_text = run_git_command(["git", "show", f":{CTA_FILE.as_posix()}"])
    except RuntimeError as exc:  # pragma: no cover - staged deletion or rename
        raise GuardrailViolation(
            "AI guardrail violation: unable to read staged version of FieldValidators.kt"
        ) from exc

    old_words = parse_cta_stopwords(old_text)
    new_words = parse_cta_stopwords(new_text)

    if old_words and not new_words:
        raise GuardrailViolation(
            "AI guardrail violation: CTA stop-word set missing in staged changes."
        )

    if len(new_words) < len(old_words):
        removed = ", ".join(sorted(old_words - new_words)) or "(unknown)"
        raise GuardrailViolation(
            "AI guardrail violation: CTA stop-word list shrank. Removed entries: " + removed
        )


def check_critical_invariants(diff_files: Sequence[DiffFile]) -> None:
    for diff in diff_files:
        for line in diff.diff_text.splitlines():
            if line.startswith("-") and "@critical-invariant" in line:
                raise GuardrailViolation(
                    "AI guardrail violation: modifications removed @critical-invariant commentary."
                )


def check_validator_relaxations(diff_files: Sequence[DiffFile]) -> None:
    risky_tokens = (
        "issues +=",
        "needsAttention",
        "FieldValidationSeverity.ERROR",
        "highConfidenceCount",
        "tier <=",
    )
    for diff in diff_files:
        if "FieldValidators.kt" not in diff.path.as_posix() and "StoreNameResolver.kt" not in diff.path.as_posix():
            continue
        for line in diff.diff_text.splitlines():
            if not line.startswith("-"):
                continue
            if any(token in line for token in risky_tokens):
                raise GuardrailViolation(
                    "AI guardrail violation: detected potential validator relaxation involving critical checks."
                )


def check_field_candidate_provenance(diff_files: Sequence[DiffFile]) -> None:
    changed_files = [df.path for df in diff_files if df.path.suffix == ".kt"]
    if not changed_files:
        return

    for path in changed_files:
        try:
            new_text = run_git_command(["git", "show", f":{path.as_posix()}"])
        except RuntimeError:
            continue
        for match in FIELD_CANDIDATE_PATTERN.finditer(new_text):
            args = match.group(1)
            if "=" in args:
                required = {"value", "confidence", "source", "context"}
                present = {part.split("=")[0].strip() for part in args.split(",") if "=" in part}
                if not required.issubset(present):
                    raise GuardrailViolation(
                        f"AI guardrail violation: FieldCandidate missing provenance fields in {path}."
                    )
            else:
                parts = [part.strip() for part in args.split(",") if part.strip()]
                if len(parts) < 4:
                    raise GuardrailViolation(
                        f"AI guardrail violation: FieldCandidate constructor missing arguments in {path}."
                    )


def check_cta_false_positive_changes(diff_files: Sequence[DiffFile]) -> None:
    for diff in diff_files:
        if diff.path != CTA_FILE:
            continue
        for line in diff.diff_text.splitlines():
            if line.startswith("-") and "cta_stopword" in line:
                raise GuardrailViolation(
                    "AI guardrail violation: removal of CTA stop-word handling detected."
                )


def run_checks() -> None:
    diff_files = load_staged_diff()
    if not diff_files:
        return

    check_critical_invariants(diff_files)
    check_validator_relaxations(diff_files)
    check_cta_false_positive_changes(diff_files)
    check_cta_stopwords()
    check_field_candidate_provenance(diff_files)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Validate AI guardrail invariants")
    parser.add_argument(
        "--allow-empty",
        action="store_true",
        help="Allow execution with no staged changes (useful for CI safety).",
    )
    args = parser.parse_args(argv)

    try:
        run_checks()
    except GuardrailViolation as err:
        print(str(err), file=sys.stderr)
        return 1
    except Exception as exc:  # pragma: no cover - defensive
        print(f"AI guardrail check failed: {exc}", file=sys.stderr)
        return 2

    if not args.allow_empty:
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
