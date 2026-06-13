package com.diving.pungdong.address;

import com.diving.pungdong.address.dto.GeocodeRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 주소 검색 + 주소→좌표 변환. juso 통합은 BE 한 곳에만 — FE(웹·앱)는 juso 를 직접 호출하지 않고
 * 이 엔드포인트를 거친다(승인키 은닉 + 모바일 BFF 부재 해결).
 *
 * <p>매처: {@code /address-search}, {@code /geocode} → authenticated
 * ({@code global/security/SecurityConfiguration}). 좌표 변환 로컬 기본은 stub({@code geocode-mode}).
 */
@RestController
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    /** 도로명주소 검색 — 후보 목록(페이징). 사용자가 고른 항목의 키를 {@code /geocode} 로 넘긴다. */
    @GetMapping("/address-search")
    public ResponseEntity<?> search(@RequestParam String keyword,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(addressService.search(keyword, page, size));
    }

    /** 선택한 주소 → WGS84 위경도. */
    @PostMapping("/geocode")
    public ResponseEntity<?> geocode(@Valid @RequestBody GeocodeRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.ok(addressService.geocode(request));
    }
}
