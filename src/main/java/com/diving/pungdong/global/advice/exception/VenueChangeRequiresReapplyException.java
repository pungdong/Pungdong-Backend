package com.diving.pungdong.global.advice.exception;

/**
 * <b>위치까지 바꾸면서 금액이 오르는</b> 일정 변경을 시도할 때. 취소 후 재신청만이 지원 경로다.
 *
 * <p><b>왜 {@link AdditionalPaymentRequiredException}(-1018) 과 갈라야 하나</b> — -1018 은 "차액을 내면 갈 수
 * 있다"는 뜻이고 FE 는 그걸 보고 차액 결제({@code POST /payments/prepare} + {@code target*})로 유도한다.
 * 그런데 <b>차액 결제 경로는 위치를 바꾸지 못한다</b> — {@code target*} 에 위치가 없고
 * {@code applySlotChange} 는 {@code round.venueRefId} 를 그대로 둔다. 그래서 위치 변경까지 -1018 로 내보내면
 * FE 가 "차액 결제하면 된다"고 안내하고, 결제 후 학생은 <b>고른 적 없는 원래 위치</b>의 슬롯으로 옮겨진다
 * (게다가 성공 화면은 정상으로 보인다). 돈이 오가는 경로라 그 조용한 어긋남을 코드 단계에서 끊는다.
 *
 * <p><b>왜 위치 변경엔 차액 결제를 못 쓰나</b> — 위치가 바뀌면 입장료뿐 아니라 <b>장비 가격표</b>(위치 종속),
 * {@code AvailabilitySession} 자연키(위치 포함), 좌석 hold, 겹침 판정, 코스 회차 후보 검증이 전부 다시
 * 계산돼야 한다. 차액 결제는 "입장료만 갈린다"는 전제 위에 서 있다.
 *
 * <p>같거나 <b>싼</b> 위치 변경은 {@code reschedule} 로 그대로 된다(차액 자동환불) — 막히는 조합은
 * <b>"위치 변경 + 금액 상승"</b> 하나뿐이다.
 */
public class VenueChangeRequiresReapplyException extends RuntimeException {
    public VenueChangeRequiresReapplyException() {
        super();
    }
}
