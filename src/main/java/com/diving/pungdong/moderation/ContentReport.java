package com.diving.pungdong.moderation;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 신고 접수. 어드민이 목록에서 보고 수동 처리한다(자동 숨김 없음 — {@link ReportStatus} 참고).
 *
 * <p><b>{@code targetType}/{@code targetId} 에 FK 가 없는 건 의도다.</b> 게시물과 댓글 두 종류를 가리키는
 * 폴리모픽 참조라 DB 제약을 걸 수 없다. 대상이 실제로 있는지는 접수 시점에 서비스가 확인한다.
 *
 * <p><b>{@code (targetType, targetId, reporter)} UNIQUE</b> 로 같은 사람의 중복 신고를 막는다. 다만
 * 중복 신고를 에러로 돌려주지는 않는다 — 이미 신고한 걸 다시 눌러도 사용자 입장에선 "신고됨"이 맞는
 * 결과라 200 멱등으로 처리한다(레포 규칙: 기대되는 결과는 4xx 가 아니다).
 */
@Entity
@Table(name = "content_report",
        uniqueConstraints = @UniqueConstraint(name = "uk_content_report_once",
                columnNames = {"target_type", "target_id", "reporter_account_id"}))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ContentReport {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_account_id", nullable = false)
    private Account reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReportReason reason;

    /** 자유 설명. {@link ReportReason#OTHER} 일 때만 필수(서비스가 검증). */
    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportStatus status;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    /** 어드민이 처리한 시각. PENDING 인 동안 null. */
    @Column(name = "handled_at")
    private OffsetDateTime handledAt;

    @PrePersist
    void prePersist() {
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (this.status == null) {
            this.status = ReportStatus.PENDING;
        }
    }
}
