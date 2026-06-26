package com.diving.pungdong.notification.fcm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FcmGateway 빈 와이어링 회귀 테스트 — `@DisplayName` 을 위에서 아래로 읽으면 계약이 보인다.
 *
 * <p>배경: firebase 비활성(prod 의 {@code FIREBASE_ENABLED=false})에서 {@code FcmGateway} 가
 * 정확히 하나({@link LoggingFcmGateway})로 해석돼야 한다. 예전 {@code @ConditionalOnMissingBean}
 * 와이어링은 컴포넌트 스캔 순서에 의존해, 무관한 클래스가 추가되자 prod 부팅이 깨졌다
 * (APPLICATION FAILED TO START — no FcmGateway bean). 이제 {@code firebase.enabled} 프로퍼티에
 * 직접 키잉하므로 순서 무관하게 항상 성립한다. 이 테스트가 그 계약을 박제한다.
 *
 * <p>{@code properties = "firebase.enabled=false"} 로 명시 고정한다 — 이 프로퍼티는 OS 환경변수보다
 * 우선순위가 높아, 로컬 direnv 의 {@code FIREBASE_ENABLED} 누출(메모리 env-leak-into-tests)에도
 * 흔들리지 않고 prod-OFF 시나리오를 결정론적으로 재현한다. FcmGateway 를 {@code @MockBean} 하지
 * 않는다 — 실제 와이어링을 검증하는 게 목적.
 */
@SpringBootTest(properties = "firebase.enabled=false")
@ActiveProfiles("test")
class FcmGatewayWiringTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("T1 firebase.enabled=false 면 FcmGateway 빈이 정확히 하나 존재한다 — 컨텍스트 로드 성공")
    void exactlyOneGatewayWhenFirebaseDisabled() {
        assertThat(ctx.getBeanNamesForType(FcmGateway.class)).hasSize(1);
    }

    @Test
    @DisplayName("T2 firebase.enabled=false 면 그 FcmGateway 는 LoggingFcmGateway 로 해석된다")
    void loggingGatewayResolvedWhenFirebaseDisabled() {
        assertThat(ctx.getBean(FcmGateway.class)).isInstanceOf(LoggingFcmGateway.class);
    }
}
