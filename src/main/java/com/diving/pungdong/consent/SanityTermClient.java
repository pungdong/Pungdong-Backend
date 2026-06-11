package com.diving.pungdong.consent;

import java.util.Optional;

/**
 * 약관 콘텐츠 조회 경계 — Sanity(약관 authoring/저장)와 BE(동의 박제) 사이.
 * {@link com.diving.pungdong.identityverification.IdentityVerifier} 와 동일한 "interface +
 * 구현 교체" 패턴. 테스트는 이 인터페이스를 {@code @MockBean} 으로 대체한다.
 *
 * <p><b>왜 BE 가 직접 Sanity 에서 받나</b> — 박제될 전문은 증빙이므로 FE 가 보낸 본문을 믿으면
 * 위변조 가능. 따라서 {@code (key, version)} 만 FE 가 보내고, 실제 전문은 BE 가 권위 있는
 * 소스(Sanity)에서 직접 가져온다.
 */
public interface SanityTermClient {

    /** {@code (key, version)} 으로 약관 1건을 조회. 해당 버전이 없으면 {@code empty}. */
    Optional<FetchedTerm> fetchTerm(String key, String version);

    /** Sanity 에서 가져온 약관 1건 (박제 직전 형태). body 는 Portable Text JSON 문자열. */
    record FetchedTerm(String key, String version, String title, String body, boolean required) {
    }
}
