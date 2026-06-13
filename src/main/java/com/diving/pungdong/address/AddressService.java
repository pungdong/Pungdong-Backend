package com.diving.pungdong.address;

import com.diving.pungdong.address.dto.GeocodeRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 주소 검색 + 좌표 변환 — {@link AddressApiClient}(juso/stub) 위의 얇은 래퍼(입력 정규화·검증).
 * 외부 호출 자체는 client 가, 여기는 keyword 검증·페이지 클램프 정도.
 */
@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressApiClient client;

    public AddressApiClient.SearchResult search(String keyword, int page, int countPerPage) {
        if (!StringUtils.hasText(keyword)) {
            throw new BadRequestException();
        }
        int p = page < 1 ? 1 : page;
        int size = (countPerPage < 1 || countPerPage > 100) ? 10 : countPerPage;
        return client.search(keyword.trim(), p, size);
    }

    public AddressApiClient.Coordinate geocode(GeocodeRequest req) {
        return client.geocode(new AddressApiClient.GeocodeKey(
                req.getAdmCd(), req.getRnMgtSn(), req.getUdrtYn(), req.getBuldMnnm(), req.getBuldSlno()));
    }
}
