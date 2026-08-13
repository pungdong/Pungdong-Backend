package com.diving.pungdong.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 낙관적 락(@Version) 사양 — 동시 상태 전이의 blind overwrite(lost update)를 막는다.
 *
 * <p><b>왜 돈이 걸리나</b>: 이 락이 없어 만료 스윕이 {@code DONE} 주문을 {@code FAILED} 로 덮어 모든 환불 경로에서
 * 안 보이게 하거나, 취소↔승인 교차가 서로를 덮어써 "결제했는데 환불 없이 취소" 가 됐다. @Version 은 진 쪽 트랜잭션을
 * 롤백시켜(먼저 커밋한 쪽이 이긴다) 상태 손상을 원천 차단한다. 충돌은 {@code ObjectOptimisticLockingFailureException}
 * → 어드바이스가 409/-1021(CONCURRENT_MODIFICATION)로 매핑.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. VL* 낙관적 락.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentOrderConcurrencyTest {

    @Autowired
    private PaymentOrderJpaRepo orderRepo;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        orderRepo.deleteAll();
    }

    @Test
    @DisplayName("VL1 @Version 은 0 에서 시작하고 수정마다 증가한다")
    void versionIncrements() {
        Long id = orderRepo.saveAndFlush(ready("lock-vl1")).getId();
        assertThat(dbVersion(id)).isEqualTo(0L);

        PaymentOrder o = orderRepo.findById(id).orElseThrow();
        o.setStatus(PaymentStatus.DONE);
        orderRepo.saveAndFlush(o);

        assertThat(dbVersion(id)).isEqualTo(1L);
    }

    @Test
    @DisplayName("VL2 stale 복사본으로 저장하면 낙관적 락 충돌 — 먼저 커밋한 쪽이 이기고 진 쪽은 롤백")
    void staleUpdateRejected() {
        Long id = orderRepo.saveAndFlush(ready("lock-vl2")).getId();
        PaymentOrder stale = orderRepo.findById(id).orElseThrow(); // version 0 (detached)

        // 동시 트랜잭션이 먼저 승인(DONE)을 커밋한 상황 재현 — DB version 을 밀어올린다.
        jdbc.update("UPDATE payment_order SET version = version + 1, status = 'DONE' WHERE id = ?", id);

        // 만료 스윕이 stale(version 0) 로 FAILED 를 쓰려는 시도 = blind overwrite
        stale.setStatus(PaymentStatus.FAILED);
        assertThatThrownBy(() -> orderRepo.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 진 쪽이 롤백돼 DB 는 먼저 커밋한 DONE 을 유지(FAILED 로 덮이지 않는다)
        assertThat(orderRepo.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    private PaymentOrder ready(String orderId) {
        return PaymentOrder.builder()
                .orderId(orderId)
                .amount(1000)
                .status(PaymentStatus.READY)
                .build();
    }

    private long dbVersion(Long id) {
        Long v = jdbc.queryForObject("SELECT version FROM payment_order WHERE id = ?", Long.class, id);
        return v == null ? -1L : v;
    }
}
