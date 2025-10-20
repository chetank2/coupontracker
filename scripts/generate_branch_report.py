#!/usr/bin/env python3
"""Generate a categorized report of remote Git branches.

The script prints a Markdown table with branch metadata, highlighting whether
branches are merged into `origin/main`, stale (no commits for 90 days), or
active. Run it from the repository root.
"""
from __future__ import annotations

import argparse
import datetime as dt
import subprocess
from dataclasses import dataclass
from typing import Iterable, List, Set


@dataclass
class BranchInfo:
    name: str
    author: str
    commit_date: dt.datetime
    commit_age_days: int
    upstream: str
    subject: str
    status: str


def run_git(args: List[str]) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def try_git(args: List[str]) -> str:
    result = subprocess.run(["git", *args], text=True, capture_output=True)
    if result.returncode != 0:
        return ""
    return result.stdout.strip()


def parse_branch_list(output: str) -> Set[str]:
    branches: Set[str] = set()
    for raw in output.splitlines():
        line = raw.strip()
        if not line:
            continue
        # Skip symbolic refs such as origin/HEAD -> origin/main
        if " -> " in line:
            continue
        branches.add(line)
    return branches


def iter_remote_branches() -> Iterable[BranchInfo]:
    fmt = "%(refname:short)|%(committerdate:iso8601)|%(authorname)|%(upstream:short)|%(contents:subject)"
    output = run_git([
        "for-each-ref",
        "--sort=-committerdate",
        f"--format={fmt}",
        "refs/remotes",
    ])
    now = dt.datetime.now(dt.timezone.utc)
    for line in output.splitlines():
        if not line:
            continue
        name, date_str, author, upstream, subject = line.split("|", maxsplit=4)
        if name.endswith("/HEAD"):
            continue
        commit_date = dt.datetime.fromisoformat(date_str)
        age_days = (now - commit_date).days
        yield BranchInfo(
            name=name,
            author=author or "-",
            commit_date=commit_date,
            commit_age_days=age_days,
            upstream=upstream or "-",
            subject=subject.strip(),
            status="",
        )


def determine_status(branches: Iterable[BranchInfo], merged: Set[str]) -> List[BranchInfo]:
    results: List[BranchInfo] = []
    for branch in branches:
        status = "Active"
        if branch.name in merged:
            status = "Merged"
        elif branch.commit_age_days >= 90:
            status = "Stale"
        results.append(BranchInfo(
            name=branch.name,
            author=branch.author,
            commit_date=branch.commit_date,
            commit_age_days=branch.commit_age_days,
            upstream=branch.upstream,
            subject=branch.subject,
            status=status,
        ))
    return results


def print_report(branches: List[BranchInfo]) -> None:
    print("| Branch | Status | Age (days) | Author | Upstream | Last Commit Message |")
    print("| --- | --- | ---: | --- | --- | --- |")
    for branch in branches:
        print(
            f"| `{branch.name}` | {branch.status} | {branch.commit_age_days} | "
            f"{branch.author} | {branch.upstream} | {branch.subject} |"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate a remote branch report")
    parser.add_argument(
        "--main",
        default="origin/main",
        help="Fully qualified main branch to compare against (default: origin/main)",
    )
    args = parser.parse_args()

    run_git(["fetch", "--prune", "--all"])

    merged_output = try_git(["branch", "--remotes", "--merged", args.main])
    merged_branches = parse_branch_list(merged_output)

    branches = list(iter_remote_branches())
    categorized = determine_status(branches, merged_branches)
    print_report(categorized)


if __name__ == "__main__":
    main()
