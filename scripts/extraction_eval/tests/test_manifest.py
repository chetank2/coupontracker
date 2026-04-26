from pathlib import Path
import pytest
from extraction_eval.manifest import load_manifest, Sample

FIX = Path(__file__).parent / "fixtures" / "mini_manifest.json"

def test_load_manifest_returns_samples():
    samples = load_manifest(FIX, root=Path("/tmp/notreal"))
    assert len(samples) == 2
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
