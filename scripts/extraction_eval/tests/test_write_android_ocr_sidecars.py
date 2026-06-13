import json

from extraction_eval.write_android_ocr_sidecars import write_sidecars


def test_write_sidecars_splits_android_report(tmp_path):
    report = tmp_path / "android_ocr_sidecars.json"
    out_dir = tmp_path / "ocr"
    report.write_text(json.dumps({
        "schemaVersion": 1,
        "sidecars": [
            {
                "id": "sample_1",
                "ocr": {
                    "text": "Kapiva\nCode: KAP",
                    "tiles": [
                        {
                            "text": "Kapiva",
                            "left": 1,
                            "top": 2,
                            "right": 3,
                            "bottom": 4,
                            "confidence": 0.9,
                        }
                    ],
                },
            }
        ],
    }))

    written = write_sidecars(report=report, out_dir=out_dir)

    assert written == [out_dir / "sample_1.json"]
    sidecar = json.loads(written[0].read_text())
    assert sidecar["_source"] == "android-mlkit-tesseract"
    assert sidecar["text"] == "Kapiva\nCode: KAP"
    assert sidecar["tiles"][0]["text"] == "Kapiva"
