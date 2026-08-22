package com.diving.pungdong.course;

import java.util.Collection;
import java.util.Set;

/**
 * 회차(CourseRound)가 <b>수강 기록에 물려 있는지</b> 알려준다 — 구현은 {@code enrollment} 패키지에 있다
 * ({@code enrollment.CourseRoundUsageAdapter}).
 *
 * <p><b>왜 인터페이스를 여기 두고 구현을 저쪽에 두나.</b> 코스 수정은 사라진 회차를 지우는데, 그 회차를
 * {@code EnrollmentRound} 가 FK 로 참조하고 있으면 DB 가 거부한다(참조 무결성). 지우기 전에 물어보려면
 * 코스가 수강 쪽을 알아야 하는데, <b>{@code enrollment} 가 이미 {@code course} 를 참조한다</b>. 여기서
 * {@code course} 가 {@code enrollment} 를 import 하면 서로를 참조하게 된다 — 이 레포는 도메인 간 의존을
 * 단방향으로 유지한다(루트 CLAUDE.md). 그래서 방향을 뒤집지 않고 <b>필요한 쪽이 계약만 선언</b>하고, 이미
 * 양쪽을 다 아는 {@code enrollment} 가 구현한다. {@link InstructorSummaryProvider} 와 같은 seam 이다.
 *
 * <p><b>정확히 "행이 존재하는가" 다.</b> 취소·거절된 수강도 회차 행은 남으므로 여기 걸린다 — 그게 맞다.
 * FK 는 상태를 보지 않기 때문에, 상태로 걸러 지웠다가는 그대로 500 이 난다.
 */
public interface CourseRoundUsageProbe {

    /**
     * @param courseRoundIds 지우려는 회차 id 들(비어 있으면 빈 집합).
     * @return 그중 <b>수강 기록이 참조 중인</b> 회차 id 집합. 지워도 되는 것만 남기려면 차집합을 쓴다.
     */
    Set<Long> inUse(Collection<Long> courseRoundIds);
}
