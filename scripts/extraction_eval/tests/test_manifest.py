from pathlib import Path
import pytest
from extraction_eval.manifest import load_manifest, Sample

FIX = Path(__file__).parent / "fixtures" / "mini_manifest.json"

def test_load_manifest_returns_samples():
    samples = load_manifest(FIX, root=Path("/tmp/notreal"))
    assert len(samples) == 4
    assert samples[0].id == "sample_a"
    assert samples[0].image_sha256 == "aaaa"
    assert samples[0].expected["storeName"] == "Acme"

def test_load_manifest_resolves_image_path():
    samples = load_manifest(FIX, root=Path("/tmp/notreal"))
    assert samples[0].image_path == Path("/tmp/notreal/images/sample_a.png")

def test_load_manifest_rejects_unknown_schema_version(tmp_path):
    bad = tmp_path / "bad.json"
    bad.write_text('{"schemaVersion": 99, "samples": []}')
    with pytest.raises(ValueError, match="schemaVersion"):
        load_manifest(bad, root=tmp_path)

def test_load_manifest_index_by_image_sha(tmp_path):
    samples = load_manifest(FIX, root=Path("/tmp/notreal"))
    by_sha = {s.image_sha256: s for s in samples}
    assert "aaaa" in by_sha
    assert by_sha["bbbb"].id == "sample_b"

def test_load_manifest_handles_samples_without_expected_block():
    samples = load_manifest(FIX, root=Path("/tmp/notreal"))
    by_id = {s.id: s for s in samples}
    assert "pending_a" in by_id
    assert by_id["pending_a"].expected is None
    assert by_id["pending_a"].is_pending is True
    # Existing samples are unaffected
    assert samples[0].id == "sample_a"
    assert samples[0].expected is not None
    assert samples[0].is_pending is False

def test_load_ocr_returns_sidecar_contents():
    """Sample with an OCR sidecar returns the parsed sidecar JSON."""
    samples = load_manifest(FIX, root=Path("/tmp/notreal"))
    by_id = {s.id: s for s in samples}
    sample = by_id["sample_ocr"]
    assert sample.ocr_path is not None, "sample_ocr should have a sidecar"
    ocr = sample.load_ocr()
    assert "Kapiva" in ocr["text"]
    assert len(ocr["tiles"]) == 3
    assert ocr["tiles"][0]["text"] == "Kapiva"

def test_load_ocr_returns_empty_when_no_sidecar():
    """Sample without an OCR sidecar returns the empty payload."""
    samples = load_manifest(FIX, root=Path("/tmp/notreal"))
    by_id = {s.id: s for s in samples}
    sample = by_id["sample_a"]
    assert sample.ocr_path is None, "sample_a should not have a sidecar"
    ocr = sample.load_ocr()
    assert ocr == {"text": "", "tiles": []}

def test_load_manifest_honors_explicit_ocr_root(tmp_path):
    """Passing ocr_root= switches the sidecar lookup directory.

    Lets callers swap between manual / Mac-Tesseract / Android-real
    sidecar sources without copying files.
    """
    custom_ocr = tmp_path / "custom_ocr"
    custom_ocr.mkdir()
    (custom_ocr / "sample_a.json").write_text('{"text": "from-custom-root", "tiles": []}')

    samples = load_manifest(FIX, root=Path("/tmp/notreal"), ocr_root=custom_ocr)
    by_id = {s.id: s for s in samples}

    # sample_a now resolves a sidecar from the custom root
    assert by_id["sample_a"].ocr_path == custom_ocr / "sample_a.json"
    assert by_id["sample_a"].load_ocr()["text"] == "from-custom-root"

    # sample_ocr (which has a sidecar in the default fixtures/ocr/ root) no
    # longer resolves one — the custom root has nothing for it.
    assert by_id["sample_ocr"].ocr_path is None
    assert by_id["sample_ocr"].load_ocr() == {"text": "", "tiles": []}
