package com.diving.pungdong.chat;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.chat.dto.ChatParticipantResponse;
import com.diving.pungdong.chat.dto.ChatRoomResponse;
import com.diving.pungdong.chat.dto.ChatParticipantsResponse;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.venue.VenueRefResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 방 해석·생성·참여자 동기화.
 *
 * <p><b>방 키는 세션 id 다</b>({@link ChatRoom} 참고) — 그래서 방 행이 없어도 {@code roomId} 를 알 수 있고,
 * 회차 카드 목록이 읽기 중에 행을 만들 필요가 없다. 실제 생성은 <b>참여 자격이 있는 사용자가 방을 처음
 * 열 때</b>(지연 생성)다.
 *
 * <p><b>왜 결제 완료 이벤트에 걸지 않았나</b>: {@code PaymentCompletedEvent} 를 들으면 결제 코드 무변경으로
 * 방을 미리 만들 수 있어 매력적이지만, {@code PaymentService.publishPaymentCompleted} javadoc 이
 * "알림 리스너는 결제 트랜잭션에 합류하므로 여기서 실패하면 <b>승인된 결제가 롤백</b>된다" 고 경고한다.
 * 리스너는 {@code @Transactional(MANDATORY)} 라 방 생성이 던지면 승인된 결제가 날아간다. 지연 생성은
 * 결제·환불 write path 를 전혀 건드리지 않는다.
 *
 * <p>이 서비스는 enrollment/availability 의 <b>레포만</b> 참조한다(서비스 아님) — enrollment 쪽이
 * {@link ChatQueryService} 를 주입받으므로, 서비스끼리 물리면 Spring Boot 2.6+ 가 금지하는 순환 참조가 된다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomJpaRepo roomRepo;
    private final ChatParticipantJpaRepo participantRepo;
    private final ChatMessageJpaRepo messageRepo;
    private final ChatReadStateJpaRepo readStateRepo;
    private final AvailabilitySessionJpaRepo sessionRepo;
    private final EnrollmentRoundJpaRepo roundRepo;
    private final AccountJpaRepo accountRepo;
    private final VenueRefResolver venueRefResolver;

    /** 방을 열 때 첫 줄로 남는 안내. ⚠️ 날짜를 넣지 않는다 — 접두는 FE 가 sentAt 으로 합성한다. */
    static final String OPENED_SYSTEM_TEXT = "회차 채팅방이 열렸어요";

    /* ─── 조회 진입점 ─────────────────────────────────────────── */

    /**
     * 방 상세 — 없으면 만든다. {@code GET /chat/rooms/{roomId}}.
     *
     * <p>비참여자·없는 방은 {@link ResourceNotFoundException}(존재 숨김) — 이 레포의 관용구이고, HTTP 로는
     * <b>404 가 아니라 400 + code -1009</b> 로 나간다({@code ExceptionAdvice}). FE 는 status 가 아니라
     * body 의 code 로 딥링크 폴백을 건다.
     */
    @Transactional
    public ChatRoomResponse open(Account viewer, Long roomId) {
        ChatRoom room = requireAccessibleRoom(viewer, roomId);
        List<ChatParticipant> active = participantRepo.findByRoomIdAndLeftAtIsNull(room.getId());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean sessionAlive = sessionRepo.existsById(room.getId());
        ChatRoomState state = stateOf(room, sessionAlive, now);

        return ChatRoomResponse.builder()
                .roomId(room.getId())
                .state(state)
                .closesInSeconds(state == ChatRoomState.ACTIVE
                        ? ChatRooms.closesInSeconds(room.getClosesAt(), now) : null)
                .courseTitle(room.getCourseTitle())
                .roundIndex(room.getRoundIndex())
                .date(room.getDate())
                .startTime(room.getStartTime())
                .endTime(room.getEndTime())
                .venueName(room.getVenueName())
                .participantCount(active.size())
                .participants(toParticipantResponses(active))
                .unreadCount(unreadCount(viewer.getId(), room.getId()))
                .latestMessageId(messageRepo.findFirstByRoomIdOrderByIdDesc(room.getId())
                        .map(ChatMessage::getId).orElse(null))
                .build();
    }

    /** 참여자 목록 — 헤더 부제 확장용. <b>현재 참여자만</b>(이탈자 제외). */
    @Transactional
    public ChatParticipantsResponse participants(Account viewer, Long roomId) {
        ChatRoom room = requireAccessibleRoom(viewer, roomId);
        List<ChatParticipant> active = participantRepo.findByRoomIdAndLeftAtIsNull(room.getId());
        return ChatParticipantsResponse.builder()
                .participants(toParticipantResponses(active))
                .participantCount(active.size())
                .build();
    }

    /* ─── 접근 판정 + 지연 생성 ──────────────────────────────── */

    /**
     * 접근 가능한 방을 돌려주거나 던진다. 살아 있는 세션이면 방을 만들고/갱신하고 참여자를 맞춘다.
     *
     * <p>세션이 이미 물리 삭제됐으면(전원 환불) 새로 만들지 않고 <b>기존 방만</b> 읽는다 — 그 방은 CLOSED 로
     * 파생되며, 이 경로가 있어야 옛 푸시를 눌러도 대화가 열린다.
     */
    ChatRoom requireAccessibleRoom(Account viewer, Long roomId) {
        Optional<AvailabilitySession> session = sessionRepo.findById(roomId);
        if (session.isEmpty()) {
            ChatRoom room = roomRepo.findById(roomId).orElseThrow(ResourceNotFoundException::new);
            requireActiveParticipant(room.getId(), viewer.getId());
            return room;
        }
        return openForLiveSession(viewer, session.get());
    }

    private ChatRoom openForLiveSession(Account viewer, AvailabilitySession session) {
        List<EnrollmentRound> paid = roundRepo.findByAvailabilitySessionIdAndStatusIn(
                session.getId(), EnrollmentStatus.OCCUPYING);
        // 결제자가 0 이면 방이 아직 "생기지 않은" 상태다 — 디자인의 "결제 전 = 미생성".
        if (paid.isEmpty() || !isEligible(viewer.getId(), session, paid)) {
            throw new ResourceNotFoundException();
        }
        ChatRoom room = ensureRoom(session, paid);
        reconcileParticipants(room, session, paid);
        return room;
    }

    /** 강사(슬롯 소유자)이거나, 그 세션에 결제완료 회차를 가진 수강생이면 참여 자격이 있다. */
    private boolean isEligible(Long accountId, AvailabilitySession session, List<EnrollmentRound> paid) {
        if (session.getInstructor() != null && accountId.equals(session.getInstructor().getId())) {
            return true;
        }
        return paid.stream().anyMatch(r -> accountId.equals(studentIdOf(r)));
    }

    /** 방을 만들거나(첫 진입) 스냅샷·마감을 갱신한다. 슬롯 시간이 바뀌면 마감이 <b>늘어날 수도</b> 있다. */
    private ChatRoom ensureRoom(AvailabilitySession session, List<EnrollmentRound> paid) {
        OffsetDateTime closesAt = ChatRooms.closesAt(session);
        Optional<ChatRoom> existing = roomRepo.findById(session.getId());
        if (existing.isPresent()) {
            ChatRoom room = existing.get();
            applySnapshot(room, session, paid, closesAt);
            return room;
        }
        ChatRoom room = ChatRoom.builder()
                .id(session.getId())
                .instructorId(session.getInstructor() == null ? null : session.getInstructor().getId())
                .closesAt(closesAt)
                .isNew(true)
                .build();
        applySnapshot(room, session, paid, closesAt);
        ChatRoom saved = roomRepo.save(room);
        messageRepo.save(ChatMessage.builder()
                .roomId(saved.getId())
                .kind(ChatMessageKind.SYSTEM)
                .text(OPENED_SYSTEM_TEXT)
                .deleted(false)
                .build());
        return saved;
    }

    /** 헤더 표시에 필요한 것만 박는다 — 세션이 사라져도 "AIDA2 2회차 · 12/10 수" 가 깨지지 않게. */
    private void applySnapshot(ChatRoom room, AvailabilitySession session,
                               List<EnrollmentRound> paid, OffsetDateTime closesAt) {
        room.setDate(session.getDate());
        room.setStartTime(session.getStartTime());
        room.setEndTime(session.getEndTime());
        if (closesAt != null) {
            room.setClosesAt(closesAt);
        }
        if (session.getInstructor() != null) {
            room.setInstructorId(session.getInstructor().getId());
        }
        room.setVenueName(resolveVenueName(session.getVenueRefId()));
        paid.stream().findFirst().ifPresent(r -> {
            Enrollment enrollment = r.getEnrollment();
            if (enrollment != null && enrollment.getCourse() != null) {
                room.setCourseTitle(enrollment.getCourse().getTitle());
            }
            room.setRoundIndex(r.getRoundIndex());
        });
    }

    private String resolveVenueName(String venueRefId) {
        if (venueRefId == null) {
            return null;
        }
        return Optional.ofNullable(venueRefResolver.resolveAll(List.of(venueRefId)).get(venueRefId))
                .map(VenueRefResolver.Resolved::getName)
                .orElse(null);
    }

    /* ─── 참여자 동기화 ──────────────────────────────────────── */

    /**
     * enrollment 현재 상태로 참여자 행을 맞춘다 — 결제 완료로 합류, 환불·거절로 이탈.
     *
     * <p>이탈은 <b>행 삭제가 아니라 {@code leftAt}</b> 이다. 지우면 그 사람이 과거에 남긴 말풍선의 이름이
     * 빈칸이 된다. 재신청으로 돌아오면 UNIQUE 때문에 새로 못 넣으므로 같은 행을 되살린다.
     */
    void reconcileParticipants(ChatRoom room, AvailabilitySession session, List<EnrollmentRound> paid) {
        Map<Long, ChatParticipantRole> expected = new LinkedHashMap<>();
        if (session.getInstructor() != null) {
            expected.put(session.getInstructor().getId(), ChatParticipantRole.INSTRUCTOR);
        }
        for (EnrollmentRound r : paid) {
            Long studentId = studentIdOf(r);
            if (studentId != null) {
                expected.putIfAbsent(studentId, ChatParticipantRole.STUDENT);
            }
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<ChatParticipant> known = participantRepo.findByRoomId(room.getId());
        Set<Long> knownIds = known.stream().map(ChatParticipant::getAccountId).collect(Collectors.toSet());

        for (ChatParticipant p : known) {
            if (expected.containsKey(p.getAccountId())) {
                p.rejoin();
                p.setRole(expected.get(p.getAccountId()));
            } else {
                p.leave(now);
            }
        }
        List<ChatParticipant> added = new ArrayList<>();
        expected.forEach((accountId, role) -> {
            if (!knownIds.contains(accountId)) {
                added.add(ChatParticipant.builder()
                        .roomId(room.getId()).accountId(accountId).role(role).joinedAt(now).build());
            }
        });
        if (!added.isEmpty()) {
            participantRepo.saveAll(added);
        }
    }

    private static Long studentIdOf(EnrollmentRound round) {
        Enrollment enrollment = round.getEnrollment();
        if (enrollment == null || enrollment.getStudent() == null) {
            return null;
        }
        return enrollment.getStudent().getId();
    }

    /* ─── 권한 · 상태 · 표시 ─────────────────────────────────── */

    ChatParticipant requireActiveParticipant(Long roomId, Long accountId) {
        return participantRepo.findByRoomIdAndAccountId(roomId, accountId)
                .filter(ChatParticipant::isActive)
                .orElseThrow(ResourceNotFoundException::new);
    }

    /** 세션이 사라졌거나 마감이 지나면 CLOSED. 저장하지 않고 읽을 때 판단한다. */
    static ChatRoomState stateOf(ChatRoom room, boolean sessionAlive, OffsetDateTime now) {
        return (!sessionAlive || room.isClosedAt(now)) ? ChatRoomState.CLOSED : ChatRoomState.ACTIVE;
    }

    int unreadCount(Long accountId, Long roomId) {
        List<Object[]> rows = messageRepo.countUnreadByRoom(accountId, List.of(roomId));
        return rows.isEmpty() ? 0 : ((Number) rows.get(0)[1]).intValue();
    }

    /**
     * 참여자 → 표시 DTO. 닉네임은 계정에서 일괄 조회한다(id 모아 IN 조회 — 이 레포의 N+1 회피 관례).
     * 강사 먼저, 그다음 이름순으로 정렬해 헤더 "김민지 강사 외 2명" 이 안정적으로 나오게 한다.
     */
    List<ChatParticipantResponse> toParticipantResponses(List<ChatParticipant> participants) {
        Map<Long, String> nickNames = nickNamesOf(
                participants.stream().map(ChatParticipant::getAccountId).collect(Collectors.toList()));
        return participants.stream()
                .sorted(Comparator
                        .comparing((ChatParticipant p) -> p.getRole() == ChatParticipantRole.INSTRUCTOR ? 0 : 1)
                        .thenComparing(p -> nickNames.getOrDefault(p.getAccountId(), "")))
                .map(p -> {
                    String nickName = nickNames.get(p.getAccountId());
                    return ChatParticipantResponse.builder()
                            .accountId(p.getAccountId())
                            .displayName(p.getRole().displayName(nickName))
                            .name(nickName)
                            .initials(ChatRooms.initials(nickName))
                            .role(p.getRole())
                            .build();
                })
                .collect(Collectors.toList());
    }

    Map<Long, String> nickNamesOf(List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new LinkedHashMap<>();
        for (Account a : accountRepo.findAllById(accountIds)) {
            out.put(a.getId(), a.getNickName());
        }
        return out;
    }
}
