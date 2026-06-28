package com.diving.pungdong.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundOrderJpaRepo extends JpaRepository<RefundOrder, Long> {
}
