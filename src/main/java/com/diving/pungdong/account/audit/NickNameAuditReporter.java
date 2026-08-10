package com.diving.pungdong.account.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 1회 닉네임 사전 점검 리포트 — <b>기본 OFF</b>.
 * 켜기: {@code pungdong.audit.nickname.enabled=true} (env {@code AUDIT_NICKNAME_ENABLED}).
 *
 * <p><b>왜 엔드포인트가 아니라 부팅 로그인가</b>: 이 숫자를 알아야 하는 곳은 staging/prod 인데, 지금
 * 인프라엔 그 DB 를 읽을 경로가 없다 — RDS 는 {@code publicly_accessible=false} 에 app SG 로만 열려 있고,
 * ECS Exec 는 비활성이며, 런타임 이미지엔 mysql 클라이언트도 없다. 새 조회 엔드포인트를 여는 건 공격
 * 표면을 늘리고, 배스천/Exec 활성화는 인프라 변경이다. 이미 있는 DB 커넥션으로 읽어 <b>CloudWatch 로
 * 이미 흐르는 로그</b>에 남기는 게 가장 가볍고 되돌리기 쉽다.
 *
 * <p><b>수명</b>: 확인이 끝나면 이 패키지({@code account/audit})를 통째로 지운다. 상시 기능이 아니다.
 *
 * <p><b>안전</b>: 조회 전용이다({@link NickNameAuditService} 가 {@code readOnly}, 레포에 쓰기 메서드
 * 없음). 닉네임 원문은 <b>운영자가 개별 안내해야 하는 두 부류</b>(예약어·URL 불가)만 남기고, 중복 그룹은
 * 정규화 값과 계정 id 만 남긴다 — 무엇을 고쳐야 하는지 알려면 원문이 필요하지만 그 이상은 안 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pungdong.audit.nickname.enabled", havingValue = "true")
public class NickNameAuditReporter implements ApplicationRunner {

    private final NickNameAuditService auditService;

    @Override
    public void run(ApplicationArguments args) {
        NickNameAudit audit = auditService.run();

        log.info("[nickname-audit] ===== 닉네임 사전 점검 시작 (읽기 전용) =====");
        log.info("[nickname-audit] 전체 계정 {}건, 닉네임 null {}건(탈퇴 익명화 — UNIQUE 다중 NULL 허용이라 무해)",
                audit.getTotalAccounts(), audit.getNullNickNames());

        if (audit.isClean()) {
            log.info("[nickname-audit] 중복·예약어·URL불가 모두 0건 — UNIQUE 인덱스를 그대로 걸 수 있다.");
        }

        log.info("[nickname-audit] 중복 그룹 {}개 → dedupe 시 이름이 바뀌는 계정 {}건",
                audit.getDuplicates().size(), audit.renameCount());
        audit.getDuplicates().forEach(group ->
                // 원문 대신 정규화 값 + id 만 — 어느 계정이 바뀌는지는 id 로 충분하다.
                log.info("[nickname-audit]   중복 '{}' → 계정 {} (첫 번째가 이름을 지키고 나머지에 접미사)",
                        group.getNormalizedNickName(), group.getAccountIds()));

        log.info("[nickname-audit] 예약어 보유 {}건 — 자동 변경하지 않는다. 공개 프로필이 안 열리므로 개별 안내 필요",
                audit.getReservedWordHolders().size());
        audit.getReservedWordHolders().forEach(a ->
                log.info("[nickname-audit]   예약어 account={} nickName='{}' deleted={}",
                        a.getAccountId(), a.getNickName(), a.isDeleted()));

        log.info("[nickname-audit] URL 불가(/ 또는 \\) {}건 — 방화벽이 거부해 프로필이 안 열린다. 개별 안내 필요",
                audit.getUrlUnsafeHolders().size());
        audit.getUrlUnsafeHolders().forEach(a ->
                log.info("[nickname-audit]   URL불가 account={} nickName='{}' deleted={}",
                        a.getAccountId(), a.getNickName(), a.isDeleted()));

        log.info("[nickname-audit] ===== 끝. 이 숫자를 승인받은 뒤에 dedupe 마이그레이션을 배포한다 =====");
    }
}
