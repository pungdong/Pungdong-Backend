package com.diving.pungdong.legal;

import com.diving.pungdong.legal.dto.LegalDocumentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 법적 고지 전문 — 공개 조회. {@code GET /legal/{slug}} (slug = terms | privacy | refund).
 *
 * <p>BE 가 Sanity {@code legalDocument} 를 read 토큰으로 서버사이드로 읽어 제공한다(이유는
 * {@link LegalDocumentClient} 주석). 매처 {@code GET /legal/**} → permitAll. 없는 slug/문서 = 404.
 */
@RestController
@RequestMapping("/legal")
@RequiredArgsConstructor
public class LegalController {

    private final LegalDocumentClient legalDocumentClient;

    @GetMapping("/{slug}")
    public ResponseEntity<LegalDocumentResponse> get(@PathVariable String slug) {
        return legalDocumentClient.fetch(slug)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
