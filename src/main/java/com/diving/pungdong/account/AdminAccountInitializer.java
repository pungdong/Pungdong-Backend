package com.diving.pungdong.account;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 어드민 지정 부트스트랩. 권한(authorization)은 항상 DB role 이 source of truth 이고, "누구를
 * admin 으로" 의 목록만 env allowlist(`pungdong.admin.emails`, 콤마구분)로 관리한다 —
 * 보안 자격이라 secret/env 에 두지 Sanity 같은 CMS 에 두지 않는다.
 *
 * <p>부팅 시 그 이메일들의 계정에 {@code ROLE_ADMIN} 을 보장(idempotent). admin 은 <b>일반 가입
 * 후</b> 이메일이 목록에 있으면 승격된다. 가입 전이면 부여 못 함 → 다음 기동 시 부여.
 *
 * <p>env 미설정이면 no-op (dev/test 안전).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final AccountJpaRepo accountJpaRepo;

    @Value("${pungdong.admin.emails:}")
    private String adminEmailsCsv;

    @Override
    public void run(ApplicationArguments args) {
        ensureAdmins(parse(adminEmailsCsv));
    }

    /** allowlist 의 각 이메일 계정에 ROLE_ADMIN 보장. 이미 있으면 건너뜀, 계정 없으면 경고만. */
    public void ensureAdmins(List<String> emails) {
        for (String email : emails) {
            accountJpaRepo.findByEmail(email).ifPresentOrElse(
                    account -> {
                        if (account.getRoles().add(Role.ADMIN)) {   // Set.add → 새로 추가됐을 때만 true
                            accountJpaRepo.save(account);
                            log.info("[admin-bootstrap] granted ROLE_ADMIN to {}", email);
                        }
                    },
                    () -> log.warn("[admin-bootstrap] account not found for admin email '{}' — 가입 후 재기동 시 부여됨", email)
            );
        }
    }

    private List<String> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }
}
