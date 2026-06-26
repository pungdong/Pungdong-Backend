package com.diving.pungdong.demo;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilityCoverage;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시드(seeded) 강의가 <b>신청 가능</b>해지도록, 그 강의를 가진 강사들에게 가용시간을 전체 개방한다. 시드 강의는
 * 텍스트까지 공들여 만든 데모 상품(브라우즈에 노출, Sanity {@code showSeededCourses} 토글로 표시 제어)인데
 * 강사 가용시간이 없으면 신청 옵션(교집합 슬롯)이 안 생긴다 — 그 빈칸을 채운다.
 *
 * <p><b>유지 성격</b>(자동수락 {@link DemoAutoAcceptScheduler} 과 분리). coverage 는 한 번 쓰면 DB 에 남으므로
 * 토글을 한 번 켜 부팅하면 끝. 멱등 — 이미 coverage 가 있는 날은 안 건드린다(강사 본인이 연 일정 보호).
 *
 * <p><b>범위 = {@code course.seeded == true} 강사만</b> — 테스트로 만든 비-시드 강의(강사1 등)는 제외.
 * 시드 강사는 이미 강사 등록(InstructorApplication)·종목을 가지므로 가용시간만 연다(역할/종목 안 건드림).
 *
 * <p>{@code pungdong.demo.seed-availability=true} 일 때만. {@code @Profile("!test")}.
 */
@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(name = "pungdong.demo.seed-availability", havingValue = "true")
public class SeededCourseAvailabilitySeeder implements ApplicationRunner {

    private static final int LOOKAHEAD_WEEKS = 8; // enrollment/EnrollmentOptionsService.LOOKAHEAD_WEEKS 와 동일

    private final CourseJpaRepo courseRepo;
    private final AvailabilityCoverageJpaRepo coverageRepo;
    private final TransactionTemplate tx;

    public SeededCourseAvailabilitySeeder(CourseJpaRepo courseRepo, AvailabilityCoverageJpaRepo coverageRepo,
                                          PlatformTransactionManager txManager) {
        this.courseRepo = courseRepo;
        this.coverageRepo = coverageRepo;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        int[] n = {0};
        tx.executeWithoutResult(s -> {
            for (Account ins : seededCourseInstructors(courseRepo)) {
                openFullAvailability(ins);
                n[0]++;
            }
        });
        log.warn("[DEMO] 시드 강의 강사 {}명 가용시간 전체 개방(오늘~+{}주). 시드 강의가 신청 가능해짐.",
                n[0], LOOKAHEAD_WEEKS);
    }

    /** 오늘~+8주 매일 coverage 가 없으면 00:00–23:59 전체 개방(기존 coverage 는 안 건드림). */
    private void openFullAvailability(Account ins) {
        LocalDate end = LocalDate.now().plusWeeks(LOOKAHEAD_WEEKS);
        for (LocalDate d = LocalDate.now(); !d.isAfter(end); d = d.plusDays(1)) {
            if (coverageRepo.findByInstructorIdAndDate(ins.getId(), d).isEmpty()) {
                coverageRepo.save(AvailabilityCoverage.builder()
                        .instructor(ins).date(d)
                        .startTime(LocalTime.MIN).endTime(LocalTime.of(23, 59)).build());
            }
        }
    }

    /** seeded=true 강의를 가진 강사 계정(중복 제거). 트랜잭션 안에서 호출(LAZY instructor). 두 데모 빈 공용. */
    static List<Account> seededCourseInstructors(CourseJpaRepo courseRepo) {
        Map<Long, Account> byId = new LinkedHashMap<>();
        for (Course c : courseRepo.findAll()) {
            if (c.isSeeded() && c.getInstructor() != null) {
                byId.putIfAbsent(c.getInstructor().getId(), c.getInstructor());
            }
        }
        return new java.util.ArrayList<>(byId.values());
    }
}
