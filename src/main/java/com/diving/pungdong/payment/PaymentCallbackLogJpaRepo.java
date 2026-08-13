package com.diving.pungdong.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 이니시스 콜백 수신 기록. {@link PaymentCallbackLog}. */
public interface PaymentCallbackLogJpaRepo extends JpaRepository<PaymentCallbackLog, Long> {

    List<PaymentCallbackLog> findByOrderId(String orderId);
}
