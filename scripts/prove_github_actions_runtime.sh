#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/prove_github_actions_runtime.sh [--workflow NAME_OR_FILE] [--dispatch]

Checks whether GitHub Actions runtime proof is available through gh.
By default this is read-only: it validates gh, auth, workflow visibility, and
the most recent run status. Pass --dispatch to manually trigger the workflow.
USAGE
}

workflow="CI Guardrails"
dispatch=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workflow)
      workflow="${2:?--workflow requires a value}"
      shift 2
      ;;
    --dispatch)
      dispatch=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if ! command -v gh >/dev/null 2>&1; then
  echo "BLOCKED: gh is not available on PATH." >&2
  exit 127
fi

if ! gh auth status >/tmp/coupontracker-gh-auth-status.txt 2>&1; then
  echo "BLOCKED: gh auth status failed." >&2
  cat /tmp/coupontracker-gh-auth-status.txt >&2
  exit 1
fi

echo "gh auth is available."
echo "Checking workflow: ${workflow}"
gh workflow view "$workflow"

if [[ "$dispatch" == true ]]; then
  echo "Dispatching workflow: ${workflow}"
  gh workflow run "$workflow"
fi

echo "Most recent runs for ${workflow}:"
gh run list --workflow "$workflow" --limit 5
