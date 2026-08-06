package com.diving.pungdong.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 어느 {@link PaymentGateway} 로 보낼지 고르는 곳. <b>두 가지 선택이 서로 다르다</b>는 게 이 클래스의 존재 이유다.
 *
 * <ul>
 *   <li>{@link #active()} — <b>신규</b> 결제를 어디로 보낼지. 전역 설정 {@code pungdong.payment.mode} 가 정한다.</li>
 *   <li>{@link #forOrder} — <b>기존 주문</b>의 승인·환불을 어디로 보낼지. 주문에 박제된
 *       {@link PaymentOrder#getProvider()} 가 정한다.</li>
 * </ul>
 *
 * <p><b>왜 나눴나</b>(FE 리뷰 지적, PR #183): 주문은 설정보다 오래 산다. 이니시스로 결제를 받다가 토스로 스왑하려
 * 설정을 바꾸면, 그 뒤 들어오는 <b>과거 이니시스 주문의 환불</b>이 토스로 나간다 — 존재하지 않는 거래라 취소가 실패하고
 * <b>돈은 이미 받은 상태</b>가 된다. 그래서 라우팅 기준을 "지금 설정"이 아니라 "그 주문이 결제된 PG"로 둔다.
 *
 * <p>그래서 어댑터들은 {@code @ConditionalOnProperty} 로 하나만 살아남지 <b>않는다</b> — 전부 빈으로 두고
 * 여기서 고른다. 그래야 설정을 바꾼 뒤에도 옛 PG 로 취소를 보낼 수 있다.
 */
@Slf4j
@Component
public class PaymentGatewayRegistry {

    private final Map<PaymentProvider, PaymentGateway> byProvider = new EnumMap<>(PaymentProvider.class);
    private final PaymentGateway active;

    public PaymentGatewayRegistry(List<PaymentGateway> gateways,
                                  @Value("${pungdong.payment.mode:stub}") String mode) {
        for (PaymentGateway g : gateways) {
            byProvider.put(g.provider(), g);
        }
        PaymentProvider activeProvider = parseMode(mode);
        this.active = byProvider.get(activeProvider);
        if (active == null) {
            throw new IllegalStateException("pungdong.payment.mode=" + mode + " 에 해당하는 PaymentGateway 빈이 없습니다");
        }
        log.info("[payment] 신규 결제 PG={} (사용 가능: {})", activeProvider, byProvider.keySet());
    }

    /** 신규 결제(prepare)가 사용할 게이트웨이 — 전역 설정 기준. */
    public PaymentGateway active() {
        return active;
    }

    /**
     * 기존 주문의 승인·환불이 사용할 게이트웨이 — <b>주문에 박제된 provider</b> 기준.
     *
     * @param provider 주문의 provider. legacy 행은 null 일 수 있고, 그때만 현재 활성 게이트웨이로 폴백한다
     *                 (결제 라이브 이전 데이터라 실 거래가 없다).
     */
    public PaymentGateway forOrder(PaymentProvider provider) {
        if (provider == null) {
            log.warn("[payment] 주문에 provider 가 없어 활성 게이트웨이({})로 폴백 — provider 컬럼 도입 이전 주문",
                    active.provider());
            return active;
        }
        PaymentGateway g = byProvider.get(provider);
        if (g == null) {
            // 설정에서 그 PG 어댑터를 빼버린 경우. 잘못된 PG 로 취소를 보내느니 크게 실패하는 게 낫다.
            throw new IllegalStateException("주문의 PG(" + provider + ") 어댑터를 찾을 수 없습니다 — 환불/승인 불가");
        }
        return g;
    }

    private static PaymentProvider parseMode(String mode) {
        try {
            return PaymentProvider.valueOf(mode == null ? "STUB" : mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("pungdong.payment.mode 값이 올바르지 않습니다: " + mode, e);
        }
    }
}
