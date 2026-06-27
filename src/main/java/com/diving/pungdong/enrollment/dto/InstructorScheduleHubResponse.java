package com.diving.pungdong.enrollment.dto;

import com.diving.pungdong.course.CertLevel;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.diving.pungdong.enrollment.InstructorActionFlag;
import com.diving.pungdong.enrollment.InstructorEnrollmentStatus;
import com.diving.pungdong.enrollment.InstructorRoundStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 강사 수강관리 hub 응답 — {@code GET /instructor/enrollments/hub}. 거래 단위 = <b>수강(수강생×강의)</b>. 강사가
 * 신청 검토·일정변경 검토·세션 마무리를 한 곳에서. 학생 hub({@link ScheduleHubResponse})의 강사 거울.
 *
 * <p>디자인 핸드오프(features/enrollment-management): 카드 = 학생+이력 → 강의 + 회차들 + 액션 플래그/한 줄.
 * 정렬 = 액션필요 → 진행중 → 완료 → 취소. 회차 채팅/다이브로그는 미구현(별도 피처)이라 여기 없음.
 */
@Getter
@AllArgsConstructor
public class InstructorScheduleHubResponse {

    /** 필터 칩(전체 + 상태별 카운트). */
    private final List<FilterCount> filters;
    /** 거래 카드 — 액션 우선 정렬. */
    private final List<EnrollmentCard> enrollments;

    @Getter
    @AllArgsConstructor
    public static class FilterCount {
        private final String id;    // "all" | "action" | "progress" | "completed"
        private final String label;
        private final int count;
    }

    /** 학생 요약(강사 시점). 실명 미수집이라 표시는 nickName. */
    @Getter
    @Builder
    public static class StudentSummary {
        private final Long accountId;
        private final String name;       // nickName
        private final String initials;   // 이름 첫 글자(아바타)
        @JsonProperty("isNew")
        private final boolean isNew;     // 이 강사와 과거 완료 수강 0 (Jackson is-접두 회피)
        private final int historyCount;  // 과거 완료 수강 수(이 강사와)
    }

    /** 거래 카드(한 수강 = 수강생×강의). */
    @Getter
    @Builder
    public static class EnrollmentCard {
        private final Long enrollmentId;
        private final StudentSummary student;
        private final Long courseId;
        private final String courseTitle;
        private final String organizationCode;
        private final String disciplineCode;
        private final List<CertLevel> levels;
        private final InstructorEnrollmentStatus status;
        private final InstructorActionFlag flag;   // 1차 액션(없으면 null)
        private final String actionLine;           // 액션 안내 한 줄(없으면 null)
        private final int totalRounds;             // 정규 회차 총 수
        private final List<RoundCard> rounds;      // roundIndex 순(EXTRA 뒤)
    }

    /** 회차 카드(=EnrollmentRound 1건, 강사 시점). */
    @Getter
    @Builder
    public static class RoundCard {
        private final Long roundId;
        private final Integer roundIndex;
        private final String roundKind;            // REGULAR | EXTRA
        private final InstructorRoundStatus status;
        private final LocalDate date;
        private final LocalTime blockStart;
        private final LocalTime blockEnd;
        private final String venueRefId;
        private final String venueName;
        private final int amount;                  // 회차 추정 청구액 스냅샷
        private final int gearCount;               // 대여 장비 수
        /** 직전 슬롯(CHANGING 일 때 — 학생이 바꾸기 전 일정). 변경 검토 diff 용. 없으면 null. */
        private final SlotRef previousSlot;
    }

    @Getter
    @Builder
    public static class SlotRef {
        private final LocalDate date;
        private final String venueRefId;
        private final String ticketRef;
        private final LocalTime blockStart;
        private final LocalTime blockEnd;
    }
}
