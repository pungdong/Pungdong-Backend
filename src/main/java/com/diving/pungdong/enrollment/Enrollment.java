package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.course.Course;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 수강신청 1건 — 학생이 코스의 <b>첫 만남(1회차)</b>을 강사가 연 가용시간 슬롯에 신청한 것.
 * V2 디자인 booking 흐름의 산물. availability v1 이 비워둔 점유(pending/confirmed)를 실제로 채운다.
 *
 * <p><b>레이어 연결</b>: enrollment 는 {@link AvailabilitySession}(위치·시간블록·정원) 에 붙는다. 학생 첫
 * 신청이 그 (위치, 시간블록) session 을 생성하고, 같은 (위치, 블록) 신청은 그 session 에 join 한다. 자격은
 * 그 블록이 강사 coverage(예약가능시간)에 통째로 ⊆ 일 때만(부분겹침 불가). {@link #venueRefId}/{@link #blockStart}/
 * {@link #blockEnd} 는 신청 시점 스냅샷.
 *
 * <p>가격은 신청 시점 스냅샷(추정치) — 권위 금액은 강사 확정/결제 시점 재계산(결제는 후속). {@code Account}·
 * {@code Course}·{@code AvailabilitySession} 단방향 참조.
 */
@Entity
@Table(name = "enrollment")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Enrollment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Account student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    /** 신청한 회차 — v1 은 첫 만남(1회차) 고정. */
    private int roundIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private AvailabilitySession availabilitySession;

    /** 선택한 위치 토큰("CUSTOM:&lt;pk&gt;"/"OFFICIAL:&lt;sanityId&gt;"). 신청 시점 스냅샷. */
    private String venueRefId;

    /** 신청한 날짜 + venue 운영 시간블록(스냅샷). */
    private java.time.LocalDate date;
    private LocalTime blockStart;
    private LocalTime blockEnd;

    /** 선택한 이용권 식별자(입장료 daypart fee 해석용). */
    private String ticketRef;

    @Enumerated(EnumType.STRING)
    private EnrollmentStatus status;

    /** 거절 사유(REJECTED 만). */
    private String rejectionReason;

    /** 신청 시점 가격 스냅샷(추정치, 원). */
    private int tuitionSnapshot;
    private int entrySnapshot;
    private int equipmentSnapshot;

    @OneToMany(mappedBy = "enrollment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id asc")
    @Builder.Default
    private List<EnrollmentEquipment> equipment = new ArrayList<>();

    private LocalDateTime createdAt;
    /** 강사 수락/거절 시점. */
    private LocalDateTime respondedAt;

    public void addEquipment(EnrollmentEquipment e) {
        e.setEnrollment(this);
        this.equipment.add(e);
    }

    /** 추정 총액 = 수강료 + 입장료 + 장비. */
    public int estimatedTotal() {
        return tuitionSnapshot + entrySnapshot + equipmentSnapshot;
    }
}
