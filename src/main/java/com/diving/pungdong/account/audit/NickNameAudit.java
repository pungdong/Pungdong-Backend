package com.diving.pungdong.account.audit;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 닉네임 사전 점검 결과 — {@code account.nick_name} 에 UNIQUE 인덱스를 걸기 <b>전에</b> 실데이터가
 * 어떤 상태인지 파악하려고 만든 일회성 진단이다.
 *
 * <p>왜 필요한가: 닉네임이 공개 URL 식별자가 되면서 유일성이 필요해졌는데, dedupe 는 <b>유저에게 보이는
 * 식별자를 바꾸는 동작</b>이다. 몇 건인지 모르고 실행할 수 없다. 확인·승인 후 별도 마이그레이션으로 간다.
 */
@Getter
@Builder
public class NickNameAudit {

    /** 전체 계정 수. */
    private final long totalAccounts;

    /** 닉네임이 null 인 계정 수 — 탈퇴 익명화가 null 로 만든다. UNIQUE 는 다중 NULL 을 허용하므로 문제없다. */
    private final long nullNickNames;

    /** 대소문자 무시 기준 중복 그룹들. dedupe 시 각 그룹에서 가장 오래된(id 최소) 계정만 이름을 지킨다. */
    private final List<DuplicateGroup> duplicates;

    /** 닉네임이 예약어인 계정 — 이 계정들은 리터럴 라우트에 가려 공개 프로필이 열리지 않는다. */
    private final List<AffectedAccount> reservedWordHolders;

    /** 닉네임에 {@code /} 또는 {@code \} 가 든 계정 — Spring Security 방화벽이 거부해 프로필이 안 열린다. */
    private final List<AffectedAccount> urlUnsafeHolders;

    /** dedupe 로 이름이 바뀌게 될 계정 수 = 각 중복 그룹의 (구성원 - 1) 합. */
    public long renameCount() {
        return duplicates.stream().mapToLong(group -> group.getAccountIds().size() - 1L).sum();
    }

    public boolean isClean() {
        return duplicates.isEmpty() && reservedWordHolders.isEmpty() && urlUnsafeHolders.isEmpty();
    }

    @Getter
    @Builder
    public static class DuplicateGroup {
        /** 소문자로 정규화한 닉네임 — 그룹 키. */
        private final String normalizedNickName;
        /** 그 이름을 쓰는 계정 id 들(오름차순). 첫 번째가 이름을 지킨다. */
        private final List<Long> accountIds;
    }

    @Getter
    @Builder
    public static class AffectedAccount {
        private final Long accountId;
        /** 원문 — 무엇을 고쳐야 하는지 알아야 운영 안내가 가능해서 이 두 부류만 값을 남긴다(레포 기준 non-PII). */
        private final String nickName;
        private final boolean deleted;
    }
}
