package com.diving.pungdong.global.validation;

/**
 * 한국 휴대폰 번호 형식 — 정규화된(숫자만) 번호에 적용하는 <b>크로스도메인 상수</b>.
 * 본인확인(다날 전송)·계정 프로필 등 여러 도메인이 공유한다(그래서 특정 도메인 패키지가 아니라 global).
 *
 * <p>형식만 본다 — 실존·해지·명의 일치 판정은 외부 기관(다날) 몫. 국가 확장 시 이 regex 를
 * 파라미터화하는 게 아니라 도메인별 검증기를 붙인다(본인확인의 "한국 종속 인벤토리" 참조).
 */
public final class KoreanMobileNumber {

    /**
     * {@code 010}(현행) + {@code 011/016/017/018/019}(구 2G). {@code 013·014·015} 는 IoT·부가서비스
     * 번호대라 SMS 수신 불가 → 제외. 뒤 7~8자리(총 10~11자리)까지만 보고 그 이상은 외부 기관에 맡긴다.
     */
    public static final String PATTERN = "^01[016789]\\d{7,8}$";

    public static final String MESSAGE = "휴대폰 번호 형식이 올바르지 않습니다.";

    private KoreanMobileNumber() {
    }
}
