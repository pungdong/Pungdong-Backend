package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.account.audit.NickNameAudit;
import com.diving.pungdong.account.audit.NickNameAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 닉네임 사전 점검 — {@code account.nick_name} 에 UNIQUE 를 걸기 전에 실데이터가 어떤 상태인지 재는 진단.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양. D* 중복 / R* 예약어 / U* URL 불가 / S* 정상.
 *
 * <p>이 판정 로직이 곧 <b>dedupe 가 무엇을 바꿀지</b>를 결정하므로, "몇 건이 바뀌는가"를 여기서 못 박는다.
 * 유저에게 보이는 식별자를 바꾸는 작업이라 추정으로 둘 수 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
class NickNameAuditUseCaseTest {

    @Autowired AccountJpaRepo accountRepo;
    @Autowired NickNameAuditService auditService;

    @AfterEach
    void cleanUp() {
        accountRepo.deleteAll();
    }

    private Account account(String email, String nickName) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nickName)
                .roles(new HashSet<>(Set.of(Role.STUDENT)))
                .isDeleted(false)
                .build());
    }

    /* ════════════════ D — 중복 ════════════════ */

    @Test
    @DisplayName("D1: 대소문자만 다른 닉네임은 같은 중복으로 잡고, 가장 오래된 계정이 이름을 지킨다")
    void caseInsensitiveDuplicatesAreGrouped() {
        Account first = account("d1a@test.com", "Owen");
        Account second = account("d1b@test.com", "owen");
        Account third = account("d1c@test.com", "OWEN");

        NickNameAudit audit = auditService.run();

        assertThat(audit.getDuplicates()).hasSize(1);
        NickNameAudit.DuplicateGroup group = audit.getDuplicates().get(0);
        assertThat(group.getNormalizedNickName()).isEqualTo("owen");
        // id 오름차순 — 첫 번째(가장 오래된)가 이름을 지키고 나머지 2건이 바뀐다.
        assertThat(group.getAccountIds()).containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(audit.renameCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("D2: 닉네임이 null 인 계정(탈퇴 익명화)은 중복으로 세지 않는다 — UNIQUE 는 다중 NULL 을 허용한다")
    void nullNickNamesAreNotDuplicates() {
        account("d2a@test.com", null);
        account("d2b@test.com", null);

        NickNameAudit audit = auditService.run();

        assertThat(audit.getDuplicates()).isEmpty();
        assertThat(audit.getNullNickNames()).isEqualTo(2);
        assertThat(audit.renameCount()).isZero();
    }

    /* ════════════════ R — 예약어 ════════════════ */

    @Test
    @DisplayName("R1: 닉네임이 'public' 인 계정을 잡아낸다 — 기존 /instructors/public 리터럴에 가려 프로필이 안 열린다")
    void reservedNickNameIsReported() {
        Account holder = account("r1@test.com", "public");

        NickNameAudit audit = auditService.run();

        assertThat(audit.getReservedWordHolders()).hasSize(1);
        assertThat(audit.getReservedWordHolders().get(0).getAccountId()).isEqualTo(holder.getId());
        assertThat(audit.getReservedWordHolders().get(0).getNickName()).isEqualTo("public");
    }

    @Test
    @DisplayName("R2: 예약어 판정도 대소문자를 무시한다")
    void reservedCheckIsCaseInsensitive() {
        account("r2@test.com", "Admin");

        assertThat(auditService.run().getReservedWordHolders()).hasSize(1);
    }

    @Test
    @DisplayName("R3: 브랜드명을 품은 '풍덩공식' 도 잡아낸다 — 리포트가 가입 차단 규칙(NickNamePolicy)과 같은 판정을 쓴다")
    void reservedCheckUsesTheSamePolicyAsSignUp() {
        account("r3@test.com", "풍덩공식");

        assertThat(auditService.run().getReservedWordHolders())
                .extracting(NickNameAudit.AffectedAccount::getNickName)
                .containsExactly("풍덩공식");
    }

    /* ════════════════ U — URL 불가 ════════════════ */

    @Test
    @DisplayName("U1: '/' 나 '\\' 가 든 닉네임을 잡아낸다 — 인코딩해도 방화벽이 거부해 프로필이 안 열린다")
    void urlUnsafeNickNamesAreReported() {
        account("u1a@test.com", "diver/pro");
        account("u1b@test.com", "diver\\pro");
        account("u1c@test.com", "diver.pro+1");   // 이건 정상 — 잡히면 안 된다

        NickNameAudit audit = auditService.run();

        assertThat(audit.getUrlUnsafeHolders()).hasSize(2);
        assertThat(audit.getUrlUnsafeHolders())
                .extracting(NickNameAudit.AffectedAccount::getNickName)
                .containsExactlyInAnyOrder("diver/pro", "diver\\pro");
    }

    /* ════════════════ S — 정상 ════════════════ */

    @Test
    @DisplayName("S1: 문제가 없으면 clean 으로 보고한다 — UNIQUE 인덱스를 그대로 걸 수 있다는 뜻")
    void cleanDataIsReportedAsClean() {
        account("s1a@test.com", "diverA");
        account("s1b@test.com", "diverB");

        NickNameAudit audit = auditService.run();

        assertThat(audit.isClean()).isTrue();
        assertThat(audit.getTotalAccounts()).isEqualTo(2);
        assertThat(audit.renameCount()).isZero();
    }
}
