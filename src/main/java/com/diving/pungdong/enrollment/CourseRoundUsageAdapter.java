package com.diving.pungdong.enrollment;

import com.diving.pungdong.course.CourseRoundUsageProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * {@link CourseRoundUsageProbe} 구현 — 코스가 회차를 지워도 되는지 수강 쪽에 묻는 창구.
 *
 * <p>계약이 {@code course} 에 있고 구현이 여기 있는 이유는 인터페이스 쪽 주석 참고(의존 방향을
 * {@code enrollment → course} 한 방향으로 유지). {@code branding.CourseInstructorSummaryAdapter} 와 같은 형태다.
 */
@Component
@RequiredArgsConstructor
public class CourseRoundUsageAdapter implements CourseRoundUsageProbe {

    private final EnrollmentRoundJpaRepo roundRepo;

    @Override
    @Transactional(readOnly = true)
    public Set<Long> inUse(Collection<Long> courseRoundIds) {
        if (courseRoundIds == null || courseRoundIds.isEmpty()) {
            return Collections.emptySet(); // 빈 in (:ids) 는 DB 마다 문법이 갈린다 — 아예 안 물어본다
        }
        return Set.copyOf(roundRepo.findReferencedCourseRoundIds(courseRoundIds));
    }
}
