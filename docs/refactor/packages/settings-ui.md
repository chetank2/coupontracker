# Settings UI Package

## Problem

Settings state and persistence are coupled through ViewModels and data models.
The refactor needs a UI package that renders preferences without owning storage
details.

## Target Structure

`ui/settings` owns `SettingsScreen`, `SettingsViewModel`, settings UI state, and
events. Data-backed preference storage belongs in `data/preferences` and
`data/repository`.

## Solution

Expose typed settings state from a repository or use case. UI events update
settings through a single boundary. Extraction strategy, privacy, reminders,
theme, and debug toggles should be grouped by product area.

## Files

Current files include `ui/settings/SettingsScreen.kt`,
`ui/fragment/SettingsFragment.kt`, `ui/settings/SettingsViewModel.kt`,
`data/model/Settings.kt`, `data/repository/SettingsRepository.kt`, and
`data/preferences/PreferencesManager.kt`.

## Tests

Test ViewModel state loading, update events, default values, persistence
failures, and strategy toggles.

## Risks

Duplicated settings storage can create mismatched values between scanner,
workers, and UI.

## Definition Of Done

Settings UI is package-owned, settings persistence is data-owned, and all
settings changes flow through typed update methods.
