package com.diving.pungdong.enrollment;

import com.diving.pungdong.availability.AvailabilityHold;
import com.diving.pungdong.availability.AvailabilityHoldJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.SessionCleaner;
import com.diving.pungdong.enrollment.event.EnrollmentRefundRequestedEvent;
import com.diving.pungdong.notification.event.EnrollmentExpiredEvent;
import com.diving.pungdong.notification.event.RoundCompletedEvent;
import com.diving.pungdong.global.sitesettings.SiteSettings;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 좌석 lock 자동 만료 — 신청 시점 좌석 lock(선착순)의 짝꿍. 방치된 점유를 풀어 슬롯을 다른 학생에게 돌린다.
 *
 * <ul>
 *   <li><b>PENDING</b>(선결제 미결제·장바구니) — 신청({@code createdAt}) 후 {@code paymentTtlHours}(기본 12h) 미결제면 만료(환불 없음).</li>
 *   <li><b>ACCEPT_PENDING</b>(결제완료·강사 결정 대기) — 결제({@code respondedAt}) 후 {@code pendingTtlHours}(기본 24h) 강사 무응답이면 만료 <b>+ 전액 자동환불</b>({@code EnrollmentRefundRequestedEvent} → payment).</li>
 * </ul>
 *
 * <p>두 상태뿐이다 — 선결제가 전 회차로 통일돼(2026-08-09) "강사 사전수락 후 결제 대기"라는 제3의 상태가 없어졌다.
 *
 * <p>만료 = {@code CANCELLED} 로 전환 + 점유 0 이면 {@link SessionCleaner} 가 빈 일정 삭제(좌석 해제). 결제완료분(ACCEPT_PENDING)은 자동환불. TTL 값은
 * {@link SiteSettings}(Sanity, 런타임 config). 각 건은 자기 트랜잭션 — 한 건 실패가 배치를 막지 않는다.
 * <p>만료 시 학생에게 {@code ENROLLMENT_EXPIRED} 알림을 발행한다 — 통보 없이 신청이 사라지지 않게.
 * 결제완료분은 자동환불이 함께 일어나므로 그 사실을 body 에 포함한다(별도 환불 알림은 보내지 않는다).
 */
@Slf4j
@Service
public class EnrollmentExpiryService {

    private final EnrollmentRoundJpaRepo roundRepo;
    private final AvailabilityHoldJpaRepo holdRepo;
    private final SessionCleaner sessionCleaner;
    private final SiteSettingsProvider siteSettings;
    private final ApplicationEventPublisher events; // ACCEPT_PENDING 만료 → 환불 이벤트(payment 수신)
    private final TransactionTemplate tx;

