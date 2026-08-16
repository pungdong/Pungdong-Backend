package com.diving.pungdong.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 커서 목록 응답 — {@code PagedModel} 이 아니다(의도적 이탈, 패키지 CLAUDE.md 참고).
 *
 * <p>종료조건을 <b>명시 필드 {@link #hasMore}</b> 로 준다. 이 레포엔 목록 응답에서 {@code page} 블록이
 * 빠져 FE 무한스크롤 종료조건이 깨진 회귀 이력이 있어, HAL 래핑에 의존하지 않는다.
 * {@code messages.length < size} 로 추론하게 하지 않는다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageListResponse {

    /** <b>항상 id 오름차순</b>(과거→최신). 요청 방향과 무관 — FE 가 뒤집을 필요가 없다. */
    private List<ChatMessageResponse> messages;

    /**
     * 요청한 방향으로 더 있는가.
     * {@code before}/커서없음 → 더 <b>과거</b>가 있는가. {@code after} → 더 <b>최신</b>이 있는가
     * (= 버스트가 size 를 넘음 → FE 즉시 재조회).
     */
    private boolean hasMore;

    /**
     * 그 방향 다음 요청에 그대로 넣을 값. {@code before}/커서없음 → 목록 <b>최소</b> id,
     * {@code after} → 목록 <b>최대</b> id.
     *
     * <p>⚠️ <b>빈 목록이면 요청에 쓴 커서를 그대로 에코한다</b>(커서 없이 불렀고 결과도 비면 null).
     * {@code after} 폴링은 대부분 빈 목록이라, 여기서 null 을 주면 호출부가 무심코
     * {@code cursor = res.nextCursor} 했을 때 커서가 날아가고 다음 폴링이 최신 N건을 통째로 다시 가져와
     * 중복 렌더가 난다.
     */
    private Long nextCursor;
}
