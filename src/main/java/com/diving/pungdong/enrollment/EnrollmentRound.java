package com.diving.pungdong.enrollment;

import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.RoundKind;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 회차 — 한 수강(Enrollment)의 진행 단위 1건. 예약·일정·결제·완료가 모두 여기에 묶인다(슬롯 1개).
 *
 * <p>다회차 재설계(붕어빵): Course=틀(CourseRound[] 정의), Enrollment=수강 컨테이너, EnrollmentRound=회차 sub.
 * 옛 단일회차 모델의 슬롯·상태·부대비용 필드가 여기로 내려왔다. <b>수강료는 부모 Enrollment 에 1번</b> 박제하고,
 * 입장료·장비·추가세션비(부대비용)만 회차별 스냅샷. 회차 설명은 {@link #courseRound} 에서 라이브 표시(비민감).
 *
 * <p>{@link AvailabilitySession}(위치·시간블록·정원)에 붙는다(같은 (위치,블록)이면 여러 수강생 회차가 join).
 * 거절/취소로 점유 0 이면 session 끊김 — 단 스냅샷(date/위치/블록/가격/사유)은 보존(CS·환불 증빙).
 * 완료는 {@code done = status==CONFIRMED && doneAt!=null}(별도 enum 없음).
 */
@Entity
@Table(name = "enrollment_round")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EnrollmentRound {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id")
    private Enrollment enrollment;

    /** 코스 회차 정의 — 설명·위치·이용권·가격 근거. EXTRA 회차는 코스 EXTRA 정의(perSessionPrice/freeCount)를 가리킴. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_round_id")
    private CourseRound courseRound;

    /** 정규 회차 번호(1..N). EXTRA 는 null. 정렬·표시용 비정규화. */
    private Integer roundIndex;

    @Enumerated(EnumType.STRING)
    private RoundKind roundKind;

    /** 위치·시간블록·정원 단위. 점유 0 이면 끊김(이력 스냅샷은 보존). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private AvailabilitySession availabilitySession;

    /** 슬롯 스냅샷(일정 잡을 때 박제). */
    private String venueRefId;
    private LocalDate date;
    private LocalTime blockStart;
    private LocalTime blockEnd;
    private String ticketRef;

    /** 부대비용 스냅샷(원) — 수강료는 Enrollment. 입장료 + 장비합 + 추가세션비(EXTRA). */
    private int entrySnapshot;
    private int equipmentSnapshot;
    private int extraSnapshot;

    @Enumerated(EnumType.STRING)
    private EnrollmentStatus status;

    /** 거절 사유(REJECTED — 1회차/진입 한정). */
    private String rejectionReason;

    /** 완료 시점. done = status==CONFIRMED && doneAt!=null. */
    private OffsetDateTime doneAt;

    private OffsetDateTime createdAt;
    /** 강사 수락/거절/일정변경요청 시점. */
    private OffsetDateTime respondedAt;

    /**
     * 강사 일정변경요청 — 위치 고정, <b>완전한 대안 슬롯(날짜+이용권+블록)</b> 목록(서버 검증). 비어있지 않으면
     * 학생이 그 중 하나를 골라야(pick) 한다(= 강사 사전 수락 → 고르면 바로 PAYMENT_PENDING). PENDING 에서만 의미.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "enrollment_round_proposed_slot", joinColumns = @JoinColumn(name = "round_id"))
    @OrderColumn(name = "slot_order")
    @Builder.Default
    private List<ProposedSlot> proposedSlots = new ArrayList<>();

    /**
     * 슬롯 변경 이력 — 일정 수정/제안 선택으로 슬롯이 바뀔 때 <b>변경 전</b> 슬롯을 쌓는다(취소 아님, 회차는 유지).
     * CS 추적용("원래 X였다가 Y로").
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "enrollment_round_slot_history", joinColumns = @JoinColumn(name = "round_id"))
    @OrderColumn(name = "history_order")
    @Builder.Default
    private List<PastSlot> slotHistory = new ArrayList<>();

    @OneToMany(mappedBy = "enrollmentRound", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id asc")
    @Builder.Default
    private List<EnrollmentRoundEquipment> equipment = new ArrayList<>();

    public void addEquipment(EnrollmentRoundEquipment e) {
        e.setEnrollmentRound(this);
        this.equipment.add(e);
    }

    /** 슬롯 바꾸기 전, 현재 슬롯을 이력에 박제(취소 아님 — 일정 수정 추적). */
    public void archiveCurrentSlot(java.time.OffsetDateTime changedAt) {
        slotHistory.add(PastSlot.builder()
                .date(date).venueRefId(venueRefId).ticketRef(ticketRef)
                .blockStart(blockStart).blockEnd(blockEnd).changedAt(changedAt).build());
    }

    /** 회차 부대비용 합 = 입장료 + 장비 + 추가세션비. (수강료는 부모 Enrollment.) */
    public int extrasTotal() {
        return entrySnapshot + equipmentSnapshot + extraSnapshot;
    }

    /** 첫 만남(정규 1회차) — 수강료가 이 회차 결제에 전액 청구된다. */
    public boolean isFirstMeeting() {
        return roundKind == RoundKind.REGULAR && roundIndex != null && roundIndex == 1;
    }

    /** 이 회차 결제 추정 총액 = (첫 만남이면 수강료) + 부대비용. */
    public int chargeTotal() {
        int tuition = isFirstMeeting() && enrollment != null ? enrollment.getTuitionSnapshot() : 0;
        return tuition + extrasTotal();
    }

    public boolean isDone() {
        return status == EnrollmentStatus.CONFIRMED && doneAt != null;
    }

    /** 강사가 일정변경요청(대안 슬롯 제안)을 보냈고 학생이 아직 안 고른 상태. */
    public boolean hasRescheduleOffer() {
        return status == EnrollmentStatus.PENDING && proposedSlots != null && !proposedSlots.isEmpty();
    }
}
