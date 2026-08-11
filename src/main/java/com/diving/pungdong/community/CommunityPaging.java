package com.diving.pungdong.community;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 페이지 파라미터 정규화 — <b>클라이언트 정렬을 버리고 번호·크기만 취한다.</b>
 *
 * <p>임의 필드 정렬을 {@code Pageable} 에 태우면 내부 컬럼을 탐색하거나 인덱스 없는 정렬로 풀스캔을
 * 유발할 수 있다. 크기 상한은 전수 스크래핑 방지용이다.
 *
 * <p>피드와 어드민 큐가 <b>같은 규칙</b>을 써야 해서 별도 클래스로 뺐다 — 각자 두면 한쪽만 상한이
 * 걸려 있는 상태가 된다(실제로 어드민 큐에 상한이 없어 {@code ?size=100000} 이 통했다).
 */
final class CommunityPaging {

    static final int MAX_PAGE_SIZE = 50;
    static final int DEFAULT_PAGE_SIZE = 20;

    private CommunityPaging() {
    }

    static Pageable fixed(Pageable pageable) {
        int size = pageable.isPaged() ? Math.min(pageable.getPageSize(), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        return PageRequest.of(page, size);
    }
}
