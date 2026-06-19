# Git, Branches, And Maintenance Knowledge

Use this page for branch inventory, sync state, pull/push questions, and
repository maintenance.

## Current Principle

Confirm branch and remote state before making claims.

Do not rely on memory for whether a commit is pulled, pushed, merged, or present
on a branch.

## Current Docs

- [Branch management policy](../../BRANCH_MANAGEMENT_POLICY.md)
- [Branch inventory](../../branch_inventory.md)
- [Feature branch sync notes](../../feature_qwen_multi_coupon_extraction_sync.md)
- [Git maintenance README](../../git-maintenance/README.md)
- [Git maintenance branch inventory](../../git-maintenance/branch-inventory.md)
- [Protected branches](../../git-maintenance/protected-branches.md)
- [Project knowledge diary](../../PROJECT_KNOWLEDGE_DIARY.md)

## Historical Docs

Use for history only:

- [Branch analysis 2025-10-30](../../archive/branch_analysis_20251030.md)
- [Branch report after cleanup 2025-10-30](../../archive/branch_report_after_cleanup_20251030.md)
- [Latest archived branch report](../../archive/branch_report_latest.md)

## Required Commands For Branch Questions

Check local state:

```bash
git status --short --branch
git branch --show-current
git log --oneline -n 10
```

Check remote state:

```bash
git fetch --all --prune
git branch -a --contains <commit>
git ls-remote --heads origin
```

Check whether a commit is present:

```bash
git branch --contains <commit>
git branch -r --contains <commit>
git log --all --oneline --decorate --grep "<message fragment>"
```

## Commit Rules

- Review `git status --short` before staging.
- Stage only intended files.
- Do not revert user changes.
- Include docs/tests with behavior changes.
- Push only after commit succeeds and branch is confirmed.

## Maintenance Rules

When cleaning branches or docs:

- document what changed,
- preserve historical docs in archive unless the user asks to delete,
- avoid destructive git commands without explicit approval.
