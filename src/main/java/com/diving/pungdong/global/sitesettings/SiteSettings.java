package com.diving.pungdong.global.sitesettings;

/**
 * 사이트 전역 설정 — Sanity {@code siteSettings} 싱글톤을 읽어 담는 값. 런칭 상태/데모 노출을 무배포로
 * 토글하는 단일 스위치(상세 {@code sanity/schemas/siteSettings.ts}).
 *
 * @param launched           정식 런칭 여부. false 면 전 코스 신청 차단(BE 강제) + FE 런칭대기 배너.
 * @param showSeededCourses  데모(seeded) 코스 공개 노출 여부. false 면 둘러보기에서 제외.
 */
public record SiteSettings(boolean launched, boolean showSeededCourses) {

    /**
     * Sanity 도달 불가 + 캐시 없음일 때의 보수적 기본값 — <b>미런칭(신청 차단) + 데모 노출</b>.
     * 사고 시 "실수로 신청이 열리는" 위험을 피하는 방향(런칭은 명시적 publish 로만 켜짐).
     */
    public static final SiteSettings SAFE_DEFAULT = new SiteSettings(false, true);
}
