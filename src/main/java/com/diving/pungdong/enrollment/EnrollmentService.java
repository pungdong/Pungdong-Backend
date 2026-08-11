package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilityHold;
import com.diving.pungdong.availability.AvailabilityHoldJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.availability.CoverageMerger;
import com.diving.pungdong.availability.SessionCleaner;
import com.diving.pungdong.availability.SessionOverlapGuard;
import com.diving.pungdong.availability.CoverageMerger.Span;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.dto.EnrollmentCreateRequest;
import com.diving.pungdong.enrollment.dto.EnrollmentResponse;
import com.diving.pungdong.enrollment.dto.PickSlotRequest;
import com.diving.pungdong.enrollment.dto.RoundScheduleRequest;
import com.diving.pungdong.enrollment.dto.RoundSlotInput;
import com.diving.pungdong.enrollment.dto.ScheduleHubResponse;
import com.diving.pungdong.enrollment.event.EnrollmentPartialRefundRequestedEvent;
import com.diving.pungdong.enrollment.event.EnrollmentRefundRequestedEvent;
import com.diving.pungdong.global.advice.exception.AdditionalPaymentRequiredException;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.IdentityVerificationRequiredException;
import com.diving.pungdong.global.advice.exception.PreLaunchException;
import com.diving.pungdong.global.advice.exception.ProposalExpiredException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.global.advice.exception.VenueChangeRequiresReapplyException;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.identityverification.IdentityVerificationStatus;
import com.diving.pungdong.venue.VenueRefResolver;
import com.diving.pungdong.venue.dto.VenueResponse;
import com.diving.pungdong.venue.equipment.VenueEquipmentService;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 수강신청 — 학생 측(1회차 신청/2회차+ 일정신청/취소/일정변경 선택/내목록/일정 hub). 신청·일정은 서버가 모두
 * 재검증한다: 코스 OPEN·그 회차 위치/이용권 · 블록이 venue 운영블록 · 강사 coverage 에 통째로 ⊆ · 만석 · 장비 · 가격.
 *
 * <p>다회차(붕어빵): 신청은 수강 컨테이너 {@link Enrollment} + 회차 {@link EnrollmentRound}. 1회차는 {@link #submit}
 * 이 수강을 만들고, 2회차+는 {@link #scheduleNextRound}(직전 정규회차 CONFIRMED 게이트 — done 추적 후 done 으로 강화).
 * 강사 일정변경요청(제안 날짜)을 학생이 {@link #pickDate} 로 고르면 사전 수락 → 결제 대기. API {@code {id}} = 회차 id.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnrollmentService {

    private final EnrollmentJpaRepo enrollmentRepo;
    private final EnrollmentRoundJpaRepo roundRepo;
    private final CourseJpaRepo courseRepo;
    private final AvailabilitySessionJpaRepo sessionRepo;
    private final AvailabilityCoverageJpaRepo coverageRepo;
    private final AvailabilityHoldJpaRepo holdRepo;
    private final VenueRefResolver venueRefResolver;
    private final VenueEquipmentService equipmentService;
    private final BookableSlotDeriver slotDeriver;
    private final SessionCleaner sessionCleaner;
    private final SessionOverlapGuard overlapGuard;
    private final com.diving.pungdong.global.sitesettings.SiteSettingsProvider siteSettings;
    private final IdentityVerificationJpaRepo identityVerificationRepo;
    private final ApplicationEventPublisher events; // 결제된 회차의 취소·차액 환불(payment 리스너 수신)

    /** 1회차 신청 — 수강 컨테이너 + 첫 만남 회차 생성. */
    @Transactional
    public EnrollmentResponse submit(Account student, EnrollmentCreateRequest req) {
        requireLaunched();
        requireVerified(student); // 정책: 수강신청 전 본인인증 선행(2회차+ 는 이 수강을 전제로 하니 전이적 커버)
        Course course = openCourse(req.getCourseId());
        Account instructor = requireInstructor(course);
        CourseRound round1 = firstMeetingRound(course);
        if (round1 == null) {
            throw new BadRequestException(); // 코스에 정규 회차 정의 없음
        }
        // ★ supersede — 같은 강의에 <b>미결제 1회차</b>가 이미 있으면 새 수강을 만들지 않고 그 회차의 슬롯을 갈아끼운다.
        // 안 그러면 결제 화면에서 뒤로 가 다시 신청할 때마다 수강 컨테이너와 좌석 점유가 하나씩 쌓인다(이중 점유).
        EnrollmentRound resumable = unpaidFirstRound(student, course);
        if (resumable != null) {
            return supersede(resumable, req);
        }
        Enrollment enrollment = Enrollment.builder()
                .student(student).course(course)
                .tuitionSnapshot(course.getPrice())
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        EnrollmentRound round = buildRound(instructor, round1, req, 0);
        enrollment.addRound(round);
        enrollmentRepo.save(enrollment); // cascade → round + 장비
        return EnrollmentResponse.of(round, venueName(round.getVenueRefId()), instructor.getNickName(), paymentExpiresInSeconds(round));
    }

    /**
     * 2회차+ 일정 신청 — 다음 schedulable 회차(직전 정규 CONFIRMED 게이트, 정규 다 끝나면 EXTRA)를 PENDING 으로 추가.
     * 슬롯은 1회차와 같은 재검증. EXTRA 는 freeCount 초과분만 추가세션비.
     */
    @Transactional
    public EnrollmentResponse scheduleNextRound(Account student, Long enrollmentId, RoundScheduleRequest req) {
        requireLaunched();
        Enrollment enrollment = enrollmentRepo.findById(enrollmentId).orElseThrow(ResourceNotFoundException::new);
        if (enrollment.getStudent() == null || !enrollment.getStudent().getId().equals(student.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 수강 — 존재 숨김
        }
        Course course = enrollment.getCourse();
        if (course == null || course.getStatus() != CourseStatus.OPEN) {
            throw new BadRequestException();
        }
        Account instructor = requireInstructor(course);
        // ★ supersede — 이 수강에 <b>미결제 회차</b>가 이미 있으면 그것을 갈아끼운다. RoundGate 는 활성 회차가 있으면
        // 다음 회차를 안 열어주므로(400), 이게 없으면 학생이 취소하기 전엔 다른 날짜로 못 바꾼다(갇힘).
        EnrollmentRound resumable = unpaidRound(enrollment);
        if (resumable != null) {
            return supersede(resumable, req);
        }
        CourseRound next = RoundGate.nextSchedulable(enrollment);
        if (next == null) {
            throw new BadRequestException(); // 지금 잡을 회차 없음(직전 회차 미확정 / 전부 완료)
        }
        int extraSnapshot = next.getRoundKind() == RoundKind.EXTRA ? extraFee(enrollment, next) : 0;
        EnrollmentRound round = buildRound(instructor, next, req, extraSnapshot);
        enrollment.addRound(round);
        roundRepo.save(round);
        return EnrollmentResponse.of(round, venueName(round.getVenueRefId()), instructor.getNickName(), paymentExpiresInSeconds(round));
    }

    /**
     * 강사 일정변경요청 중 학생이 슬롯 선택("ㅇㅋ") — 위치 고정, 날짜/이용권/블록을 그 제안 슬롯으로 바꿔 재검증 후
     * reschedule. <b>선결제라 이미 결제된 회차</b>이고 강사가 이용권·블록까지 정해 제안한 = 강사가 승인한 자리이므로,
     * 추가 결제도 재수락도 없이 <b>곧장 {@code CONFIRMED}</b>. 입장료는 그 daypart 로 재산정되며, 싸졌으면 차액을
     * 자동 환불한다.
     *
     * <p>⚠️ <b>더 비싼 제안은 예외다</b> — 강사는 더 비싼 daypart 도 제안할 수 있고(2026-08-10), 그걸 고르면
     * 여기서 {@code -1018}({@link AdditionalPaymentRequiredException})로 거부되어 차액 결제를 거쳐야 한다.
     * (옛 주석은 "비싼 슬롯은 제안 단계에서 걸러진다" 였는데 그 필터가 없어진 뒤 stale 이었다 — 실제 동작은
     * use-case 테스트 {@code C1-1} 이 고정한다.)
     *
     * <p><b>좌석 보장</b>: 좌석은 제안 시점에 그 일정에 hold 로 잡아뒀으므로 pick 은 만석으로 막히지 않는다(하드캡
     * 우회가 아니라 — 미리 잡아둔 자리를 쓰는 것). 고른 슬롯의 hold 를 회수해 실점유로 전환하고, 안 고른 나머지
     * 제안 hold + 옛 슬롯은 풀어 좌석을 반납한다(빈 일정은 정리). 만료(TTL)로 제안이 사라졌으면 위 게이트에서 400.
     */
    @Transactional
    public EnrollmentResponse pickSlot(Account student, Long roundId, PickSlotRequest req) {
        EnrollmentRound round = requireMyRound(student, roundId);
        if (!round.hasRescheduleOffer()) {
            // 제안이 없다 — TTL 만료로 사라진 경우가 대부분이다. 사용자 잘못이 아니고 회복 동선이
            // 명확해(일정 직접 선택) 범용 -1011 이 아니라 전용 코드로 안내한다.
            throw new ProposalExpiredException();
        }
        LocalDate date = req.getDate();
        String ticketRef = req.getTicketRef();
        LocalTime start = req.getBlockStart();
        LocalTime end = req.getBlockEnd();
        if (round.getProposedSlots().stream().noneMatch(p -> p.sameAs(date, ticketRef, start, end))) {
            throw new BadRequestException(); // 제안 목록 밖 슬롯
        }
        Account instructor = round.getEnrollment().getCourse().getInstructor();
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(round.getVenueRefId())).get(round.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        BookableSlotDeriver.Block block = bookableBlock(venue, ticketRef, date, start, end);
        requireCoverageAndNoOverlap(instructor, date, round.getVenueRefId(), start, end);

        AvailabilitySession oldSession = round.getAvailabilitySession();
        AvailabilitySession newSession = findOrCreateSession(instructor, date, start, end,
                round.getVenueRefId(), ticketRef);
        // 제안 보장 hold 회수(고른 슬롯 것 포함) — 고른 자리는 곧 실점유로 전환되니 hold 를 풀어 이중계산 방지.
        List<AvailabilitySession> heldSessions = releaseProposalHolds(round);

        int paidTotal = round.chargeTotal(); // 변경 전 = 이미 결제된 금액
        round.archiveCurrentSlot(OffsetDateTime.now(ZoneOffset.UTC)); // 옛 슬롯 이력 (취소 아님)
        round.setAvailabilitySession(newSession);
        round.setDate(date);
        round.setTicketRef(ticketRef);
        round.setBlockStart(start);
        round.setBlockEnd(end);
        round.setEntrySnapshot(block.getFee()); // 그 슬롯 daypart 입장료
        round.getProposedSlots().clear();
        round.setStatus(EnrollmentStatus.CONFIRMED); // 이미 결제 + 강사가 승인한 자리 → 곧장 확정
        round.setRespondedAt(OffsetDateTime.now(ZoneOffset.UTC));
        settleSlotChange(round, paidTotal, "일정 변경 차액", false); // 제안은 위치 고정이라 위치가 바뀔 수 없다
        // 옛 슬롯 + 안 고른 제안 슬롯 일정 정리(점유 0이면 삭제). 고른 newSession 은 실점유라 보존.
        if (oldSession != null && !oldSession.getId().equals(newSession.getId())) {
            sessionCleaner.deleteIfEmpty(oldSession);
        }
        for (AvailabilitySession s : heldSessions) {
            if (!s.getId().equals(newSession.getId())) {
                sessionCleaner.deleteIfEmpty(s);
            }
        }
        return EnrollmentResponse.of(round, venue.getName(), instructor.getNickName(), paymentExpiresInSeconds(round));
    }

    /** 이 회차의 강사 제안 보장 hold 를 모두 해제(orphanRemoval). 영향받은(distinct) 일정 목록 반환 — 호출자가 정리. */
    private List<AvailabilitySession> releaseProposalHolds(EnrollmentRound round) {
        List<AvailabilityHold> holds = holdRepo.findByProposalRoundId(round.getId());
        List<AvailabilitySession> touched = new ArrayList<>();
        for (AvailabilityHold h : holds) {
            AvailabilitySession s = h.getSession();
            if (s != null) {
                s.getHolds().remove(h);
                if (touched.stream().noneMatch(t -> t.getId().equals(s.getId()))) {
                    touched.add(s);
                }
            }
        }
        return touched;
    }

    /**
     * 직접 일정 수정 — 학생이 (제안 외) 원하는 슬롯으로 회차를 바꾼다. 날짜에 따라 위치가 다를 수 있어 위치·이용권·
     * 장비까지 재선택 가능. <b>취소 아님</b> — 회차 유지, 옛 슬롯은 이력 적재. {@code roundId} = 회차 id.
     *
     * <p>두 경로 모두 허용한다:
     * <ul>
     *   <li><b>미결제({@code PENDING})</b> — 아직 아무 일도 안 일어난 신청이라 슬롯만 갈아끼우고 결제 시계 재시작.</li>
     *   <li><b>결제완료({@code ACCEPT_PENDING})</b> — 강사 제안이 다 안 맞을 때의 <b>학생 재제안</b>. 결제는 유지한 채
     *       슬롯만 바꾸고 강사 결정 시계(24h)를 재시작한다(강사 hub 에 {@code CHANGING} 으로 뜬다). 강사가 제안 안 한
     *       슬롯이라 강사 재수락이 필요하다. 금액이 줄면 차액 자동환불, 늘면 400(추가 청구 없이는 못 옮김).</li>
     * </ul>
     */
    @Transactional
    public EnrollmentResponse reschedule(Account student, Long roundId, RoundScheduleRequest req) {
        EnrollmentRound round = requireMyRound(student, roundId);
        boolean paid = round.getStatus() == EnrollmentStatus.ACCEPT_PENDING;
        if (round.getStatus() != EnrollmentStatus.PENDING && !paid) {
            throw new BadRequestException(); // 확정/취소/거절된 회차는 직접 수정 불가
        }
        Course course = round.getEnrollment() == null ? null : round.getEnrollment().getCourse();
        if (course == null) {
            throw new BadRequestException();
        }
        Account instructor = course.getInstructor();
        requireRoundCandidate(round.getCourseRound(), req.getVenueRefId(), req.getTicketRef());
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(req.getVenueRefId())).get(req.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        BookableSlotDeriver.Block block = bookableBlock(venue, req.getTicketRef(), req.getDate(),
                req.getBlockStart(), req.getBlockEnd());
        return swapSlot(round, instructor, venue, block, req, paid); // coverage·겹침·좌석 검사는 여기서

    }

    /**
     * 회차의 슬롯을 요청 슬롯으로 <b>제자리 교체</b>한다 — {@code reschedule} 과 <b>미결제 재신청(supersede)</b> 이 공유.
     *
     * <p><b>왜 공유하나</b>: 학생이 결제 화면에서 뒤로 가 다른 날짜로 다시 신청하는 것은 사실상 "일정 수정"이다.
     * 새 신청을 하나 더 만들면 옛 미결제 건이 좌석을 잡은 채 남아 <b>이중 점유</b>가 되므로, 같은 자리(학생×강의×회차)의
     * 미결제 PENDING 은 새로 만들지 않고 이 경로로 갈아끼운다.
     *
     * <p><b>내 유령 점유가 나를 막지 않게</b>: 옮기고 나면 비어서 사라질 <b>자기 옛 일정</b>은 이중부킹 판정에서
     * 제외하고({@code -1015} 오판 방지), 같은 일정으로 되돌아가는 경우엔 만석 검사를 건너뛴다(이미 그 자리를 쓰고 있다).
     */
    private EnrollmentResponse swapSlot(EnrollmentRound round, Account instructor, VenueResponse venue,
                                        BookableSlotDeriver.Block block, RoundSlotInput req, boolean paid) {
        AvailabilitySession oldSession = round.getAvailabilitySession();
        // 옛 일정이 이 회차만 붙들고 있다면 옮기는 순간 사라진다 → 겹침 판정에서 제외(내 유령이 나를 막는 것 방지).
        Long vacating = willVacate(oldSession) ? oldSession.getId() : null;
        requireCoverageAndNoOverlap(instructor, req.getDate(), req.getVenueRefId(),
                req.getBlockStart(), req.getBlockEnd(), vacating);

        AvailabilitySession newSession = findOrCreateSession(instructor, req.getDate(),
                req.getBlockStart(), req.getBlockEnd(), req.getVenueRefId(), req.getTicketRef());
        if (oldSession == null || !oldSession.getId().equals(newSession.getId())) {
            requireSeat(newSession); // 같은 일정으로 되돌아가면 이미 내가 점유 중이라 검사 불필요(자기 자신에 막힘)
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int paidTotal = round.chargeTotal(); // 결제완료 경로에서 = 이미 결제된 금액
        // 위치가 바뀌는 변경인가 — 금액까지 오르면 차액 결제로는 못 가는 조합이라 아래에서 갈라 거부한다.
        boolean venueChanged = !Objects.equals(round.getVenueRefId(), req.getVenueRefId());
        round.archiveCurrentSlot(now); // 옛 슬롯 이력 (취소 아님)
        round.setAvailabilitySession(newSession);
        round.setVenueRefId(req.getVenueRefId());
        round.setDate(req.getDate());
        round.setBlockStart(req.getBlockStart());
        round.setBlockEnd(req.getBlockEnd());
        round.setTicketRef(req.getTicketRef());
        round.setEntrySnapshot(block.getFee());
        round.getEquipment().clear(); // 위치 바뀔 수 있어 장비 재선택
        round.setEquipmentSnapshot(addEquipment(round, req.getEquipmentRefs(), req.getEquipmentSizes(),
                equipmentItems(instructor, req.getVenueRefId())));
        round.getProposedSlots().clear();
        if (paid) {
            // 학생 재제안 — 결제는 유지, 강사 결정 대기로 되돌리고 24h 시계 재시작. 금액 줄면 차액 환불.
            round.setStatus(EnrollmentStatus.ACCEPT_PENDING);
            round.setRespondedAt(now);
            settleSlotChange(round, paidTotal, "일정 변경 차액", venueChanged);
        } else {
            round.setStatus(EnrollmentStatus.PENDING); // 미결제 — 그대로 결제 대기
            round.setCreatedAt(now);     // 새 요청 = 결제 클럭 재시작
            round.setRespondedAt(null);  // 아직 강사 응답 전
        }
        if (oldSession != null && !oldSession.getId().equals(newSession.getId())) {
            sessionCleaner.deleteIfEmpty(oldSession);
        }
        return EnrollmentResponse.of(round, venue.getName(), instructor.getNickName(), paymentExpiresInSeconds(round));
    }

    /**
     * 미결제 재신청(supersede) — 옛 미결제 회차를 새 슬롯으로 갈아끼운다. 새 회차를 만들지 않으므로 옛 좌석이
     * 자동 반납되고 이중 점유가 생기지 않는다. 상태는 그대로 {@code PENDING}(결제 시계만 재시작).
     *
     * <p><b>스코프 엄수</b>: 호출자가 (학생 × 강의 × 미결제 PENDING)로 좁혀 찾은 회차만 넘긴다 —
     * 결제완료({@code ACCEPT_PENDING})·확정·다른 강의는 절대 건드리지 않는다.
     */
    private EnrollmentResponse supersede(EnrollmentRound round, RoundSlotInput req) {
        Account instructor = round.getEnrollment().getCourse().getInstructor();
        requireRoundCandidate(round.getCourseRound(), req.getVenueRefId(), req.getTicketRef());
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(req.getVenueRefId())).get(req.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        BookableSlotDeriver.Block block = bookableBlock(venue, req.getTicketRef(), req.getDate(),
                req.getBlockStart(), req.getBlockEnd());
        return swapSlot(round, instructor, venue, block, req, false);
    }

    /** 그 학생이 그 강의에 대해 들고 있는 <b>미결제 1회차</b>(없으면 null). supersede 대상. */
    private EnrollmentRound unpaidFirstRound(Account student, Course course) {
        return roundRepo.findByEnrollment_Student_IdAndEnrollment_Course_IdAndStatus(
                        student.getId(), course.getId(), EnrollmentStatus.PENDING).stream()
                .filter(EnrollmentRound::isFirstMeeting)
                .max(Comparator.comparing(EnrollmentRound::getId))
                .orElse(null);
    }

    /**
     * 그 수강 안의 <b>미결제 2회차+ 회차</b>(없으면 null). {@code POST /{id}/rounds} 의 supersede 대상.
     *
     * <p>⚠️ <b>1회차는 제외</b> — 이 엔드포인트의 의도는 "다음 회차 신청"이다. 1회차가 미결제로 남아 있는 건
     * "아직 1회차도 확정 안 됨"이므로 순차 게이트가 400 으로 막아야 하고(그게 M1 사양), 여기서 1회차 슬롯을
     * 갈아끼우면 게이트가 무력화된다. 1회차 재신청은 {@code POST /enrollments}(submit) 소관.
     */
    private EnrollmentRound unpaidRound(Enrollment enrollment) {
        return enrollment.getRounds().stream()
                .filter(r -> r.getStatus() == EnrollmentStatus.PENDING && !r.isFirstMeeting())
                .max(Comparator.comparing(EnrollmentRound::getId))
                .orElse(null);
    }

    /** 이 일정이 지금 회차 하나만 붙들고 있나 — 그 회차를 옮기면 점유 0 이 되어 정리된다. */
    private boolean willVacate(AvailabilitySession session) {
        if (session == null) {
            return false;
        }
        int occupied = roundRepo.countByAvailabilitySessionIdAndStatusIn(session.getId(), EnrollmentStatus.ACTIVE);
        return occupied <= 1 && session.heldCount() == 0;
    }

    /**
     * <b>차액 결제 경로 — 견적</b>. 더 비싼 슬롯으로 옮기려는 요청을 검증하고, 옮겼을 때의 회차 금액을 돌려준다.
     * <b>아무것도 바꾸지 않는다</b>(슬롯 교체는 결제 승인 후 {@link #applySlotChange}).
     *
     * <p><b>범위</b>: 바꾸는 건 <b>일정(날짜·이용권·블록)</b> 뿐이고 <b>위치와 장비는 현재 것을 유지</b>한다.
     * 위치·장비까지 바꾸려면 취소 후 재신청 — 목표 슬롯을 주문에 싣는 구조라 담을 수 있는 건 일정 한 벌이다.
     *
     * <p>payment 도메인이 호출한다(payment→enrollment, 허용 방향). 학생 소유·상태·슬롯 유효성을 여기서 다 본다.
     */
    @Transactional(readOnly = true)
    public SlotChangeQuote quoteSlotChange(Account student, Long roundId, LocalDate date, String ticketRef,
                                           LocalTime start, LocalTime end) {
        EnrollmentRound round = requireMyRound(student, roundId);
        if (round.getStatus() != EnrollmentStatus.ACCEPT_PENDING) {
            // 결제완료·강사 결정 대기 건만 — 미결제는 그냥 reschedule, 확정된 회차는 학생이 직접 못 옮긴다
            // (reschedule 과 같은 정책. 확정 건은 강사 일정변경요청 경로로.)
            throw new BadRequestException();
        }
        Course course = round.getEnrollment() == null ? null : round.getEnrollment().getCourse();
        if (course == null) {
            throw new BadRequestException();
        }
        Account instructor = course.getInstructor();
        requireRoundCandidate(round.getCourseRound(), round.getVenueRefId(), ticketRef);
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(round.getVenueRefId())).get(round.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        BookableSlotDeriver.Block block = bookableBlock(venue, ticketRef, date, start, end);
        requireCoverageAndNoOverlap(instructor, date, round.getVenueRefId(), start, end);

        int currentTotal = round.chargeTotal();
        int targetTotal = currentTotal - round.getEntrySnapshot() + block.getFee(); // 입장료만 갈린다(위치·장비 유지)
        if (targetTotal <= currentTotal) {
            throw new BadRequestException(); // 안 비싸짐 — 차액 결제가 아니라 그냥 reschedule/pick-slot 경로
        }
        return new SlotChangeQuote(targetTotal - currentTotal, block.getFee());
    }

    /** 차액 결제 견적 — 추가로 받을 금액과 목표 슬롯의 입장료. */
    public record SlotChangeQuote(int additionalAmount, int targetEntryFee) {
    }

    /**
     * <b>차액 결제 경로 — 좌석 확보</b>. 결제창이 떠 있는 동안 목표 슬롯 자리를 주문에 귀속된 hold 로 잡아둔다.
     * 안 잡으면 결제 중에 자리가 나가 <b>돈은 받고 자리는 못 주는</b> 상태가 된다(강사 제안 hold 와 같은 이유).
     */
    @Transactional
    public void holdSlotForOrder(Account student, Long roundId, Long paymentOrderId, LocalDate date, String ticketRef,
                                 LocalTime start, LocalTime end, OffsetDateTime expiresAt) {
        EnrollmentRound round = requireMyRound(student, roundId);
        Account instructor = round.getEnrollment().getCourse().getInstructor();
        // ⚠️ 이전 hold 회수를 세션 생성 <b>전</b>에 — 해제는 빈 일정을 정리하므로, 방금 만든 세션을 지워버린다.
        releaseOrderHold(paymentOrderId); // 재-prepare 멱등
        AvailabilitySession session = findOrCreateSession(instructor, date, start, end,
                round.getVenueRefId(), ticketRef);
        requireSeat(session);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        session.addHold(AvailabilityHold.builder()
                .count(1).paymentOrderId(paymentOrderId).expiresAt(expiresAt).createdAt(now).build());
    }

    /** 그 주문에 귀속된 좌석 hold 를 해제한다(빈 일정 정리 포함). 승인/만료/취소 공용. 멱등. */
    @Transactional
    public void releaseOrderHold(Long paymentOrderId) {
        List<AvailabilityHold> holds = holdRepo.findByPaymentOrderId(paymentOrderId);
        List<AvailabilitySession> touched = new ArrayList<>();
        for (AvailabilityHold h : holds) {
            AvailabilitySession s = h.getSession();
            if (s != null) {
                s.getHolds().remove(h);
                if (touched.stream().noneMatch(t -> t.getId().equals(s.getId()))) {
                    touched.add(s);
                }
            }
        }
        touched.forEach(sessionCleaner::deleteIfEmpty);
    }

    /**
     * <b>차액 결제 경로 — 적용</b>. 결제 승인 직후 슬롯을 실제로 교체한다(payment 가 호출).
     *
     * <p><b>강사 수락은 여전히 필요하다</b> — 학생이 임의로 고른 시간은 강사가 동의한 적이 없고, 우리 coverage 가
     * 강사의 실제 일정(타 플랫폼 예약 등)을 다 반영한다는 보장이 없다. 그래서 슬롯을 바꾸고 <b>강사 결정 대기
     * ({@code ACCEPT_PENDING})로 되돌리며 24h 시계를 재시작</b>한다(옛 슬롯이 이력에 남아 강사 hub 엔 {@code CHANGING}).
     * 강사가 수락하면 확정, 거절하면 그 회차 전액(차액 포함) 자동환불 — {@code reschedule} 의 결제완료 경로와 같은 규칙.
     *
     * <p>(강사 <b>제안</b>을 고르는 {@code pickSlot} 만 재수락이 없다 — 강사가 낸 자리는 이미 동의한 자리이므로.)
     *
     * <p>잡아둔 hold 를 회수해 실점유로 전환하고 옛 슬롯은 이력으로.
     */
    @Transactional
    public void applySlotChange(Long roundId, Long paymentOrderId, LocalDate date, String ticketRef,
                                LocalTime start, LocalTime end, int targetEntryFee) {
        EnrollmentRound round = roundRepo.findById(roundId).orElseThrow(ResourceNotFoundException::new);
        Account instructor = round.getEnrollment().getCourse().getInstructor();
        AvailabilitySession oldSession = round.getAvailabilitySession();
        AvailabilitySession newSession = findOrCreateSession(instructor, date, start, end,
                round.getVenueRefId(), ticketRef);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        round.archiveCurrentSlot(now); // 취소 아님 — 슬롯 이력
        round.setAvailabilitySession(newSession);
        round.setDate(date);
        round.setTicketRef(ticketRef);
        round.setBlockStart(start);
        round.setBlockEnd(end);
        round.setEntrySnapshot(targetEntryFee);
        round.getProposedSlots().clear();
        // 학생이 고른 시간이라 강사 동의가 없다 → 강사 결정 대기로 되돌리고 24h 시계 재시작.
        round.setStatus(EnrollmentStatus.ACCEPT_PENDING);
        round.setRespondedAt(now);
        // ⚠️ hold 해제는 <b>회차를 새 세션에 붙인 뒤</b>에 — 먼저 풀면 그 일정이 "점유 0"이 되어 정리돼 버린다.
        releaseOrderHold(paymentOrderId); // 잡아둔 자리를 실점유로 전환(이중계산 방지)
        if (oldSession != null && !oldSession.getId().equals(newSession.getId())) {
            sessionCleaner.deleteIfEmpty(oldSession);
        }
    }

    /**
     * 결제된 회차의 슬롯이 바뀌었을 때 금액 정산 — 줄었으면 차액 자동환불, 늘었으면 400.
     *
     * <p><b>불변식</b>: "그 회차에 남아 있는 결제 순액 == {@code chargeTotal()}" — 줄 때마다 즉시 환불하므로
     * 변경 <i>전</i> {@code chargeTotal()} 이 곧 결제액이다(payment 도메인 조회 불필요 = 역참조 없음).
     * 더 비싼 슬롯으로 옮기려면 취소(전액환불) 후 재신청 — 추가 청구 상태를 되살리지 않으려는 의도적 제약.
     */
    private void settleSlotChange(EnrollmentRound round, int paidTotal, String reason, boolean venueChanged) {
        int refundable = paidTotal - round.chargeTotal();
        if (refundable < 0) {
            if (venueChanged) {
                // 위치까지 바뀌는데 금액도 오름 — 차액 결제 경로는 위치를 못 바꾸므로(-1018 로 내보내면
                // FE 가 결제로 유도하고, 결제 후 학생은 고른 적 없는 원래 위치로 옮겨진다) 아예 갈라 거부한다.
                throw new VenueChangeRequiresReapplyException();
            }
            // 금액이 늘어남 — 추가 결제 없이는 못 옮긴다. 전용 코드(-1018)로 내려 FE 가 나머지 400
            // (만석·확정 회차·슬롯 무효 …)과 구분해 차액 결제로 유도하게 한다.
            throw new AdditionalPaymentRequiredException();
        }
        if (refundable > 0) {
            events.publishEvent(new EnrollmentPartialRefundRequestedEvent(round.getId(), refundable, reason));
        }
    }

    /**
     * 회차 취소 — <b>강사 확정 전(PENDING·ACCEPT_PENDING)</b> 언제든. 취소된 회차는 자리를 비우므로 학생은 나중에
     * <b>그 회차를 다른 날짜로 다시 신청</b>할 수 있다({@code RoundGate} 는 활성 회차만 "이미 잡음"으로 본다).
     *
     * <ul>
     *   <li><b>PENDING</b>(미결제) — 낸 돈이 없으니 좌석만 반납.</li>
     *   <li><b>ACCEPT_PENDING</b>(결제완료·강사 결정 대기) — <b>전액 자동환불</b>. 강사 제안이 다 안 맞을 때의
     *       "ㄴㄴ" 경로이기도 하다. 환불은 동기라 실패하면 취소까지 롤백된다(돈-상태 원자성).</li>
     * </ul>
     *
     * <p>확정(CONFIRMED) 이후 취소는 수강 단위 환불 거래({@code RefundService.refundEnrollment}) 소관.
     * {@code roundId} = 회차 id.
     */
    @Transactional
    public EnrollmentResponse cancel(Account student, Long roundId) {
        EnrollmentRound round = requireMyRound(student, roundId);
        boolean paid = round.getStatus() == EnrollmentStatus.ACCEPT_PENDING;
        if (round.getStatus() != EnrollmentStatus.PENDING && !paid) {
            throw new BadRequestException(); // 확정/거절/이미취소된 회차는 이 경로로 취소 불가
        }
        AvailabilitySession session = round.getAvailabilitySession();
        round.setStatus(EnrollmentStatus.CANCELLED);
        round.setRespondedAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (paid) {
            events.publishEvent(new EnrollmentRefundRequestedEvent(roundId, "학생 취소"));
        }
        EnrollmentResponse resp = EnrollmentResponse.of(round, venueName(round.getVenueRefId()), instructorName(round), paymentExpiresInSeconds(round));
        sessionCleaner.deleteIfEmpty(session);
        return resp;
    }

    /** 내 회차 목록(평탄) — 최신 수강 먼저, 그 안 회차들. */
    public List<EnrollmentResponse> listMine(Account student) {
        List<EnrollmentRound> rounds = enrollmentRepo.findByStudentIdOrderByIdDesc(student.getId()).stream()
                .flatMap(e -> e.getRounds().stream()).collect(Collectors.toList());
        Map<String, String> names = resolveNames(rounds);
        return rounds.stream()
                .map(r -> EnrollmentResponse.of(r, names.get(r.getVenueRefId()), instructorName(r), paymentExpiresInSeconds(r)))
                .collect(Collectors.toList());
    }

    /**
     * 수강생 강의일정 hub — 내 수강을 강의(course=수강 컨테이너) 단위로 묶고 회차 진행상태를 파생. 한 {@link Enrollment}
     * = 한 강의 카드. 잡은 회차는 {@code rounds[]}, 미래 회차는 {@code totalRounds}/{@code nextRoundIndex} 로 FE 가 그림.
     */
    public ScheduleHubResponse mySchedule(Account student) {
        List<Enrollment> enrollments = enrollmentRepo.findByStudentIdOrderByIdDesc(student.getId());
        Map<String, String> venueNames = resolveNames(
                enrollments.stream().flatMap(e -> e.getRounds().stream()).collect(Collectors.toList()));

        List<ScheduleHubResponse.ScheduleCourse> courses = enrollments.stream()
                .map(e -> buildScheduleCourse(e, venueNames))
                .sorted(Comparator.comparingInt(c -> CourseScheduleStatus.ORDER.indexOf(c.getStatus())))
                .collect(Collectors.toList());

        return new ScheduleHubResponse(buildScheduleFilters(courses), courses);
    }

    /**
     * 미결제 회차의 결제 잔여 초 — 그 상태가 아니면 null. TTL 은 Sanity 런타임값이라 매 응답 시점에 푼다
     * (60s 캐시라 값싸다). 계산 규칙은 만료 스윕과 {@link PaymentWindow} 로 공유한다.
     */
    private Long paymentExpiresInSeconds(EnrollmentRound round) {
        return PaymentWindow.remainingSecondsFor(round, siteSettings.current().paymentTtlHours(),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private ScheduleHubResponse.ScheduleCourse buildScheduleCourse(Enrollment e, Map<String, String> venueNames) {
        int paymentTtlHours = siteSettings.current().paymentTtlHours();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // 재신청으로 대체된 죽은 회차는 제외 — 안 그러면 옛 REJECTED 때문에 강의가 영원히 RESCHEDULING 으로 굳는다.
        List<ScheduleHubResponse.ScheduleRound> rounds = RoundHistory.current(e.getRounds()).stream()
                .sorted(Comparator.comparing(r -> r.getRoundIndex() == null ? Integer.MAX_VALUE : r.getRoundIndex()))
                .map(r -> ScheduleHubResponse.ScheduleRound.builder()
                        .roundId(r.getId())
                        .roundIndex(r.getRoundIndex())
                        .roundKind(r.getRoundKind() == null ? null : r.getRoundKind().name())
                        .status(RoundScheduleStatus.from(r))
                        .date(r.getDate())
                        .blockStart(r.getBlockStart())
                        .blockEnd(r.getBlockEnd())
                        .venueRefId(r.getVenueRefId())
                        .venueName(venueNames.get(r.getVenueRefId()))
                        .amount(r.chargeTotal())
                        .gearItems(r.getEquipment().stream()
                                .map(eq -> com.diving.pungdong.enrollment.dto.GearItem.builder()
                                        .name(eq.getName()).sizeLabel(eq.getSize()).build())
                                .collect(Collectors.toList()))
                        .proposedSlots(new ArrayList<>(r.getProposedSlots()))
                        .rejectionReason(r.getRejectionReason())
                        .paymentExpiresInSeconds(PaymentWindow.remainingSecondsFor(r, paymentTtlHours, now))
                        .createdAt(r.getCreatedAt())
                        .respondedAt(r.getRespondedAt())
                        .build())
                .collect(Collectors.toList());

        CourseScheduleStatus status = CourseScheduleStatus.derive(rounds.stream()
                .map(ScheduleHubResponse.ScheduleRound::getStatus).collect(Collectors.toList()));

        Course course = e.getCourse();
        int totalRounds = course == null ? 0 : (int) course.getRounds().stream()
                .filter(cr -> cr.getRoundKind() == RoundKind.REGULAR).count();
        // COMPLETED 는 모든 정규회차가 잡혀 done 일 때만 — 아직 안 잡은 회차가 남으면 진행중.
        if (status == CourseScheduleStatus.COMPLETED) {
            long doneRegular = e.getRounds().stream()
                    .filter(r -> r.getRoundKind() == RoundKind.REGULAR && r.isDone()).count();
            if (doneRegular < totalRounds) {
                status = CourseScheduleStatus.PROGRESS;
            }
        }
        CourseRound next = course == null ? null : RoundGate.nextSchedulable(e);
        Integer nextRoundIndex = next != null && next.getRoundKind() == RoundKind.REGULAR ? next.getRoundIndex() : null;
        boolean canScheduleExtra = next != null && next.getRoundKind() == RoundKind.EXTRA;

        return ScheduleHubResponse.ScheduleCourse.builder()
                .courseId(course == null ? null : course.getId())
                .title(course == null ? null : course.getTitle())
                .organizationCode(course == null ? null : course.getOrganizationCode())
                .disciplineCode(course == null ? null : course.getDisciplineCode())
                .levels(course == null ? List.of() : new ArrayList<>(course.getLevels()))
                .instructorName(course == null || course.getInstructor() == null
                        ? null : course.getInstructor().getNickName())
                .status(status)
                .totalRounds(totalRounds)
                .nextRoundIndex(nextRoundIndex)
                .canScheduleExtra(canScheduleExtra)
                .enrollmentId(e.getId())
                .rounds(rounds)
                .build();
    }

    private static final Map<CourseScheduleStatus, String> COURSE_STATUS_LABEL = Map.of(
            CourseScheduleStatus.PAYMENT_DUE, "결제 대기",
            CourseScheduleStatus.RESCHEDULING, "일정 변경",
            CourseScheduleStatus.WAITING, "수락 대기",
            CourseScheduleStatus.PROGRESS, "진행중",
            CourseScheduleStatus.COMPLETED, "수강 완료",
            CourseScheduleStatus.CANCELLED, "취소");

    private List<ScheduleHubResponse.FilterCount> buildScheduleFilters(
            List<ScheduleHubResponse.ScheduleCourse> courses) {
        List<ScheduleHubResponse.FilterCount> filters = new ArrayList<>();
        filters.add(new ScheduleHubResponse.FilterCount("all", "전체", courses.size()));
        for (CourseScheduleStatus s : CourseScheduleStatus.ORDER) {
            int count = (int) courses.stream().filter(c -> c.getStatus() == s).count();
            filters.add(new ScheduleHubResponse.FilterCount(s.name(), COURSE_STATUS_LABEL.get(s), count));
        }
        return filters;
    }

    /* ─── 공유 회차 빌더 + 게이트 ─── */

    /** 슬롯 재검증(코스 회차 후보·블록·coverage·overlap·세션·만석·장비·가격) 후 PENDING 회차 생성(미부착). */
    private EnrollmentRound buildRound(Account instructor, CourseRound courseRound, RoundSlotInput slot, int extraSnapshot) {
        requireRoundCandidate(courseRound, slot.getVenueRefId(), slot.getTicketRef());
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(slot.getVenueRefId())).get(slot.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        BookableSlotDeriver.Block block = bookableBlock(venue, slot.getTicketRef(), slot.getDate(),
                slot.getBlockStart(), slot.getBlockEnd());
        requireCoverageAndNoOverlap(instructor, slot.getDate(), slot.getVenueRefId(),
                slot.getBlockStart(), slot.getBlockEnd());
        AvailabilitySession session = findOrCreateSession(instructor, slot.getDate(),
                slot.getBlockStart(), slot.getBlockEnd(), slot.getVenueRefId(), slot.getTicketRef());
        requireSeat(session);

        Map<String, VenueEquipmentResponse.Item> items = equipmentItems(instructor, slot.getVenueRefId());
        EnrollmentRound round = EnrollmentRound.builder()
                .courseRound(courseRound)
                .roundIndex(courseRound.getRoundIndex())
                .roundKind(courseRound.getRoundKind())
                .availabilitySession(session)
                .venueRefId(slot.getVenueRefId())
                .date(slot.getDate())
                .blockStart(slot.getBlockStart())
                .blockEnd(slot.getBlockEnd())
                .ticketRef(slot.getTicketRef())
                .status(EnrollmentStatus.PENDING)
                .entrySnapshot(block.getFee())
                .extraSnapshot(extraSnapshot)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        round.setEquipmentSnapshot(addEquipment(round, slot.getEquipmentRefs(), slot.getEquipmentSizes(), items));
        return round;
    }

    private int addEquipment(EnrollmentRound round, List<String> refs, Map<String, String> sizes,
                             Map<String, VenueEquipmentResponse.Item> items) {
        int total = 0;
        if (refs != null) {
            for (String ref : refs) {
                VenueEquipmentResponse.Item item = items.get(ref);
                if (item == null) {
                    throw new BadRequestException(); // 그 위치 장비가 아님
                }
                String size = validateSize(item, sizes == null ? null : sizes.get(ref));
                round.addEquipment(EnrollmentRoundEquipment.builder()
                        .itemRef(ref).name(item.getName()).priceSnapshot(item.getPrice()).size(size).build());
                total += item.getPrice();
            }
        }
        return total;
    }

    /**
     * 선택 사이즈 검증·정규화 — 사이즈 개념 없는 품목(옵션 비어있음)이면 무시(null 스냅샷), 있으면 그 품목의
     * {@code sizeOptions} 멤버십을 강제(프리셋 밖 = 자유입력 → 400). 미선택(null)은 허용(표시용, 필수 아님).
     */
    private String validateSize(VenueEquipmentResponse.Item item, String size) {
        List<String> options = item.getSizeOptions();
        if (options == null || options.isEmpty()) {
            return null; // NONE 형식 — 사이즈 없음
        }
        if (size == null) {
            return null; // 미선택 허용
        }
        if (!options.contains(size)) {
            throw new BadRequestException(); // 프리셋에 없는 사이즈(자유입력 차단)
        }
        return size;
    }

    /**
     * 만석 — 신청 시점 좌석 lock(선착순): 활성 + 외부 hold 가 유효정원을 채웠으면 거부.
     *
     * <p><b>동시성</b>: count 직전에 세션 행을 <b>비관적 쓰기잠금</b>({@code lockById} = SELECT … FOR UPDATE)으로 잡는다.
     * 동시 신청 두 건이 정원 1 을 함께 통과하는 오버부킹을 막는다 — 두 트랜잭션이 같은 세션 행 위에서 직렬화돼,
     * 뒤 신청은 앞 신청이 커밋(좌석 채움)한 뒤에야 count 를 실행해 만석을 본다. (중복 세션 생성 경합은 자연키 UNIQUE 제약으로 차단.)
     */
    private void requireSeat(AvailabilitySession session) {
        AvailabilitySession locked = sessionRepo.lockById(session.getId()).orElse(session);
        int occupied = roundRepo.countByAvailabilitySessionIdAndStatusIn(locked.getId(), EnrollmentStatus.ACTIVE);
        if (occupied + locked.heldCount() >= locked.effectiveCapacity()) {
            throw new BadRequestException(); // 만석
        }
    }

    private int extraFee(Enrollment enrollment, CourseRound extra) {
        long existingExtra = enrollment.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.EXTRA && r.getStatus().isActive()).count();
        int freeCount = extra.getFreeCount() == null ? 0 : extra.getFreeCount();
        int perSession = extra.getPerSessionPrice() == null ? 0 : extra.getPerSessionPrice();
        return existingExtra < freeCount ? 0 : perSession;
    }

    /* ─── 검증 helpers ─── */

    private BookableSlotDeriver.Block bookableBlock(VenueResponse venue, String ticketRef, LocalDate date,
                                                    LocalTime start, LocalTime end) {
        return slotDeriver.blocksFor(venue, ticketRef, date).stream()
                .filter(b -> b.sameTime(start, end))
                .findFirst().orElseThrow(BadRequestException::new);
    }

    /** 블록이 강사 coverage 에 통째로 ⊆ + 강사 기존 일정과 시간 안 겹침(같은 위치/블록 join 제외). */
    private void requireCoverageAndNoOverlap(Account instructor, LocalDate date, String venueRef,
                                             LocalTime start, LocalTime end) {
        requireCoverageAndNoOverlap(instructor, date, venueRef, start, end, null);
    }

    /** {@code ignoreSessionId} = 이 이동으로 비워질 내 옛 일정(겹침 판정에서 제외 — 내 유령이 나를 막지 않게). */
    private void requireCoverageAndNoOverlap(Account instructor, LocalDate date, String venueRef,
                                             LocalTime start, LocalTime end, Long ignoreSessionId) {
        if (!coversWhole(instructor, date, start, end)) {
            throw new BadRequestException(); // 예약가능시간 밖
        }
        overlapGuard.requireNoOverlap(instructor.getId(), date, venueRef, start, end, ignoreSessionId);
    }

    private boolean coversWhole(Account instructor, LocalDate date, LocalTime start, LocalTime end) {
        List<Span> spans = coverageRepo.findByInstructorIdAndDate(instructor.getId(), date).stream()
                .map(c -> new Span(c.getStartTime(), c.getEndTime())).collect(Collectors.toList());
        return CoverageMerger.containsWhole(spans, new Span(start, end));
    }

    private AvailabilitySession findOrCreateSession(Account instructor, LocalDate date,
                                                    LocalTime start, LocalTime end, String venueRef, String ticketRef) {
        return sessionRepo.findByInstructorIdAndDateAndStartTimeAndEndTime(instructor.getId(), date, start, end)
                .stream().filter(s -> Objects.equals(s.getVenueRefId(), venueRef)).findFirst()
                .orElseGet(() -> sessionRepo.save(AvailabilitySession.builder()
                        .instructor(instructor).date(date).startTime(start).endTime(end)
                        .venueRefId(venueRef).ticketRef(ticketRef)
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build()));
    }

    private CourseRound firstMeetingRound(Course course) {
        return course.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR)
                .min(Comparator.comparing(r -> r.getRoundIndex() == null ? Integer.MAX_VALUE : r.getRoundIndex()))
                .orElse(null);
    }

    private void requireRoundCandidate(CourseRound courseRound, String venueRefId, String ticketRef) {
        boolean ok = courseRound != null && courseRound.getVenues().stream()
                .filter(rv -> rv.getVenueRefId().equals(venueRefId))
                .flatMap(rv -> rv.getTickets().stream())
                .anyMatch(t -> ticketRef.equals(t.getTicketRef()));
        if (!ok) {
            throw new BadRequestException(); // 그 회차의 위치/이용권이 아님
        }
    }

    private Map<String, VenueEquipmentResponse.Item> equipmentItems(Account instructor, String venueRefId) {
        return equipmentService.findMine(instructor, venueRefId)
                .map(e -> e.getItems() == null ? Map.<String, VenueEquipmentResponse.Item>of()
                        : e.getItems().stream().collect(Collectors.toMap(
                                i -> String.valueOf(i.getId()), i -> i, (a, b) -> a)))
                .orElse(Map.of());
    }

    private void requireLaunched() {
        if (!siteSettings.current().launched()) {
            throw new PreLaunchException(); // 런칭 전 전역 신청 차단
        }
    }

    /**
     * 본인인증 선행 게이트 — 최신 VERIFIED 레코드가 없으면 403(-1017). 강사 신청과 같은 진실원
     * ({@code GET /identity-verifications/me} 의 쿼리)을 쓴다. 강사 신청은 verificationId 참조 방식이지만,
     * 수강신청은 요청에 verificationId 를 싣지 않으므로 세션 계정으로 직접 조회한다.
     */
    private void requireVerified(Account student) {
        identityVerificationRepo
                .findTopByAccountIdAndStatusOrderByIdDesc(student.getId(), IdentityVerificationStatus.VERIFIED)
                .orElseThrow(IdentityVerificationRequiredException::new);
    }

    private Course openCourse(Long courseId) {
        return courseRepo.findById(courseId)
                .filter(c -> c.getStatus() == CourseStatus.OPEN)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private Account requireInstructor(Course course) {
        Account instructor = course.getInstructor();
        if (instructor == null) {
            throw new ResourceNotFoundException();
        }
        return instructor;
    }

    private EnrollmentRound requireMyRound(Account student, Long roundId) {
        EnrollmentRound r = roundRepo.findById(roundId).orElseThrow(ResourceNotFoundException::new);
        Account owner = r.getEnrollment() == null ? null : r.getEnrollment().getStudent();
        if (owner == null || !owner.getId().equals(student.getId())) {
            throw new ResourceNotFoundException();
        }
        return r;
    }

    private String instructorName(EnrollmentRound r) {
        Course c = r.getEnrollment() == null ? null : r.getEnrollment().getCourse();
        return c == null || c.getInstructor() == null ? null : c.getInstructor().getNickName();
    }

    private String venueName(String venueRefId) {
        if (!StringUtils.hasText(venueRefId)) {
            return null;
        }
        VenueRefResolver.Resolved r = venueRefResolver.resolveAll(List.of(venueRefId)).get(venueRefId);
        return r == null ? null : r.getName();
    }

    private Map<String, String> resolveNames(List<EnrollmentRound> rounds) {
        List<String> refs = rounds.stream().map(EnrollmentRound::getVenueRefId)
                .filter(StringUtils::hasText).distinct().collect(Collectors.toList());
        if (refs.isEmpty()) {
            return Map.of();
        }
        return venueRefResolver.resolveAll(refs).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, x -> x.getValue().getName()));
    }
}
