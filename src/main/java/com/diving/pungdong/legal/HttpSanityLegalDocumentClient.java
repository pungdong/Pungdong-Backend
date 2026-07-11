package com.diving.pungdong.legal;

import com.diving.pungdong.legal.dto.LegalDocumentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LegalDocumentClient} 실 구현 — Sanity GROQ HTTP API 로 {@code legalDocument} 조회.
 *
 * <p>consent {@link com.diving.pungdong.consent.HttpSanityTermClient} 와 달리 <b>토큰 + origin
 * 엔드포인트(api.sanity.io)</b>로 읽는다 — legalDocument 는 익명(CDN)에서 거부되기 때문(인터페이스 주석 참고).
 * slug 당 짧은 TTL 캐시(기본 5분) + fail-safe(조회 실패 시 마지막 캐시값). 토큰 미설정(로컬)이면 익명
 * 시도 → 거부되어 empty(로컬에선 법적 고지 안 뜸, 허용).
 */
@Slf4j
@Component
public class HttpSanityLegalDocumentClient implements LegalDocumentClient {

    private static final Set<String> ALLOWED_SLUGS = Set.of("terms", "privacy", "refund");
    private static final String QUERY =
            "*[_type == \"legalDocument\" && slug.current == $slug && active == true][0]" +
                    "{\"slug\": slug.current, title, body, version, effectiveDate}";

    private final String projectId;
    private final String dataset;
    private final String apiVersion;
    private final String token;
    private final long ttlMillis;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(LegalDocumentResponse doc, long at) {}

    public HttpSanityLegalDocumentClient(
            @Value("${pungdong.sanity.project-id:rc448mwo}") String projectId,
            @Value("${pungdong.sanity.dataset:production}") String dataset,
            @Value("${pungdong.sanity.api-version:2024-01-01}") String apiVersion,
            @Value("${pungdong.sanity.token:}") String token,
            @Value("${pungdong.sanity.legal-ttl-ms:300000}") long ttlMillis,
            ObjectMapper objectMapper) {
        this.projectId = projectId;
        this.dataset = dataset;
        this.apiVersion = apiVersion;
        // SSM SecureString 등에 붙는 trailing 개행/공백 방어 — 안 자르면 "Bearer <token>\n" 이
        // HttpRequest.header() 에서 IllegalArgumentException(invalid header value) → 500 (staging 사고).
        this.token = token == null ? null : token.trim();
        this.ttlMillis = ttlMillis;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public Optional<LegalDocumentResponse> fetch(String slug) {
        if (slug == null || !ALLOWED_SLUGS.contains(slug)) {
            return Optional.empty();
        }
        Cached snapshot = cache.get(slug);
        if (snapshot != null && System.currentTimeMillis() - snapshot.at() < ttlMillis) {
            return Optional.ofNullable(snapshot.doc());
        }
        try {
            Optional<LegalDocumentResponse> fetched = query(slug);
            cache.put(slug, new Cached(fetched.orElse(null), System.currentTimeMillis()));
            return fetched;
        } catch (RuntimeException e) {
            if (snapshot != null) {
                log.warn("[legal] Sanity 조회 실패 slug={} — 마지막 캐시값으로 폴백", slug, e);
                return Optional.ofNullable(snapshot.doc());
            }
            throw e;
        }
    }

    private Optional<LegalDocumentResponse> query(String slug) {
        URI uri = URI.create(String.format(
                "https://%s.api.sanity.io/v%s/data/query/%s?query=%s&$slug=%s",
                projectId, apiVersion, dataset, enc(QUERY), enc(jsonString(slug))));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Sanity legal query failed: HTTP " + response.statusCode());
            }
            JsonNode result = objectMapper.readTree(response.body()).get("result");
            if (result == null || result.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new LegalDocumentResponse(
                    result.path("slug").asText(slug),
                    result.path("title").asText(null),
                    result.get("body"),
                    result.path("version").asText(null),
                    result.path("effectiveDate").asText(null)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sanity legal query interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Sanity legal query transport error", e);
        }
    }

    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
