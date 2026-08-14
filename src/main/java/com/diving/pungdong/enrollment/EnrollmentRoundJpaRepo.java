package com.diving.pungdong.enrollment;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.util.Collection;
import java.util.List;

/**
 * 회차(EnrollmentRound) 레포 — 슬롯·상태가 회차로 내려오면서 옛 {@code EnrollmentJpaRepo} 의 session/status 집계
 * 쿼리가 여기로 이동했다. 정원 집계·캘린더 점유·강사 목록은 모두 회차 단위로 센다.
 */
public interface EnrollmentRoundJpaRepo extends JpaRepository<EnrollmentRound, Long> {

    /** 한 일정의 상태 집합에 드는 회차 수 — 점유(결제대기+확정, {@link EnrollmentStatus#OCCUPYING}) 합산용. */
    int countByAvailabilitySessionIdAndStatusIn(Long sessionId, Collection<EnrollmentStatus> statuses);

    /**
     * 만석 판정용 <b>점유 회차 잠금 조회</b>(id 목록, 크기가 점유 수). 왜 plain count 가 아니라 이건가 —
     * {@code EnrollmentService.requireSeat} 는 세션 행을 {@code FOR UPDATE} 로 잡아 동시 신청을 직렬화하는데,
     * MySQL <b>REPEATABLE READ</b> 에선 그 앞서 실행된 course/coverage 조회가 트랜잭션 스냅샷을 이미 고정해버려,
     * 뒤이은 <b>plain count 는 상대가 방금 커밋한 신청을 못 본다</b>(락은 상호배제만, 가시성은 안 줌) → 두 신청이
     * 각자 "안 참"을 읽고 둘 다 통과 = overbooking(H-4). 잠금 조회는 스냅샷을 우회해 <b>최신 커밋</b>을 읽으므로
     * 세션 락 뒤의 이 count 가 상대의 신청을 본다. (세션 락으로 이미 직렬화된 구간이라 추가 락 경합은 없다.)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r.id from EnrollmentRound r "
            + "where r.availabilitySession.id = :sessionId and r.status in :statuses")
    List<Long> lockOccupyingRoundIds(@Param("sessionId") Long sessionId,
                                     @Param("statuses") Collection<EnrollmentStatus> statuses);

    /** 한 일정의 상태 집합에 드는 회차들 — 활성 조회·삭제 판정. */
    /**
     * 세션 일괄 완료용. {@code @EntityGraph} 로 수강·코스·학생을 함께 당긴다 — 완료 시 회차마다
     * 알림 좌표({@link EnrollmentRefs})를 뽑는데, LAZY 로 두면 수강생 수만큼 추가 쿼리가 나간다(N+1).
     */
    @EntityGraph(attributePaths = {"enrollment", "enrollment.course", "enrollment.student"})
    List<EnrollmentRound> findByAvailabilitySessionIdAndStatusIn(Long sessionId, Collection<EnrollmentStatus> statuses);

    /** 한 일정의 모든 회차(상태 무관) — 빈 일정 삭제 시 FK 끊기용. */
    List<EnrollmentRound> findByAvailabilitySessionId(Long sessionId);

    /** 여러 일정의 활성 회차 일괄 조회 — 캘린더 N+1 회피. */
    List<EnrollmentRound> findByAvailabilitySessionIdInAndStatusIn(Collection<Long> sessionIds,
                                                                   Collection<EnrollmentStatus> statuses);

    /** 강사 — 내 코스로 들어온 회차(상태별). enrollment.course.instructor.id 경유. */
    List<EnrollmentRound> findByEnrollment_Course_Instructor_IdAndStatusOrderByIdDesc(Long instructorId,
                                                                                      EnrollmentStatus status);

    /** 한 학생의 회차(최신순) — 주로 테스트/내부 조회. enrollment.student.id 경유. */
    List<EnrollmentRound> findByEnrollment_Student_IdOrderByIdDesc(Long studentId);

    /** 만료 스위프 — 신청(PENDING) 무응답: createdAt 이 cutoff 이전인 그 상태 회차들. */
    List<EnrollmentRound> findByStatusAndCreatedAtBefore(EnrollmentStatus status, java.time.OffsetDateTime cutoff);

    /**
     * 그 학생이 그 강의에 대해 가진 특정 상태 회차들 — <b>미결제 PENDING supersede</b>(재신청 시 옛 hold 반환)용.
     * 스코프를 (학생 × 강의 × 상태)로 좁혀 다른 강의·다른 상태를 절대 건드리지 않는다.
     */
    List<EnrollmentRound> findByEnrollment_Student_IdAndEnrollment_Course_IdAndStatus(
            Long studentId, Long courseId, EnrollmentStatus status);

    /** 만료 스위프 — 강사 결정 대기(ACCEPT_PENDING): respondedAt(결제 시각)이 cutoff 이전인 그 상태 회차들. */
    List<EnrollmentRound> findByStatusAndRespondedAtBefore(EnrollmentStatus status, java.time.OffsetDateTime cutoff);

    /** 자동 완료 스위프 — 세션 날짜가 cutoff 이전(지남)이고 아직 done 안 된 확정(CONFIRMED) 회차들. */
    List<EnrollmentRound> findByStatusAndDoneAtIsNullAndDateBefore(EnrollmentStatus status, java.time.LocalDate cutoff);

    /**
     * 금액 대사(M1) — 결제완료/확정 상태이고 respondedAt 이 cutoff 이전인 회차들. 순액==chargeTotal 검증 대상.
     * {@code enrollment} 를 함께 당긴다(chargeTotal 이 1회차 수강료를 부모에서 읽어 N+1 방지).
     */
    @EntityGraph(attributePaths = {"enrollment"})
    List<EnrollmentRound> findByStatusInAndRespondedAtBefore(Collection<EnrollmentStatus> statuses,
                                                             java.time.OffsetDateTime cutoff);
}
