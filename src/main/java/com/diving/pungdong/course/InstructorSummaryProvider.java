package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.course.dto.CourseInstructorResponse;

/**
 * 강의 상세에 실을 강사 요약을 공급한다 — <b>구현은 {@code branding} 패키지에 있다</b>
 * ({@code branding.CourseInstructorSummaryAdapter}).
 *
 * <p><b>왜 인터페이스를 여기 두고 구현을 저쪽에 두나 (핵심).</b> 요약에 필요한 값은 네 도메인에
 * 흩어져 있다 — 아바타({@code account}) · 인증마크·자격({@code instructorapplication}) ·
 * 한마디·자기소개({@code branding}) · 공개 강의 수({@code course}). 그런데 <b>{@code branding} 이
 * 이미 {@code course} 를 참조한다</b>(프로필의 강의 수·연결 강의). 여기서 {@code course} 가
 * {@code branding} 을 import 하면 <b>두 패키지가 서로를 참조</b>하게 된다 — 이 레포는 도메인 간
 * 의존을 단방향으로 유지한다(루트 CLAUDE.md).
 *
 * <p>그래서 방향을 뒤집지 않고 <b>필요한 쪽이 계약만 선언</b>하고, 이미 양쪽을 다 아는
 * {@code branding} 이 그 계약을 구현한다. 의존은 여전히 {@code branding → course} 한 방향이고,
 * Spring 이 부팅 시 구현체를 꽂는다.
 *
 * <p><b>목록에는 쓰지 말 것.</b> 단건 상세용이라 강사 1명을 조회당 몇 쿼리로 합성한다. 카드 목록에
 * 붙이려면 계정 묶음을 한 번에 받는 배치 메서드를 따로 추가해야 한다 — 그러지 않으면 목록 크기만큼
 * 쿼리가 나간다(N+1). {@code community.CommunityAuthorComposer} 가 그 배치 형태의 선례다.
 */
public interface InstructorSummaryProvider {

    /**
     * @param instructor 코스의 강사. {@code null} 이면(강사 없는 레거시 코스) {@code null} 을 돌려준다.
     * @return 강사 요약. 브랜딩 프로필을 만든 적 없어도 <b>null 이 아니다</b> — tagline·bio 만 null 이고
     *         닉네임·아바타·인증마크·자격은 브랜딩과 무관하게 채워진다.
     */
    CourseInstructorResponse summarize(Account instructor);
}
