"""Tests for scripts.git.cleanup_branches."""

from __future__ import annotations

import datetime as dt
import sys
from pathlib import Path
from unittest import mock

import pytest

# Ensure the repository root is on sys.path so we can import the script module.
sys.path.append(str(Path(__file__).resolve().parents[2]))

from scripts.git import cleanup_branches


def test_parse_remote_branches_skips_symbolic_refs_and_parses_dates():
    raw = (
        "origin/feature/keep|2025-10-18 12:00:00 +0000\n"
        "origin/feature/iso|2025-10-18T10:15:30+00:00\n"
        "origin/HEAD -> origin/main|2025-10-18 09:00:00 +0000\n"
    )

    parsed = list(cleanup_branches.parse_remote_branches(raw))

    assert [name for name, _ in parsed] == [
        "origin/feature/keep",
        "origin/feature/iso",
    ]
    assert all(entry.tzinfo is not None for _, entry in parsed)


@pytest.fixture()
def fixed_datetime():
    """Provide a deterministic datetime class for load_remote_branch_status."""

    real_datetime = cleanup_branches.dt.datetime

    class FixedDatetime(real_datetime):
        @classmethod
        def now(cls, tz=None):
            tz = tz or dt.timezone.utc
            return real_datetime(2025, 10, 20, tzinfo=tz)

        @classmethod
        def strptime(cls, date_string, format_):
            return real_datetime.strptime(date_string, format_)

        @classmethod
        def fromisoformat(cls, date_string):
            return real_datetime.fromisoformat(date_string)

    with mock.patch("scripts.git.cleanup_branches.dt.datetime", FixedDatetime):
        yield


def test_load_remote_branch_status_marks_merged_and_stale(fixed_datetime):
    remote_listing = (
        "origin/feature/active|2025-10-18 08:00:00 +0000\n"
        "origin/feature/merged|2025-08-01 12:00:00 +0000\n"
        "origin/feature/stale|2025-09-01T10:00:00+00:00\n"
        "origin/keep|2025-10-18 07:00:00 +0000\n"
        "origin/main|2025-10-18 06:00:00 +0000\n"
    )

    merged_listing = "origin/feature/merged\n"

    def fake_run_git(args, *, cwd=None):
        if args and args[0] == "for-each-ref":
            return remote_listing
        if args[:3] == ["branch", "--remotes", "--merged"]:
            return merged_listing
        raise AssertionError(f"Unexpected git invocation: {args}")

    with mock.patch("scripts.git.cleanup_branches.run_git", side_effect=fake_run_git):
        statuses = cleanup_branches.load_remote_branch_status(
            base_branch="origin/main",
            protected={"origin/main", "origin/keep"},
            stale_days=30,
        )

    status_by_name = {status.name: status for status in statuses}

    assert "origin/main" not in status_by_name
    assert status_by_name["origin/feature/active"].flags == []
    assert status_by_name["origin/feature/merged"].flags == ["merged", "stale"]
    assert status_by_name["origin/feature/stale"].flags == ["stale"]


def test_render_markdown_reports_flagged_branches():
    statuses = [
        cleanup_branches.BranchStatus(
            name="origin/feature/merged",
            last_commit_at=dt.datetime(2025, 9, 1, 12, 0, tzinfo=dt.timezone.utc),
            merged=True,
            stale=True,
            age_days=49,
        ),
        cleanup_branches.BranchStatus(
            name="origin/feature/active",
            last_commit_at=dt.datetime(2025, 10, 18, 9, 0, tzinfo=dt.timezone.utc),
            merged=False,
            stale=False,
            age_days=2,
        ),
    ]

    report = cleanup_branches.render_markdown(statuses, base_branch="origin/main", stale_days=30)

    assert "Total remote branches inspected: **2**" in report
    assert "Branches Requiring Attention" in report
    assert "`origin/feature/merged`" in report
    assert "merged, stale" in report


def test_render_markdown_handles_no_flagged_branches():
    statuses = [
        cleanup_branches.BranchStatus(
            name="origin/feature/new",
            last_commit_at=dt.datetime(2025, 10, 19, 11, 0, tzinfo=dt.timezone.utc),
            merged=False,
            stale=False,
            age_days=1,
        )
    ]

    report = cleanup_branches.render_markdown(statuses, base_branch="origin/main", stale_days=30)

    assert "No merged or stale branches were found" in report
