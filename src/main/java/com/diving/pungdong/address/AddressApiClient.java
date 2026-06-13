package com.diving.pungdong.address;

import java.util.List;

/**
 * 주소 검색 + 주소→좌표 변환 경계 — 외부 juso(주소기반산업지원서비스) API 와 BE 사이.
 * {@link com.diving.pungdong.consent.SanityTermClient} / {@code IdentityVerifier} 와 동일한
 * "interface + 구현 교체(@ConditionalOnProperty)" 패턴.
 *
 * <ul>
 *   <li>{@link JusoAddressApiClient} — 실 구현(juso 호출). 운영/staging.</li>
 *   <li>{@link StubAddressApiClient} — 로컬 stub(고정값). 좌표제공 API 는 개발용 승인키가 없어
 *       로컬에서 실호출 불가 → 기본값.</li>
 * </ul>
 *
 * <p>FE(웹·앱)는 juso 를 직접 호출하지 않는다 — 승인키 노출/모바일 BFF 부재 때문. 항상 이 도메인의
 * {@code /address-search}·{@code /geocode} 를 거친다(키는 BE 밖으로 안 나감).
 */
public interface AddressApiClient {

    /** 도로명주소 검색 — keyword 로 후보 주소 목록(페이징). juso 오류는 호출부에서 400 으로 변환. */
    SearchResult search(String keyword, int page, int countPerPage);

    /** 선택한 주소(검색 결과의 키 5개)를 WGS84 위경도로 변환. */
    Coordinate geocode(GeocodeKey key);

    /**
     * 검색 결과 1건. 표시용(roadAddr/zipNo 등) + 좌표 변환에 필요한 키(admCd/rnMgtSn/udrtYn/buldMnnm/buldSlno).
     * FE 는 사용자가 고른 항목의 키 5개를 {@code /geocode} 로 그대로 넘긴다.
     */
    record AddressItem(
            String roadAddr, String jibunAddr, String zipNo, String bdNm,
            String siNm, String sggNm, String emdNm,
            String admCd, String rnMgtSn, String udrtYn, String buldMnnm, String buldSlno) {
    }

    /** 검색 응답 — 총건수 + 현재 페이지 + 항목들. */
    record SearchResult(int totalCount, int page, int countPerPage, List<AddressItem> items) {
    }

    /** 좌표 변환 입력 — juso 좌표제공 API 가 요구하는 5개 키. */
    record GeocodeKey(String admCd, String rnMgtSn, String udrtYn, String buldMnnm, String buldSlno) {
    }

    /** WGS84 위경도 (구글맵 등 표준). */
    record Coordinate(double latitude, double longitude) {
    }
}
