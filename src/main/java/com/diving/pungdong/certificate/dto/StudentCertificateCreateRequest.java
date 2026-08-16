package com.diving.pungdong.certificate.dto;

import com.diving.pungdong.course.CertLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PastOrPresent;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 자격증 등록 요청 — {@code POST /certificates}.
 *
 * <p><b>없는 필드가 설계다</b>:
 * <ul>
 *   <li>{@code source} — 강의 연결 여부에서 서버가 파생. 클라이언트가 고르면 "강사 없는 풍덩 발급"이 가능해진다.</li>
 *   <li>{@code holderName} — 세션에서 파생(레포 규칙: identity 는 세션에서, 입력에서 받지 않는다).</li>
 *   <li>{@code instructorName}/{@code courseTitle} — {@code enrollmentId} 로 서버가 조회해 박제.
 *       클라이언트가 준 강사명은 신뢰 대상이 아니다.</li>
 * </ul>
 *
 * <p>메시지는 FE 가 그대로 노출하는 사용자 문구라 한국어다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class StudentCertificateCreateRequest {

    @NotBlank(message = "종목을 선택해주세요.")
    @Size(max = 50)
    private String disciplineCode;

    @NotBlank(message = "자격 단체를 선택해주세요.")
    @Size(max = 50)
    @Pattern(regexp = "[A-Za-z0-9_-]{1,50}", message = "자격 단체 코드 형식이 올바르지 않아요.")
    private String organizationCode;

    /** 표시명 스냅샷(선택) — Sanity 에서 고른 값. 없으면 FE 가 코드로 폴백한다. */
    @Size(max = 200)
    private String organizationName;

    @Size(max = 200)
    private String organizationFullName;

    @NotNull(message = "자격을 선택해주세요.")
    private CertLevel level;

    @Size(max = 200)
    private String certificationDisplayName;

    /** 단체마다 형식이 달라 <b>정규식을 걸지 않는다</b>(FE 도 자유 텍스트). 길이만 제한. */
    @NotBlank(message = "자격증 번호를 입력해주세요.")
    @Size(max = 100, message = "자격증 번호는 100자 이하로 입력해주세요.")
    private String certificateNumber;

    /** ISO {@code yyyy-MM-dd}. 미래 취득일은 거부한다. */
    @NotNull(message = "취득일을 입력해주세요.")
    @PastOrPresent(message = "취득일은 오늘보다 미래일 수 없어요.")
    private LocalDate acquiredAt;

    @Size(max = 100, message = "발급 기관은 100자 이하로 입력해주세요.")
    private String issuer;

    /**
     * 업로드 응답({@code POST /certificates/photos})의 {@code fileKey} 를 그대로. 본인 것이어야 한다.
     *
     * <p><b>필수다</b>(2026-08-16 선택 → 필수로 뒤집음). 이 도메인은 "사진이 진실"에 기대고 있다 —
     * 표시명·번호는 자기 신고라 대조하지 않고, 실제 확인은 <b>수영장 입장 때 사진을 제시</b>해서
     * 이뤄진다. 사진 없는 자격증은 그 확인을 통과하지 못하니 기록으로서 쓸모가 없다.
     */
    @NotBlank(message = "자격증 사진을 추가해주세요.")
    @Size(max = 500)
    private String photoFileKey;

    /** 연결할 수강 id — 있으면 {@code source=PUNGDONG} 이 되고 강사·강의가 박제된다. */
    @Positive(message = "잘못된 강의 정보예요.")
    private Long enrollmentId;
}
