package com.diving.pungdong.address;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 🔒 로컬 stub — juso 를 호출하지 않고 고정 결과를 돌려준다. 좌표제공 API 는 개발용 승인키가 없어
 * 로컬에서 실호출이 불가능하므로, 로컬 개발이 외부 정부 API 에 묶이지 않게 하는 기본 모드.
 *
 * <p>실호출(검증)이 필요하면 {@code pungdong.address.geocode-mode=juso} 로 전환 →
 * {@link JusoAddressApiClient} 활성. (StubIdentityVerifier 와 동일 패턴.)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pungdong.address.geocode-mode", havingValue = "stub", matchIfMissing = true)
public class StubAddressApiClient implements AddressApiClient {

    @Override
    public SearchResult search(String keyword, int page, int countPerPage) {
        log.info("[address-stub] search keyword={}", keyword);
        AddressItem item = new AddressItem(
                "서울특별시 중구 세종대로 110 (태평로1가)", "서울특별시 중구 태평로1가 31", "04524", "서울특별시청",
                "서울특별시", "중구", "태평로1가",
                "1114010300", "111103000015", "0", "110", "0");
        return new SearchResult(1, page, countPerPage, List.of(item));
    }

    @Override
    public Coordinate geocode(GeocodeKey key) {
        log.info("[address-stub] geocode admCd={} → 고정 좌표", key.admCd());
        return new Coordinate(37.566535, 126.977969); // 서울시청 (고정)
    }
}
