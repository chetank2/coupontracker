# Protected Branches

The following long-lived branches are designated as protected and should only be updated via approved pull requests:

| Branch Pattern | Purpose | Update Policy |
| -------------- | ------- | ------------- |
| `main` | Primary integration branch that always reflects the latest production-ready code. | Only merge commits created through approved pull requests. Rebase, fast-forward, and direct pushes are prohibited. |
| `release/*` | Release stabilization branches used to prepare tagged releases. | Only release managers may merge approved changes related to the upcoming release. Delete old release branches after the release has shipped. |
| `hotfix/*` | Emergency fixes that must be applied to production quickly. | Hotfix branches are created from the latest production tag, reviewed, and merged back into both `main` and the relevant `release/*` branch as soon as they pass review. |

## Required Reviews and Status Checks

* Every pull request targeting a protected branch must receive at least one approving review from a code owner or designated reviewer.
* All required status checks must pass before the pull request can be merged.
* Squash or merge commits are allowed, but merge commits must be created via the pull request UI to preserve review history.

## Merge Restrictions

* Force pushes to protected branches are disabled.
* Branch deletions are restricted to repository administrators.
* Signed commits are recommended for merges into protected branches.

## Contributor Workflow Expectations

1. Create feature branches from `main` when starting work.
2. Open a pull request targeting `main` (or the appropriate `release/*` or `hotfix/*` branch) once the work is ready for review.
3. Ensure that automated checks pass before requesting a review.
4. Obtain the required approvals and merge via the pull request interface.

For questions about branch policies, reach out to the release management team in the `#dev-infra` channel.
