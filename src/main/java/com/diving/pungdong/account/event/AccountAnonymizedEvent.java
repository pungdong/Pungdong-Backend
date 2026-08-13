package com.diving.pungdong.account.event;

/**
 * 탈퇴 유예기간이 지난 계정의 PII 파기가 일어났을 때 발행 — 각 도메인이 <b>자기가 보관한 PII</b>를 지우도록.
 *
 * <p><b>왜 이벤트인가</b>: 파기해야 할 PII 는 도메인마다 흩어져 있는데(자격증 이미지 = instructorapplication,
 * 앞으로 학생 자격증 사진 = certificate …), <b>account 는 feature 도메인을 import 하지 않는다</b>(단방향 규칙 —
 * 이미 {@code instructorapplication → account} 가 있어 반대 방향을 더하면 사이클이다. `profile` 패키지가
 * 존재하는 이유와 같은 제약). account 가 이 이벤트를 발행하면 각 도메인의 {@code @EventListener} 가
 * 자기 저장소를 정리한다 — 새 도메인은 <b>account 를 건드리지 않고 리스너만 추가</b>하면 된다.
 *
 * <p><b>수신부는 예외를 삼켜야 한다</b>: 기본 {@code @EventListener} 는 발행자 트랜잭션 안에서 동기 실행되므로,
 * 리스너가 던지면 익명화 자체가 롤백된다. 파기의 우선순위는 "고아 객체 1개 남기기" 보다 "익명화는 반드시 완료"
 * 이므로 각 리스너가 try/catch + log 로 best-effort 처리한다({@code ProfilePhotoService} 의 순서 원칙과 동일).
 */
public record AccountAnonymizedEvent(Long accountId) {
}
