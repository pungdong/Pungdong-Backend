package com.diving.pungdong.branding.dto;

import com.diving.pungdong.branding.Medal;
import com.diving.pungdong.branding.RecordEventCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * 공식 기록 <b>스냅샷 교체</b> — {@code PUT /branding/me/records}.
 *
 * <p><b>왜 항목별 POST/PATCH/DELETE 가 아니라 스냅샷인가</b>: 디자인이 chip 순서 조정을 요구한다. 드래그로
 * 재정렬한 뒤 항목마다 요청을 쏘면 중간 상태가 노출되고, 일부만 성공하면 순서가 깨진다. 배열을 통째로
 * 보내면 추가·삭제·재정렬이 <b>한 번의 원자적 호출</b>로 끝난다. course·venue 도 같은 관례다.
 *
 * <p>보낸 배열이 곧 최종 상태다 — 빈 배열을 보내면 기록이 전부 지워진다.
 */
@Getter @Setter
@NoArgsConstructor
public class RecordsUpdateRequest {

    @Valid
    @NotNull(message = "기록 목록을 보내주세요.")
    @Size(max = 12, message = "기록은 12개까지 등록할 수 있어요.")
    private List<RecordItem> records = new ArrayList<>();

    @Getter @Setter
    @NoArgsConstructor
    public static class RecordItem {

        @NotNull(message = "메달을 선택해주세요.")
        private Medal medal;

        @NotNull(message = "종목을 선택해주세요.")
        private RecordEventCode eventCode;

        /**
         * 기록 원문. 종목마다 단위가 달라(깊이 {@code -75m} / 거리 {@code 180m} / 시간 {@code 6:24})
         * 숫자로 정규화하지 않는다 — 정규화하면 표시가 깨지고 BE 가 포맷을 재구현하게 된다.
         */
        @NotBlank(message = "기록을 입력해주세요.")
        @Size(max = 16, message = "기록은 16자까지 쓸 수 있어요.")
        private String value;
    }
}
