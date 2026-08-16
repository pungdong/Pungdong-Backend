package com.diving.pungdong.chat;

import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.chat.dto.RoundChatState;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 회차 카드·슬롯 상세에 붙일 채팅 상태를 <b>일괄</b> 계산한다.
 *
 * <p>enrollment/availability 쪽 응답 조립부가 이걸 부른다. 방향이 그쪽 → chat 이므로 이 서비스는
 * <b>레포만</b> 참조한다(서비스 아님) — 서비스끼리 물리면 Spring Boot 2.6+ 가 금지하는 순환 참조가 된다.
 *
 * <p><b>읽기가 쓰기를 하지 않는다.</b> 방 키가 세션 id 라 방 행이 없어도 {@code roomId} 를 알 수 있어서,
 * 목록을 그리려고 방을 미리 만들 필요가 없다. 실제 생성은 사용자가 방을 처음 열 때다.
 *
 * <p>쿼리 수는 세션 개수와 무관하게 <b>고정</b>이다(세션·결제회차·방·unread 각 1회) — 이 레포의
 * "id 모아 IN 조회 후 클로저 매핑" 관례.
 */
@Service
@RequiredArgsConstructor
public class ChatQueryService {

    private final ChatRoomJpaRepo roomRepo;
    private final ChatMessageJpaRepo messageRepo;
    private final AvailabilitySessionJpaRepo sessionRepo;
    private final EnrollmentRoundJpaRepo roundRepo;

    /**
     * 세션 id → 조회자 기준 채팅 상태. 요청한 모든 id 에 대해 항상 값이 있다(없으면
     * {@link RoundChatState#hidden()}) — 호출부가 null 체크를 하지 않게.
     *
     * @param viewerAccountId 조회자(강사일 수도, 수강생일 수도)
     */
    @Transactional(readOnly = true)
    public Map<Long, RoundChatState> statesFor(Long viewerAccountId, Collection<Long> sessionIds) {
        Map<Long, RoundChatState> out = new HashMap<>();
        Set<Long> ids = sessionIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (viewerAccountId == null || ids.isEmpty()) {
            sessionIds.forEach(id -> out.put(id, RoundChatState.hidden()));
            return out;
        }

        Map<Long, AvailabilitySession> sessions = sessionRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(AvailabilitySession::getId, s -> s));

        // 결제완료(OCCUPYING) 회차가 1건도 없는 세션은 "아직 방이 없는" 상태다 — 디자인의 "결제 전 = 미생성".
        Map<Long, List<EnrollmentRound>> paidBySession =
                roundRepo.findByAvailabilitySessionIdInAndStatusIn(ids, EnrollmentStatus.OCCUPYING).stream()
                        .filter(r -> r.getAvailabilitySession() != null)
                        .collect(Collectors.groupingBy(r -> r.getAvailabilitySession().getId()));

        Set<Long> eligible = new HashSet<>();
        for (Long id : ids) {
            AvailabilitySession session = sessions.get(id);
            List<EnrollmentRound> paid = paidBySession.getOrDefault(id, List.of());
            if (session == null || paid.isEmpty()) {
                continue;
            }
            boolean isInstructor = session.getInstructor() != null
                    && viewerAccountId.equals(session.getInstructor().getId());
            boolean isPaidStudent = paid.stream().anyMatch(r -> viewerAccountId.equals(studentIdOf(r)));
            if (isInstructor || isPaidStudent) {
                eligible.add(id);
            }
        }

        Map<Long, Integer> unread = eligible.isEmpty() ? Map.of() : unreadByRoom(viewerAccountId, eligible);
        Map<Long, ChatRoom> rooms = eligible.isEmpty() ? Map.of()
                : roomRepo.findByIdIn(eligible).stream().collect(Collectors.toMap(ChatRoom::getId, r -> r));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Long id : sessionIds) {
            if (id == null || !eligible.contains(id)) {
                out.put(id, RoundChatState.hidden());
                continue;
            }
            // 방 행이 아직 없으면 세션에서 직접 마감을 계산한다(첫 진입 전에도 상태가 맞아야 한다).
            OffsetDateTime closesAt = rooms.containsKey(id)
                    ? rooms.get(id).getClosesAt()
                    : ChatRooms.closesAt(sessions.get(id));
            boolean closed = closesAt == null || !now.isBefore(closesAt);
            out.put(id, RoundChatState.of(
                    closed ? ChatRoomState.CLOSED : ChatRoomState.ACTIVE, id, unread.getOrDefault(id, 0)));
        }
        return out;
    }

    /** 편의 — 단일 세션. */
    @Transactional(readOnly = true)
    public RoundChatState stateFor(Long viewerAccountId, Long sessionId) {
        if (sessionId == null) {
            return RoundChatState.hidden();
        }
        return statesFor(viewerAccountId, List.of(sessionId))
                .getOrDefault(sessionId, RoundChatState.hidden());
    }

    private Map<Long, Integer> unreadByRoom(Long accountId, Collection<Long> roomIds) {
        Map<Long, Integer> out = new HashMap<>();
        for (Object[] row : messageRepo.countUnreadByRoom(accountId, roomIds)) {
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return out;
    }

    private static Long studentIdOf(EnrollmentRound round) {
        Enrollment enrollment = round.getEnrollment();
        if (enrollment == null || enrollment.getStudent() == null) {
            return null;
        }
        return enrollment.getStudent().getId();
    }
}
