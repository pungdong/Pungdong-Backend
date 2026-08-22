package com.diving.pungdong.certificate;

import com.diving.pungdong.course.CertLevel;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 어드민 검수 큐의 한 행 — "무엇을 심사해 달라" 는 요청 1건.
 *
 * <p>세 종류({@link CertificateReviewKind})가 한 테이블에 있는 이유는 <b>큐가 하나</b>여야 해서다. 강사 신청(NEW)과
 * 추가/재검수 자격증(ADDITIONAL/RE_VERIFY)을 다른 테이블에서 합쳐 한 목록으로 페이징하면 두 쿼리 + 메모리 병합이
 * 된다. 대신 NEW 행은 {@code instructor_application} 과 상태가 중복된다(둘 다 서비스가 한 트랜잭션에서 맞춘다).
 *
 * <p><b>{@code previous*} 가 이 테이블을 강제했다.</b> RE_VERIFY 는 자격증 행이 이미 새 값으로 덮인 뒤라, 어드민이
 * 대조할 "이전 값"은 여기 말고 둘 곳이 없다. 최초 VERIFIED 시점의 스냅샷이며, PENDING 인 채로 또 수정돼도 갱신하지
 * 않는다(직전 수정본이 되면 대조 의미가 사라진다).
 *
 * <p>FK 를 걸지 않는다 — {@code application_id}/{@code certificate_id} 는 다른 수명주기(신청은 영구, 자격증은 사용자가
 * 지운다)라 참조만 한다. 자격증 삭제 시 그 자격증의 행은 서비스가 함께 지운다.
 */
@Entity
@Table(name = "certificate_review", indexes = {
        @Index(name = "idx_certificate_review_status", columnList = "status, requested_at"),
        @Index(name = "idx_certificate_review_certificate", columnList = "certificate_id"),
        @Index(name = "idx_certificate_review_application", columnList = "application_id")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(of = "id")
public class CertificateReview {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CertificateReviewKind kind;

    /** NEW 일 때 강사 신청 id. 다른 종류는 null. */
    @Column(name = "application_id")
    private Long applicationId;

    /** ADDITIONAL/RE_VERIFY 일 때 자격증 id. NEW 는 null(자격증은 신청이 들고 있다). */
    @Column(name = "certificate_id")
    private Long certificateId;

    /** 신청자/보유자 — 큐 목록의 사람 식별 + 탈퇴 시 일괄 삭제 키. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "discipline_code", nullable = false, length = 50)
    private String disciplineCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CertificateReviewStatus status;

    /* RE_VERIFY 대조용 — 최초 VERIFIED 시점 식별값 스냅샷 */
    @Column(name = "previous_discipline_code", length = 50)
    private String previousDisciplineCode;
    @Column(name = "previous_organization_code", length = 50)
    private String previousOrganizationCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_level", length = 30)
    private CertLevel previousLevel;
    @Column(name = "previous_certificate_number", length = 100)
    private String previousCertificateNumber;

    @Lob
    private String reason;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    public void approve(Long reviewerId, OffsetDateTime now) {
        this.status = CertificateReviewStatus.APPROVED;
        this.reviewerId = reviewerId;
        this.reviewedAt = now;
        this.reason = null;
    }

    public void reject(Long reviewerId, String reason, OffsetDateTime now) {
        this.status = CertificateReviewStatus.REJECTED;
        this.reviewerId = reviewerId;
        this.reviewedAt = now;
        this.reason = reason;
    }

    /** PENDING 인 채로 자격증 종목이 바뀌면 큐 행의 종목만 따라간다(previous 는 그대로). */
    void moveToDiscipline(String disciplineCode) {
        this.disciplineCode = disciplineCode;
    }

    public boolean hasPrevious() {
        return previousOrganizationCode != null || previousLevel != null || previousCertificateNumber != null;
    }
}
