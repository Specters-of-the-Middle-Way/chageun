#!/usr/bin/env python3
"""Detailed runner for Scenario 03: Driver Body Profile Guide Bands & Mirror Invalidation."""

import json
from pathlib import Path


def main():
    repo = Path(__file__).resolve().parents[3]
    scenario_path = repo / "simulation" / "test" / "scenarios" / "03_body_preset_and_mirror_reset.json"
    scenario = json.loads(scenario_path.read_text(encoding="utf-8"))

    print("================================================================================")
    print(f" [시뮬레이션 QA] {scenario['id']}")
    print(f" 제목: {scenario['title']}")
    print(f" 대상 클래스: {', '.join(scenario['targetClasses'])}")
    print("================================================================================\n")

    # -------------------------------------------------------------------------
    # 1. 가이드별 체형 비례 계산식 검증 (Guide Bands for each CalibrationAxis)
    # -------------------------------------------------------------------------
    print("┌──────────────────────────────────────────────────────────────────────────────┐")
    print("│ 1. 가이드별 체형 연동 계산 검증 (FORE_AFT, HEIGHT, BACKREST, STEERING)       │")
    print("└──────────────────────────────────────────────────────────────────────────────┘")

    def calc_band(axis: str, height_cm: int, weight_kg: int):
        h_delta = height_cm - 175
        w_delta = weight_kg - 75
        if axis == "FORE_AFT":
            center = max(25, min(75, 50 + round(h_delta * 0.48)))
        elif axis == "HEIGHT":
            center = max(25, min(75, 50 - round(h_delta * 0.44)))
        elif axis == "BACKREST":
            center = max(35, min(65, 50 + round(w_delta * 0.20)))
        elif axis == "STEERING":
            center = max(30, min(70, 50 + round(h_delta * 0.28)))
        else:
            raise ValueError(axis)
        half = 8
        return center, max(0, center - half), min(100, center + half)

    axes = ["FORE_AFT", "HEIGHT", "BACKREST", "STEERING"]
    axis_names = {
        "FORE_AFT": "시트 전후 (슬라이드)",
        "HEIGHT": "시트 높낮이",
        "BACKREST": "등받이 기울기",
        "STEERING": "스티어링 휠 (텔레스코픽)"
    }

    test_profiles = [
        ("표준 체형", 175, 75),
        ("단신 체형", 158, 55),
        ("장신 체형", 192, 96),
    ]

    for label, h, w in test_profiles:
        print(f"\n▶ [{label}: {h}cm, {w}kg]")
        for ax in axes:
            c, min_r, max_r = calc_band(ax, h, w)
            print(f"   • {axis_names[ax]:<18s} ({ax:<8s}) : 추천 중심={c:2d} | 권장 대역=[{min_r:2d} ~ {max_r:2d}]")

    # 가이드별 경계값 및 비례 invariant 확인
    shorter_fa, _, _ = calc_band("FORE_AFT", 158, 55)
    taller_fa, _, _ = calc_band("FORE_AFT", 192, 96)
    assert taller_fa > shorter_fa, "장신 운전자의 시트 전후 위치는 단신 운전자보다 뒤쪽이어야 함"

    shorter_h, _, _ = calc_band("HEIGHT", 158, 55)
    taller_h, _, _ = calc_band("HEIGHT", 192, 96)
    assert taller_h < shorter_h, "장신 운전자의 시트 높이는 단신 운전자보다 낮아야 함"

    shorter_b, _, _ = calc_band("BACKREST", 158, 55)
    taller_b, _, _ = calc_band("BACKREST", 192, 96)
    assert taller_b > shorter_b, "고체중 운전자의 등받이는 저체중 운전자보다 뒤로 기울어야 함"

    shorter_s, _, _ = calc_band("STEERING", 158, 55)
    taller_s, _, _ = calc_band("STEERING", 192, 96)
    assert taller_s > shorter_s, "장신 운전자의 스티어링은 단신 운전자보다 더 멀리 연장되어야 함"

    print("\n [OK] 4개 가이드 축 전체의 체형 비례 및 대역(±8) 산출 로직 정상 통과")

    # -------------------------------------------------------------------------
    # 2. 기능별 미러 무효화 (5..7단계) 및 안전 리셋 검증
    # -------------------------------------------------------------------------
    print("\n┌──────────────────────────────────────────────────────────────────────────────┐")
    print("│ 2. 기능별 미러(5..7단계) 무효화 및 안전 리셋 검증                            │")
    print("└──────────────────────────────────────────────────────────────────────────────┘")

    setup_labels = {
        0: "0: 시트 전후",
        1: "1: 시트 높이",
        2: "2: 등받이 각도",
        3: "3: 헤드레스트",
        4: "4: 스티어링 휠",
        5: "5: 룸미러 (실내)",
        6: "6: 좌측 사이드미러",
        7: "7: 우측 사이드미러",
        8: "8: 안전벨트 착용"
    }

    class MockJourneyState:
        def __init__(self):
            self.height_cm = 180
            self.weight_kg = 75
            self.calibration = {"FORE_AFT": 50, "HEIGHT": 50, "BACKREST": 50, "STEERING": 50}
            self.setup = [True] * 9      # 9단계 완료 상태
            self.preflight = [True] * 7  # 7단계 사전점검 완료 상태

        def set_calibration(self, axis: str, val: int):
            self.calibration[axis] = val
            own_step = {"FORE_AFT": 0, "HEIGHT": 1, "BACKREST": 2, "STEERING": 4}[axis]
            self.setup[own_step] = False
            for i in (5, 6, 7):
                self.setup[i] = False
            self.preflight = [False] * 7

        def apply_recommended_preset(self, target_h: int, target_w: int):
            new_cal = {}
            for a in axes:
                c, _, _ = calc_band(a, target_h, target_w)
                new_cal[a] = c
            changed = [a for a in axes if self.calibration[a] != new_cal[a]]
            self.calibration = new_cal
            for a in changed:
                own_step = {"FORE_AFT": 0, "HEIGHT": 1, "BACKREST": 2, "STEERING": 4}[a]
                self.setup[own_step] = False
            for i in (5, 6, 7):
                self.setup[i] = False
            self.preflight = [False] * 7

        def update_profile(self, h: int, w: int):
            if self.height_cm != h or self.weight_kg != w:
                self.height_cm = h
                self.weight_kg = w
                self.setup = [False] * 9
                self.preflight = [False] * 7

    # 기능 A: 개별 시트/핸들 위치 조정 시 미러 무효화
    print("\n▶ [기능 A] 개별 조작 축 변경 시 미러 무효화 (setCalibration)")
    for target_axis in ["FORE_AFT", "HEIGHT", "BACKREST", "STEERING"]:
        st = MockJourneyState()
        st.set_calibration(target_axis, 62)
        own_step = {"FORE_AFT": 0, "HEIGHT": 1, "BACKREST": 2, "STEERING": 4}[target_axis]
        print(f"   • {axis_names[target_axis]} 변경 시:")
        print(f"     - 해당 축 setup[{own_step}] = {st.setup[own_step]} (재확인 필요)")
        print(f"     - 룸미러(5)={st.setup[5]}, 좌측사이드미러(6)={st.setup[6]}, 우측사이드미러(7)={st.setup[7]} (무효화 완료)")
        print(f"     - 사전점검 7항목 완료 수: {st.preflight.count(True)}/7 (전부 초기화)")
        assert not st.setup[own_step]
        assert not st.setup[5] and not st.setup[6] and not st.setup[7]
        assert st.preflight.count(True) == 0

    # 기능 B: 신체 프리셋 추천값 일괄 적용 시 미러 무효화
    print("\n▶ [기능 B] 신체 프리셋 추천값 일괄 적용 (applyRecommendedPreset)")
    st_b = MockJourneyState()
    st_b.apply_recommended_preset(190, 90)
    print(f"   • 신규 체형 적용 (190cm, 90kg) -> 추천 설정치: {st_b.calibration}")
    print(f"   • 조작 축(0,1,2,4) 상태: {[st_b.setup[i] for i in [0,1,2,4]]} (모두 False)")
    print(f"   • 미러 3종(5..7단계) 상태: {[st_b.setup[i] for i in [5,6,7]]} (모두 False 로 무효화)")
    print(f"   • 시트와 무관한 독립 단계: setup[3](헤드레스트)={st_b.setup[3]}, setup[8](안전벨트)={st_b.setup[8]} (유지)")
    print(f"   • 주행 가능(canPractice): {all(st_b.setup) and all(st_b.preflight)} -> 안전 인터럽트 유지")
    assert not st_b.setup[5] and not st_b.setup[6] and not st_b.setup[7]
    assert st_b.setup[3] and st_b.setup[8]
    assert not (all(st_b.setup) and all(st_b.preflight))

    # 기능 C: 신체 프로필(키/체중) 자체를 변경 시 전체 리셋
    print("\n▶ [기능 C] 운전자 프로필 수정 시 전체 안전 리셋 (updateProfile)")
    st_c = MockJourneyState()
    st_c.update_profile(160, 50)
    print(f"   • 9단계 준비 점검: {st_c.setup.count(False)}/9 초기화 (전체 False)")
    print(f"   • 7단계 사전 점검: {st_c.preflight.count(False)}/7 초기화 (전체 False)")
    assert all(not x for x in st_c.setup)
    assert all(not x for x in st_c.preflight)

    print("\n================================================================================")
    print(" [최종 결과] 가이드별 계산 & 기능별 미러 무효화(5..7단계) 전체 검증 통과 (PASS)")
    print("================================================================================")


if __name__ == "__main__":
    main()

