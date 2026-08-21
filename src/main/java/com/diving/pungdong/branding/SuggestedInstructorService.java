package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.block.BlockService;
import com.diving.pungdong.branding.dto.SuggestedInstructorResponse;
import com.diving.pungdong.branding.dto.SuggestedInstructorsResponse;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 추천 강사 — 커뮤니티 사이드바("이 강사님은 어때요?")와 홈의 공식 강사 카드가 공유하는 한 API.
 *
 * <p><b>왜 기존 {@code GET /instructors/public} 에 얹지 않았나.</b> 그 목록은 페이징되고 정렬이
 * "최근 가입순" 으로 계약에 못 박혀 있다({@code types.ts}). 랜덤은 페이지네이션과 상충한다 —
 * 페이지마다 순서가 다시 뽑히면 같은 강사가 2페이지에 또 나오거나 아예 안 나온다.
 * 그래서 표면은 새로 두되 <b>조회 로직은 재사용</b>한다(승인 판정·종목 일괄 조회 모두 기존 것).
 *
 * <p><b>왜 {@code ORDER BY RAND()} 가 아닌가.</b> JPQL 표준이 아니라 네이티브 쿼리가 되고,
 * 레포에 선례가 없으며, H2(테스트)와 MySQL(운영)에서 같은지 따로 증명해야 한다. 후보 id 를 받아
 * 앱에서 셔플하면 DB 방언에 의존하지 않는다 — 강사가 수만 명이 되기 전까지는 이쪽이 싸고 안전하다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SuggestedInstructorService {

    /** 사이드바 카드용이라 크게 받을 이유가 없다. 클라이언트가 limit 를 키워 전수 수집하는 것도 막는다. */
    private static final int MAX_LIMIT = 20;

    private final AccountBrandingJpaRepo brandingRepo;
    private final AccountJpaRepo accountRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    /** 차단한(또는 나를 차단한) 강사는 추천하지 않는다. */
    private final BlockService blockService;

    /**
     * 무작위 {@code limit} 명 + 추천 가능한 강사 총 수.
     *
     * <p><b>매 요청 다시 뽑는다</b> — 새로고침할 때마다 다른 강사가 뜨는 게 이 위젯의 의도다
     * (한 명만 계속 노출되면 나머지 강사에게 순서가 영영 안 온다). 캐시를 두지 않는 이유이기도 하다.
     *
     * <p>강사가 {@code limit} 보다 적으면 <b>있는 만큼만</b> 온다. 빈 목록도 정상 응답(200)이다 —
     * 승인된 강사가 아직 없거나 아무도 프로필을 발행하지 않은 건 실패가 아니라 사실이다.
     */
    public SuggestedInstructorsResponse suggest(int limit, Account viewer) {
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<Long> candidates = new ArrayList<>(brandingRepo.findSuggestableInstructorAccountIds());
        // 차단 관계인 강사는 후보에서 뺀다. 페이징이 없는 목록이라 메모리에서 걸러도 개수가
        // 어긋나지 않는다 — totalCount 도 걸러낸 뒤의 수여야 "N명 중 무작위" 가 사실이 된다.
        Set<Long> blocked = blockService.relatedAccountIds(viewer == null ? null : viewer.getId());
        if (!blocked.isEmpty()) {
            candidates.removeAll(blocked);
        }
        long totalCount = candidates.size();
        if (candidates.isEmpty()) {
            return SuggestedInstructorsResponse.builder().totalCount(0).instructors(List.of()).build();
        }

        Collections.shuffle(candidates, ThreadLocalRandom.current());
        List<Long> picked = candidates.subList(0, Math.min(size, candidates.size()));

        // 고른 N명만 살을 붙인다 — 계정·종목 각각 한 번씩(N+1 회피, 기존 디렉토리와 같은 패턴).
        Map<Long, Account> accounts = accountRepo.findAllById(picked).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<Long, List<String>> disciplines = applicationRepo
                .findByAccountIdInAndStatus(picked, InstructorApplicationStatus.APPROVED).stream()
                .collect(Collectors.groupingBy(application -> application.getAccount().getId(),
                        Collectors.mapping(InstructorApplication::getDisciplineCode, Collectors.toList())));

        // 셔플한 순서를 그대로 유지한다 — findAllById 는 순서를 보장하지 않아 id 순으로 되돌아온다.
        List<SuggestedInstructorResponse> cards = picked.stream()
                .map(accounts::get)
                .filter(java.util.Objects::nonNull)
                .map(account -> SuggestedInstructorResponse.builder()
                        .nickName(account.getNickName())
                        .avatarUrl(ProfilePhoto.displayUrlOf(account))
                        .disciplineCodes(disciplines.getOrDefault(account.getId(), List.of()))
                        .build())
                .collect(Collectors.toList());

        return SuggestedInstructorsResponse.builder().totalCount(totalCount).instructors(cards).build();
    }
}
