#!/usr/bin/env python3
"""Offline QA verifier for Chageun (/simulation/test/scenarios + codebase invariants)."""

from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    repo = Path(__file__).resolve().parents[3]
    scenarios_dir = repo / "simulation" / "test" / "scenarios"
    expected_scenarios = [
        "01_parked_gate_and_d_gear.json",
        "02_timeout_2tier_policy.json",
        "03_body_preset_and_mirror_reset.json",
        "04_parking_reverse_and_parallel.json",
        "05_review_no_auto_penalty.json",
    ]
    for name in expected_scenarios:
        path = scenarios_dir / name
        if not path.is_file():
            raise AssertionError(f"Missing scenario file: {path}")
        payload = json.loads(path.read_text(encoding="utf-8"))
        assert "id" in payload and "title" in payload, f"Invalid schema in {name}"
    print(f"PASS: Verified {len(expected_scenarios)} scenario JSON files.")

    # Check 2-Tier timeout policy
    timeout_kt = (
        repo / "app/src/main/java/kr/drivestep/tutor/vehicle/VehicleTimeoutPolicy.kt"
    ).read_text(encoding="utf-8")
    assert "PARKED_CONNECT_TIMEOUT_MS = 10_000L" in timeout_kt
    assert "ACTIVE_READ_TIMEOUT_MS = 2_500L" in timeout_kt
    assert "DRIVING_SITUATION_FRESHNESS_MS = 1_500L" in timeout_kt
    print("PASS: Verified 2-Tier VehicleTimeoutPolicy constants.")

    # Check body profile guide band & PostureGuideView
    journey_kt = (
        repo / "app/src/main/java/kr/drivestep/tutor/journey/JourneyState.kt"
    ).read_text(encoding="utf-8")
    posture_view_kt = (
        repo / "app/src/main/java/kr/drivestep/tutor/journey/PostureGuideView.kt"
    ).read_text(encoding="utf-8")
    assert "fun guideBand(" in journey_kt and "fun applyRecommendedPreset(" in journey_kt
    assert "class PostureGuideView" in posture_view_kt
    print("PASS: Verified DriverProfile guideBand, applyRecommendedPreset, and PostureGuideView.")

    # Check 3-way voice resource synchronization
    raw_dir = repo / "app/src/main/res/raw"
    raw_names = {f"@raw/{p.stem}" for p in raw_dir.glob("coach_*.wav")}
    xml_path = repo / "app/src/main/res/values/coach_voice_resources.xml"
    tree = ET.parse(xml_path)
    xml_items = {elem.text.strip() for elem in tree.findall(".//item") if elem.text}
    assert raw_names == xml_items, (
        f"Voice resource mismatch: only in raw={raw_names - xml_items}, "
        f"only in xml={xml_items - raw_names}"
    )
    print(f"PASS: Verified {len(raw_names)} bundled voice WAV resources match coach_voice_resources.xml.")

    # Check terminology compliance (no '백미러' in main sources)
    main_java = repo / "app/src/main/java"
    offenders = [
        str(p.relative_to(repo))
        for p in main_java.rglob("*.kt")
        if "백미러" in p.read_text(encoding="utf-8")
    ]
    assert not offenders, f"Prohibited term '백미러' found in: {offenders}"
    print("PASS: Verified Korean terminology compliance ('룸미러' used consistently).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
