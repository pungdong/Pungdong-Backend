package com.diving.pungdong.legal.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * GET /legal/{slug} 응답 — 법적 고지 전문 1건.
 * {@code body} 는 Sanity Portable Text(블록 배열)를 그대로 통과시킨다(FE {@code <PortableTextBody/>} 렌더).
 */
@Getter
@AllArgsConstructor
public class LegalDocumentResponse {
    private final String slug;          // terms | privacy | refund
    private final String title;
    private final JsonNode body;        // Portable Text 블록 배열(passthrough)
    private final String version;       // 예: "1.0" (nullable)
    private final String effectiveDate; // ISO date YYYY-MM-DD (nullable)
}
