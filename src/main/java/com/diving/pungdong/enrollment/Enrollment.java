package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.course.Course;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 수강 1건 — 학생이 한 코스를 신청한 <b>컨테이너(붕어빵)</b>. 회차들({@link EnrollmentRound})을 묶고 수강료를 보유한다.
 * 예약·일정·결제·완료는 모두 <b>회차 단위</b>이고, 강의(수강) 상태는 회차들에서 <b>파생</b>(저장 X)한다.
 *
 * <p>다회차 재설계(2026-06-28): 옛 단일회차 Enrollment 의 슬롯·상태·부대비용 필드는 EnrollmentRound 로 내려갔다.
 * 수강료만 신청 시점 {@code Course.price} 스냅샷으로 여기 고정(1회차 결제에 전액 청구, 환불 정산 = 수강료÷정규회차수).
 * {@code Account}·{@code Course} 단방향 참조.
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

    /** 수강료 스냅샷(신청 시점 {@code Course.price} 박제·고정). 1회차 결제에 전액, 환불은 ÷정규회차수. */
    private int tuitionSnapshot;

    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "enrollment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id asc")
    @Builder.Default
    private List<EnrollmentRound> rounds = new ArrayList<>();

    public void addRound(EnrollmentRound r) {
        r.setEnrollment(this);
        this.rounds.add(r);
    }
}
