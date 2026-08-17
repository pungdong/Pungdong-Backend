package com.diving.pungdong.ota.dto;

import com.diving.pungdong.account.DeviceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /app/ota/devices} — 부팅 1회 + 포그라운드 재체크(4h 스로틀) upsert. 인증 선택.
 *
 * <p><b>검증 원칙: 관측은 관용적으로.</b> 앱은 4xx/5xx 를 전부 삼키고 재시도하지 않으므로, 검증 하나가
 * 어긋나면 그 기기가 <b>영구히 집계 밖</b>으로 사라지고 아무도 모른다. 그래서 {@code installId}·
 * {@code platform} 외에는 전부 선택이고 형식 검증도 최소다. (반대로 {@code PUT /admin/app/policy} 는
 * 사람이 폼에 넣고 에러가 화면에 보이며 틀린 값이 게이트를 잘못 여닫으므로 엄격하게 간다.)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtaDeviceUpsertRequest {

    /**
     * 앱이 만든 불투명 설치 식별자.
     *
     * <p>UUID 형식을 강제하지 <b>않는다</b> — RN(Hermes)엔 WebCrypto 가 없어 앱이 만드는 값은 암호학적
     * 난수가 아니고, 형식을 못 박으면 앱이 생성 방식을 바꾸는 순간 그 기기가 조용히 영구 이탈한다.
     * {@code @Pattern} 은 형식 강제가 아니라 <b>URL·로그 안전 문자셋 제한</b>이다 — permitAll 경로변수로도
     * 들어오므로 {@code /}·CRLF·공백 유입을 막는다.
     */
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "installId 형식이 올바르지 않습니다.")
    private String installId;

    /** 유일하게 required 인 선택지 — 앱이 {@code Platform.OS} 에서 만드는 JS 상수라 throw 하지 않는다. */
    @NotNull
    private DeviceType platform;

    /** ★ {@code "1.0"}(2자리)도 허용한다. iOS 의 CFBundleShortVersionString 은 2자리가 완전히 합법이라,
     *  3자리로 못 박으면 누가 한 번 {@code 1.0} 으로 올린 순간 그 빌드의 전 기기가 영구히 400 이 된다. */
    @Size(max = 20)
    @Pattern(regexp = "^\\d{1,3}(\\.\\d{1,3}){1,2}$", message = "appVersion 형식이 올바르지 않습니다.")
    private String appVersion;

    /** {@code staging} | {@code production}. enum 으로 올리지 않는다 — 채널이 늘면 역직렬화가 깨진다. */
    @Size(max = 16)
    @Pattern(regexp = "^[a-z0-9_-]{1,16}$", message = "otaChannel 형식이 올바르지 않습니다.")
    private String otaChannel;

    /** 형식 검증 없음 — 해시 알고리즘이 라이브러리 소관이라 SHA-256(64자)으로 바뀌면 전 기기가 사라진다. */
    @Size(max = 64)
    private String fingerprintHash;

    /** 내장 번들이면 null. min 과 같으면 서버가 null 로 정규화한다(이중 방어). */
    @Size(max = 36)
    private String otaBundleId;

    @Size(max = 36)
    private String otaMinBundleId;

    /** {@code "1"}~{@code "1000"} 또는 커스텀 슬러그. 둘이 섞이므로 {@code @Pattern} 을 걸지 않는다. */
    @Size(max = 64)
    private String otaCohort;

    /** 원소 검증 실패는 400 이 아니라 "그 원소만 버림"(서비스가 정제). 보조 신호가 주 신호를 죽이면 안 된다. */
    private List<String> otaCrashHistory;
}
