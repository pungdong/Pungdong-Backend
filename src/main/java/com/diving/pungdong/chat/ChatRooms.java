package com.diving.pungdong.chat;

import com.diving.pungdong.availability.AvailabilitySession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 방 수명 계산 — civil 슬롯 시각을 절대시각으로 옮기는 <b>한 곳</b>.
 *
 * <p>{@code AvailabilitySession.date}/{@code endTime} 은 오프셋 없는 벽시계(civil)라 그대로는 타임라인
 * 위의 점이 아니다(docs/architecture/time-handling.md §1 이 "변환 금지 위험군" 으로 분류한 그 필드다).
 * 마감 판정은 절대시각 비교라 존을 붙여야 하는데, {@code venue.timeZone} 이 아직 없어(§5, 일본 확장과
 * 한 몸으로 미룸) <b>KST 고정</b>이다.
 *
 * <p>같은 이유로 같은 선택을 한 선례: {@code payment.RefundService} 의
 * {@code private static final ZoneId KST = ZoneId.of("Asia/Seoul")} — 환불율 기준일이 KST 운영 캘린더라
 * 오늘 날짜도 KST 로 잡는다. 글로벌화 때 이 상수를 {@code venue.timeZone} 으로 승격한다.
 */
final class ChatRooms {

    /** 세션 종료 후 이만큼 더 열어 둔다 — 수업 직후 정산·후기·분실물 같은 대화가 남는다. */
    static final int GRACE_HOURS = 24;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private ChatRooms() {
    }

    /** 마감 instant(UTC) = (date, endTime)@KST + 24h. 날짜/시간이 없으면 null. */
    static OffsetDateTime closesAt(LocalDate date, LocalTime endTime) {
        if (date == null || endTime == null) {
            return null;
        }
        return LocalDateTime.of(date, endTime)
                .atZone(KST)
                .plusHours(GRACE_HOURS)
                .toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC);
    }

    static OffsetDateTime closesAt(AvailabilitySession session) {
        return closesAt(session.getDate(), session.getEndTime());
    }

    /**
     * 마감까지 남은 초. 이미 지났거나 마감이 없으면 null.
     *
     * <p>응답에 절대시각 대신 잔여 초를 싣는 이유 — 기기 시계가 서버와 어긋나면 카운트다운이 그만큼
     * 밀린다({@code otpExpiresInSeconds}/{@code paymentExpiresInSeconds} 와 같은 규칙).
     */
    static Long closesInSeconds(OffsetDateTime closesAt, OffsetDateTime now) {
        if (closesAt == null || !now.isBefore(closesAt)) {
            return null;
        }
        return java.time.Duration.between(now, closesAt).getSeconds();
    }

    /** 아바타용 첫 글자. */
    static String initials(String nickName) {
        if (nickName == null || nickName.isBlank()) {
            return "";
        }
        return nickName.strip().substring(0, 1);
    }
}
