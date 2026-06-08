package com.diving.pungdong.account;

import com.diving.pungdong.global.advice.exception.CEmailSigninFailedException;
import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.Gender;
import com.diving.pungdong.account.Role;
import org.assertj.core.util.Sets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@DataJpaTest
class AccountJpaRepoTest {
    @Autowired
    TestEntityManager em;

    @Autowired
    private AccountJpaRepo accountJpaRepo;

    @Test
    public void save() {
        Account account = Account.builder()
                .email("rrr@gmail.com")
                .password("1234")
                .nickName("yechan")
                .birth("1999-09-11")
                .gender(Gender.MALE)
                .roles(Set.of(Role.INSTRUCTOR))
                .build();

        Account savedAccount = accountJpaRepo.save(account);

        assertThat(savedAccount.getId()).isNotNull();
    }

    @Test
    @DisplayName("이메일로 단건 조회")
    public void findByEmail() {
        Account account = Account.builder()
                .email("rrr@gmail.com")
                .password("1234")
                .nickName("yechan")
                .birth("1999-09-11")
                .gender(Gender.MALE)
                .roles(Sets.newLinkedHashSet(Role.STUDENT))
                .build();

        Account savedAccount = accountJpaRepo.save(account);

        Account findAccount = accountJpaRepo.findByEmail(savedAccount.getEmail()).orElseThrow(CEmailSigninFailedException::new);

        assertThat(findAccount.getEmail()).isEqualTo(savedAccount.getEmail());
    }

    @Test
    @DisplayName("수정")
    public void update() {
        Account account = Account.builder()
                .email("rrr@gmail.com")
                .password("1234")
                .nickName("yechan")
                .birth("1999-09-11")
                .gender(Gender.MALE)
                .roles(Sets.newLinkedHashSet(Role.STUDENT))
                .build();

        Account savedAccount = accountJpaRepo.save(account);

        savedAccount.getRoles().add(Role.INSTRUCTOR);
        Account updatedAccount = accountJpaRepo.save(savedAccount);

        assertThat(updatedAccount.getRoles()).isEqualTo(savedAccount.getRoles());
    }

}