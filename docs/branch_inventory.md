# Branch Inventory Review (October 20, 2025)

- **Reference time:** 2025-10-20 15:32:10 UTC
- **Stale threshold:** ≥ 14 days without new commits (tagged as `Stale`)
- **Inventory source:** `python3 scripts/generate_branch_report.py` (run after pruning merged remotes)

## Branch Status Tracking

| Branch | Age (days) | Tag | Last Commit Message |
| --- | ---: | --- | --- |
| `origin/feature/qwen-multi-coupon-extraction` | 0 | Active | Merge pull request #167 from chetank2/codex/merge-tests-into-feature/qwen-multi-coupon-extraction |
| `origin/codex/merge-tests-into-feature/qwen-multi-coupon-extraction` | 0 | Active | Restore test model binaries to textual-friendly versions |
| `origin/main` | 0 | Merged | Merge pull request #166 from chetank2/codex/fix-twostagedetector-and-database-issues-kncls1 |
| `origin/codex/organize-git-branches` | 0 | Active | Establish branch management policy and reporting tooling |
| `origin/codex/fix-twostagedetector-and-database-issues-u5aocq` | 0 | Active | Merge branch 'feature/qwen-multi-coupon-extraction' into codex/fix-twostagedetector-and-database-issues-u5aocq |
| `origin/codex/fix-twostagedetector-and-database-issues-zlrypj` | 0 | Active | Document feature branch sync with model guard changes |
| `origin/codex/investigate-data-extraction-issues` | 0 | Active | Enforce integrity checks for bundled detector models |
| `origin/codex/create-plan-to-clean-git-repository-branches` | 0 | Active | Add tests for branch cleanup reporting |
| `origin/codex/enhance-coupon-validation-and-extraction-process` | 0 | Active | Merge pull request #141 from chetank2/codex/fix-high-priority-bug-in-store-validation |
| `origin/codex/filter-and-manage-old-branches` | 0 | Active | Add branch inventory review |
| `origin/codex/add-automatic-branch-cleanup-script` | 0 | Active | Add automated branch lifecycle reporting |
| `origin/codex/clean-up-merged-local-and-remote-branches` | 0 | Active | Document branch cleanup |
| `origin/codex/document-and-protect-long-lived-branches` | 0 | Active | Document and configure protected branches |
| `origin/codex/create-branch-inventory-document` | 0 | Active | Add branch inventory summary |
| `origin/codex/identify-issues-in-coupon-tracker-implementations` | 1 | Active | Enable advanced extraction runtime and harden LLM retries |
| `origin/codex/fix-extraction-issue-after-changes` | 1 | Active | Adjust multi-coupon flow fallback |
| `origin/codex/fix-score-card-placeholder-data-and-layout` | 1 | Active | Make quality score card collapsible below actions |
| `origin/codex/implement-ai-guardrails-and-monitoring` | 1 | Active | Merge pull request #150 from chetank2/codex/fix-high-priority-bug-in-storenamemetricstracker-r6dl4z |
| `origin/codex/fix-high-priority-bug-in-storenamemetricstracker-r6dl4z` | 1 | Active | Fix double sampling of store-name provenance records |
| `origin/codex/investigate-incomplete-checks-status` | 1 | Active | Skip CI pipelines for documentation-only changes |
| `origin/codex/replace-currentstate-with-state-in-scannerscreen.kt` | 1 | Active | Fix remaining state references in ScannerScreen |
| `origin/codex/refactor-visibility-and-state-handling-in-kotlin` | 1 | Active | Refine store resolution API and tighten tests |
| `origin/codex/fix-kotlin-compiler-errors-in-analytics-and-validation` | 1 | Active | Fix visibility issues and stabilize scanner state casting |
| `origin/codex/capture-ocr-text-and-validation-outcomes` | 1 | Active | Add validator feedback capture and offline retraining worker |
| `origin/codex/redesign-universalextractionservice-and-improve-pipeline` | 1 | Active | Refine universal extraction pipeline with adaptive blending |
| `origin/codex/update-extractionconfig-to-honor-selections` | 1 | Active | Honor advanced extraction strategies with instrumentation |
| `origin/codex/update-production-timeout-and-warmup` | 1 | Active | Improve LLM warmup progress and fallback handling |
| `origin/codex/fix-high-priority-bug-in-store-validation` | 1 | Active | Adjust store attention threshold |
| `origin/codex/add-comprehensive-test-suite-for-storenamevalidator` | 1 | Active | Merge pull request #139 from chetank2/codex/fix-ci-workflow-for-android-sdk-installation |
| `origin/codex/fix-high-priority-bug-in-storenamemetricstracker` | 2 | Active | Avoid double sampling provenance evidence |
| `origin/codex/fix-ci-workflow-for-android-sdk-installation` | 2 | Active | Configure Android SDK for validator CI |
| `origin/codex/create-ai-guardrails-documentation-and-update-readme` | 2 | Active | Add coupon extraction guardrail policy and checklist |
| `origin/codex/llm-store-fallback` | 2 | Active | Fix local LLM store fallback and stabilize migrations |
| `origin/codex/explain-extraction-issue-in-plain-english` | 3 | Active | Allow LLM model detection without .verified sentinel |
| `origin/codex/adjust-calculateuniversalcodescore-logic` | 3 | Active | Improve fusion scoring for OCR-prefixed codes |
| `origin/codex/fix-incorrect-coupon-code-and-description` | 3 | Active | Wire quality telemetry into coupon detail scoring |
| `origin/codex/fix-incomplete-description-fetch-issue-nexgag` | 3 | Active | Merge branch 'feature/qwen-multi-coupon-extraction' into codex/fix-incomplete-description-fetch-issue-nexgag |
| `origin/codex/fix-incomplete-description-fetch-issue` | 3 | Active | Strengthen description quality heuristics |
| `origin/codex/make-it-available` | 3 | Active | Harden fallback availability checks |
| `origin/codex/add-score-card-to-screen` | 3 | Active | Add extraction quality score card to coupon detail |
| `origin/codex/locate-missing-score-in-ui` | 3 | Active | Expose extraction score diagnostics on detail screen |
| `origin/codex/evaluate-production-readiness-of-implementation` | 3 | Active | Add scorer unit tests for extraction debug snapshots |
| `origin/codex/update-testing-tasks-for-android` | 3 | Active | Replace boolean returns in model verification tests |
| `origin/codex/evaluate-training-needs-for-extraction-performance` | 4 | Active | Add debug extraction diagnostics overlay |
| `origin/codex/find-discrepancies-in-coupon-extraction-hghfvu` | 4 | Active | Introduce field extraction framework skeleton |
| `origin/codex/find-discrepancies-in-coupon-extraction` | 4 | Active | Improve coupon field heuristics |
| `origin/codex/develop-plan-for-coupon-data-extraction-f2o0gl` | 4 | Active | Merge branch 'feature/qwen-multi-coupon-extraction' into codex/develop-plan-for-coupon-data-extraction-f2o0gl |
| `origin/codex/develop-plan-for-coupon-data-extraction-3hzp04` | 4 | Active | Merge branch 'feature/qwen-multi-coupon-extraction' into codex/develop-plan-for-coupon-data-extraction-3hzp04 |
| `origin/codex/develop-plan-for-coupon-data-extraction` | 4 | Active | Add field validation pipeline for LLM coupon extraction |
| `origin/codex/fix-incorrect-coupon-description-issue` | 4 | Active | Filter dashboard text from coupon description |
| `origin/codex/fix-missing-expiry-date-visibility` | 4 | Active | Fix expiry parsing for day-first dates with time |
| `origin/codex/review-smartcoupon-extraction-plan-msk79l` | 4 | Active | Merge branch 'feature/qwen-multi-coupon-extraction' into codex/review-smartcoupon-extraction-plan-msk79l |
| `origin/codex/review-smartcoupon-extraction-plan` | 5 | Active | Implement deterministic multi-coupon extraction scaffolding |
| `origin/codex/remove-all-offertext-references-8ulkp3` | 5 | Active | docs: review offerText plan compliance |
| `origin/codex/remove-all-offertext-references-x67m2r` | 5 | Active | Merge branch 'feature/qwen-multi-coupon-extraction' into codex/remove-all-offertext-references-x67m2r |
| `origin/codex/remove-all-offertext-references-1kca9g` | 5 | Active | Merge branch 'feature/qwen-multi-coupon-extraction' into codex/remove-all-offertext-references-1kca9g |
| `origin/codex/remove-all-offertext-references-sr9sa7` | 5 | Active | Merge branch 'feature/qwen-multi-coupon-extraction' into codex/remove-all-offertext-references-sr9sa7 |
| `origin/codex/remove-all-offertext-references` | 5 | Active | refactor: remove offerText and tighten extraction contract |
| `origin/codex/fix-incomplete-coupon-description-retrieval` | 5 | Active | Fix multi-line offer parsing for 'upto' connectors |
| `origin/codex/diagnose-coupon-app-fetching-issues` | 6 | Active | Detect and block stub LLM responses |
| `origin/codex/investigate-coupon-processing-logs` | 6 | Active | Prevent duplicate coupon form image processing on initial load |
| `origin/codex/find-reason-for-incomplete-description-extraction` | 7 | Active | Fix multi-line description extraction |
| `origin/codex/analyze-discrepancy-report-for-model-retraining` | 7 | Active | Document universal scope of extraction heuristics |
| `origin/codex/fix-analytics-reporting-for-coupon-uploads` | 7 | Active | Add analytics tracking to scanner workflows |
| `origin/codex/fix-incorrect-description` | 7 | Active | Improve coupon description cleaning |
| `origin/codex/fix-expiry-date-extraction-issue` | 7 | Active | Fix expiry date parsing for comma formatted dates |
| `origin/codex/update-readme-file-in-git` | 7 | Active | Highlight Qwen2 model in README |
| `origin/codex/check-bulk-upload-of-screenshots-implementation` | 7 | Active | Implement Android bulk coupon upload flow |
| `origin/codex/remove-one-search-feature-and-align-header-left` | 7 | Active | Adjust home screen top bar and filters |
| `origin/codex/fix-backup-and-restore-import-issue` | 7 | Active | Fix secure backup restore flow |
| `origin/codex/fix-light-mode-and-import-feature-issues` | 7 | Active | Fix light theme palette and relax model import storage check |
| `origin/codex/fix-missing-download-button-on-home-screen` | 7 | Active | Merge pull request #96 from chetank2/codex/fix-missing-imports-in-homescreen |
| `origin/codex/fix-missing-imports-in-homescreen` | 7 | Active | Restore missing HomeScreen imports |
| `origin/gh-pages` | 26 | Stale | docs: add comprehensive README for web training app |

## Status Totals

- Active: 72 branches (ensure owners rebase against `main` at least twice per week and confirm naming aligns with policy)
- Stale: 1 branch (`origin/gh-pages`; follow up with owner and archive if no response within 7 days)
- Merged: 1 branch (`origin/main`; primary branch, retain as protected)

## Follow-up Notes

- Confirmed `origin/codex/fix-twostagedetector-and-database-issues` and `origin/codex/fix-twostagedetector-and-database-issues-kncls1` are fully merged into `main` and removed both remotes.
- Notified @chetank2 on Slack (2025-10-20 15:35 UTC) about `origin/gh-pages` inactivity; archive on 2025-10-27 if no response.
- Rerun `python3 scripts/generate_branch_report.py` on 2025-10-27 before archiving to capture the final state.
- Use the standardized naming taxonomy for any new active branch and rebase all active lines with `main` twice weekly to avoid drift.
