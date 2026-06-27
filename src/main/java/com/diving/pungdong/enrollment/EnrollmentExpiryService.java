package com.diving.pungdong.enrollment;

import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.SessionCleaner;
import com.diving.pungdong.global.sitesettings.SiteSettings;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 좌석 lock 자동 만료 — 신청 시점 좌석 lock(선착순)의 짝꿍. 방치된 점유를 풀어 슬롯을 다른 학생에게 돌린다.
 *
 * <ul>
 *   <li><b>PENDING</b>(강사 응답 대기) — 신청({@code createdAt}) 후 {@code pendingTtlHours}(기본 24h) 무응답이면 만료.</li>
 *   <li><b>PAYMENT_PENDING</b>(결제 대기) — 수락({@code respondedAt}) 후 {@code paymentTtlHours}(기본 12h) 미결제면 만료.</li>
 * </ul>
 *
 * <p>만료 = {@code CANCELLED} 로 전환 + 점유 0 이면 {@link SessionCleaner} 가 빈 일정 삭제(좌석 해제). TTL 값은
 * {@link SiteSettings}(Sanity, 런타임 config). 각 건은 자기 트랜잭션 — 한 건 실패가 배치를 막지 않는다.
 * (만료 알림 — 학생에게 "시간 초과 자동취소" 푸시 — 은 후속: enrollment→notification outbox 연동 필요.)
 */
@Slf4j
@Service
public class EnrollmentExpiryService {

    private final EnrollmentRoundJpaRepo roundRepo;
    private final SessionCleaner sessionCleaner;
    private final SiteSettingsProvider siteSettings;
    private final TransactionTemplate tx;

    public EnrollmentExpiryService(EnrollmentRoundJpaRepo roundRepo, SessionCleaner sessionCleaner,
                                   SiteSettingsProvider siteSettings, PlatformTransactionManager txManager) {
        this.roundRepo = roundRepo;
        this.sessionCleaner = sessionCleaner;
        this.siteSettings = siteSettings;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 만료 대상을 찾아 각자 트랜잭션으로 해제. 만료 건수 반환. {@code now} 주입(테스트 가능). */
    public int sweepExpired(LocalDateTime now) {
        List<Long> ids = tx.execute(st -> {
            SiteSettings s = siteSettings.current();
            List<Long> out = new ArrayList<>();
            roundRepo.findByStatusAndCreatedAtBefore(EnrollmentStatus.PENDING, now.minusHours(s.pendingTtlHours()))
                    .forEach(r -> out.add(r.getId()));
            roundRepo.findByStatusAndRespondedAtBefore(
                            EnrollmentStatus.PAYMENT_PENDING, now.minusHours(s.paymentTtlHours()))
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
                log.warn("[expiry] 회차 {} 만료 건너뜀 ({})", id, e.toString());
            }
        }
        return expired;
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
        r.setDoneAt(LocalDateTime.now());
        roundRepo.save(r);
        return true;
    }

    private boolean expireOne(Long id, LocalDateTime now) {
        EnrollmentRound r = roundRepo.findById(id).orElse(null);
        if (r == null
                || (r.getStatus() != EnrollmentStatus.PENDING && r.getStatus() != EnrollmentStatus.PAYMENT_PENDING)) {
            return false; // 그새 수락/결제/취소됨 — 멱등
        }
        AvailabilitySession session = r.getAvailabilitySession();
        r.setStatus(EnrollmentStatus.CANCELLED);
        r.setRespondedAt(now);
        roundRepo.save(r);
        sessionCleaner.deleteIfEmpty(session); // 점유 0 이면 빈 일정 삭제(좌석 해제)
        return true;
    }
}
