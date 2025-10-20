# Git Maintenance Guidelines

## Branch Lifecycle

Keeping the repository healthy requires predictable branch hygiene so that
automation and humans can reason about what is safe to delete. The following
expectations apply to all contributors:

### Naming
- Use `feature/<slug>` for feature development, `bugfix/<slug>` for defect
  work, and `chore/<slug>` for maintenance tasks.
- Prefer short, descriptive slugs that relate directly to the task or issue
  identifier (for example `feature/receipt-scanning-ui`).
- Avoid personal names or ambiguous labels such as `wip` or `temp` to make it
  easy for automation to categorize branches.

### Lifespan
- Keep active work branches rebased or merged with `main` at least twice a
  week to minimize divergence.
- Close or merge branches as soon as their related pull request is merged.
- Branches with no commits for more than 90 days are considered stale and
  should be archived or removed after a quick review with the owners.

### Cleanup cadence
- The automated branch lifecycle report runs weekly and flags merged or stale
  branches for follow-up.
- Owners are expected to resolve flagged branches within one week of the
  report by deleting, archiving, or rebasing them as appropriate.
- The release engineering team performs a monthly audit to ensure protected
  branches remain up-to-date and that automation continues to run.
