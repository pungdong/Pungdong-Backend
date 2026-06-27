package com.diving.pungdong.enrollment;

import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.RoundKind;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 다회차 진행 게이트(순차) — 한 수강에서 지금 신청 가능한 다음 회차를 정한다. 신청 서비스와 옵션 서비스가 공유.
 *
 * <p>규칙: 정규 회차는 아직 활성으로 안 잡은 가장 낮은 번호이며 (1회차거나 직전 정규회차 <b>done(수강 완료)</b>) 일
 * 때 열린다. 정규를 전부 done 하면 코스 EXTRA 정의가 열린다. 그 외 null(아직 잠김/전부 완료). 직전 회차를
 * 실제로 수강 완료해야 다음을 잡는다(docs/features/booking.md).
 */
final class RoundGate {

    private RoundGate() {
    }

    static CourseRound nextSchedulable(Enrollment enrollment) {
        Course course = enrollment.getCourse();
        if (course == null) {
            return null;
        }
        List<CourseRound> regulars = course.getRounds().stream()
                .filter(cr -> cr.getRoundKind() == RoundKind.REGULAR && cr.getRoundIndex() != null)
                .sorted(Comparator.comparing(CourseRound::getRoundIndex))
                .collect(Collectors.toList());
        for (CourseRound cr : regulars) {
            if (hasActiveRound(enrollment, cr.getRoundIndex())) {
                continue; // 이미 잡음(활성)
            }
            return cr.getRoundIndex() == 1 || isDone(enrollment, cr.getRoundIndex() - 1) ? cr : null;
        }
        boolean allDone = regulars.stream().allMatch(cr -> isDone(enrollment, cr.getRoundIndex()));
        if (allDone) {
            return course.getRounds().stream()
                    .filter(cr -> cr.getRoundKind() == RoundKind.EXTRA).findFirst().orElse(null);
        }
        return null;
    }

    private static boolean hasActiveRound(Enrollment enrollment, Integer roundIndex) {
        return enrollment.getRounds().stream().anyMatch(r -> r.getRoundKind() == RoundKind.REGULAR
                && Objects.equals(r.getRoundIndex(), roundIndex) && r.getStatus().isActive());
    }

    /** 직전 정규회차가 수강 완료(done = CONFIRMED + doneAt)인가 — 게이트 신호. */
    private static boolean isDone(Enrollment enrollment, Integer roundIndex) {
        return enrollment.getRounds().stream().anyMatch(r -> r.getRoundKind() == RoundKind.REGULAR
                && Objects.equals(r.getRoundIndex(), roundIndex) && r.isDone());
    }
}
