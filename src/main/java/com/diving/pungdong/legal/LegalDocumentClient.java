package com.diving.pungdong.legal;

import com.diving.pungdong.legal.dto.LegalDocumentResponse;

import java.util.Optional;

/**
 * 법적 고지 전문(이용약관/개인정보/환불) 조회 경계. 테스트는 이 인터페이스를 {@code @MockBean}.
 *
 * <p>왜 BE 가 읽어 프록시하나 — 원래는 FE 가 Sanity CDN 을 익명 직접 읽는 설계였으나, 이 Sanity
 * 프로젝트는 **2026-06-11 이후 생성된 문서를 익명 읽기에서 거부**한다(`reason:permission`, 생성일 기반,
 * 플랜상 콘솔로 못 풀어 Sanity 지원 문의 중). 그래서 BE 가 **read 토큰으로 서버사이드**로 읽어 공개
 * 제공한다 — consent {@code term}/siteSettings 와 같은 BE-read 패턴. Sanity 가 접근을 고치면 FE 직접
 * 읽기로 되돌릴 수 있다.
 */
public interface LegalDocumentClient {

    /** slug(terms/privacy/refund) 로 활성 전문 조회. 없으면 empty. */
    Optional<LegalDocumentResponse> fetch(String slug);
}
