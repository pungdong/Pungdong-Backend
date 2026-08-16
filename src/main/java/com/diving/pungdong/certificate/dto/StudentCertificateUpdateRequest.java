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
 * 자격증 수정 요청 — {@code PUT /certificates/{id}}.
 *
 * <p><b>전면 교체(full replace)다</b>. 스칼라 필드는 보낸 값이 곧 결과이고, 생략하면 비워진다
 * ({@code issuer} 를 빼면 {@code null} 이 된다). PATCH 가 아니므로 FE 는 폼 전체를 보낸다.
 *
 * <p><b>필드·검증은 {@link StudentCertificateCreateRequest} 와 같아야 한다</b> — 한쪽에만 필드를
 * 추가하면 등록은 받는데 수정은 <b>조용히 무시</b>하는 어긋남이 생긴다. 그럼에도 클래스를 나눈 이유는
 * <b>같은 필드가 다른 뜻을 갖기 때문</b>이다(아래 두 항목). 이름이 같다고 의미까지 같지 않다.
 *
 * <ul>
 *   <li>{@code photoFileKey} — 등록에선 "없음 = 사진 없음", 수정에선 <b>"없음 = 기존 사진 유지"</b>.
 *       FE 편집 폼이 기존 사진을 다시 업로드하지 않게 하려는 것이다. (사진 <i>제거</i>는 이 계약으로
 *       표현할 수 없다 — 화면에도 제거 버튼이 없다. 생기면 별도 필드/엔드포인트가 필요하다.)</li>
 *   <li>{@code enrollmentId} — 없으면 <b>연결 해제</b>({@code source=EXTERNAL} + 강의 스냅샷 삭제)다.
 *       등록과 달리 "원래 없었다"가 아니라 "지금부터 없다"를 뜻한다.</li>
 * </ul>
 *
 * <p>없는 필드가 설계인 것도 등록과 같다 — {@code source}·{@code holderName}·강사·강의명은 전부
 * 서버가 파생한다.
 *
 * <p>메시지는 FE 가 그대로 노출하는 사용자 문구라 한국어다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class StudentCertificateUpdateRequest {

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
     * 새 사진의 저장 참조({@code POST /certificates/photos} 응답의 {@code fileKey}). 본인 것이어야 한다.
     *
     * <p><b>비우면 기존 사진을 유지한다</b> — 교체할 때만 채운다. 값이 지금 것과 같으면 아무 일도 없고,
     * 다르면 교체하고 <b>옛 객체는 커밋 이후 파기</b>한다(PII 를 고아로 남기지 않는다).
     */
    @Size(max = 500)
    private String photoFileKey;

    /**
     * 연결할 수강 id. 있으면 {@code source=PUNGDONG} 으로 재박제하고, <b>없으면 연결을 해제</b>한다
     * ({@code source=EXTERNAL} + 강의 스냅샷 전부 삭제).
     */
    @Positive(message = "잘못된 강의 정보예요.")
    private Long enrollmentId;
}
