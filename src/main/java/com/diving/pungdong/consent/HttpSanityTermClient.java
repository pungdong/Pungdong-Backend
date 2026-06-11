package com.diving.pungdong.consent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * {@link SanityTermClient} 의 실 구현 — Sanity GROQ 쿼리 HTTP API 로 약관을 조회한다.
 * public dataset 읽기라 토큰 불필요(CDN 엔드포인트). 추가 의존성 없이 JDK 내장
 * {@link HttpClient} + Jackson 사용.
 *
 * <p>구분: <b>해당 버전이 없음</b>(result=null) → {@code empty} (호출부가 400 으로 변환).
 * <b>전송 실패</b>(Sanity 도달 불가/응답 오류) → 예외(500) — 증빙 freeze 를 조용히 건너뛰지 않는다.
 */
@Slf4j
@Component
public class HttpSanityTermClient implements SanityTermClient {

    private static final String QUERY =
            "*[_type == \"term\" && key == $key && version == $version][0]" +
                    "{key, version, title, required, body}";

    private final String projectId;
    private final String dataset;
    private final String apiVersion;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpSanityTermClient(
            @Value("${pungdong.sanity.project-id:rc448mwo}") String projectId,
            @Value("${pungdong.sanity.dataset:production}") String dataset,
            @Value("${pungdong.sanity.api-version:2024-01-01}") String apiVersion,
            ObjectMapper objectMapper) {
        this.projectId = projectId;
        this.dataset = dataset;
        this.apiVersion = apiVersion;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public Optional<FetchedTerm> fetchTerm(String key, String version) {
        URI uri = URI.create(String.format(
                "https://%s.apicdn.sanity.io/v%s/data/query/%s?query=%s&$key=%s&$version=%s",
                projectId, apiVersion, dataset,
                enc(QUERY), enc(jsonString(key)), enc(jsonString(version))));

        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Sanity query failed: HTTP " + response.statusCode());
            }
            JsonNode result = objectMapper.readTree(response.body()).get("result");
            if (result == null || result.isNull()) {
                log.warn("[consent] no Sanity term for key={} version={}", key, version);
                return Optional.empty();
            }
            JsonNode bodyNode = result.get("body");
            String bodyJson = (bodyNode == null || bodyNode.isNull()) ? null : objectMapper.writeValueAsString(bodyNode);
            return Optional.of(new FetchedTerm(
                    result.path("key").asText(key),
                    result.path("version").asText(version),
                    result.path("title").asText(null),
                    bodyJson,
                    result.path("required").asBoolean(false)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sanity query interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Sanity query transport error", e);
        }
    }

    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
