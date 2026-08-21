package com.diving.pungdong.concurrency;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilityCoverage;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseKind;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.course.RoundVenue;
import com.diving.pungdong.course.RoundVenueTicket;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentService;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.enrollment.dto.EnrollmentCreateRequest;
import com.diving.pungdong.identityverification.IdentityVerification;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.identityverification.IdentityVerificationStatus;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.support.InstructorApprovalFixture;
import com.diving.pungdong.venue.DaypartKind;
import com.diving.pungdong.venue.TimeMode;
import com.diving.pungdong.venue.Venue;
import com.diving.pungdong.venue.VenueDaypart;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueScope;
import com.diving.pungdong.venue.VenueTicket;
import com.diving.pungdong.venue.VenueTimeBlock;
import com.diving.pungdong.venue.VenueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-4 좌석 overbooking 동시성 — 정원이 꽉 차기 직전(1자리 남음)에 두 학생이 <b>같은 슬롯을 동시에</b> 신청해도
 * 점유가 정원을 넘지 않는지를 <b>실 MySQL</b>에서 검증한다. 방어는 {@code EnrollmentService.requireSeat} 가
 * 좌석 count 직전 세션 행을 {@code SELECT ... FOR UPDATE}({@code AvailabilitySessionJpaRepo.lockById})로 잡아
 * count+insert 를 직렬화하는 것 — H2 는 이 비관 락을 제대로 재현 못 해(락 타임아웃) 이 방어를 검증할 수 없었다.
 *
 * <p>구성: 정원 2인 슬롯에 seed 학생 1명이 먼저 점유(occupied=1) → 두 학생이 동시에 같은 슬롯에 join 신청.
 * 락이 없으면 둘 다 "1 &lt; 2" 를 읽고 각자 insert → occupied=3(overbooking). 락이 있으면 직렬화되어 한 명만
 * 들어가고(occupied=2=정원) 다른 한 명은 만석(400)으로 거절된다.
 */
class SeatOverbookingConcurrencyTest extends MySqlConcurrencyTestBase {

    @Autowired EnrollmentService enrollmentService;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired AvailabilityCoverageJpaRepo coverageRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired EnrollmentRoundJpaRepo roundRepo;
    @Autowired IdentityVerificationJpaRepo identityVerificationRepo;
    @Autowired com.diving.pungdong.notification.UserNotificationJpaRepo userNotificationRepo;

    private static final LocalDate D1 = LocalDate.now().plusWeeks(1);
    private static final LocalTime B_START = LocalTime.of(14, 0);
    private static final LocalTime B_END = LocalTime.of(17, 0);
    private static final int CAP = 2;

    @AfterEach
    void clean() {
        userNotificationRepo.deleteAll();
        enrollmentRepo.deleteAll();
        sessionRepo.deleteAll();
        coverageRepo.deleteAll();
        courseRepo.deleteAll();
        venueRepo.deleteAll();
        applicationRepo.deleteAll();
        identityVerificationRepo.deleteAll();
        accountRepo.deleteAll();
    }

