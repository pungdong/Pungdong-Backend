package com.diving.pungdong.venue.sync;

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
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SanityVenueClient} 실 구현 — Sanity GROQ HTTP API(public dataset, 토큰 불필요, apicdn).
 * consent {@code HttpSanityTermClient} 와 동일하게 JDK {@link HttpClient} + Jackson, 추가 의존성 없음.
 *
 * <p>{@code @Profile("!test")} — 테스트는 {@link StubSanityVenueClient} 가 대신한다(실 Sanity 미접속).
 * 투영은 FE {@code sanity/queries.ts} 와 같은 모양 + {@code _rev}(캐시 정합성용).
 */
@Slf4j
@Component
@Profile("!test")
public class HttpSanityVenueClient implements SanityVenueClient {

    private static final String DAYPART = "{ sold, fee, timeMode, blocks[]{ start, end }, open, close, holdHours }";

    /** 공식 위치 전량(활성) + _rev. */
    private static final String ALL_QUERY =
            "*[_type == \"venue\" && active == true] | order(sortOrder asc) {" +
                    " _id, _rev, name, type, maxDepth, address, addressDetail, latitude, longitude," +
                    " equipInfo, closures[]{ type, weekdays, nth, monthlyWeekday }," +
                    " tickets[]{ name, disciplines, weekday " + DAYPART + ", weekend " + DAYPART + " } }";

    /** 리비전 토큰만 — 바이트 단위, reconcile 의 변경 감지 게이트. */
    private static final String REVS_QUERY = "*[_type == \"venue\"]{ _id, _rev }";

    private final String projectId;
    private final String dataset;
    private final String apiVersion;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpSanityVenueClient(
            @Value("${pungdong.sanity.project-id:rc448mwo}") String projectId,
            @Value("${pungdong.sanity.dataset:production}") String dataset,
            @Value("${pungdong.sanity.api-version:2024-01-01}") String apiVersion,
            ObjectMapper objectMapper) {
        this.projectId = projectId;
        this.dataset = dataset;
        this.apiVersion = apiVersion;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public List<OfficialVenueDoc> fetchAll() {
        JsonNode result = query(ALL_QUERY);
        List<OfficialVenueDoc> out = new ArrayList<>();
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                out.add(new OfficialVenueDoc(
                        node.path("_id").asText(null),
                        node.path("_rev").asText(null),
                        node));
            }
        }
        return out;
    }

    @Override
    public List<RevEntry> fetchRevs() {
        JsonNode result = query(REVS_QUERY);
        List<RevEntry> out = new ArrayList<>();
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                out.add(new RevEntry(node.path("_id").asText(null), node.path("_rev").asText(null)));
            }
        }
        return out;
    }

    private JsonNode query(String groq) {
        URI uri = URI.create(String.format(
                "https://%s.apicdn.sanity.io/v%s/data/query/%s?query=%s",
                projectId, apiVersion, dataset, enc(groq)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Sanity venue query failed: HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body()).get("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sanity venue query interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Sanity venue query transport error", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
