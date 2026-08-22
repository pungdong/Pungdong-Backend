package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.identityverification.IdentityVerification;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 강사 신청 1건. <b>종목별</b> — 한 계정이 종목마다 1건씩 (프리다이빙 + 스쿠버 동시 가능).
 * {@code (account_id, discipline_code)} 유니크.
 *
 * <p>자격증은 {@link #certificateIds} 로 <b>참조</b>한다(정본은 {@code certificate} 도메인). 상태머신은 그대로
 * SUBMITTED/APPROVED/REJECTED 이고, 어드민 큐에서는 {@code certificate.CertificateReview}(kind=NEW) 행이 이 신청을 가리킨다.
 *
 * <p>레거시 {@code Account.isRequestCertified}/{@code isCertified} 플래그 방식을 대체한다.
 * 상태머신({@link InstructorApplicationStatus})으로 제출/승인/반려/재제출을 표현하고,
 * 심사 이력(reviewer/reviewedAt/rejectionReason)을 보유한다. 승인된 신청 = 그 종목의 강사 자격.
 *
 * <p>소속 단체는 enum 이 아니라 {@code organizationCode} 문자열로 저장한다 — 단체 목록은
 * Sanity 카탈로그가 source of truth 라 BE enum 으로 박으면 단체 추가 때마다 배포가 필요해진다.
 * 자격증 필수 여부는 종목({@link com.diving.pungdong.discipline.Discipline})의 requiresCertification.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_application_account_discipline", columnNames = {"account_id", "discipline_code"}))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class InstructorApplication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    /** 신청 종목 코드 ({@code discipline.code}, 예 "FREEDIVING"). 계정당 종목별 1신청. */
    @Column(name = "discipline_code")
    private String disciplineCode;

    @Enumerated(EnumType.STRING)
    private InstructorApplicationStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    private IdentityVerification identityVerification;

    /**
     * 첨부 자격증 — {@code certificate.StudentCertificate} 의 id 목록(제출 순서). 2026-08-22 수렴 전엔 신청이 자체
     * {@code ApplicationCertificate}(단체+이미지)를 소유했는데, 강사가 같은 자격증을 두 번 올려야 했다. 이제 정본은
     * 내 자격증이고 신청은 참조만 한다. 심사 결과는 그 자격증의 {@code verification} 에 붙는다.
     *
     * <p>FK 없음 — 자격증은 사용자가 지울 수 있다(심사 중인 건 Rule C 가 막는다). 지워진 id 는 읽을 때 걸러진다.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "instructor_application_certificate", joinColumns = @JoinColumn(name = "application_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "certificate_id", nullable = false)
    @Builder.Default
    private List<Long> certificateIds = new ArrayList<>();

    /**
     * (선택) 다이빙보험 증빙 이미지의 <b>비공개 저장 참조 key</b> — 자격증과 동일한 비공개 패턴
     * (presigned 로 열람). 보험은 활동 특화라 <b>종목 신청별</b>로 둔다(계정 공유 아님). null 허용.
     */
    @Column(name = "insurance_file_key")
    private String insuranceFileKey;

    /** 반려 사유 (status = REJECTED 일 때). */
    @Lob
    private String rejectionReason;

    /** 심사한 어드민 계정. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Account reviewer;

    private OffsetDateTime submittedAt;
    private OffsetDateTime reviewedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public void replaceCertificateIds(List<Long> ids) {
        this.certificateIds.clear();
        if (ids != null) {
            this.certificateIds.addAll(ids);
        }
    }
}
