package com.diving.pungdong.demo;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilityCoverage;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.enrollment.InstructorEnrollmentService;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🧪 [DEMO] PG 심사용 임시 지원 — <b>운영 코드 아님</b>. 심사가 끝나면 이 패키지(또는 이 커밋)만 통째로
 * drop/revert 하면 됨. 단일 토글 {@code pungdong.demo.enabled=true} 로만 켜진다(기본 false). {@code @Profile("!test")}
 * 라 테스트 컨텍스트엔 안 뜬다.
 *
 * <p>하는 일(켜졌을 때):
 * <ul>
 *   <li><b>부팅 시</b> — 코스를 보유한 모든 강사 계정을 <b>프리다이빙 강사</b>(InstructorApplication FREEDIVING
 *       APPROVED + {@code Role.INSTRUCTOR})로 만들고, <b>가용시간을 전체 개방</b>(오늘~+8주 매일 00:00–23:59 —
 *       enrollment 옵션 lookahead 와 동일). 멱등(이미 있으면 건너뜀, 기존 coverage 는 안 건드림).</li>
 *   <li><b>주기적으로(2초 폴링)</b> — 들어온 신청(PENDING)을 <b>~3초 뒤 자동 수락</b>(= 강사 수락, PAYMENT_PENDING).
 *       그래야 심사자가 신청 → 결제까지 사람 개입 없이 진행 가능. 실제 강사 수락 로직({@link InstructorEnrollmentService#accept})을
 *       그대로 호출(정원 재검증 포함).</li>
 * </ul>
 *
 * <p>전제: 학생 신청 자체는 Sanity siteSettings 의 {@code launched} 게이트가 true 여야 가능(BE config 아님).
 * 코스가 OPEN + 위치/이용권을 가져야 신청 옵션이 생긴다(기존 테스트 데이터 가정).
 */
@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(name = "pungdong.demo.enabled", havingValue = "true")
public class DemoPgReviewSupport implements ApplicationRunner {

    private static final String FREEDIVING = "FREEDIVING";
    private static final int LOOKAHEAD_WEEKS = 8;   // enrollment/EnrollmentOptionsService.LOOKAHEAD_WEEKS 와 동일
    private static final long AUTO_ACCEPT_DELAY_SECONDS = 3;

    private final CourseJpaRepo courseRepo;
    private final AccountJpaRepo accountRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final AvailabilityCoverageJpaRepo coverageRepo;
    private final EnrollmentJpaRepo enrollmentRepo;
    private final InstructorEnrollmentService instructorEnrollmentService;
    private final TransactionTemplate tx;

    public DemoPgReviewSupport(CourseJpaRepo courseRepo, AccountJpaRepo accountRepo,
                               InstructorApplicationJpaRepo applicationRepo,
                               AvailabilityCoverageJpaRepo coverageRepo, EnrollmentJpaRepo enrollmentRepo,
                               InstructorEnrollmentService instructorEnrollmentService,
                               PlatformTransactionManager txManager) {
        this.courseRepo = courseRepo;
        this.accountRepo = accountRepo;
        this.applicationRepo = applicationRepo;
        this.coverageRepo = coverageRepo;
        this.enrollmentRepo = enrollmentRepo;
        this.instructorEnrollmentService = instructorEnrollmentService;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 부팅 시 1회 — 코스 보유 강사 전부 프리다이빙 강사 + 가용시간 전체 개방. */
    @Override
    public void run(ApplicationArguments args) {
        int[] n = {0};
        tx.executeWithoutResult(s -> {
            for (Account ins : courseOwningInstructors()) {
                ensureFreedivingInstructor(ins);
                openFullAvailability(ins);
                n[0]++;
            }
        });
        log.warn("[DEMO] PG 심사 지원 ON — 강사 {}명 프리다이빙+가용시간 전체 개방. 신청은 {}초 뒤 자동 수락.",
                n[0], AUTO_ACCEPT_DELAY_SECONDS);
    }

    /** 2초 폴링 — 생성 후 ~3초 지난 PENDING 신청을 자동 수락. accept 는 각자 트랜잭션이라 한 건 실패가 배치를 안 막음. */
    @Scheduled(fixedDelay = 2000)
    public void autoAccept() {
        List<Object[]> due = tx.execute(s -> {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(AUTO_ACCEPT_DELAY_SECONDS);
            List<Object[]> list = new ArrayList<>();
            for (Account ins : courseOwningInstructors()) {
                for (Enrollment e : enrollmentRepo.findByCourse_Instructor_IdAndStatusOrderByIdDesc(
                        ins.getId(), EnrollmentStatus.PENDING)) {
                    if (e.getCreatedAt() == null || e.getCreatedAt().isBefore(cutoff)) {
                        list.add(new Object[]{ins, e.getId()}); // ins 는 detached 후 getId() 만 쓰임
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

    /* ─── helpers ─── */

    /** 코스를 보유한 강사 계정(중복 제거). 트랜잭션 안에서 호출(LAZY instructor). */
    private List<Account> courseOwningInstructors() {
        Map<Long, Account> byId = new LinkedHashMap<>();
        for (Course c : courseRepo.findAll()) {
            Account ins = c.getInstructor();
            if (ins != null) {
                byId.putIfAbsent(ins.getId(), ins);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private void ensureFreedivingInstructor(Account ins) {
        if (!applicationRepo.existsByAccountIdAndDisciplineCode(ins.getId(), FREEDIVING)) {
            applicationRepo.save(InstructorApplication.builder()
                    .account(ins).disciplineCode(FREEDIVING)
                    .status(InstructorApplicationStatus.APPROVED)
                    .submittedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build());
        }
        if (ins.getRoles() != null && ins.getRoles().add(Role.INSTRUCTOR)) {
            ins.setIsCertified(true);
            accountRepo.save(ins);
        }
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
}
