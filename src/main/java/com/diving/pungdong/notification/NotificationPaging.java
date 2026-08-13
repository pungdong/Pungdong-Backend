package com.diving.pungdong.notification;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 페이지 파라미터 정규화 — <b>클라이언트 정렬을 버리고 번호·크기만 취한다.</b>
 *
 * <p>임의 필드 정렬을 {@code Pageable} 에 태우면 인덱스 없는 정렬로 풀스캔을 유발하거나 내부 컬럼을
 * 탐색당한다. 크기 상한은 전수 스크래핑 방지용이다 — 커뮤니티에서 어드민 큐에 상한이 없어
 * {@code ?size=100000} 이 통했던 전례가 있다({@code CommunityPaging}).
 *
 * <p>정렬은 리포지토리 메서드명({@code ...OrderByCreatedAtDesc})이 고정한다.
 *
 * <p>{@code CommunityPaging} 과 같은 로직인 <b>의도적 중복</b>이다 — 그쪽이 package-private 이라
 * 재사용이 안 된다. 세 번째 사용처가 생기면 {@code global} 로 승격한다.
 */
final class NotificationPaging {

    static final int MAX_PAGE_SIZE = 50;
    static final int DEFAULT_PAGE_SIZE = 20;

    private NotificationPaging() {
    }

    static Pageable fixed(Pageable pageable) {
        int size = pageable.isPaged() ? Math.min(pageable.getPageSize(), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        return PageRequest.of(page, size);
    }
}
