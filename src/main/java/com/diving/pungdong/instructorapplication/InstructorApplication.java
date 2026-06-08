package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 강사 신청 1건 (한 계정당 최대 1건 — {@code account_id} 유니크).
 *
 * <p>레거시 {@code Account.isRequestCertified}/{@code isCertified} 플래그 방식을 대체한다.
 * 상태머신({@link InstructorApplicationStatus})으로 제출/승인/반려/재제출을 표현하고,
 * 심사 이력(reviewer/reviewedAt/rejectionReason)을 보유한다.
 *
 * <p>소속 단체는 enum 이 아니라 {@code organizationCode} 문자열로 저장한다 — 단체 목록은
 * Sanity 카탈로그가 source of truth 라 BE enum 으로 박으면 단체 추가 때마다 배포가 필요해진다.
 */
@Entity
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class InstructorApplication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", unique = true)
    private Account account;

    @Enumerated(EnumType.STRING)
    private InstructorApplicationStatus status;

    /** Sanity 자격증 카탈로그의 단체 code (예: "PADI", "AIDA", "OTHER"). */
    private String organizationCode;

    /** organizationCode 가 OTHER(기타) 일 때만 채워지는 직접입력 단체명. */
    private String organizationOther;

    @ManyToOne(fetch = FetchType.LAZY)
    private IdentityVerification identityVerification;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ApplicationCertificate> certificates = new ArrayList<>();

    /** 반려 사유 (status = REJECTED 일 때). */
    @Lob
    private String rejectionReason;

    /** 심사한 어드민 계정. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Account reviewer;

    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 자격증 이미지를 신청에 연결한다 (양방향 동기화). */
    public void addCertificate(ApplicationCertificate certificate) {
        certificate.setApplication(this);
        this.certificates.add(certificate);
    }

    public void clearCertificates() {
        this.certificates.clear();
    }
}
