package com.diving.pungdong.ota.dto;

import com.diving.pungdong.ota.OtaEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * {@code POST /app/ota/devices/{installId}/events} — 인증 선택.
 *
 * <p><b>{@code at} 필드가 없는 이유</b>: 이벤트는 즉시 전송되고 앱은 실패 시 재시도하지 않고 버리므로
 * "오프라인 중 발생분을 나중에 보내는" 시나리오가 없다. 그러면 서버 수신 시각이 곧 사건 시각이고,
 * 기기 시계를 받으면 오차·조작만 집계에 들어온다(레포 시간 규약).
 *
 * <p>단 {@code CRASH_ROLLBACK} 만은 의미가 다르다 — 크래시는 <b>이전 실행</b>에서 났고 다음 부팅에
 * 보고된다. 실제 크래시 시각은 네이티브가 주지 않아 관측 불가라 서버 시각이 최선이고, 그 사실을
 * 컬럼 이름({@code crashRollbackReportedAt})으로 드러낸다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtaEventRequest {

    @NotNull
    private OtaEventType type;

    @NotBlank
    @Size(max = 36)
    private String bundleId;
}
