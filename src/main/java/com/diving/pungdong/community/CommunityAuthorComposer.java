package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.community.dto.CommunityAuthorResponse;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 작성자 합성 — 닉네임·아바타에 <b>강사 여부와 공개 강의 수</b>를 얹는다. 강사 강조 UI(아바타 링 + ✓ +
 * "강사 · 강의 N")의 유일한 소스라 <b>피드 카드와 댓글 한 줄이 같은 모양</b>을 써야 한다.
 *
 * <p><b>별도 컴포넌트로 뺀 이유</b>: 게시물 서비스와 댓글 서비스가 같은 합성을 필요로 한다. 각자 구현하면
 * 한쪽만 고쳐지는 순간 같은 사람이 피드에선 강사로, 댓글에선 일반 유저로 보인다.
 *
 * <p><b>항상 일괄 조회다.</b> 계정 id 를 모아 강사 판정 1회 + 강의 수 group by 1회로 끝낸다 —
 * 작성자마다 조회하면 목록 크기만큼 쿼리가 나간다(N+1). 쿼리 수가 목록 크기와 무관해야 한다.
 */
@Component
@RequiredArgsConstructor
public class CommunityAuthorComposer {

    private final InstructorApplicationJpaRepo applicationRepo;
    private final CourseJpaRepo courseRepo;
    private final SiteSettingsProvider siteSettings;

    /**
     * 계정 목록 → {@code accountId → 작성자 응답}.
     *
     * <p>강의 수는 브랜딩의 {@code products.lessons} 와 <b>같은 규칙</b>을 따른다 —
     * {@code showSeededCourses} 게이팅까지 동일해서, 같은 강사의 프로필 강의 수와 커뮤니티 칩 숫자가
     * 어긋나지 않는다.
     */
    public Map<Long, CommunityAuthorResponse> compose(Collection<Account> accounts) {
        Set<Long> accountIds = accounts.stream().map(Account::getId).collect(Collectors.toSet());
        if (accountIds.isEmpty()) {
            return Map.of();
        }

        Set<Long> instructorIds = applicationRepo
                .findByAccountIdInAndStatus(accountIds, InstructorApplicationStatus.APPROVED).stream()
                .map(application -> application.getAccount().getId())
                .collect(Collectors.toSet());

        Map<Long, Long> lessonCounts = instructorIds.isEmpty()
                ? Map.of()
                : toCountMap(siteSettings.current().showSeededCourses()
                        ? courseRepo.countByInstructorIdsAndStatus(instructorIds, CourseStatus.OPEN)
                        : courseRepo.countByInstructorIdsAndStatusExcludingSeeded(instructorIds, CourseStatus.OPEN));

        Map<Long, CommunityAuthorResponse> result = new HashMap<>();
        for (Account account : accounts) {
            boolean isInstructor = instructorIds.contains(account.getId());
            result.put(account.getId(), CommunityAuthorResponse.builder()
                    .nickName(account.getNickName())
                    .avatarUrl(avatarUrlOf(account))
                    .instructor(isInstructor)
                    // 강사가 아니면 키 자체를 생략한다 — 0 을 주면 "강의 0개인 강사" 로 읽힌다.
                    .lessonCount(isInstructor
                            ? (int) (long) lessonCounts.getOrDefault(account.getId(), 0L)
                            : null)
                    .build());
        }
        return result;
    }

    private Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((Long) row[0], (Long) row[1]);
        }
        return map;
    }

    private String avatarUrlOf(Account account) {
        return ProfilePhoto.displayUrlOf(account);
    }
}
