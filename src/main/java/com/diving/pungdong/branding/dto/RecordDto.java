package com.diving.pungdong.branding.dto;

import com.diving.pungdong.branding.Medal;
import com.diving.pungdong.branding.RecordEventCode;
import lombok.*;

/**
 * 공식 기록 chip 1개. 편집 API 는 PR3(스냅샷 교체) — 이 PR 은 조회 응답에만 쓴다.
 *
 * <p>{@code value} 는 단위가 종목마다 달라(깊이 {@code "-75m"} / 거리 {@code "180m"} / 시간
 * {@code "6:24"}) <b>문자열 그대로</b> 주고받는다. 숫자로 정규화하면 표시가 깨진다.
 */
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class RecordDto {
    private Medal medal;
    private RecordEventCode eventCode;
    private String value;
}
