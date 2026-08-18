package com.diving.pungdong.global.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.LongSupplier;

/**
 * UNIQUE 로 멱등성을 보장하는 삽입을 <b>별도 트랜잭션</b>에서 실행한다.
 *
 * <p><b>왜 별도 트랜잭션인가</b>: 제약 위반을 같은 트랜잭션 안에서 잡아 무시하면 멱등이 되지 않는다.
 * 위반 순간 트랜잭션이 rollback-only 로 표시돼 뒤이은 카운트 조회나 커밋이
 * {@code UnexpectedRollbackException} 으로 터진다 — <b>catch 해도 결국 500</b> 이다.
 * 삽입만 새 트랜잭션에 넣으면 실패가 그 안에서 끝나고, 바깥 트랜잭션은 멀쩡한 채로 "이미 있다" 를
 * 정상 흐름으로 이어갈 수 있다.
 *
 * <p>{@code saveAndFlush} 인 이유도 같다 — flush 를 미루면 위반이 <b>바깥</b> 커밋 시점에 터져
 * 격리한 의미가 없어진다.
 *
 * <p>예외는 여기서 삼키지 않는다. 이 경계를 넘어가야 안쪽 트랜잭션이 깨끗이 롤백되고, 호출부가
 * {@code DataIntegrityViolationException} 을 "경쟁 요청이 먼저 넣었다" 로 해석한다.
 *
 * <p><b>삽입 뒤의 카운트도 새 트랜잭션에서 읽어야 한다</b>({@link #countFresh}). MySQL(InnoDB) 기본
 * 격리는 REPEATABLE READ 라 바깥 트랜잭션은 <b>첫 SELECT 시점의 스냅샷</b>을 끝까지 본다 — 그 뒤에
 * 여기서 REQUIRES_NEW 로 커밋한 행은 바깥의 {@code count} 쿼리에 안 보인다. 그래서 POST 응답의
 * {@code count} 가 "내 것 빠진 값" 으로 나갔다(2026-08-17 FE 실측). 제거는 바깥 트랜잭션 자신이 지우니
 * 정확했다 — 이 비대칭이 지문이다. flush 문제가 아니다({@code saveAndFlush} + 커밋까지 끝난 행이다).
 * 테스트 H2 는 기본이 READ COMMITTED 라 재현이 안 됐다 — {@code CommunityReactionCountUseCaseTest} 가
 * <b>자기 컨텍스트만</b> 격리 수준을 REPEATABLE READ 로 올려 잠근다(전역으로 올리면 H2 2.1 이
 * REPEATABLE READ + ON DELETE CASCADE 에서 내부 NPE 를 내 게시물 삭제 테스트가 깨진다).
 */
@Component
public class IdempotentInsert {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> void insert(JpaRepository<T, ?> repo, T entity) {
        repo.saveAndFlush(entity);
    }

    /**
     * 카운트를 <b>새 스냅샷</b>으로 읽는다 — {@link #insert} 가 커밋한 행까지 포함한 값이 나온다.
     * 응답의 {@code count} 는 "이번 변경이 반영된 값" 이 계약이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long countFresh(LongSupplier count) {
        return count.getAsLong();
    }
}
