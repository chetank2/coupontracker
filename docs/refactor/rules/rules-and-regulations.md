# Rules And Regulations

## Problem

Agents need a shared operating contract so concurrent work does not overwrite
local changes, break schema compatibility, or change coupon extraction behavior
while moving packages.

## Target Structure

Rules live as docs and are enforced through small implementation slices:
`domain/usecase` for business behavior, `data` for persistence, `extraction` for
OCR-first field extraction, `ai` for model cleanup, `ui` for rendering and
navigation, and `worker` for background orchestration.

## Solution

- Do not revert or reformat unrelated files.
- Do not move Room entities or DAOs without checking table names, indices,
  migrations, converters, and schema expectations.
- Do not move legacy fragments without updating `nav_graph.xml`, Safe Args, and
  route imports in the same slice.
- Keep ViewModels thin. New decisions belong in use cases or pipeline classes.
- Preserve crop-first extraction. If a coupon-card crop exists, field extraction
  must receive the crop, not the original multi-card screenshot.
- Keep capture OCR-first. AI cleanup is explicit or background verification.

## Files

Apply these rules to all implementation files, especially `app/src/main/kotlin`,
navigation resources, Room schema-adjacent classes, Hilt modules, and tests.

## Tests

Run the required Gradle checks after each package move. For schema-adjacent work,
add or update Room migration/schema tests before trusting manual inspection.

## Risks

Concurrent agent edits can silently collide. File moves can look mechanical while
changing constructor injection, serialization names, navigation args, or WorkManager
entry points.

## Definition Of Done

The diff only contains the intended slice, required checks pass, no unrelated
work was reverted, and any temporary adapter or deprecated import has a clear
follow-up path.

