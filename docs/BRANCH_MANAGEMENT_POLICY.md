# Branch Management Policy

To reduce the current backlog of 69 branches and keep the repository tidy, the
team will manage branches according to the taxonomy, lifecycle rules, and
operational routine below.

## Branch Taxonomy

| Branch Type         | Purpose                                                       | Naming Format             | Update Rules |
| ------------------- | ------------------------------------------------------------- | ------------------------- | ------------ |
| `main`              | Single source of truth for production-ready code.             | `main`                    | Protected; accept reviewed pull requests only. |
| `release/<slug>`    | Code snapshots used to prepare releases and hotfix builds.    | `release/<version>`       | Protected; cherry-pick or merge from `main`. |
| `hotfix/<slug>`     | Emergency fixes cut from the current release.                 | `hotfix/<issue>`          | Protected; merge back into `main` and `release/*`. |
| `feature/<slug>`    | New feature development work.                                 | `feature/<ticket-or-topic>` | Short lived; delete when merged. |
| `bugfix/<slug>`     | Corrective changes for defects.                               | `bugfix/<ticket-or-topic>` | Short lived; delete when merged. |
| `chore/<slug>`      | Repository maintenance (docs, tooling, cleanup).              | `chore/<description>`     | Short lived; delete when merged. |
| `archive/<date>/<name>` | Frozen copy of a stale branch that must be preserved.      | `archive/<YYYY-MM-DD>/<original-name>` | Read-only reference when work must be retained. |

## Lifecycle & Hygiene

1. **Keep active branches fresh.** Rebase or merge each active topic branch with
   `main` at least twice per week.
2. **Delete merged branches promptly.** Remove the remote branch as soon as its
   pull request merges.
3. **Cull stale branches every 90 days.** If a branch has no activity for 90 days,
   contact the owner. Archive it under `archive/<date>/<name>` and delete the
   working branch if no response is received within a week.
4. **Guard protected branches.** Require reviews, status checks, and fast-forward
   merges on `main`, `release/*`, and `hotfix/*`.

## Weekly Cleanup Routine

Perform these steps each week to keep the branch count under control:

1. **Inventory.** Run `scripts/generate_branch_report.py` to produce an overview
   of all remote branches sorted by most recent activity.
2. **Categorize.** Mark each branch as *Active*, *Stale*, or *Merged* using the
   generated report. Confirm status in the branch inventory document.
3. **Act.**
   - Delete branches marked *Merged* after verifying their pull requests are
     closed.
   - Ping owners of *Stale* branches with a one-week deadline. Archive and
     delete if no owner responds.
   - Ensure *Active* branches follow the naming convention and are rebased on
     `main`.
4. **Review.** Add a short summary of the actions taken to
   `BRANCH_CLEANUP_LOG.md`.

## Communication & Enforcement

- Share this policy during onboarding and link it in contribution guidelines.
- Review the weekly report during the team stand-up.
- Configure repository branch protection to enforce the rules for protected
  branches.

Adhering to this policy will reduce the current backlog and prevent branch
sprawl from recurring.
