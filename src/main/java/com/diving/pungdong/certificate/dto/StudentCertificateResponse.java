package com.diving.pungdong.certificate.dto;

import com.diving.pungdong.certificate.CertificateSource;
import com.diving.pungdong.certificate.StudentCertificate;
import com.diving.pungdong.course.CertLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 자격증 1건 응답. 목록은 {@code _embedded.certificates}(CollectionModel + {@code @Relation}).
 *
 * <p><b>null 은 생략하지 않고 명시한다</b> — FE 가 어차피 매퍼를 거치므로(ISO 날짜·{@code String(id)}),
 * 소비 계약이 자기설명적인 쪽을 택했다(계약 Q5).
 */
@Getter
@Builder
@AllArgsConstructor
@Relation(collectionRelation = "certificates")
public class StudentCertificateResponse {

    private final Long id;
    private final String disciplineCode;

    /** 저장·비교의 키. FE 는 이걸 화면에 쓰지 않는다(모노그램은 {@code organizationName}). */
    private final String organizationCode;
    private final String organizationName;
    private final String organizationFullName;

    private final CertLevel level;
    private final String certificationDisplayName;

    /** 세션에서 파생(본인확인 실명 → 없으면 닉네임). 요청 필드가 아니다. */
    private final String holderName;

    private final String certificateNumber;
    private final LocalDate acquiredAt;
    private final CertificateSource source;
    private final String issuer;

    private final Long enrollmentId;
    private final Long courseId;
    private final String courseTitle;
    private final LocalDate courseCompletedAt;
    private final String instructorName;

    /** 표시용 <b>한시</b> URL(presigned, TTL 3분). 저장값이 아니라 조회 시점에 발급된다. */
    private final String photoViewUrl;

    private final OffsetDateTime createdAt;

    public static StudentCertificateResponse of(StudentCertificate c, String holderName, String photoViewUrl) {
        return StudentCertificateResponse.builder()
                .id(c.getId())
                .disciplineCode(c.getDisciplineCode())
                .organizationCode(c.getOrganizationCode())
                .organizationName(c.getOrganizationName())
                .organizationFullName(c.getOrganizationFullName())
                .level(c.getLevel())
                .certificationDisplayName(c.getCertificationDisplayName())
                .holderName(holderName)
                .certificateNumber(c.getCertificateNumber())
                .acquiredAt(c.getAcquiredAt())
                .source(c.getSource())
                .issuer(c.getIssuer())
                .enrollmentId(c.getEnrollmentId())
                .courseId(c.getCourseId())
                .courseTitle(c.getCourseTitle())
                .courseCompletedAt(c.getCourseCompletedAt())
                .instructorName(c.getInstructorName())
                .photoViewUrl(photoViewUrl)
                .createdAt(c.getCreatedAt())
                .build();
    }
}
