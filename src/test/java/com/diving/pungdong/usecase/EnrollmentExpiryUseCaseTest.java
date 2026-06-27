package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseKind;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentExpiryService;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌석 lock 자동 만료 use-case — {@link EnrollmentExpiryService#sweepExpired}. {@code @DisplayName} = 사양.
 *
 * <p>TTL 은 {@code TestSiteSettingsConfig} 고정값 = 신청(PENDING) 24h / 결제대기(PAYMENT_PENDING) 12h. 회차를
 * 과거 타임스탬프로 직접 저장 → sweep → 만료(CANCELLED)·슬롯 해제 검증. 스케줄러는 @Profile("!test") 라 서비스 직접 호출.
 */
@SpringBootTest
@ActiveProfiles("test")
class EnrollmentExpiryUseCaseTest {

    @Autowired EnrollmentExpiryService expiryService;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired EnrollmentRoundJpaRepo roundRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;

    @AfterEach
    void clean() {
        enrollmentRepo.deleteAll(); // cascade → rounds
        sessionRepo.deleteAll();
        courseRepo.deleteAll();
        accountRepo.deleteAll();
    }

    private EnrollmentRound persist(String tag, EnrollmentStatus status,
                                    LocalDateTime createdAt, LocalDateTime respondedAt, boolean withSession) {
        Account ins = accountRepo.save(Account.builder().email("ins-" + tag + "@pd.com").password("x")
                .nickName("강사" + tag).roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Account stu = accountRepo.save(Account.builder().email("stu-" + tag + "@pd.com").password("x")
                .nickName("학생" + tag).roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Course c = courseRepo.save(Course.builder().instructor(ins).title("과정" + tag)
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(1).price(100000).status(CourseStatus.OPEN).createdAt(LocalDateTime.now()).build());
        AvailabilitySession sess = withSession ? sessionRepo.save(AvailabilitySession.builder()
                .instructor(ins).date(LocalDate.now().plusWeeks(1))
                .startTime(LocalTime.of(14, 0)).endTime(LocalTime.of(17, 0))
                .venueRefId("CUSTOM:1").createdAt(LocalDateTime.now()).build()) : null;
        Enrollment e = Enrollment.builder().student(stu).course(c).tuitionSnapshot(100000).createdAt(createdAt).build();
        EnrollmentRound r = EnrollmentRound.builder()
                .roundIndex(1).roundKind(RoundKind.REGULAR).availabilitySession(sess)
                .venueRefId("CUSTOM:1").date(LocalDate.now().plusWeeks(1))
                .blockStart(LocalTime.of(14, 0)).blockEnd(LocalTime.of(17, 0))
                .status(status).createdAt(createdAt).respondedAt(respondedAt).build();
        e.addRound(r);
        enrollmentRepo.save(e);
        return r;
    }

    @Test
    @DisplayName("T1 신청(PENDING) 24h 무응답이면 자동 만료(CANCELLED) + 빈 일정 삭제로 좌석 해제")
    void pendingExpiresAndFreesSlot() {
        EnrollmentRound r = persist("t1", EnrollmentStatus.PENDING,
                LocalDateTime.now().minusHours(25), null, true);

        int n = expiryService.sweepExpired(LocalDateTime.now());

        assertThat(n).isEqualTo(1);
        assertThat(roundRepo.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(sessionRepo.findAll()).isEmpty(); // 점유 0 → 빈 일정 삭제(좌석 해제)
    }

    @Test
    @DisplayName("T2 결제 대기(PAYMENT_PENDING) 12h 미결제면 자동 만료(CANCELLED)")
    void paymentPendingExpires() {
        EnrollmentRound r = persist("t2", EnrollmentStatus.PAYMENT_PENDING,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusHours(13), false);

        int n = expiryService.sweepExpired(LocalDateTime.now());

        assertThat(n).isEqualTo(1);
        assertThat(roundRepo.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("T3 아직 TTL 안 지난 신청은 만료되지 않는다(PENDING 유지)")
    void freshPendingSurvives() {
        EnrollmentRound r = persist("t3", EnrollmentStatus.PENDING,
                LocalDateTime.now().minusHours(1), null, false);

        int n = expiryService.sweepExpired(LocalDateTime.now());

        assertThat(n).isZero();
        assertThat(roundRepo.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.PENDING);
    }
}
