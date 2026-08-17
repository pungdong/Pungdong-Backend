package com.diving.pungdong.ota.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /admin/ota/bundle-stats}.
 *
 * <p><b>{@code bundleIds} 지정 모드</b>: 요청한 순서 그대로, 없는 id 도 전부 0 으로 채워서 돌려준다
 * (어드민이 zero-fill 을 추측하지 않게 — 엔트리 누락은 상태가 아니라 버그다).
 * <b>생략 모드</b>: BE 가 아는 전량을 {@code bundleId DESC}(uuidv7 이라 시간 역순)로.
 * 생략 모드가 필요한 이유는 <b>D1 에 없는 고아 번들</b>(삭제됐는데 기기는 아직 그 번들을 실행 중)을
 * 어드민이 찾아야 하기 때문이다 — 지정 모드만 있으면 그런 번들은 애초에 질문 목록에 못 들어간다.
 */
@Getter
@Builder
public class OtaBundleStatsResponse {

    /** 응답에 실어 내려서 화면이 "최근 N일 기준"을 그대로 말할 수 있게 한다. */
    private final int activeWindowDays;

    private final OffsetDateTime generatedAt;

    private final List<OtaBundleStats> stats;
}
