package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.branding.dto.InstructorBrowseCardResponse;
import com.diving.pungdong.branding.dto.InstructorBrowseCondition;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.persistence.PageClamp;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import com.diving.pungdong.instructorapplication.ApplicationCertificateJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 강사 둘러보기 — {@code GET /instructors/browse}. 홈 "풍덩 공식 강사" 더보기에서 들어오는 무한 스크롤 목록.
 *
 * <p><b>왜 branding 패키지인가.</b> URL 네임스페이스는 {@code /instructors} 지만, 이 목록의 모수는
 * {@code AccountBranding.isPublished} 이고 카드도 브랜딩 필드(한 줄 소개·활동지역)를 싣는다. branding 은
 * instructorapplication·course 를 단방향 참조해도 되지만 <b>그 반대는 순환</b>이다 —
 * {@code /instructors/suggested} 가 같은 이유로 이미 여기 있다.
 *
 * <p><b>기존 세 목록과의 관계</b>(셋 다 모수가 다르다, 헷갈리면 사고 난다):
 * <ul>
 *   <li>{@code /instructors/public} — 승인 강사 전부. <b>발행 여부를 안 본다</b> → 눌러도 400 인 카드가 섞인다.
 *       "몇 명이 검수를 통과했나" 를 세는 목록.</li>
 *   <li>{@code /instructors/suggested} — 승인 ∧ 발행 중 <b>무작위 N명</b>. 페이지네이션 불가(설계).</li>
 *   <li><b>여기</b> — 승인(그 종목) ∧ 발행. 필터·검색·정렬·페이지네이션이 되는 유일한 목록.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstructorBrowseService {

    /**
     * {@code organizationCodes} 필터가 꺼졌을 때 JPQL {@code in} 에 넘기는 더미.
     * 빈 컬렉션을 넘기면 {@code in ()} 이라 SQL 이 깨진다 — 조건은 {@code orgFilterOff} 로 단락되므로
     * 이 값이 평가될 일은 없다. (레포 쿼리의 주석과 짝이다.)
     */
    private static final List<String> ORG_FILTER_UNUSED = List.of("");

    private final AccountBrandingJpaRepo brandingRepo;
    private final AccountJpaRepo accountRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final ApplicationCertificateJpaRepo certificateRepo;
    private final SiteSettingsProvider siteSettings;

    /**
     * 한 페이지. 빈 결과는 예외가 아니라 빈 페이지(레포 규약: 음성 결과는 200) — 없는 종목 코드를
     * 넣어도 마찬가지다. 400 은 {@code disciplineCode} 자체가 없을 때뿐이다.
     */
    public Page<InstructorBrowseCardResponse> browse(InstructorBrowseCondition condition, Pageable pageable) {
        if (!StringUtils.hasText(condition.getDisciplineCode())) {
            throw new BadRequestException();
        }
        Pageable fixed = PageClamp.fixed(pageable);
        String discipline = condition.getDisciplineCode();

        String keyword = StringUtils.hasText(condition.getKeyword())
                ? "%" + condition.getKeyword().trim().toLowerCase() + "%" : null;
        boolean orgFilterOff = CollectionUtils.isEmpty(condition.getOrganizationCodes());
        List<String> orgCodes = orgFilterOff ? ORG_FILTER_UNUSED : condition.getOrganizationCodes();
        boolean hasOpenCourse = Boolean.TRUE.equals(condition.getHasOpenCourse());
        boolean showSeeded = siteSettings.current().showSeededCourses();

        InstructorBrowseCondition.Sort sort = condition.getSort() == null
                ? InstructorBrowseCondition.Sort.LATEST : condition.getSort();
        Page<Object[]> rows = sort == InstructorBrowseCondition.Sort.COURSE_COUNT_DESC
                ? brandingRepo.browseInstructorsByCourseCount(
                        discipline, keyword, orgFilterOff, orgCodes, hasOpenCourse, showSeeded, fixed)
                : brandingRepo.browseInstructorsLatest(
                        discipline, keyword, orgFilterOff, orgCodes, hasOpenCourse, showSeeded, fixed);

        return new PageImpl<>(hydrate(rows.getContent(), discipline),
                PageRequest.of(fixed.getPageNumber(), fixed.getPageSize()), rows.getTotalElements());
    }

    /**
     * 고른 강사들에게만 살을 붙인다 — 계정 / 브랜딩 / 승인 종목 / 단체를 <b>각각 한 번씩</b> 조회한다.
     * 카드마다 접근하면 페이지 크기만큼 쿼리가 나간다(추천 카드·공개 디렉토리와 같은 패턴).
     *
     * <p>레포가 준 <b>정렬 순서를 그대로 유지</b>한다 — {@code findAllById} 류는 순서를 보장하지 않아
     * id 순으로 되돌아온다. 정렬이 조용히 뒤집히면 "강의 많은순" 이 거짓말이 된다.
     */
    private List<InstructorBrowseCardResponse> hydrate(List<Object[]> rows, String discipline) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        Map<Long, Long> countByAccount = new java.util.HashMap<>();
        for (Object[] row : rows) {
            Long accountId = ((Number) row[0]).longValue();
            ids.add(accountId);
            countByAccount.put(accountId, ((Number) row[1]).longValue());
        }

        Map<Long, Account> accounts = accountRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<Long, AccountBranding> brandings = brandingRepo.findAllByAccountIdIn(ids).stream()
                .collect(Collectors.toMap(b -> b.getAccount().getId(), Function.identity(),
                        (first, second) -> first)); // account_id UNIQUE — 병합 함수는 형식상
        List<InstructorApplication> approved = applicationRepo
                .findByAccountIdInAndStatus(ids, InstructorApplicationStatus.APPROVED);
        Map<Long, List<String>> disciplines = approved.stream()
                .collect(Collectors.groupingBy(a -> a.getAccount().getId(),
                        Collectors.mapping(InstructorApplication::getDisciplineCode, Collectors.toList())));
        // 프로필 변경 시각의 두 번째 소스(자격·종목). 위에서 이미 읽은 목록이라 추가 쿼리는 없다.
        // updatedAt 은 옛 행에서 null 일 수 있어 건너뛴다(Map.merge 는 null 값에 NPE 를 낸다).
        Map<Long, OffsetDateTime> applicationTouchedAt = new java.util.HashMap<>();
        for (InstructorApplication a : approved) {
            if (a.getUpdatedAt() != null) {
                applicationTouchedAt.merge(a.getAccount().getId(), a.getUpdatedAt(), InstructorBrowseService::later);
            }
        }
        Map<Long, Set<String>> organizations = new java.util.HashMap<>();
        for (Object[] pair : certificateRepo.findOrganizationCodesByAccountIds(ids, discipline)) {
            organizations.computeIfAbsent(((Number) pair[0]).longValue(), k -> new TreeSet<>())
                    .add((String) pair[1]);
        }

        return ids.stream()
                .map(accounts::get)
                .filter(Objects::nonNull)
                .map(account -> {
                    AccountBranding branding = brandings.get(account.getId());
                    return InstructorBrowseCardResponse.builder()
                            .nickName(account.getNickName())
                            .avatarUrl(account.getProfilePhoto() == null
                                    ? null : account.getProfilePhoto().getImageUrl())
                            .tagline(branding == null ? null : branding.getTagline())
                            .locationLabel(branding == null ? null : branding.getLocationLabel())
                            .disciplineCodes(new ArrayList<>(new LinkedHashSet<>(
                                    disciplines.getOrDefault(account.getId(), List.of()))))
                            .organizationCodes(new ArrayList<>(
                                    organizations.getOrDefault(account.getId(), Set.of())))
                            .openCourseCount(countByAccount.getOrDefault(account.getId(), 0L))
                            .updatedAt(later(branding == null ? null : branding.getUpdatedAt(),
                                    applicationTouchedAt.get(account.getId())))
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 둘 중 나중 시각(둘 다 null 이면 null). 강사 카드의 {@code updatedAt} 은 단일 컬럼이 아니라
     * 여러 소스의 최대값이라 이게 필요하다 — 자세한 이유·한계는
     * {@link InstructorBrowseCardResponse#getUpdatedAt()}.
     */
    private static OffsetDateTime later(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