    public EnrollmentExpiryService(EnrollmentRoundJpaRepo roundRepo, AvailabilityHoldJpaRepo holdRepo,
                                   SessionCleaner sessionCleaner, SiteSettingsProvider siteSettings,
                                   ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this.roundRepo = roundRepo;
        this.holdRepo = holdRepo;
        this.sessionCleaner = sessionCleaner;
        this.siteSettings = siteSettings;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 만료 대상을 찾아 각자 트랜잭션으로 해제. 만료 건수 반환. {@code now} 주입(테스트 가능). */
    public int sweepExpired(OffsetDateTime now) {
        List<Long> ids = tx.execute(st -> {
            SiteSettings s = siteSettings.current();
            List<Long> out = new ArrayList<>();
            // 선결제: PENDING = 미결제(장바구니) → 결제창 window(paymentTtlHours 12h, createdAt 기준). 환불 없음.
            // ⚠️ 이 컷오프(now - ttl)는 {@link PaymentWindow#deadline}(createdAt + ttl)의 뒤집은 식이다 —
            // FE 카운트다운이 여기서 실제로 자르는 시점과 어긋나지 않으려면 둘을 같이 고칠 것.
            roundRepo.findByStatusAndCreatedAtBefore(EnrollmentStatus.PENDING, now.minusHours(s.paymentTtlHours()))
                    .forEach(r -> out.add(r.getId()));
            // ACCEPT_PENDING = 결제완료·강사 결정 대기 → 강사 응답 window(pendingTtlHours 24h, 결제시각 respondedAt 기준). 만료 시 자동환불.
            roundRepo.findByStatusAndRespondedAtBefore(
                            EnrollmentStatus.ACCEPT_PENDING, now.minusHours(s.pendingTtlHours()))
                    .forEach(r -> out.add(r.getId()));
            return out;
        });
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int expired = 0;
        for (Long id : ids) {
            try {
                Boolean ok = tx.execute(st -> expireOne(id, now));
                if (Boolean.TRUE.equals(ok)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // 돈이 걸린 경로(ACCEPT_PENDING 만료 = 전액 자동환불) — 예외 객체를 넘겨 스택을 남긴다.
                // 환불 실패로 롤백된 건은 다음 스윕이 재시도하지만, 왜 실패했는지는 여기서만 드러난다.
                log.error("[expiry] 회차 {} 만료 건너뜀 — 다음 스윕 재시도", id, e);
            }
        }
        return expired;
    }

    /**
     * 강사 일정변경 제안 보장 hold 만료 — 학생이 {@code proposalTtlHours}(기본 6h) 내 안 고르면 그 회차의 제안
     * hold 를 풀어(다른 학생을 막던 좌석 반납) 빈 일정 정리 + {@code proposedSlots} 비움.
     *
     * <p><b>회차 상태는 건드리지 않는다</b>(취소 아님 — 제안만 lapse, 강사 재제안 가능). 선결제 통일 이후 그
     * 상태는 {@code ACCEPT_PENDING}(결제완료·강사 결정 대기)이며, 제안이 비워지므로 파생 뷰
     * {@link RoundScheduleStatus} 는 {@code RESCHEDULING} → {@code WAITING} 으로 돌아간다.
     * (옛 주석은 "PENDING 유지" 라고 적혀 있었는데 선결제 전 표현이라 stale 이었다.)
     *
     * <p>회차 자체의 무응답 TTL({@code pendingTtlHours}, {@code respondedAt} 기준)은 별개로 계속 돈다
     * ({@link #sweepExpired}) — 만료되면 그때 CANCELLED + 전액 자동환불. 각 건 자기 트랜잭션.
     */
    public int sweepExpiredProposals(OffsetDateTime now) {
        List<Long> roundIds = tx.execute(st ->
                holdRepo.findByProposalRoundIdIsNotNullAndExpiresAtBefore(now).stream()
                        .map(AvailabilityHold::getProposalRoundId).distinct().collect(Collectors.toList()));
        if (roundIds == null || roundIds.isEmpty()) {
            return 0;
        }
        int lapsed = 0;
        for (Long roundId : roundIds) {
            try {
                Boolean ok = tx.execute(st -> lapseProposal(roundId));
                if (Boolean.TRUE.equals(ok)) {
                    lapsed++;
                }
            } catch (RuntimeException e) {
                log.warn("[proposal-expiry] 회차 {} 제안 만료 건너뜀 ({})", roundId, e.toString());
            }
        }
        return lapsed;
    }

    /** 한 회차의 제안 hold 를 모두 풀고(빈 일정 정리) proposedSlots 비움. 멱등(이미 풀렸으면 false). */
    private boolean lapseProposal(Long roundId) {
        List<AvailabilityHold> holds = holdRepo.findByProposalRoundId(roundId);
        if (holds.isEmpty()) {
            return false; // 그새 학생이 pick 했거나 강사가 재제안 — 멱등
        }
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
        roundRepo.findById(roundId).ifPresent(r -> r.getProposedSlots().clear());
        touched.forEach(sessionCleaner::deleteIfEmpty);
        return true;
    }

    /**
     * 자동 완료 — 세션 날짜가 지난(date &lt; today, +24h 그레이스) 확정 회차를 done 처리(강사 미마킹 대비 fallback).
     * 각 건 자기 트랜잭션. 완료 건수 반환.
     */
    public int sweepAutoDone(LocalDate today) {
        List<Long> ids = tx.execute(st -> {
            List<Long> out = new ArrayList<>();
            roundRepo.findByStatusAndDoneAtIsNullAndDateBefore(EnrollmentStatus.CONFIRMED, today)
                    .forEach(r -> out.add(r.getId()));
            return out;
        });
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int done = 0;
        for (Long id : ids) {
            try {
                Boolean ok = tx.execute(st -> markDone(id));
                if (Boolean.TRUE.equals(ok)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("[auto-done] 회차 {} 완료 건너뜀 ({})", id, e.toString());
            }
        }
        return done;
    }

    private boolean markDone(Long id) {
        EnrollmentRound r = roundRepo.findById(id).orElse(null);
        if (r == null || r.getStatus() != EnrollmentStatus.CONFIRMED || r.getDoneAt() != null) {
            return false; // 그새 변경됨 — 멱등
        }
        r.setDoneAt(OffsetDateTime.now(ZoneOffset.UTC));
        roundRepo.save(r);
        // 완료 경로는 둘이다 — 여기(세션일+24h 자동) 와 강사 수동(InstructorEnrollmentService).
        // 위 :176 가드가 doneAt != null 이면 빠져나가므로 중복 발행은 없다.
        EnrollmentRefs refs = EnrollmentRefs.of(r);
        if (refs.canNotifyStudent()) {
            events.publishEvent(RoundCompletedEvent.builder()
                    .studentAccountId(refs.getStudentAccountId())
                    .courseId(refs.getCourseId())
                    .enrollmentId(refs.getEnrollmentId())
                    .roundId(refs.getRoundId())
                    .courseTitle(refs.courseTitleOrFallback())
                    .build());
        }
        return true;
    }


    private boolean expireOne(Long id, OffsetDateTime now) {
        EnrollmentRound r = roundRepo.findById(id).orElse(null);
        if (r == null || (r.getStatus() != EnrollmentStatus.PENDING
                && r.getStatus() != EnrollmentStatus.ACCEPT_PENDING)) {
            return false; // 그새 수락/결제/취소됨 — 멱등
        }
        boolean wasPaid = r.getStatus() == EnrollmentStatus.ACCEPT_PENDING; // 결제완료분만 환불 대상
        AvailabilitySession session = r.getAvailabilitySession();
        r.setStatus(EnrollmentStatus.CANCELLED);
        r.setRespondedAt(now);
        roundRepo.save(r);
        if (wasPaid) {
            // 결제완료(ACCEPT_PENDING) 무응답 만료 → 전액 자동환불. 동기 이벤트라 환불 실패 시 이 트랜잭션(CANCELLED)까지 롤백 → 다음 sweep 재시도.
            events.publishEvent(new EnrollmentRefundRequestedEvent(id, "미응답 만료"));
        }
        // 통보 없이 신청이 사라지지 않게 학생에게 알린다. paid 갈래는 body 에 환불 안내가 붙으므로
        // 별도 REFUND_COMPLETED 는 보내지 않는다(사용자 결정 — 같은 사건에 알림 2건은 소음).
        // 위 :186-189 가드가 "그새 수락/결제/취소됨" 을 걸러내므로 sweep 재실행 시 중복 발행은 없다.
        EnrollmentRefs refs = EnrollmentRefs.of(r);
        if (refs.canNotifyStudent()) {
            events.publishEvent(EnrollmentExpiredEvent.builder()
                    .studentAccountId(refs.getStudentAccountId())
                    .courseId(refs.getCourseId())
                    .enrollmentId(refs.getEnrollmentId())
                    .roundId(refs.getRoundId())
                    .courseTitle(refs.courseTitleOrFallback())
                    .instructorNickName(refs.instructorNickNameOrFallback())
                    .paid(wasPaid)
                    .build());
        }
        sessionCleaner.deleteIfEmpty(session); // 점유 0 이면 빈 일정 삭제(좌석 해제)
        return true;
    }
}
