package com.diving.pungdong.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Component
public class IdempotentInsert {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> void insert(JpaRepository<T, ?> repo, T entity) {
        repo.saveAndFlush(entity);
    }
}
