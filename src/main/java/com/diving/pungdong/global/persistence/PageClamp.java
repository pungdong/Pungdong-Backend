package com.diving.pungdong.global.persistence;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 페이지 파라미터 정규화 — <b>클라이언트 정렬을 버리고 번호·크기만 취한다.</b>
 *
 * <p>임의 필드 정렬을 {@code Pageable} 에 태우면 내부 컬럼을 탐색하거나 인덱스 없는 정렬로 풀스캔을
 * 유발할 수 있다. 크기 상한은 전수 스크래핑 방지용이다 — 실제로 어드민 신고 큐에 상한이 없어
 * {@code ?size=100000} 이 통했다.
 *
 * <p><b>왜 {@code global} 인가.</b> 원래 {@code community.CommunityPaging} 이었는데 package-private 이라
 * 알림함이 같은 로직을 "의도적 중복" 으로 복사했고, 차단·신고가 붙으며 사본이 넷이 될 참이었다.
 * 상한은 도메인 정책이 아니라 <b>전 목록 엔드포인트에 같게 걸려야 하는 가드</b>라 한 곳에 둔다 —
 * 각자 두면 한쪽만 상한이 걸린 상태가 조용히 생긴다(그게 실제로 일어난 일이다).
 */
public final class PageClamp {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private PageClamp() {
    }

    public static Pageable fixed(Pageable pageable) {
        int size = pageable.isPaged() ? Math.min(pageable.getPageSize(), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        return PageRequest.of(page, size);
    }
}
