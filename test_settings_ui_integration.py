"""Integration checks for Settings UI and LLM preferences.

This script validates that key UI components, secure preference helpers,
and dependency injection bindings required for the Settings screen remain
present. The checks are intentionally string based so that they fail fast
if a refactor accidentally removes or renames critical pieces that the
release process depends on.
"""
from __future__ import annotations

from pathlib import Path
import sys

REPO_ROOT = Path(__file__).parent

SETTINGS_SCREEN = REPO_ROOT / "app/src/main/kotlin/com/example/coupontracker/ui/screen/SettingsScreen.kt"
SECURE_PREFS = REPO_ROOT / "app/src/main/kotlin/com/example/coupontracker/util/SecurePreferencesManager.kt"
LLM_MODULE = REPO_ROOT / "app/src/main/kotlin/com/example/coupontracker/di/LlmModule.kt"


class CheckFailure(RuntimeError):
    """Raised when a required string is missing."""


def assert_contains(path: Path, requirements: dict[str, str]) -> None:
    """Assert that the file contains all required substrings.

    Args:
        path: The file to scan.
        requirements: Mapping of human-readable requirement descriptions to
            substring literals that must appear in the file contents.
    Raises:
        CheckFailure: If a substring is missing.
    """

    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError as exc:  # pragma: no cover - fail fast
        raise CheckFailure(f"Missing required file: {path}") from exc

    missing = [desc for desc, needle in requirements.items() if needle not in text]
    if missing:
        details = "\n".join(f" - {desc}" for desc in missing)
        raise CheckFailure(f"{path} is missing required components:\n{details}")


def main() -> int:
    settings_requirements = {
        "ApiTypeSelector composable is used": "ApiTypeSelector(securePreferencesManager = securePreferencesManager)",
        "LlmStatusCard composable is present": "LlmStatusCard(securePreferencesManager = securePreferencesManager)",
    }

    llm_pref_requirements = {
        "LLM download flag getter": "fun getLlmModelDownloaded()",
        "LLM download flag setter": "fun setLlmModelDownloaded(downloaded: Boolean)",
        "LLM version getter": "fun getLlmModelVersion()",
        "LLM version setter": "fun setLlmModelVersion(version: String)",
        "LLM size getter": "fun getLlmModelSizeMB()",
        "LLM size setter": "fun setLlmModelSizeMB(sizeMB: Float)",
        "LLM checksum getter": "fun getLlmModelChecksum()",
        "LLM checksum setter": "fun setLlmModelChecksum(checksum: String)",
        "LLM auto download getter": "fun getLlmAutoDownloadEnabled()",
        "LLM auto download setter": "fun setLlmAutoDownloadEnabled(enabled: Boolean)",
        "LLM WiFi-only getter": "fun getLlmDownloadWifiOnly()",
        "LLM WiFi-only setter": "fun setLlmDownloadWifiOnly(wifiOnly: Boolean)",
        "LLM base URL override getter": "fun getLlmModelBaseUrlOverride()",
        "LLM base URL override setter": "fun setLlmModelBaseUrlOverride(baseUrl: String?)",
    }

    llm_module_requirements = {
        "Provides LlmRuntimeManager": "fun provideLlmRuntimeManager(",
        "Provides ModelDownloadManager": "fun provideModelDownloadManager(",
        "Provides LlmTelemetryService": "fun provideLlmTelemetryService(",
        "Provides LocalLlmOcrService": "fun provideLocalLlmOcrService(",
        "Provides ImageProcessor": "fun provideImageProcessor(",
        "Provides SecurePreferencesManager": "fun provideSecurePreferencesManager(",
    }

    try:
        assert_contains(SETTINGS_SCREEN, settings_requirements)
        assert_contains(SECURE_PREFS, llm_pref_requirements)
        assert_contains(LLM_MODULE, llm_module_requirements)
    except CheckFailure as exc:
        print(exc, file=sys.stderr)
        return 1

    print("All Settings UI integration checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
