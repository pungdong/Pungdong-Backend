package com.diving.pungdong.consent.dto;

import com.diving.pungdong.consent.ConsentContext;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.LocalDateTime;

/**
 * GET /consents/me 항목 — 내 동의 이력 1건. 배열은 {@code _embedded.consents} 로 묶인다
 * (CollectionModel + {@code @Relation}).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "consents")
public class MyConsentResponse {
    private String key;
    private String version;
    private String title;
    private ConsentContext context;
    private LocalDateTime agreedAt;
}
