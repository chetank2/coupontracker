#!/usr/bin/env python3
"""
Analyze branch status and identify candidates for deletion.
This script checks:
1. Which branches have been merged via PR
2. Which branches are behind main by many commits
3. Which branches haven't been updated in a while
"""

import subprocess
import json
import sys
from datetime import datetime, timezone
from typing import List, Dict, Set

def run_git(args: List[str]) -> str:
    """Run a git command and return output."""
    result = subprocess.run(
        ["git"] + args,
        capture_output=True,
        text=True,
        check=False
    )
    return result.stdout.strip()

def get_all_remote_branches() -> List[str]:
    """Get all remote branches except HEAD."""
    output = run_git(["branch", "-r"])
    branches = []
    for line in output.splitlines():
        branch = line.strip()
        if "HEAD" not in branch and branch:
            branches.append(branch)
    return branches

def get_branch_info(branch: str) -> Dict:
    """Get detailed info about a branch."""
    # Get last commit date
    date_str = run_git([
        "log", "-1", "--format=%ci", branch
    ])
    
    # Get last commit message
    message = run_git([
        "log", "-1", "--format=%s", branch
    ])
    
    # Get commits ahead/behind main
    ahead_behind = run_git([
        "rev-list", "--left-right", "--count",
        f"origin/main...{branch}"
    ])
    
    behind, ahead = 0, 0
    if ahead_behind:
        parts = ahead_behind.split()
        if len(parts) == 2:
            behind, ahead = int(parts[0]), int(parts[1])
    
    # Calculate age
    age_days = 0
    if date_str:
        try:
            commit_date = datetime.fromisoformat(date_str.replace(' +', '+').replace(' -', '-'))
            now = datetime.now(timezone.utc)
            age_days = (now - commit_date).days
        except:
            pass
    
    return {
        "name": branch,
        "last_commit_date": date_str,
        "last_commit_message": message,
        "age_days": age_days,
        "commits_ahead": ahead,
        "commits_behind": behind
    }

def check_if_pr_merged(branch_name: str) -> bool:
    """Check if branch name appears in recent merge commits to main."""
    # Get recent merge commits
    merges = run_git([
        "log", "origin/main", "--merges", "--oneline", "-100"
    ])
    
    # Extract branch name from origin/codex/branch-name
    simple_name = branch_name.replace("origin/", "").replace("codex/", "")
    
    # Check if branch name appears in merge commits
    return simple_name in merges.lower()

def categorize_branches(branches: List[Dict]) -> Dict[str, List[Dict]]:
    """Categorize branches into different groups."""
    categories = {
        "merged_via_pr": [],
        "very_old": [],  # 30+ days old
        "far_behind": [],  # 50+ commits behind main
        "no_unique_commits": [],  # 0 commits ahead
        "active": []  # Recent activity
    }
    
    for branch in branches:
        name = branch["name"]
        
        # Skip main and feature branches
        if "main" in name or "feature/qwen" in name:
            continue
        
        # Check if merged via PR
        if check_if_pr_merged(name):
            categories["merged_via_pr"].append(branch)
            continue
        
        # Check age
        if branch["age_days"] >= 30:
            categories["very_old"].append(branch)
        
        # Check if far behind
        if branch["commits_behind"] >= 50:
            categories["far_behind"].append(branch)
        
        # Check if no unique commits
        if branch["commits_ahead"] == 0:
            categories["no_unique_commits"].append(branch)
        
        # Active branches
        if branch["age_days"] < 14 and branch["commits_ahead"] > 0:
            categories["active"].append(branch)
    
    return categories

def print_category(title: str, branches: List[Dict], color: str = ""):
    """Print a category of branches."""
    if not branches:
        return
    
    colors = {
        "red": "\033[0;31m",
        "green": "\033[0;32m",
        "yellow": "\033[1;33m",
        "blue": "\033[0;34m",
        "nc": "\033[0m"
    }
    
    c = colors.get(color, "")
    nc = colors["nc"] if color else ""
    
    print(f"\n{c}{'='*80}{nc}")
    print(f"{c}{title} ({len(branches)} branches){nc}")
    print(f"{c}{'='*80}{nc}\n")
    
    for branch in sorted(branches, key=lambda x: x["age_days"], reverse=True):
        print(f"Branch: {branch['name']}")
        print(f"  Age: {branch['age_days']} days")
        print(f"  Ahead/Behind: +{branch['commits_ahead']}/-{branch['commits_behind']}")
        print(f"  Last commit: {branch['last_commit_message'][:80]}")
        print()

def main():
    print("🔍 Analyzing branch status...\n")
    
    # Fetch latest
    print("📥 Fetching latest from remote...")
    run_git(["fetch", "--prune", "--all"])
    
    # Get all branches
    print("📋 Getting branch information...")
    branch_names = get_all_remote_branches()
    branches = [get_branch_info(b) for b in branch_names]
    
    # Categorize
    print("🏷️  Categorizing branches...\n")
    categories = categorize_branches(branches)
    
    # Print results
    print_category(
        "🗑️  SAFE TO DELETE: Merged via Pull Request",
        categories["merged_via_pr"],
        "green"
    )
    
    print_category(
        "⚠️  CANDIDATES FOR DELETION: No Unique Commits",
        categories["no_unique_commits"],
        "yellow"
    )
    
    print_category(
        "⚠️  CANDIDATES FOR DELETION: Very Old (30+ days)",
        categories["very_old"],
        "yellow"
    )
    
    print_category(
        "⚠️  CANDIDATES FOR DELETION: Far Behind Main (50+ commits)",
        categories["far_behind"],
        "yellow"
    )
    
    print_category(
        "✅ ACTIVE: Recent Activity",
        categories["active"],
        "blue"
    )
    
    # Summary
    print("\n" + "="*80)
    print("📊 SUMMARY")
    print("="*80)
    print(f"Total branches analyzed: {len(branches)}")
    print(f"Merged via PR (safe to delete): {len(categories['merged_via_pr'])}")
    print(f"No unique commits: {len(categories['no_unique_commits'])}")
    print(f"Very old (30+ days): {len(categories['very_old'])}")
    print(f"Far behind main: {len(categories['far_behind'])}")
    print(f"Active branches: {len(categories['active'])}")
    
    # Calculate potential cleanup
    potential_cleanup = (
        len(categories['merged_via_pr']) +
        len(categories['no_unique_commits'])
    )
    print(f"\n🎯 Potential branches to clean up: {potential_cleanup}")
    
    # Save to file
    output_file = "branch_analysis_detailed.json"
    with open(output_file, "w") as f:
        json.dump({
            "analysis_date": datetime.now().isoformat(),
            "total_branches": len(branches),
            "categories": {
                k: [b["name"] for b in v]
                for k, v in categories.items()
            }
        }, f, indent=2)
    
    print(f"\n💾 Detailed analysis saved to: {output_file}")

if __name__ == "__main__":
    main()

