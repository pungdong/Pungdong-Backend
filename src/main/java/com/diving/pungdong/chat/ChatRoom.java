package com.diving.pungdong.chat;

import lombok.*;
import org.springframework.data.domain.Persistable;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 세션 단체 채팅방 — 강사 가용시간 슬롯({@code AvailabilitySession}) 하나당 1개.
 *
 * <p><b>PK 가 세션 id 다</b>(assigned — {@code @GeneratedValue} 없음). 방 : 세션 = 1 : 1 이고, 방 행이
 * 아직 없어도 식별자를 알 수 있어야 하기 때문이다 — 회차 카드 목록이 {@code roomId} 를 채우려고
 * <b>읽기 중에 행을 만들 필요가 없다</b>(방은 처음 열 때 생긴다).
 *
 * <p><b>{@code availability_session} 으로의 FK 가 없다.</b> {@code availability.SessionCleaner} 는 점유가
 * 0 이 되면(결제자 전원 취소·환불) 세션 행을 <b>물리 삭제</b>하는데, FK 가 있으면 그 삭제가 제약 위반으로
 * 실패해 환불 플로우가 깨진다. FK 를 없애면 SessionCleaner 를 건드릴 필요가 없고 방·메시지는 CS·감사용으로
 * 남는다. 세션 재사용 id 로 인한 충돌은 MySQL 8.4 가 AUTO_INCREMENT 를 영속하고 삭제 id 를 재사용하지
 * 않으므로 발생하지 않는다(V28 상단 주석).
 *
 * <p><b>상태(ACTIVE/CLOSED)는 저장하지 않는다</b> — 세션 생존 + {@link #closesAt} 경과로 읽을 때 파생한다
 * ({@link ChatRoomState}). 저장하면 "세션은 지워졌는데 방은 ACTIVE" 같은 어긋남을 배치로 따라다녀야 한다.
 *
 * <p>슬롯 정보는 <b>스냅샷</b>이다(헤더 표시용). 세션이 사라져도 "AIDA2 2회차 · 12/10 수" 가 깨지지 않아야
 * 하고, {@code EnrollmentRound} 도 같은 이유로 슬롯 스냅샷을 들고 있다. 세션이 살아 있으면 조회 때 갱신된다.
 */
@Entity
@Table(name = "chat_room")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ChatRoom implements Persistable<Long> {

    /** = {@code AvailabilitySession.id}. 서비스가 직접 넣는다(자동 생성 아님). */
    @Id
    private Long id;

    /** 슬롯 소유자 스냅샷 — 세션이 사라진 뒤에도 강사 판정이 되어야 한다. FK 없음. */
    @Column(name = "instructor_id", nullable = false)
    private Long instructorId;

    @Column(name = "course_title")
    private String courseTitle;

    @Column(name = "round_index")
    private Integer roundIndex;

    @Column(name = "venue_name")
    private String venueName;

    /** 슬롯 civil 스냅샷 — 오프셋 없는 벽시계. FE 변환 금지(docs/architecture/time-handling.md §1). */
    @Column(name = "date")
    private LocalDate date;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    /**
     * 마감 instant(UTC) = (date, endTime) 을 KST 로 해석한 시각 + 24h.
     *
     * <p>civil→instant 라 존이 필요한데 {@code venue.timeZone} 이 아직 없어 KST 고정이다
     * ({@code payment.RefundService} 와 같은 선례). 응답에는 이 절대시각 대신 <b>잔여 초</b>를 내린다 —
     * 기기 시계가 틀어져도 카운트다운이 안 밀리게(= {@code paymentExpiresInSeconds} 규칙).
     */
    @Column(name = "closes_at", nullable = false)
    private OffsetDateTime closesAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /**
     * assigned PK 라 Spring Data 가 {@code isNew()} 를 id 유무로 판단하면 항상 false 가 되어 신규 저장이
     * {@code merge}(불필요한 SELECT + 경우에 따라 조용한 no-op)로 나간다. 삽입 의도를 명시적으로 들고 있는다.
     */
    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.createdAt = now;
        this.updatedAt = now;
        this.isNew = false;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PostLoad
    void postLoad() {
        this.isNew = false;
    }

    /** 마감이 지났는가. 세션 소멸 여부와 함께 {@link ChatRoomState} 를 만든다. */
    public boolean isClosedAt(OffsetDateTime now) {
        return !now.isBefore(closesAt);
    }
}
