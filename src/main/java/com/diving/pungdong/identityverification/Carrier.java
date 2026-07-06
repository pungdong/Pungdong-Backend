package com.diving.pungdong.identityverification;

/**
 * 통신사 — SMS 본인확인 시 사용자가 <b>입력</b>하는 값(다날 요구). 확인 성공 시 포트원이 반환하는
 * operator 로 덮어써 권위값으로 만든다. (기존 stub 단계엔 기관 반환 속성이라 입력이 아니었음 —
 * SMS 실연동에선 발송 대상 통신사를 알아야 하므로 요청 입력으로 승격.)
 *
 * <p>표기는 포트원 v2 {@code operator} enum 과 <b>동일</b>하게 맞춰 {@code name()} 으로 1:1 매핑한다:
 * {@code SKT, KT, LGU} + 알뜰폰 {@code *_MVNO}. (다날 IsCarrier 바이패스 SKT/KTF/LGT/MVNO 는
 * 포트원이 내부 변환.)
 */
public enum Carrier {
    SKT, KT, LGU,
    SKT_MVNO, KT_MVNO, LGU_MVNO
}
