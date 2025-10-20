#!/usr/bin/env python3
"""Utility for reporting merged and stale Git branches.

The script inspects remote branches, flags branches that are already merged into
an origin branch, and highlights branches whose last commit is older than a
configurable threshold.  It is intentionally non-destructive so it can be used
from local development environments or in automation such as CI jobs.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import subprocess
from pathlib import Path
from typing import Iterable, List, Sequence, Set


@dataclasses.dataclass
class BranchStatus:
    """Represents metadata about a remote branch."""

    name: str
    last_commit_at: dt.datetime
    merged: bool
    stale: bool
    age_days: int

    @property
    def flags(self) -> List[str]:
        labels: List[str] = []
        if self.merged:
            labels.append("merged")
        if self.stale:
            labels.append("stale")
        return labels


class GitError(RuntimeError):
    """Raised when a git command returns a non-zero exit code."""


def run_git(args: Sequence[str], *, cwd: Path | None = None) -> str:
    """Runs a git command and returns stdout."""

    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise GitError(result.stderr.strip() or result.stdout.strip())
    return result.stdout.strip()


def parse_remote_branches(raw: str) -> Iterable[tuple[str, dt.datetime]]:
    for line in raw.splitlines():
        if not line:
            continue
        name, _, commit_date = line.partition("|")
        # Skip symbolic references such as "origin/HEAD -> origin/main".
        if "->" in name:
            continue
        name = name.strip()
        commit_date = commit_date.strip()
        if not name or not commit_date:
            continue
        try:
            parsed_date = dt.datetime.strptime(commit_date, "%Y-%m-%d %H:%M:%S %z")
        except ValueError:
            # Fall back to ISO parser for unexpected formats.
            parsed_date = dt.datetime.fromisoformat(commit_date)
        yield name, parsed_date


def load_remote_branch_status(
    *,
    base_branch: str,
    protected: Set[str],
    stale_days: int,
) -> List[BranchStatus]:
    raw_refs = run_git(
        [
            "for-each-ref",
            "--format=%(refname:short)|%(committerdate:iso8601)",
            "refs/remotes/",
        ]
    )
    try:
        merged_refs_raw = run_git(["branch", "--remotes", "--merged", base_branch])
    except GitError:
        merged_refs_raw = ""
    merged_refs = {
        ref.strip()
        for ref in merged_refs_raw.splitlines()
        if ref.strip() and "->" not in ref
    }

    now = dt.datetime.now(dt.timezone.utc)
    statuses: List[BranchStatus] = []
    for name, commit_at in parse_remote_branches(raw_refs):
        if name in protected:
            continue
        if name == base_branch:
            continue
        age = int((now - commit_at.astimezone(dt.timezone.utc)).days)
        statuses.append(
            BranchStatus(
                name=name,
                last_commit_at=commit_at,
                merged=name in merged_refs,
                stale=age >= stale_days,
                age_days=age,
            )
        )
    return statuses


def render_markdown(
    statuses: Sequence[BranchStatus],
    *,
    base_branch: str,
    stale_days: int,
) -> str:
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S %Z")
    total = len(statuses)
    merged = [b for b in statuses if b.merged]
    stale = [b for b in statuses if b.stale]
    flagged = [b for b in statuses if b.flags]

    lines = [
        "# Branch Lifecycle Report",
        "",
        f"_Generated {timestamp}_",
        "",
        f"**Base branch:** `{base_branch}`  ",
        f"**Stale threshold:** {stale_days} days",
        "",
        "## Summary",
        "",
        f"* Total remote branches inspected: **{total}**",
        f"* Branches merged into `{base_branch}`: **{len(merged)}**",
        f"* Branches flagged as stale (>{stale_days} days since last commit): **{len(stale)}**",
        f"* Branches requiring attention (merged, stale, or both): **{len(flagged)}**",
        "",
    ]

    if flagged:
        lines.extend([
            "## Branches Requiring Attention",
            "",
            "| Branch | Status | Last Commit (UTC) | Age (days) |",
            "| --- | --- | --- | --- |",
        ])
        for status in sorted(flagged, key=lambda b: (";".join(b.flags), b.age_days), reverse=True):
            last_commit = status.last_commit_at.astimezone(dt.timezone.utc).strftime(
                "%Y-%m-%d %H:%M:%S"
            )
            labels = ", ".join(status.flags) if status.flags else "active"
            lines.append(
                f"| `{status.name}` | {labels or 'active'} | {last_commit} | {status.age_days} |"
            )
    else:
        lines.extend([
            "## Branches Requiring Attention",
            "",
            "No merged or stale branches were found. 🎉",
        ])

    lines.extend([
        "",
        "## Notes",
        "",
        "* Merged branches are candidates for deletion once validated.",
        "* Stale branches should be reviewed with their owners to determine whether they can be archived or closed.",
    ])

    return "\n".join(lines).strip() + "\n"


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base-branch",
        default="origin/main",
        help="Remote branch to compare merges against (default: origin/main)",
    )
    parser.add_argument(
        "--stale-days",
        type=int,
        default=90,
        help="Number of days after which a branch is considered stale (default: 90)",
    )
    parser.add_argument(
        "--protected",
        action="append",
        default=["origin/main", "origin/gh-pages"],
        help="Remote branch to ignore; can be passed multiple times.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="Optional path to write a Markdown report.",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)

    protected = {*(args.protected or []), args.base_branch}
    try:
        remotes_raw = run_git(["remote"])
    except GitError:
        remotes_raw = ""

    remotes = [line.strip() for line in remotes_raw.splitlines() if line.strip()]

    try:
        # Ensure we have up-to-date remote information when remotes exist.
        if remotes:
            run_git(["fetch", "--all", "--prune"])
        else:
            print("::notice::No git remotes configured; skipping fetch step.")
        statuses = load_remote_branch_status(
            base_branch=args.base_branch,
            protected=protected,
            stale_days=args.stale_days,
        )
    except GitError as exc:
        print(f"::error::git command failed: {exc}")
        return 1

    report = render_markdown(
        statuses,
        base_branch=args.base_branch,
        stale_days=args.stale_days,
    )
    print(report)

    if args.output:
        args.output.write_text(report, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
