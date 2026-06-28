package com.diving.pungdong.payment;

/** 환불 주문 상태. stub/실연동 모두 토스 취소 성공 시 DONE. */
public enum RefundStatus {
    REQUESTED,
    DONE,
    FAILED
}
