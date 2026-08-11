package com.diving.pungdong.community;

/**
 * 신고 처리 상태. 어드민이 사람 눈으로 판단하는 큐라 상태가 세 개면 충분하다.
 *
 * <p>자동 숨김 임계값(신고 N건 누적 시 자동 비공개)은 넣지 않았다 — 조직적 신고로 정상 글이 사라지는
 * 위험이 어드민 부재 시간대의 노출보다 크고, 임계값은 실데이터 없이 정하면 감에 불과하다.
 * 필요해지면 {@code auto_hidden_at} 컬럼과 카운트 조건만 얹으면 된다.
 */
public enum ReportStatus {

    /** 접수됨, 어드민 검토 대기. */
    PENDING,

    /** 검토 결과 조치함(콘텐츠 숨김 등). */
    ACTIONED,

    /** 검토 결과 문제없음으로 기각. */
    DISMISSED
}
