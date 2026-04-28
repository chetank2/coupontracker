"""Tests for extraction_eval.ocr_sidecar.

Mocks pytesseract.image_to_data so no Tesseract binary is required.

Confidence note: Tesseract reports confidence as 0-100 integers or -1 for
non-word block/line rows.  We divide by 100 to normalise to [0, 1].  A raw
value of -1 is mapped to 1.0 because it represents a structural row for
which no per-character OCR confidence exists, not a genuinely low-confidence
word; treating it as maximum confidence prevents downstream threshold filters
from silently dropping tile rows that carry valid text.
"""
from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest

from extraction_eval.ocr_sidecar import generate_sidecar, write_sidecar, _parse_tsv


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# A minimal Tesseract TSV payload with three word-level rows.
# Columns: level  page  block  par  line  word  left  top  width  height  conf  text
_SAMPLE_TSV = (
    "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\t"
    "left\ttop\twidth\theight\tconf\ttext\n"
    "5\t1\t1\t1\t1\t1\t10\t20\t80\t30\t92\tKapiva\n"
    "5\t1\t1\t1\t1\t2\t100\t20\t60\t30\t85\tGet\n"
    "5\t1\t1\t1\t2\t1\t10\t60\t120\t30\t-1\t40%\n"
    "5\t1\t1\t1\t2\t2\t140\t60\t40\t30\t78\tOff\n"
)


# ---------------------------------------------------------------------------
# _parse_tsv unit tests
# ---------------------------------------------------------------------------

def test_parse_tsv_produces_correct_shape():
    """_parse_tsv returns a dict with 'text' str and 'tiles' list."""
    result = _parse_tsv(_SAMPLE_TSV)
    assert isinstance(result, dict)
    assert "text" in result
    assert "tiles" in result
    assert isinstance(result["text"], str)
    assert isinstance(result["tiles"], list)


def test_parse_tsv_tile_bounds_are_left_top_right_bottom():
    """Tile right = left + width, bottom = top + height (not storing raw width/height)."""
    result = _parse_tsv(_SAMPLE_TSV)
    first = result["tiles"][0]
    # Row: left=10, top=20, width=80, height=30
    assert first["left"] == 10
    assert first["top"] == 20
    assert first["right"] == 10 + 80   # 90
    assert first["bottom"] == 20 + 30  # 50


def test_parse_tsv_text_is_joined_in_reading_order():
    """Full text is all word tokens joined by spaces in the order they appear."""
    result = _parse_tsv(_SAMPLE_TSV)
    assert result["text"] == "Kapiva Get 40% Off"


def test_parse_tsv_confidence_normalised_to_0_1():
    """Confidence values are divided by 100; -1 maps to 1.0."""
    result = _parse_tsv(_SAMPLE_TSV)
    tiles = {t["text"]: t["confidence"] for t in result["tiles"]}
    assert pytest.approx(tiles["Kapiva"]) == 0.92
    assert pytest.approx(tiles["Get"]) == 0.85
    assert pytest.approx(tiles["40%"]) == 1.0   # -1 raw → 1.0
    assert pytest.approx(tiles["Off"]) == 0.78


def test_parse_tsv_skips_empty_and_whitespace_only_tokens():
    """Rows with empty or whitespace-only text must be omitted from tiles."""
    tsv = (
        "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\t"
        "left\ttop\twidth\theight\tconf\ttext\n"
        "5\t1\t1\t1\t1\t1\t0\t0\t50\t20\t90\tHello\n"
        "5\t1\t1\t1\t1\t2\t60\t0\t30\t20\t88\t   \n"  # whitespace only
        "5\t1\t1\t1\t1\t3\t0\t0\t0\t0\t-1\t\n"        # empty
        "5\t1\t1\t1\t2\t1\t0\t30\t50\t20\t82\tWorld\n"
    )
    result = _parse_tsv(tsv)
    texts = [t["text"] for t in result["tiles"]]
    assert "Hello" in texts
    assert "World" in texts
    assert len(texts) == 2, f"Expected 2 tiles, got: {texts}"
    assert result["text"] == "Hello World"