    @Test
    @DisplayName("H-4 1자리 남은 슬롯에 두 학생이 동시 신청 → 정확히 1명만 성사, 점유는 정원 2를 안 넘는다 (overbooking 없음)")
    void concurrentJoinDoesNotOverbook() throws Exception {
        Account instructor = verifiedInstructor();
        Object[] fx = setup(instructor);
        Course course = (Course) fx[0];
        String venueRef = (String) fx[1];
        String ticketRef = (String) fx[2];

        // seed — 세션을 만들고 1자리 채운다(정원 2 중 1 점유). 이제 남은 자리는 1.
        Account seed = verifiedStudent("seed@pd.com", "씨드");
        enrollmentService.submit(seed, req(course.getId(), venueRef, ticketRef));
        Long sessionId = sessionRepo.findAll().get(0).getId();
        assertThat(roundRepo.countByAvailabilitySessionIdAndStatusIn(sessionId, EnrollmentStatus.ACTIVE)).isEqualTo(1);

        // 두 학생이 남은 1자리를 동시에 노린다.
        Account a = verifiedStudent("racer-a@pd.com", "레이서A");
        Account b = verifiedStudent("racer-b@pd.com", "레이서B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        for (Account racer : List.of(a, b)) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    startGate.await();
                    enrollmentService.submit(racer, req(course.getId(), venueRef, ticketRef));
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet(); // 만석(BadRequestException) — overbooking 을 막은 정상 거절
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("두 스레드 종료").isTrue();
        pool.shutdownNow();

        // 핵심 불변식 — 점유는 정원(2)을 절대 넘지 않는다.
        int occupied = roundRepo.countByAvailabilitySessionIdAndStatusIn(sessionId, EnrollmentStatus.ACTIVE);
        assertThat(occupied).as("최종 점유 = 정원, overbooking 없음").isEqualTo(CAP);
        assertThat(succeeded.get()).as("남은 1자리에 성사된 신청은 정확히 1건").isEqualTo(1);
        assertThat(rejected.get()).as("나머지 1건은 만석으로 거절").isEqualTo(1);
    }

    @Test
    @DisplayName("H-4b 정원 1 브랜뉴 슬롯에 8명이 동시에 처음 신청 → 세션 1개만 생성·정확히 1명 성사 (생성 경합에도 overbooking 없음)")
    void concurrentCreateSameNewSlotDoesNotOverbook() throws Exception {
        Account instructor = verifiedInstructor("h4b-ins@pd.com", "H4b강사", 1); // 정원 1
        Object[] fx = setup(instructor);
        Course course = (Course) fx[0];
        String venueRef = (String) fx[1];
        String ticketRef = (String) fx[2];

        int n = 8; // 스레드 수는 무관 — 세션행 FOR UPDATE / 자연키 UNIQUE 가 몇이든 직렬화한다. 8이면 경합 재현 충분.
        List<Account> racers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            racers.add(verifiedStudent("h4b-racer-" + i + "@pd.com", "레이서" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (Account racer : racers) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    startGate.await();
                    enrollmentService.submit(racer, req(course.getId(), venueRef, ticketRef));
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet(); // 만석(BadRequest) 또는 세션 생성 유니크 위반 — 어느 쪽이든 좌석 못 얻음
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("모든 스레드 종료").isTrue();
        pool.shutdownNow();

        // 세션은 자연키 UNIQUE(V12)로 최대 1개만 생성된다(동시 생성 경합 차단).
        assertThat(sessionRepo.findAll()).as("생성된 세션 1개").hasSize(1);
        Long sessionId = sessionRepo.findAll().get(0).getId();
        // 핵심 불변식 — 정원 1 을 절대 안 넘는다.
        assertThat(roundRepo.countByAvailabilitySessionIdAndStatusIn(sessionId, EnrollmentStatus.ACTIVE))
                .as("점유 = 1, overbooking 없음").isEqualTo(1);
        assertThat(succeeded.get()).as("성사된 신청은 정확히 1건").isEqualTo(1);
        assertThat(failed.get()).as("나머지는 전부 실패(좌석 못 얻음)").isEqualTo(n - 1);
    }

    /* ─── fixtures (EnrollmentUseCaseTest 에서 발췌 — 실 submit 게이트를 통과하는 최소 세트) ─── */

    private Account verifiedStudent(String email, String nick) {
        Account a = accountRepo.save(Account.builder().email(email).password("x").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        identityVerificationRepo.save(IdentityVerification.builder()
                .account(a).status(IdentityVerificationStatus.VERIFIED)
                .verifiedAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        return a;
    }

    private Account verifiedInstructor() {
        return verifiedInstructor("h4-ins@pd.com", "H4강사", CAP);
    }

    private Account verifiedInstructor(String email, String nick, int cap) {
        Account ins = verifiedStudent(email, nick);
        ins.setDefaultCapacity(cap);
        accountRepo.save(ins);
        applicationRepo.save(InstructorApplication.builder()
                .account(ins).disciplineCode("FREEDIVING").status(InstructorApplicationStatus.SUBMITTED)
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC)).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        // 학생이 신청하려면 그 종목의 **승인**이 필요하다(course.InstructorApprovalPolicy, 2026-08-22).
        // 위 SUBMITTED 는 가용시간 게이트("신청 보유")를 통과시키려는 것이고, 그것만으로는 부족하다 —
        // 공유 픽스처로 승격해 다른 테스트와 같은 기준을 쓴다(각자 심으면 한쪽만 상태를 잘못 넣는다).
        InstructorApprovalFixture.approveFreediving(applicationRepo, ins);
        return ins;
    }

    /** 강사·venue·course + coverage 09–18. 반환 [course, venueRef, ticketRef]. */
    private Object[] setup(Account instructor) {
        Venue venue = saveVenue(instructor);
        String venueRef = VenueScope.token(VenueScope.CUSTOM, String.valueOf(venue.getId()));
        String ticketRef = venue.getTickets().get(0).getRef();
        Course course = saveCourse(instructor, venueRef, ticketRef);
        coverageRepo.save(AvailabilityCoverage.builder()
                .instructor(instructor).date(D1).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build());
        return new Object[]{course, venueRef, ticketRef};
    }

    private Venue saveVenue(Account owner) {
        VenueDaypart weekday = VenueDaypart.builder().kind(DaypartKind.WEEKDAY).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        weekday.addTimeBlock(VenueTimeBlock.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).sortOrder(0).build());
        weekday.addTimeBlock(VenueTimeBlock.builder().startTime(B_START).endTime(B_END).sortOrder(1).build());
        VenueDaypart weekend = VenueDaypart.builder().kind(DaypartKind.WEEKEND).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        weekend.addTimeBlock(VenueTimeBlock.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).sortOrder(0).build());
        weekend.addTimeBlock(VenueTimeBlock.builder().startTime(B_START).endTime(B_END).sortOrder(1).build());
        VenueTicket ticket = VenueTicket.builder().name("일반권").sortOrder(0)
                .disciplineCodes(new java.util.LinkedHashSet<>(Set.of("FREEDIVING"))).build();
        ticket.addDaypart(weekday);
        ticket.addDaypart(weekend);
        Venue venue = Venue.builder().owner(owner).name("잠실 잠수풀장").type(VenueType.SWIMMING_POOL)
                .address("서울 송파구").lockedDisciplineCode("FREEDIVING").createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        venue.addTicket(ticket);
        return venueRepo.save(venue);
    }

    private Course saveCourse(Account instructor, String venueRef, String ticketRef) {
        Course course = Course.builder().instructor(instructor).title("AIDA2 프리다이빙 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(1).price(350000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        CourseRound round = CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build();
        RoundVenue rv = RoundVenue.builder().venueRefId(venueRef).sortOrder(0).build();
        rv.addTicket(RoundVenueTicket.builder().ticketRef(ticketRef).daypart(DaypartKind.WEEKDAY).sortOrder(0).build());
        round.addVenue(rv);
        course.addRound(round);
        return courseRepo.save(course);
    }

    private EnrollmentCreateRequest req(Long courseId, String venueRef, String ticketRef) {
        return EnrollmentCreateRequest.builder()
                .courseId(courseId).date(D1).venueRefId(venueRef).ticketRef(ticketRef)
                .blockStart(B_START).blockEnd(B_END).equipmentRefs(List.of()).build();
    }
}
