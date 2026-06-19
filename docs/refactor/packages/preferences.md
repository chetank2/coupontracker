# Preferences Package

## Problem

Preferences are split between settings repositories, managers, secure managers,
theme managers, extraction strategy config, and model settings. This can create
conflicting defaults and stale values.

## Target Structure

`data/preferences` owns typed preference stores, defaults, migrations, secure
storage wrappers, and preference keys. Repositories expose flows or suspend
methods to domain/UI.

## Solution

Centralize keys and defaults. Group preferences by product area: settings,
theme, extraction strategy, privacy, model management, reminders, and debug.
Expose typed models instead of raw strings/booleans. Keep Android storage
details out of ViewModels.

## Files

Current implementation files include `data/preferences/PreferencesManager.kt`
and `data/preferences/SecurePreferencesManager.kt`. Compatibility aliases remain
at `util/PreferencesManager.kt` and `util/SecurePreferencesManager.kt` while
callers are migrated. Related files include `util/ThemeManager.kt`,
`util/ExtractionStrategy.kt`, `data/repository/SettingsRepository.kt`,
`data/model/Settings.kt`, and model import settings code.

## Tests

Test default values, read/write round trips, key migrations, secure preference
fallbacks, and flows observed by Settings and Scanner ViewModels.

## Risks

Changing keys can reset user settings. Duplicated defaults can make scanner,
workers, and settings screens disagree.

## Definition Of Done

Preference keys/defaults are centralized, callers use typed APIs, and migration
or compatibility behavior is tested.