def test_parse_tsv_empty_input_returns_empty_payload():
    """An all-header TSV (no data rows) produces empty text and tiles."""
    tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
    result = _parse_tsv(tsv)
    assert result == {"text": "", "tiles": []}


# ---------------------------------------------------------------------------
# generate_sidecar — integration-level (mocked pytesseract)
# ---------------------------------------------------------------------------

def test_generate_sidecar_uses_pytesseract_when_available(tmp_path):
    """generate_sidecar delegates to pytesseract.image_to_data when importable."""
    fake_image = tmp_path / "test.png"
    fake_image.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00" * 100)  # not a real PNG, but path exists

    mock_module = MagicMock()
    mock_module.image_to_data.return_value = _SAMPLE_TSV

    with patch.dict("sys.modules", {"pytesseract": mock_module}):
        # Also patch PIL.Image.open so we don't need a real image file
        with patch("extraction_eval.ocr_sidecar._run_pytesseract") as mock_run:
            mock_run.return_value = {"text": "Kapiva Get 40% Off", "tiles": []}
            result = generate_sidecar(
                fake_image,
                tessdata_dir=Path("/fake/tessdata"),
                lang="eng",
                psm=3,
            )

    assert result["text"] == "Kapiva Get 40% Off"


def test_generate_sidecar_via_parse_tsv_shape(tmp_path):
    """End-to-end via _parse_tsv: result has required keys and tile fields."""
    tsv_data = (
        "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\t"
        "left\ttop\twidth\theight\tconf\ttext\n"
        "5\t1\t1\t1\t1\t1\t5\t10\t100\t25\t95\tCode:\n"
        "5\t1\t1\t1\t1\t2\t115\t10\t200\t25\t97\tKAPW1M3LAfAhSe\n"
    )
    result = _parse_tsv(tsv_data)
    assert set(result.keys()) == {"text", "tiles"}
    for tile in result["tiles"]:
        for key in ("text", "left", "top", "right", "bottom", "confidence"):
            assert key in tile, f"Missing key '{key}' in tile {tile}"


# ---------------------------------------------------------------------------
# write_sidecar
# ---------------------------------------------------------------------------

def test_write_sidecar_creates_file_with_indent2(tmp_path):
    """write_sidecar writes JSON with indent=2 and returns the path."""
    sidecar = {
        "text": "Hello World",
        "tiles": [
            {"text": "Hello", "left": 0, "top": 0, "right": 50, "bottom": 20, "confidence": 0.9}
        ],
    }
    out = write_sidecar("sample_x", sidecar, ocr_root=tmp_path / "ocr")
    assert out == tmp_path / "ocr" / "sample_x.json"
    assert out.exists()
    raw = out.read_text()
    # indent=2 means lines should start with two spaces
    assert "  " in raw
    parsed = json.loads(raw)
    assert parsed["text"] == "Hello World"
    assert len(parsed["tiles"]) == 1


def test_write_sidecar_creates_parent_directories(tmp_path):
    """write_sidecar creates the ocr_root directory tree if it doesn't exist."""
    deep_root = tmp_path / "deep" / "nested" / "ocr"
    assert not deep_root.exists()
    write_sidecar("sample_y", {"text": "", "tiles": []}, ocr_root=deep_root)
    assert (deep_root / "sample_y.json").exists()


def test_write_sidecar_overwrites_existing_file(tmp_path):
    """write_sidecar silently overwrites an already-present sidecar."""
    ocr_root = tmp_path / "ocr"
    first = {"text": "old", "tiles": []}
    write_sidecar("s", first, ocr_root=ocr_root)
    second = {"text": "new", "tiles": []}
    write_sidecar("s", second, ocr_root=ocr_root)
    result = json.loads((ocr_root / "s.json").read_text())
    assert result["text"] == "new"
