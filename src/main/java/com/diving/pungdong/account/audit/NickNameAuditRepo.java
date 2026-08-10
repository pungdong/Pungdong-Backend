package com.diving.pungdong.account.audit;

import com.diving.pungdong.account.Account;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 닉네임 사전 점검 전용 조회 — <b>전부 SELECT</b>. 쓰기 메서드가 하나도 없다.
 *
 * <p>일회성 진단이라 {@code AccountJpaRepo} 를 건드리지 않고 별도 인터페이스로 뒀다 — 확인이 끝나면
 * 이 패키지({@code account/audit})를 통째로 지우면 된다.
 *
 * <p>모든 비교를 {@code lower()} 로 정규화한다. 실 DB 는 collation 이 {@code utf8mb4_unicode_ci} 라
 * 대소문자를 이미 무시하지만, 그 사실에 기대면 <b>H2(테스트)에서는 대소문자를 구분해</b> 같은 쿼리가
 * 다르게 동작한다. 정규화를 쿼리에 명시해 두 환경의 판정을 일치시킨다.
 */
public interface NickNameAuditRepo extends Repository<Account, Long> {

    @Query("select count(a) from Account a")
    long countAll();

    @Query("select count(a) from Account a where a.nickName is null")
    long countNullNickNames();

    /** 대소문자 무시 기준 중복 닉네임(소문자 정규화 값)만. */
    @Query("select lower(a.nickName) from Account a where a.nickName is not null "
            + "group by lower(a.nickName) having count(a) > 1")
    List<String> findDuplicatedNickNames();

    /** 특정 정규화 닉네임을 쓰는 계정 id — id 오름차순(가장 오래된 계정이 이름을 지킨다). */
    @Query("select a.id from Account a where lower(a.nickName) = :normalized order by a.id asc")
    List<Long> findAccountIdsByNormalizedNickName(@Param("normalized") String normalized);

    @Query("select a from Account a where lower(a.nickName) in :reserved order by a.id asc")
    List<Account> findByReservedNickNames(@Param("reserved") Collection<String> reserved);

    /**
     * URL 로 열 수 없는 닉네임 — {@code /} 또는 {@code \} 포함. 인코딩해도 Spring Security 방화벽이
     * 거부하므로 이 계정들의 공개 프로필은 열리지 않는다.
     */
    @Query("select a from Account a where a.nickName is not null "
            + "and (locate('/', a.nickName) > 0 or locate('\\', a.nickName) > 0) order by a.id asc")
    List<Account> findUrlUnsafeNickNames();
}
