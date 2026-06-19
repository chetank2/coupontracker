# Design Rules

## Problem

The UI is split across fragments, Compose screens, shared components, and legacy
ViewModels. Refactoring without design rules can create inconsistent screens and
duplicate one-off components.

## Target Structure

Each UI package owns its screen, state model, event surface, and ViewModel:
`ui/home`, `ui/details`, `ui/review`, `ui/settings`, `ui/modelsettings`, and
`ui/scanner`. Shared visual primitives stay in `ui/components` and brand tokens
stay in `ui/theme`.

## Solution

- Screen packages should render state and emit events only.
- ViewModels expose stable UI state and call use cases for business work.
- Shared components must stay generic and coupon-domain aware only when the
  component represents a reusable coupon concept.
- Fragments should act as navigation and host adapters during migration.
- Error, loading, empty, permission, and low-confidence states must be explicit.
- Do not encode extraction rules in button handlers or composable text.

## Files

Current UI files are in `ui/screen`, `ui/viewmodel`, `ui/fragment`,
`ui/details`, `ui/components`, `ui/model`, `ui/navigation`, and `ui/theme`.

## Tests

Use ViewModel unit tests for state transitions and use-case calls. Use Compose UI
tests or screenshot checks for complex review/scanner states when feasible.

## Risks

Duplicated screens can drift. Moving fragments can break XML navigation. Moving
ViewModels without route and Hilt updates can compile locally but fail runtime
injection or navigation.

## Definition Of Done

The UI package has one state owner, one event contract, no business logic beyond
presentation decisions, and all navigation and Hilt references point at the
package owner.

