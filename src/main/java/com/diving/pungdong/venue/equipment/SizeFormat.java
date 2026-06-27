package com.diving.pungdong.venue.equipment;

import java.util.List;

/**
 * 대여 장비의 사이즈 표기 방식 — 수강생이 신청 시 고를 사이즈의 형식(chat39 "형식 자동 추론").
 * 강사는 항목 이름만 적고 형식을 고르면 프리셋이 자동으로 채워지며, 직접 수정(override)도 가능하다.
 *
 * <p>자유입력(CUSTOM)은 두지 않는다 — 다이빙 장비 사이즈는 표준화돼 있어 폐쇄 프리셋으로 충분하고,
 * 자유입력 표면을 없애 보안·데이터품질을 지킨다(프리셋에 없는 사이즈가 필요하면 프리셋 상수 확장+배포).
 *
 * <ul>
 *   <li>{@code NONE} — 사이즈 없음 (예: 마스크·스노클·웨이트). 옵션 비움.</li>
 *   <li>{@code SHOE_MM} — 신발 mm (예: 롱핀·부츠). 프리셋 230~300.</li>
 *   <li>{@code APPAREL_SXL} — 의류 S~XL (예: 슈트). 프리셋 XS~XXL.</li>
 * </ul>
 */
public enum SizeFormat {
    NONE(List.of()),
    SHOE_MM(List.of("230", "240", "250", "260", "270", "280", "290", "300")),
    APPAREL_SXL(List.of("XS", "S", "M", "L", "XL", "XXL"));

    private final List<String> presetOptions;

    SizeFormat(List<String> presetOptions) {
        this.presetOptions = presetOptions;
    }

    /** 강사가 옵션을 직접 주지 않았을 때 채울 기본 사이즈 옵션(NONE 은 빈 목록). */
    public List<String> presetOptions() {
        return presetOptions;
    }
}
