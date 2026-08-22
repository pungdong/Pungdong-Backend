package com.diving.pungdong.certificate.dto;

import com.diving.pungdong.certificate.CertificateVerificationStatus;
import com.diving.pungdong.certificate.StudentCertificate;
import com.diving.pungdong.course.CertLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 공개 자격 뱃지 1개 — 브랜딩·강의상세·프로필·커뮤니티의 {@code CertBadge} 가 전부 이 값에서 매핑된다.
 *
 * <p><b>{@code verified} 는 {@code level} 에서 추론하지 않는다.</b> 지금은 "강사 레벨 ⟹ VERIFIED" 가 참이지만
 * 그건 정책(수강생 레벨은 검수하지 않는다)이지 구조가 아니다 — 나중에 수강생 자격도 검증하기로 하면 추론이
 * 조용히 틀린다. FE 는 이 값으로 <i>검증마크 룩</i>과 <i>중립 칩 룩</i>을 가른다(자기신고가 검증마크를 참칭하면 안 된다).
 *
 * <p>{@code organizationOther} 는 {@code organizationCode == OTHER} 일 때 {@code organizationName}(자유입력 단체명
 * 스냅샷). 옛 {@code ApplicationCertificate.organizationOther} 의 자리를 새 필드 없이 메운다.
 */
@Getter
@AllArgsConstructor
public class CertificateBadge {
    public static final String ORGANIZATION_OTHER = "OTHER";

    private final String disciplineCode;
    private final String organizationCode;
    private final String organizationOther;
    /** 평탄화 레벨 — 사다리 순서는 {@link CertLevel} 선언 순서. */
    private final CertLevel level;
    /** {@code verification.status == VERIFIED}. 레벨과 무관하게 <b>실제 상태</b>에서 읽는다(클래스 javadoc). */
    private final boolean verified;

    public static CertificateBadge of(StudentCertificate c) {
        boolean other = ORGANIZATION_OTHER.equalsIgnoreCase(c.getOrganizationCode());
        return new CertificateBadge(c.getDisciplineCode(), c.getOrganizationCode(),
                other ? c.getOrganizationName() : null,
                c.getLevel(),
                c.getVerification().is(CertificateVerificationStatus.VERIFIED));
    }
}
