package com.diving.pungdong.global.sitesettings;

/**
 * 사이트 전역 설정 공급자 — 코스 둘러보기(데모 필터)·수강신청(런칭 게이트)이 의존하는 경계.
 * 실 구현은 Sanity 싱글톤을 읽어 캐시한다({@link HttpSanitySiteSettingsProvider}). 테스트는 이 인터페이스를
 * {@code @MockBean} 으로 갈아끼워 런칭 전/후를 시뮬레이션한다.
 */
public interface SiteSettingsProvider {

    /** 현재 설정(캐시됨). 도달 불가 시 마지막 값 또는 {@link SiteSettings#SAFE_DEFAULT}. */
    SiteSettings current();
}
