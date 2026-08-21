package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.persistence.PageClamp;
import com.diving.pungdong.instructorapplication.dto.PublicInstructorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 공개 강사 디렉토리 — 승인된(APPROVED) 신청을 가진 실가입 강사만 카드로 노출. account 의 공개 필드(nickName·
 * 아바타)와 instructorapplication 의 승인 종목을 합친다. (account 는 이 패키지를 모르므로 합성을 여기서 — 단방향 유지.)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicInstructorService {

    private final InstructorApplicationJpaRepo applicationRepo;

    /**
     * <p>{@link PageClamp} 를 먼저 통과시킨다 — 상한이 없어 {@code ?size=100000} 으로 강사 명단을 통째로
     * 긁을 수 있었다. 클라이언트 {@code sort} 도 여기서 버린다: 아래 레포 쿼리에 {@code order by acc.id desc}
     * 가 리터럴로 박혀 있지만 <b>Spring Data 는 Pageable 의 정렬을 그 뒤에 덧붙인다</b> — 즉 클라이언트가
     * 임의 컬럼 정렬을 끼워 넣을 수 있었다(브랜딩 게시물이 먼저 막은 것과 같은 구멍).
     */
    public Page<PublicInstructorResponse> listPublicInstructors(Pageable pageable) {
        Page<Account> accounts = applicationRepo.findInstructorAccountsByStatus(
                InstructorApplicationStatus.APPROVED, PageClamp.fixed(pageable));
        List<Long> ids = accounts.getContent().stream().map(Account::getId).collect(Collectors.toList());

        // 카드별 승인 종목 코드 — 한 번에 모아 그룹핑(N+1 회피).
        Map<Long, List<String>> disciplinesByAccount = ids.isEmpty() ? Map.of()
                : applicationRepo.findByAccountIdInAndStatus(ids, InstructorApplicationStatus.APPROVED).stream()
                .collect(Collectors.groupingBy(a -> a.getAccount().getId(),
                        Collectors.mapping(InstructorApplication::getDisciplineCode, Collectors.toList())));

        return accounts.map(account -> PublicInstructorResponse.builder()
                .id(account.getId())
                .nickName(account.getNickName())
                .avatarUrl(account.getProfilePhoto() == null ? null : account.getProfilePhoto().getImageUrl())
                .disciplineCodes(disciplinesByAccount.getOrDefault(account.getId(), List.of()))
                .build());
    }
}
