package com.diving.pungdong.availability;

import com.diving.pungdong.availability.CoverageMerger.Span;
import com.diving.pungdong.global.advice.exception.SessionTimeOverlapException;
import com.diving.pungdong.global.model.SessionOverlapResult;
import com.diving.pungdong.venue.VenueRefResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 일정 시간겹침 방지 — 한 강사는 한 번에 한 세션만 운영하므로, 새 일정이 같은 날 기존 일정과 시간상 겹치면
 * 안 된다(위치 무관 — 동시에 두 곳 불가). 정확히 같은 (위치,시간)은 join 이라 겹침에서 제외. 맞닿는 경계
 * (08–11 + 11–14)는 겹침 아님(strict overlap).
 *
 * <p>겹침을 허용하면 강사가 이중부킹되고 그 시간대 정원도 이중계산된다(같은 부류의 버그).
 */
@Component
@RequiredArgsConstructor
public class SessionOverlapGuard {

    private final AvailabilitySessionJpaRepo sessionRepo;
    private final VenueRefResolver venueRefResolver;

    /** 새 (위치,시간) 일정이 기존과 겹치면 예외. (정확히 같은 위치·시간 = join 대상이라 제외.) */
    public void requireNoOverlap(Long instructorId, LocalDate date, String venueRef, LocalTime start, LocalTime end) {
        requireNoOverlap(instructorId, date, venueRef, start, end, null);
    }

    /**
     * 같은 판정이되 <b>곧 사라질 내 일정 하나를 제외</b>한다({@code ignoreSessionId}).
     *
     * <p><b>왜 필요한가</b>: 회차의 슬롯을 옮길 때, 옮기고 나면 비어서 삭제될 <b>자기 자신의 옛 일정</b>이
     * 새 슬롯과 시간이 겹치면 "이중부킹"으로 오판돼 <b>내 유령 점유가 나를 막는다</b>(13~16 → 14~17 이동 등).
     * 옛 일정에 다른 학생/hold 가 남아 실제로 살아남는 경우엔 제외하면 안 되므로, 호출자가 "이 일정은 비워진다"를
     * 확인했을 때만 넘긴다.
     */
    public void requireNoOverlap(Long instructorId, LocalDate date, String venueRef, LocalTime start, LocalTime end,
                                 Long ignoreSessionId) {
        List<AvailabilitySession> daySessions = sessionRepo.findByInstructorIdAndDate(instructorId, date).stream()
                .filter(s -> ignoreSessionId == null || !ignoreSessionId.equals(s.getId()))
                .collect(Collectors.toList());
        List<AvailabilitySession> overlapping = findOverlapping(daySessions, venueRef, start, end);
        if (!overlapping.isEmpty()) {
            throw new SessionTimeOverlapException(toConflicts(overlapping));
        }
    }

    /** daySessions(그 날 강사 일정) 중 (위치,시간) exact 가 아니면서 [start,end] 와 strict 하게 겹치는 게 있나. */
    public static boolean wouldOverlap(List<AvailabilitySession> daySessions, String venueRef,
                                       LocalTime start, LocalTime end) {
        return !findOverlapping(daySessions, venueRef, start, end).isEmpty();
    }

    /** 겹치는 기존 일정들(시작 시각 순). exact (위치,시간) 은 join 이라 제외. */
    public static List<AvailabilitySession> findOverlapping(List<AvailabilitySession> daySessions, String venueRef,
                                                            LocalTime start, LocalTime end) {
        Span nw = new Span(start, end);
        return daySessions.stream()
                .filter(s -> !(Objects.equals(s.getVenueRefId(), venueRef)
                        && s.getStartTime().equals(start) && s.getEndTime().equals(end))) // exact = join, 제외
                .filter(s -> new Span(s.getStartTime(), s.getEndTime()).overlaps(nw))
                .sorted(Comparator.comparing(AvailabilitySession::getStartTime))
                .collect(Collectors.toList());
    }

    /** 응답용 변환 — 위치 표시명은 한 번에 해석(미존재/미지정이면 null, 토큰은 보존). */
    private List<SessionOverlapResult.Conflict> toConflicts(List<AvailabilitySession> sessions) {
        List<String> refs = sessions.stream().map(AvailabilitySession::getVenueRefId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<String, VenueRefResolver.Resolved> names = refs.isEmpty() ? Map.of() : venueRefResolver.resolveAll(refs);
        return sessions.stream()
                .map(s -> new SessionOverlapResult.Conflict(
                        s.getId(), s.getDate(), s.getStartTime(), s.getEndTime(), s.getVenueRefId(),
                        s.getVenueRefId() == null || !names.containsKey(s.getVenueRefId())
                                ? null : names.get(s.getVenueRefId()).getName()))
                .collect(Collectors.toList());
    }
}
