package com.diving.pungdong.enrollment.dto;

import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.enrollment.CourseScheduleStatus;
import com.diving.pungdong.enrollment.RoundScheduleStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 수강생 강의일정 hub 응답 — {@code GET /enrollments/mine/schedule}.
 * 내 신청들을 **강의(course) 단위로 그룹핑**하고 설계 상태어휘로 파생한다(docs/features/student-schedule.md).
 * 메모·세션채팅·결제만료·완료/리뷰/자격증은 BE 미구현이라 여기 없음(로드맵).
 */
@Getter
@AllArgsConstructor
public class ScheduleHubResponse {

    /** 필터 칩(전체 + 강의상태별 카운트). */
    private final List<FilterCount> filters;
    /** 강의 카드 — 액션 우선 정렬. */
    private final List<ScheduleCourse> courses;

    @Getter
    @AllArgsConstructor
    public static class FilterCount {
        private final String id;    // "all" 또는 CourseScheduleStatus 이름
        private final String label; // 한글 표시
        private final int count;
    }

    /** 강의 카드(한 수강 = 한 강의). */
    @Getter
    @Builder
    public static class ScheduleCourse {
        private final Long enrollmentId; // 수강 id — 다음 회차 신청(POST /enrollments/{id}/rounds) 대상
        private final Long courseId;
        private final String title;
        private final String organizationCode; // 자격 단체 코드(Sanity) — CERTIFICATION만
        private final String disciplineCode;
        private final List<CertLevel> levels;
        private final String instructorName;
        private final CourseScheduleStatus status;
        /**
         * 이 수강으로 <b>자격증을 등록할 수 있는가</b>(정규 회차 전부 이수). 자격증 등록 폼의 "강의 연결"
         * 피커는 <b>이 값</b>으로 거른다 — {@code status === 'COMPLETED'} 로 거르면 정규를 다 끝낸 뒤
         * 추가세션(EXTRA)을 잡은 동안 카드가 {@code PROGRESS} 로 돌아가면서 <b>이미 취득한 자격증의
         * 강의가 피커에서 사라진다</b>. 표시용 상태와 자격 판정은 다른 질문이다.
         */
        private final boolean certifiable;
        /** 정규 회차 총 수. FE 가 미잡힌(locked) 회차 placeholder 를 그릴 기준. */
        private final int totalRounds;
        /** 지금 신청 가능한 다음 정규 회차 번호(없으면 null — 직전 미확정/전부 완료). */
        private final Integer nextRoundIndex;
        /** 정규 다 끝나 추가세션(EXTRA) 신청 가능 여부. */
        private final boolean canScheduleExtra;
        private final List<ScheduleRound> rounds; // roundIndex 순(잡은 회차만)
    }

    /** 회차(=EnrollmentRound 1건). */
    @Getter
    @Builder
    public static class ScheduleRound {
        /** 회차 id — 취소·결제·일정변경 등 행위 단위. */
        private final Long roundId;
        /**
         * 이 회차가 붙은 일정(session) id. 슬롯 미배정/소멸이면 null.
         *
         * <p>⚠️ <b>채팅 진입에 쓰지 않는다</b> — 그건 {@link #chat} 의 {@code state}/{@code roomId} 로 판단한다.
         * 이 필드는 일정 단위 API(예: 세션 일괄 완료)를 부르기 위한 좌표다.
         */
        private final Long sessionId;
        /**
         * 회차 채팅 진입 정보. <b>항상 non-null</b> — 채팅이 없는 회차는 {@code state=HIDDEN} 이다.
         * null 과 HIDDEN 두 가지로 "안 보임" 을 표현하면 한쪽만 검사한 호출부가 조용히 버그가 된다.
         */
        private final com.diving.pungdong.chat.dto.RoundChatState chat;
        /** 정규 회차 번호(1..N). EXTRA 는 null. */
        private final Integer roundIndex;
        private final String roundKind; // REGULAR | EXTRA
        private final RoundScheduleStatus status;
        private final LocalDate date;
        private final LocalTime blockStart;
        private final LocalTime blockEnd;
        private final String venueRefId;
        private final String venueName;
        /** 신청 시점 추정 총액 스냅샷(원). 권위 결제금액은 POST /payments/prepare. */
        private final int amount;
        /** 내가 그 회차에 신청한 대여 장비 내역(신청 시점 스냅샷). 없으면 빈 배열. 강사 hub gearItems 와 동일 형태. */
        private final List<GearItem> gearItems;
        /** 강사 일정변경 제안 슬롯(RESCHEDULING 일 때) — 날짜+이용권+블록 완전 슬롯. 학생이 골라 pick-slot. */
        private final List<com.diving.pungdong.enrollment.ProposedSlot> proposedSlots;
        private final String rejectionReason; // REJECTED만
        /**
         * 결제 기한까지 남은 <b>초</b> — {@code PAYMENT_DUE}(미결제)일 때만 채워지고 그 외엔 null.
         * "OO분 안에 결제" 카운트다운의 단일 출처다. TTL 은 Sanity 운영값이라 배포 없이 바뀌므로 FE 가
         * 하드코딩하면 안 되고, 절대시각이 아니라 잔여 초인 이유는 기기 시계가 틀어져도 안 밀리게 하려는 것
         * ({@code otpExpiresInSeconds} 와 같은 규칙). ⚠️ 0 이 곧 결제 불가는 아니다 —
         * {@link com.diving.pungdong.enrollment.PaymentWindow} 주석 참고.
         */
        private final Long paymentExpiresInSeconds;
        private final OffsetDateTime createdAt;
        private final OffsetDateTime respondedAt;
    }
}
