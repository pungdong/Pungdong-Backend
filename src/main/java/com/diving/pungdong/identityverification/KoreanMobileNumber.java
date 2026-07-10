package com.diving.pungdong.identityverification;

/**
 * 한국 휴대폰 번호 형식 규칙 — <b>KR 전용 상수</b>. 정규화된(숫자만) 번호에 적용한다.
 *
 * <p>본인확인 도메인은 이미 한국에 통째로 묶여 있다 — 다날(한국 본인확인기관), {@link Carrier}
 * (SKT/KT/LGU), CI/DI(고유식별정보), {@link ForeignerType}(내·외국인). 이 클래스는 그 결합 중
 * "번호 형식" 조각을 <b>한 곳에 모아 눈에 띄게</b> 두려는 것이지 국가 추상화가 아니다. 국가 확장은
 * 이 regex 를 파라미터화하는 일이 아니라 {@link IdentityVerifier} 뒤에 새 구현을 붙이고 레코드
 * 모양(CI/DI·carrier)을 바꾸는 일이다 — 한국 종속 전체 목록은
 * {@code docs/features/identity-verification.md} 의 "한국 종속 인벤토리".
 *
 * <p>형식만 본다. <b>실존·해지·명의 일치 판정은 다날 몫</b> — 여기서 거르는 목적은 구조적으로
 * 불가능한 값으로 유료 외부 API 를 호출하지 않는 것뿐이다.
 */
public final class KoreanMobileNumber {

    /**
     * {@code 010}(현행) + {@code 011/016/017/018/019}(구 2G, 번호통합으로 신규 없음).
     * {@code 013·014·015} 는 IoT·부가서비스 번호대라 SMS 수신 자체가 불가 → 제외.
     * 뒤 7~8자리(총 10~11자리)까지만 보고, 그 이상 정밀도는 다날에 맡긴다.
     */
    public static final String PATTERN = "^01[016789]\\d{7,8}$";

    public static final String MESSAGE = "휴대폰 번호 형식이 올바르지 않습니다.";

    private KoreanMobileNumber() {
    }
}
