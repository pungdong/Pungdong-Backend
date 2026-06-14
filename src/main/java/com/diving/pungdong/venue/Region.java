package com.diving.pungdong.venue;

import org.springframework.util.StringUtils;

/**
 * 둘러보기 지역 묶음 — 수강생 메인 홈의 지역 필터 칩(서울·경기 / 강원 / 제주 / 부산·경남)과 1:1.
 * 다이빙 가능한 풀이 적어 시/구 단위는 과분할이라, 시·도를 광역으로 묶는다(핸드오프 {@code home-data.jsx}
 * 의 {@code FILTER_REGIONS} 결정 그대로).
 *
 * <p><b>강사에게 지역을 따로 묻지 않는다</b> — 위치 등록 시 받은 도로명주소({@link Venue#getAddress()})의
 * 시·도 토큰에서 파생({@link #fromAddress}). 묶이지 않는 시·도(충청·전라 등)는 {@link #ETC} 로 떨어져
 * 명시 필터엔 안 뜨지만 "전체"(필터 미적용)에는 포함된다 — 매핑 안 된 지역의 코스가 사라지지 않게.
 *
 * <p>코스 저장 시점에 회차 위치들의 주소에서 풀어 {@code Course.regions} 로 비정규화한다(OFFICIAL 위치
 * 주소는 Sanity 캐시에 있어 쿼리 타임 JOIN 이 불가 — 저장 시점 스냅샷이 단일 해법).
 */
public enum Region {
    SEOUL_GYEONGGI("서울·경기"),
    GANGWON("강원"),
    JEJU("제주"),
    BUSAN_GYEONGNAM("부산·경남"),
    ETC("그 외");

    private final String displayName;

    Region(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 도로명주소 → 지역 묶음. 첫 공백 토큰(시·도)을 prefix 로 매핑한다. 주소가 비거나 어느 묶음에도
     * 안 맞으면 {@link #ETC}.
     */
    public static Region fromAddress(String address) {
        if (!StringUtils.hasText(address)) {
            return ETC;
        }
        String sido = address.trim().split("\\s+")[0];
        if (startsWithAny(sido, "서울", "경기", "인천")) {
            return SEOUL_GYEONGGI;
        }
        if (sido.startsWith("강원")) {
            return GANGWON;
        }
        if (sido.startsWith("제주")) {
            return JEJU;
        }
        if (startsWithAny(sido, "부산", "울산", "경남", "경상남도")) {
            return BUSAN_GYEONGNAM;
        }
        return ETC;
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
