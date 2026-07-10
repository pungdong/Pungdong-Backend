package com.diving.pungdong.demo;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.enrollment.InstructorEnrollmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 🧪 [DEMO·DROP 대상] PG 심사 동안만 — 들어온 신청(PENDING)을 ~3초 뒤 <b>자동 수락</b>한다. 정상 흐름은
 * "학생 신청 → 강사 수동 수락 → 결제"라 심사자 혼자선 결제까지 못 가므로, 강사 수락만 자동화한다.
 *
 * <p><b>이 클래스(+토글)가 심사 후 삭제 대상</b>이다(가용시간 시딩 {@link SeededCourseAvailabilitySeeder} 은
 * 유지 — 별도 토글). 실제 강사 수락 로직({@link InstructorEnrollmentService#accept})을 그대로 호출하므로 정원
 * 재검증 등 동작은 동일. 각 accept 는 자기 트랜잭션 — 한 건 실패가 배치를 안 막는다.
 *
 * <p><b>범위 = 시드 강의 강사</b>(테스트 강의 제외). {@code pungdong.demo.auto-accept=true} 일 때만. {@code @Profile("!test")}.
 */
@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(name = "pungdong.demo.auto-accept", havingValue = "true")
public class DemoAutoAcceptScheduler {

    private static final long DELAY_SECONDS = 3;

    private final CourseJpaRepo courseRepo;
    private final EnrollmentRoundJpaRepo roundRepo;
    private final InstructorEnrollmentService instructorEnrollmentService;
    private final TransactionTemplate tx;

    public DemoAutoAcceptScheduler(CourseJpaRepo courseRepo, EnrollmentRoundJpaRepo roundRepo,
                                   InstructorEnrollmentService instructorEnrollmentService,
                                   PlatformTransactionManager txManager) {
        this.courseRepo = courseRepo;
        this.roundRepo = roundRepo;
        this.instructorEnrollmentService = instructorEnrollmentService;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelay = 2000)
    public void autoAccept() {
        List<Object[]> due = tx.execute(s -> {
            OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(DELAY_SECONDS);
            List<Object[]> list = new ArrayList<>();
            for (Account ins : SeededCourseAvailabilitySeeder.seededCourseInstructors(courseRepo)) {
                for (EnrollmentRound r : roundRepo.findByEnrollment_Course_Instructor_IdAndStatusOrderByIdDesc(
                        ins.getId(), EnrollmentStatus.PENDING)) {
                    if (r.getCreatedAt() == null || r.getCreatedAt().isBefore(cutoff)) {
                        list.add(new Object[]{ins, r.getId()}); // ins 는 detached 후 getId() 만 쓰임
                    }
                }
            }
            return list;
        });
        if (due == null) {
            return;
        }
        for (Object[] p : due) {
            Account ins = (Account) p[0];
            Long enrollmentId = (Long) p[1];
            try {
                instructorEnrollmentService.accept(ins, enrollmentId);
                log.warn("[DEMO] 신청 {} 자동 수락(PAYMENT_PENDING)", enrollmentId);
            } catch (Exception ex) {
                log.warn("[DEMO] 신청 {} 자동 수락 건너뜀 ({})", enrollmentId, ex.toString());
            }
        }
    }
}
