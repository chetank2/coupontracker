# Historical Archive Knowledge

Use this page to understand what the archive is for and how to read it safely.

## Current Principle

Archived docs are evidence, not instructions.

They explain how the project reached the current state, but many contain old
plans, old branches, old model names, old implementation details, or old testing
commands.

## Archive Entry Points

- [Archive README](../../archive/README.md)
- [Architecture archive](../../archive/architecture/TECHNICAL_ARCHITECTURE_GUIDE.md)
- [Implementation archive](../../archive/implementation/IMPLEMENTATION_TRACKER.md)
- [Fixes archive](../../archive/fixes/CRITICAL_FIXES_APPLIED.md)
- [Testing archive](../../archive/testing/TESTING_INSTRUCTIONS.md)
- [Release archive](../../archive/releases/FINAL_DELIVERY_SUMMARY.md)
- [Session archive](../../archive/sessions/FINAL_STATUS.md)

## Major Archive Categories

### Architecture

Historical system diagrams and extraction architecture plans.

Use to understand old design intent, then verify against current source code.

### Implementation

Historical task trackers and completion reports.

Use to reconstruct decisions, not to assume work is still current.

### Fixes

Historical bug reports and root-cause notes.

Useful for understanding recurring failure classes such as model loading,
native libraries, OCR migration, extraction corruption, and timeout behavior.

### Testing

Historical testing instructions.

Use only after checking current Gradle tasks, current SDK setup, and current
test names.

### Releases

Historical release summaries and delivery notes.

Use store/publishing current docs before using release archive docs.

## Safe Reading Rule

When an archived doc says something is complete, verify with:

```bash
git log --all --oneline --decorate -- <relevant path>
rg -n "<important class or rule>" app/src/main/kotlin docs
```

Then prefer current source code and current knowledge-base docs.

## When To Promote Archive Knowledge

Move or summarize archived knowledge into the current knowledge base only when:

- the behavior still exists,
- the rule is still valid,
- it prevents a repeated mistake,
- it has a clear owner topic.
