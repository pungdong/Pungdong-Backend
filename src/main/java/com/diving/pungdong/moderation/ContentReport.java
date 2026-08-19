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

    /**
     * <b>조치 대상의 작성자</b>(글·댓글·강의·메시지를 올린 사람) — 접수 시점에 고정한다.
     *
     * <p>매번 대상을 열어 알아내면 <b>대상이 지워지는 순간 그 신고는 "누구에 대한 신고인지 모르는 행"</b>
     * 이 된다. 접수 때 이미 작성자를 확인하고 있으므로(자기 것 신고 차단의 판정 근거) 그 값을 적어 둔다.
     * 덕분에 <b>같은 사람에 대한 반복 신고</b>가 대상 종류·강의를 가로질러 한 쿼리로 잡힌다.
     *
     * <p>FK 를 걸지 않은 건 의도다 — 이 테이블은 폴리모픽 참조라 원래 FK 가 없고, 계정 삭제(익명화)가
     * 신고 행 때문에 막히면 안 된다. V34 이전 행은 {@code null} 일 수 있다(백필했지만 대상이 이미
     * 사라진 경우) — 어드민 응답은 그때만 대상을 열어보는 방식으로 폴백한다.
     */
    @Column(name = "target_author_account_id")
    private Long targetAuthorAccountId;

    /**
     * 어드민이 처리하며 남기는 메모. 처리 시점에만 존재하는 정보라 지금 자리를 둔다.
     *
     * <p><b>왜 필요한가:</b> 조치({@code ACTIONED})는 대상별로 무겁다 — 강의면 사실상 판매 중단이다.
     * 1:1 분쟁엔 과해서 어드민이 기각을 누르게 되는데, 그러면 "강사에게 경고 전달함" 과 "문제없음" 이
     * 같은 행으로 보인다. 비파괴 조치 <b>상태값</b>을 늘리는 것과는 다른 문제다(그건 나중에 더해도
     * 과거 신고에 소급 손실이 없다).
     */
    @Column(name = "admin_note", length = 500)
    private String adminNote;

    @PrePersist
    void prePersist() {
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (this.status == null) {
            this.status = ReportStatus.PENDING;
        }
    }
}
