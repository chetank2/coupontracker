from pathlib import Path
import sys


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.append(str(REPO_ROOT / "scripts"))

import prove_real_screenshot_regressions as proof


def test_committed_golden_replays_match_expected_json():
    results = [
        proof.prove_golden_manifest(REPO_ROOT / manifest)
        for manifest in proof.DEFAULT_GOLDEN_MANIFESTS
    ]

    assert all(result.passed for result in results), [
        failure
        for result in results
        for failure in result.failures
    ]


def test_real_screenshot_corpus_is_present_and_hashable():
    inventory = proof.inventory_real_corpus(REPO_ROOT / proof.DEFAULT_REAL_CORPUS)

    assert inventory["status"] == "inventory_only"
    assert len(inventory["images"]) >= 1
    assert all(len(item["sha256"]) == 64 for item in inventory["images"])
