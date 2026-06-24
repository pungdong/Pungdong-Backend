package com.diving.pungdong.global.sitesettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link SiteSettingsProvider} 실 구현 — Sanity GROQ 로 {@code siteSettings} 싱글톤을 읽는다
 * ({@link com.diving.pungdong.consent.HttpSanityTermClient} 와 동일 패턴: public dataset, 토큰 불필요,
 * JDK {@link HttpClient} + Jackson).
 *
 * <p>둘러보기/신청마다 호출되므로 <b>짧은 TTL 캐시</b>(기본 60초)를 둔다 — Sanity publish 반영이 최대
 * TTL 만큼 지연되지만 런칭 플립은 드물어 허용. <b>fail-safe</b>: 조회 실패 시 마지막 캐시값, 그것도 없으면
 * {@link SiteSettings#SAFE_DEFAULT}(미런칭+데모노출) 로 보수적 동작 — 사고로 신청이 열리지 않게.
 */
@Slf4j
@Component
@Profile("!test") // 테스트는 네트워크 호출 대신 TestSiteSettingsConfig 의 고정 bean 사용(ES 스캐폴드와 동일)
public class HttpSanitySiteSettingsProvider implements SiteSettingsProvider {

    private static final String QUERY = "*[_type == \"siteSettings\"][0]{launched, showSeededCourses}";

    private final String projectId;
    private final String dataset;
    private final String apiVersion;
    private final long ttlMillis;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile SiteSettings cached;
    private volatile long cachedAt;

    public HttpSanitySiteSettingsProvider(
            @Value("${pungdong.sanity.project-id:rc448mwo}") String projectId,
            @Value("${pungdong.sanity.dataset:production}") String dataset,
            @Value("${pungdong.sanity.api-version:2024-01-01}") String apiVersion,
            @Value("${pungdong.sanity.site-settings-ttl-ms:60000}") long ttlMillis,
            ObjectMapper objectMapper) {
        this.projectId = projectId;
        this.dataset = dataset;
        this.apiVersion = apiVersion;
        this.ttlMillis = ttlMillis;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public SiteSettings current() {
        SiteSettings snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < ttlMillis) {
            return snapshot;
        }
        try {
            SiteSettings fetched = fetch();
            cached = fetched;
            cachedAt = System.currentTimeMillis();
            return fetched;
        } catch (RuntimeException e) {
            log.warn("[siteSettings] fetch 실패 — {} 사용", snapshot != null ? "이전 캐시" : "SAFE_DEFAULT", e);
            return snapshot != null ? snapshot : SiteSettings.SAFE_DEFAULT;
        }
    }

    private SiteSettings fetch() {
        URI uri = URI.create(String.format(
                "https://%s.apicdn.sanity.io/v%s/data/query/%s?query=%s",
                projectId, apiVersion, dataset, enc(QUERY)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Sanity siteSettings query failed: HTTP " + response.statusCode());
            }
            JsonNode result = objectMapper.readTree(response.body()).get("result");
            if (result == null || result.isNull()) {
                // 문서 미생성 — 보수적 기본값으로 동작(미런칭+데모노출).
                return SiteSettings.SAFE_DEFAULT;
            }
            return new SiteSettings(
                    result.path("launched").asBoolean(false),
                    result.path("showSeededCourses").asBoolean(true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sanity siteSettings query interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Sanity siteSettings transport error", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
