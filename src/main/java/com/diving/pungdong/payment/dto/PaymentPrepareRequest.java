package com.diving.pungdong.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 결제 준비 요청 — 미결제 <b>회차</b>(신청 직후 PENDING)에 대해 주문 생성.
 *
 * <p>⚠️ <b>식별자 이름 주의</b>: 결제 단위는 <b>회차(EnrollmentRound)</b> 다. 옛 필드명 {@code enrollmentId} 가
 * 실제로는 회차 id 를 담고 있어, 같은 이름을 <b>수강(Enrollment) id</b> 로 쓰는 환불 경로
 * ({@code POST /enrollments/{enrollmentId}/refund})와 헷갈린다 — 둘 다 number 라 타입으로 못 잡는다(FE 리뷰 지적).
 * 그래서 {@link #roundId} 를 도입했고, {@code enrollmentId} 는 하위호환으로 당분간 병행 허용한다.
 */
@Getter @Setter
@NoArgsConstructor
public class PaymentPrepareRequest {

    /** ★ 회차(EnrollmentRound) id — 권장 필드. */
    private Long roundId;

    /**
     * @deprecated 회차 id 를 담는 옛 이름. {@link #roundId} 를 쓸 것. 하위호환으로만 남아 있다.
     */
    @Deprecated
    private Long enrollmentId;

    /** 실제로 쓸 회차 id — {@code roundId} 우선, 없으면 옛 {@code enrollmentId}. 둘 다 없으면 null(컨트롤러가 400). */
    public Long resolvedRoundId() {
        return roundId != null ? roundId : enrollmentId;
    }

    /**
     * 모바일 환경 여부 — 이니시스 표준결제의 {@code P_DEVICE_TYPE}(MOBILE/WEB) 분기에 쓰인다.
     * 토스 위젯은 무관(무시). 미전달 시 PC(WEB)로 본다.
     *
     * <p>보안값이 아니라 흐름 선택값이라 클라이언트가 보내도 안전하다 — 리턴 URL 처럼 위조 시 위험한 값은
     * 받지 않고 BE 설정으로 고정한다.
     */
    private boolean mobile;

    /**
     * 클라이언트 종류 — {@code "web"} | {@code "app"} (미전달 시 web). <b>이니시스 콜백 리다이렉트 타겟</b> 선택용
     * ({@link com.diving.pungdong.payment.PaymentClient}). {@code mobile} 과 독립 축 — 웹 모바일브라우저는
     * {@code mobile:true, client:"web"} 다. TOSS/STUB 는 무시. 값 자체는 리다이렉트 URL 을 정하는 게 아니라
     * BE 의 고정 allowlist 중 하나를 고르게 할 뿐이라 클라이언트가 보내도 안전(오픈 리다이렉트 불가).
     */
    private String client;

    /* ─── 슬롯 변경 차액 결제(선택) ─── */

    /**
     * <b>목표 슬롯 날짜</b> — 넷({@code targetDate}/{@code targetTicketRef}/{@code targetBlockStart}/
     * {@code targetBlockEnd})이 <b>모두</b> 오면 이 결제는 <b>슬롯 변경 차액</b>이다. 하나라도 빠지면 일반 결제.
     *
     * <p>더 비싼 시간대로 옮길 때만 쓴다 — 같거나 싼 슬롯은 결제 없이
     * {@code pick-slot}/{@code reschedule} 로 즉시 바뀐다(싸지면 차액 자동환불).
     * <b>위치와 장비는 현재 것을 유지</b>한다(바꾸려면 취소 후 재신청).
     */
    private java.time.LocalDate targetDate;

    /** 목표 슬롯 이용권 ref. */
    private String targetTicketRef;

    /**
     * <b>목표 슬롯 위치(선택 — 보내면 대조한다)</b>. 차액 결제는 <b>위치를 바꾸지 못한다</b> — 서버는 언제나
     * 회차의 현재 위치로 목표 슬롯을 해석한다.
     *
     * <p>그래서 클라이언트가 <b>다른 위치</b>를 띄워놓고 이 요청을 보내면, (이용권·시간이 현재 위치에도 우연히
     * 존재할 경우) 학생이 고른 적 없는 <b>원래 위치</b>로 조용히 옮겨진다 — 돈은 나갔는데 장소가 다르고 성공
     * 화면은 정상으로 보인다. 그 조용한 어긋남을 막으려고, 값을 보내면 현재 위치와 대조해 다르면
     * {@code -1019}({@link com.diving.pungdong.global.advice.exception.VenueChangeRequiresReapplyException})
     * 로 <b>거부</b>한다.
     *
     * <p>즉 이 필드는 기능이 아니라 <b>가드</b>다. 클라이언트는 사용자에게 보여준 위치를 항상 실어 보내는 것을
     * 권장한다(안 보내면 대조를 못 하니 이 방어가 꺼진다). 애초에 위치가 바뀌는 경로로 들어오지 않게 하는 1차
     * 방어는 {@code reschedule} 이 {@code -1019} 를 먼저 내는 것이다.
     */
    private String targetVenueRefId;

    /**
     * 목표 슬롯 시작 시각 — {@code "14:00"} · {@code "14:00:00"} 둘 다 받는다(ISO_LOCAL_TIME).
     *
     * <p>⚠️ 예전엔 {@code @JsonFormat(pattern = "HH:mm")} 이 붙어 있어 <b>{@code "14:00:00"} 이 400</b> 이었다.
     * 슬롯 목록({@code EnrollmentOptionsResponse.Slot.blockStart})은 {@code "14:00:00"} 으로 나가는데 이 DTO 만
     * 엄격해서, "슬롯이 준 값을 그대로 되보낸다"는 자연스러운 사용이 깨졌다(다른 슬롯 DTO —
     * {@code EnrollmentCreateRequest}·{@code PickSlotRequest}·{@code RoundScheduleRequest} — 는 전부 둘 다 받는다).
     * 포맷을 떼서 맞췄다. 테스트가 {@code "18:00"} 만 보내 못 잡았던 자리다.
     */
    private java.time.LocalTime targetBlockStart;

    /** 목표 슬롯 종료 시각 — {@code "14:00"} · {@code "14:00:00"} 둘 다. ({@link #targetBlockStart} 주석 참고.) */
    private java.time.LocalTime targetBlockEnd;

    /** 슬롯 변경 차액 결제 요청인가 — 네 값이 모두 있어야 한다(부분 전달은 일반 결제로 본다). */
    public boolean hasSlotChangeTarget() {
        return targetDate != null && targetTicketRef != null
                && targetBlockStart != null && targetBlockEnd != null;
    }
}
