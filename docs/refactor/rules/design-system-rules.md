# Design System Rules

## Problem

Brand styling currently appears through shared components and theme files, but
feature screens can still introduce ad hoc colors, shapes, spacing, and text
styles during refactor work.

## Target Structure

Design tokens live in `ui/theme`. Reusable controls live in `ui/components`.
Feature packages consume those components and define only feature-specific
layout composition.

## Solution

- Use `BrandStyleGuide`, theme typography, colors, and shapes before adding
  screen-local constants.
- Keep `CouponCard`, form fields, buttons, top bars, dialogs, and bottom sheets
  reusable.
- Add new component variants only when at least two screens need the behavior or
  a package needs a stable contract for testing.
- State text comes from UI state or resources, not hardcoded scattered strings.
- Accessibility labels must be owned near the component or screen action.

## Files

Primary files include `ui/theme/BrandStyleGuide.kt`, `ui/theme/Theme.kt`,
`ui/theme/Type.kt`, `ui/theme/Shape.kt`, and `ui/components/*`.

## Tests

Add Compose previews or UI tests for new shared components when they introduce
stateful behavior. Unit test mappers that produce component models.

## Risks

Ad hoc visual constants make future design updates expensive. Over-generalized
components can become harder to use than feature-local composition.

## Definition Of Done

Feature screens use shared tokens and components, new visual constants are
justified, and UI text/state remains testable instead of embedded in business
logic.

