package com.diving.pungdong.venue;

import com.diving.pungdong.global.advice.exception.BadRequestException;

/**
 * 위치 출처 — CUSTOM(BE DB, 강사 소유) | OFFICIAL(Sanity authoring). 코스 빌더 통합 목록
 * ({@code GET /venues/builder})이 항목마다 붙여 주는 <b>참조 토큰</b>의 prefix 이기도 하다:
 * {@code "<scope>:<id>"} (예: {@code "CUSTOM:42"}, {@code "OFFICIAL:official-deepstation"}).
 *
 * <p>장비 venue-extension(이 PR)·코스 회차 위치(후속)가 이 토큰으로 위치를 가리킨다. 토큰 생성/파싱을
 * 여기 한 곳에 모아 형식이 흩어지지 않게 한다.
 */
public enum VenueScope {
    CUSTOM, OFFICIAL;

    /** {@code "<scope>:<id>"} 토큰 생성. */
    public static String token(VenueScope scope, String id) {
        return scope.name() + ":" + id;
    }

    /** 토큰 파싱. 형식이 어긋나면 400(존재하지 않는 prefix·구분자 없음·빈 id). */
    public static Ref parse(String token) {
        if (token == null) {
            throw new BadRequestException();
        }
        int sep = token.indexOf(':');
        if (sep <= 0 || sep == token.length() - 1) {
            throw new BadRequestException();
        }
        VenueScope scope;
        try {
            scope = VenueScope.valueOf(token.substring(0, sep));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException();
        }
        return new Ref(scope, token.substring(sep + 1));
    }

    /** 파싱 결과 — 출처 + 그 출처 내 id(custom=PK 문자열, official=Sanity _id). */
    public static final class Ref {
        private final VenueScope scope;
        private final String id;

        public Ref(VenueScope scope, String id) {
            this.scope = scope;
            this.id = id;
        }

        public VenueScope getScope() { return scope; }
        public String getId() { return id; }
    }
}
