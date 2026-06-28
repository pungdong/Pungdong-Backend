package com.diving.pungdong.availability;

import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 수동/외부 점유 1건 — "두 가지 점유 조정 방식"을 <b>단일 테이블</b>로 흡수(브리프). 일정({@link
 * AvailabilitySession})에 붙는다.
 *
 * <ul>
 *   <li>{@code memo == null} — ± 빠른조정. 메모 없이 1칸 점유({@code count=1}).</li>
 *   <li>{@code memo != null} — 외부예약. 메모 + 인원 1~N. 유효정원 초과해도 기록(자동확장 없음).</li>
 *   <li>{@code proposalRoundId != null} — <b>강사 일정변경 제안 보장 hold</b>(enrollment 회차 귀속, 1칸,
 *       {@code expiresAt} 만료). 학생이 그 슬롯을 고르면 실점유로 전환되고 나머지는 해제된다(보장 메커니즘).</li>
 * </ul>
 *
 * <p>풍덩 수강생 점유(enrollment)와는 별개 — 이건 "풍덩 밖 점유"(외부예약/±) + 풍덩 제안 보장 hold 를 강사가
 * 기입/시스템이 적재. heldCount 에 모두 합산돼 정원(만석) 판정에 그대로 반영된다(제안 hold 가 다른 학생을 막음).
 * {@code proposalRoundId} 는 raw Long — availability 가 enrollment 를 역참조하지 않게(단방향 의존 유지).
 */
@Entity
@Table(name = "availability_hold")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AvailabilityHold {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private AvailabilitySession session;

    /** 점유 인원 — ± 빠른조정 = 1, 외부예약 = 1~N. */
    private int count;

    /** 외부예약 메모(예: "네이버 L1 홍길동"). null 이면 ± 빠른조정 또는 제안 hold. */
    private String memo;

    /** 강사 일정변경 제안 보장 hold 가 귀속된 회차 id(EnrollmentRound). null 이면 외부/± hold. raw Long(역참조 회피). */
    private Long proposalRoundId;

    /** 제안 hold 자동 만료 시각(proposalTtlHours). proposal hold 일 때만 의미. */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    /** 강사 일정변경 제안 보장 hold 인가 — 회차 귀속(외부/±와 구분). 표시("제안중")·만료 sweep·해제용. */
    public boolean isProposal() {
        return proposalRoundId != null;
    }

    /** 외부 출처 점유인가 — 메모 동반 외부예약(±빠른조정·제안 hold 와 구분). 표시/집계용. */
    public boolean isExternal() {
        return !isProposal() && memo != null && !memo.isBlank();
    }
}
