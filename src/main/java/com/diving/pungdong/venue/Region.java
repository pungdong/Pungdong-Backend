package com.diving.pungdong.venue;

import org.springframework.util.StringUtils;

/**
 * 지역 묶음 — 수강생 둘러보기의 지역 필터 칩, 그리고 코스빌더 위치 picker 의 지역 칩과 1:1.
 * 다이빙 가능한 풀이 적어 시/구 단위는 과분할이라, 시·도를 광역으로 묶는다(핸드오프 {@code home-data.jsx}
 * 의 {@code FILTER_REGIONS} 결정 그대로).
 *
 * <p><b>⚠️ 이건 행정구역(17개 시·도)이 아니라 "권역" 묶음이다.</b> 그래서 <b>광역시를 인접 도에 묶는다</b> —
 * 인천 → {@link #SEOUL_GYEONGGI}(수도권), 울산 → {@link #BUSAN_GYEONGNAM}(동남권=부울경). 둘 다 행정구역상
 * 경기도·경상남도와 동급인 별개 광역시지만 권역 기준의 통상 묶음이 그렇다. <b>인천만 묶고 울산은 빼는 식의
 * 절충 금지</b> — 같은 지위를 다르게 처리하는 것이고, 실제로 FE 인터림 파생이 그렇게 어긋났었다.
 *
 * <p><b>표시 라벨은 클라이언트 소유다</b>(이 enum 은 name 으로만 직렬화된다). 주의: "서울·경기"/"부산·경남"
 * 같은 문구는 묶인 광역시를 <b>문자 그대로 배제</b>해 보인다(인천 위치가 "서울·경기" 칩에 뜬다) — 칩 문구는
 * 묶음을 드러내는 쪽("서울·인천·경기" 등)이 맞다. 서버가 라벨을 내보낼 일이 생기면 그때 이 주의부터 볼 것.
 *
 * <p><b>강사에게 지역을 따로 묻지 않는다</b> — 위치 등록 시 받은 도로명주소({@link Venue#getAddress()})의
 * 시·도 토큰에서 파생({@link #fromAddress}). 묶이지 않는 시·도(충청·전라 등)는 {@link #ETC} 로 떨어져
 * 명시 필터엔 안 뜨지만 "전체"(필터 미적용)에는 포함된다 — 매핑 안 된 지역의 코스가 사라지지 않게.
 * ({@code ETC} 는 예외가 아니라 실제로 카탈로그의 약 30% 를 차지한다 — docs/features/course-discovery.md 의 분포표.)
 *
 * <p><b>{@link #fromAddress} 가 파생의 단일 출처다</b> — 둘러보기({@code Course.regions} 스냅샷)와 코스빌더
 * picker({@code VenueResponse.region})가 같은 함수를 쓴다. 클라이언트가 주소에서 따로 파생하면 두 화면의
 * "지역"이 갈라진다.
 *
 * <p>코스 저장 시점에 회차 위치들의 주소에서 풀어 {@code Course.regions} 로 비정규화한다(OFFICIAL 위치
 * 주소는 Sanity 캐시에 있어 쿼리 타임 JOIN 이 불가 — 저장 시점 스냅샷이 단일 해법). <b>그래서 묶음을 추가·변경하면
 * 이미 저장된 코스의 {@code regions} 백필이 따라온다</b> — enum 한 줄로 끝나지 않는다.
 */
public enum Region {
    SEOUL_GYEONGGI,
    GANGWON,
    JEJU,
    BUSAN_GYEONGNAM,
    ETC;

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
