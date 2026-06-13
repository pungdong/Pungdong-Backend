package com.diving.pungdong.discipline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 초기 종목 seed. 부팅 시 코드가 없으면 추가(idempotent) — 기존 값은 건드리지 않는다(이름/순서/활성
 * 변경은 추후 어드민 도구 몫). {@link com.diving.pungdong.account.AdminAccountInitializer} 와 동일 패턴.
 *
 * <p>자격증 필요 종목(스쿠버/프리다이빙) vs 불필요(수영/서핑)를 {@code requiresCertification} 으로 구분.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisciplineSeeder implements ApplicationRunner {

    private final DisciplineJpaRepo disciplineRepo;

    @Override
    public void run(ApplicationArguments args) {
        // 출시 scope = 프리다이빙 · 스쿠버 · 머메이드 (모두 자격증 필요).
        // 수영/서핑 등은 출시 후 추가 — discipline 행 추가(seed 또는 어드민 엔드포인트).
        seed("FREEDIVING", "프리다이빙", true, 1);
        seed("SCUBA", "스쿠버다이빙", true, 2);
        seed("MERMAID", "머메이드", true, 3);
    }

    private void seed(String code, String name, boolean requiresCertification, int sortOrder) {
        if (!disciplineRepo.existsByCode(code)) {
            disciplineRepo.save(Discipline.builder()
                    .code(code)
                    .name(name)
                    .requiresCertification(requiresCertification)
                    .active(true)
                    .sortOrder(sortOrder)
                    .build());
            log.info("[discipline-seed] inserted {} ({})", code, name);
        }
    }
}
