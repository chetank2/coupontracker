from pathlib import Path
import json

from extraction_eval.compare_android import compare


def _write(path: Path, samples: list[dict]) -> None:
    path.write_text(json.dumps({"llmBackend": "llama.cpp", "samples": samples}))


def test_compare_flags_field_disagreement(tmp_path: Path) -> None:
    mac = tmp_path / "latest.json"
    android = tmp_path / "android.json"
    _write(mac, [{"id": "one", "parsed": {"code": "SAVE10"}, "latency_ms": 10}])
    _write(android, [{"id": "one", "parsed": {"code": "SAVE20"}, "latency_ms": 30}])

    report = compare(mac=mac, android=android)

    assert report.passed is False
    assert report.disagreements[0].field == "code"
    assert report.latency["one"] == {"mac_ms": 10, "android_ms": 30}


def test_compare_normalizes_strings_and_reports_missing_samples(tmp_path: Path) -> None:
    mac = tmp_path / "latest.json"
    android = tmp_path / "android.json"
    _write(
        mac,
        [
            {"id": "one", "parsed": {"storeName": " Zomato "}},
            {"id": "mac-only", "parsed": {}},
        ],
    )
    _write(
        android,
        [
            {"id": "one", "parsed": {"storeName": "zomato"}},
            {"id": "android-only", "parsed": {}},
        ],
    )

    report = compare(mac=mac, android=android)

    assert report.disagreements == []
    assert report.missing_on_android == ["mac-only"]
    assert report.missing_on_mac == ["android-only"]
