package com.diving.pungdong.enrollment.dto;

import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.enrollment.CourseScheduleStatus;
import com.diving.pungdong.enrollment.RoundScheduleStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /** 강의 카드(같은 course 의 회차들). */
    @Getter
    @Builder
    public static class ScheduleCourse {
        private final Long courseId;
        private final String title;
        private final String organizationCode; // 자격 단체 코드(Sanity) — CERTIFICATION만
        private final String disciplineCode;
        private final List<CertLevel> levels;
        private final String instructorName;
        private final CourseScheduleStatus status;
        private final List<ScheduleRound> rounds; // roundIndex 순
    }

    /** 회차(=EnrollmentRound 1건). */
    @Getter
    @Builder
    public static class ScheduleRound {
        /** 회차 id — 취소·결제·일정변경 등 행위 단위. */
        private final Long roundId;
        /** 정규 회차 번호(1..N). EXTRA 는 null. */
        private final Integer roundIndex;
        private final RoundScheduleStatus status;
        private final LocalDate date;
        private final LocalTime blockStart;
        private final LocalTime blockEnd;
        private final String venueRefId;
        private final String venueName;
        /** 신청 시점 추정 총액 스냅샷(원). 권위 결제금액은 POST /payments/prepare. */
        private final int amount;
        private final String rejectionReason; // REJECTED만
        private final LocalDateTime createdAt;
        private final LocalDateTime respondedAt;
    }
}
