package com.diving.pungdong.address;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * 실 구현 — juso(주소기반산업지원서비스) 도로명주소 검색 + 좌표제공 API 호출. 추가 의존성 없이 JDK
 * {@link HttpClient} + Jackson, 좌표 변환은 proj4j.
 *
 * <p>핵심 주의:
 * <ul>
 *   <li><b>승인키 2개</b> — 검색({@code search-key})과 좌표({@code coord-key})가 별개(좌표 API 는
 *       검색 키를 거부: juso E0001).</li>
 *   <li><b>좌표계 변환</b> — 좌표제공 응답 {@code entX/entY} 는 WGS84 가 아니라 한국 격자좌표.
 *       {@code source-crs}(기본 EPSG:5179 GRS80 UTM-K) → WGS84(EPSG:4326)로 변환. ⚠️ 정확한 좌표계는
 *       실제 응답을 아는 주소로 검증 후 확정(명세 페이지에 미기재).</li>
 *   <li><b>referer</b> — 운영 키는 등록 URL 제한이 있을 수 있어, 서버 호출에 {@code Referer} 헤더를
 *       등록 URL 로 세팅(설정값 비면 생략).</li>
 * </ul>
 *
 * <p>{@code pungdong.address.geocode-mode=juso} 일 때만 활성(staging/prod). 로컬 기본은
 * {@link StubAddressApiClient}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pungdong.address.geocode-mode", havingValue = "juso")
public class JusoAddressApiClient implements AddressApiClient {

    private static final String SEARCH_URL = "https://business.juso.go.kr/addrlink/addrLinkApi.do";
    private static final String COORD_URL = "https://business.juso.go.kr/addrlink/addrCoordApi.do";
    private static final String WGS84 = "+proj=longlat +datum=WGS84 +no_defs";

    private final String searchKey;
    private final String coordKey;
    private final String referer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final CoordinateReferenceSystem sourceCrs;
    private final CoordinateReferenceSystem wgs84Crs;

    public JusoAddressApiClient(
            @Value("${pungdong.address.juso.search-key:}") String searchKey,
            @Value("${pungdong.address.juso.coord-key:}") String coordKey,
            @Value("${pungdong.address.juso.referer:}") String referer,
            @Value("${pungdong.address.juso.source-crs:+proj=tmerc +lat_0=38 +lon_0=127.5 +k=0.9996 +x_0=1000000 +y_0=2000000 +ellps=GRS80 +units=m +no_defs}") String sourceCrsParams,
            ObjectMapper objectMapper) {
        this.searchKey = searchKey;
        this.coordKey = coordKey;
        this.referer = referer;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        CRSFactory crsFactory = new CRSFactory();
        this.sourceCrs = crsFactory.createFromParameters("SRC", sourceCrsParams);
        this.wgs84Crs = crsFactory.createFromParameters("WGS84", WGS84);
    }

    @Override
    public SearchResult search(String keyword, int page, int countPerPage) {
        String url = SEARCH_URL + "?confmKey=" + enc(searchKey)
                + "&currentPage=" + page + "&countPerPage=" + countPerPage
                + "&keyword=" + enc(keyword) + "&resultType=json";
        JsonNode results = call(url);
        JsonNode common = results.path("common");
        requireOk(common);

        List<AddressItem> items = new ArrayList<>();
        for (JsonNode j : results.path("juso")) {
            items.add(new AddressItem(
                    text(j, "roadAddr"), text(j, "jibunAddr"), text(j, "zipNo"), text(j, "bdNm"),
                    text(j, "siNm"), text(j, "sggNm"), text(j, "emdNm"),
                    text(j, "admCd"), text(j, "rnMgtSn"), text(j, "udrtYn"), text(j, "buldMnnm"), text(j, "buldSlno")));
        }
        return new SearchResult(common.path("totalCount").asInt(items.size()), page, countPerPage, items);
    }

    @Override
    public Coordinate geocode(GeocodeKey key) {
        String url = COORD_URL + "?confmKey=" + enc(coordKey)
                + "&admCd=" + enc(key.admCd()) + "&rnMgtSn=" + enc(key.rnMgtSn())
                + "&udrtYn=" + enc(key.udrtYn()) + "&buldMnnm=" + enc(key.buldMnnm())
                + "&buldSlno=" + enc(key.buldSlno() == null ? "0" : key.buldSlno()) + "&resultType=json";
        JsonNode results = call(url);
        requireOk(results.path("common"));

        JsonNode first = results.path("juso").path(0);
        if (first.isMissingNode() || first.path("entX").isMissingNode()) {
            throw new BadRequestException(); // 해당 주소의 좌표 없음
        }
        double entX = first.path("entX").asDouble();
        double entY = first.path("entY").asDouble();

        ProjCoordinate src = new ProjCoordinate(entX, entY);
        ProjCoordinate dst = new ProjCoordinate();
        // BasicCoordinateTransform 은 스레드 안전하지 않아 호출마다 생성(CRS 는 재사용).
        new BasicCoordinateTransform(sourceCrs, wgs84Crs).transform(src, dst);
        return new Coordinate(dst.y, dst.x); // dst.y=위도, dst.x=경도
    }

    /* ─── helpers ─────────────────────────────────────────── */

    private JsonNode call(String url) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET();
        if (referer != null && !referer.isBlank()) {
            req.header("Referer", referer); // 운영 키 등록 URL 제한 대응(서버 호출)
        }
        try {
            HttpResponse<String> res = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("juso API HTTP " + res.statusCode());
            }
            return objectMapper.readTree(res.body()).path("results");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("juso API interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("juso API transport error", e);
        }
    }

    /** juso errorCode != "0" 이면 클라이언트 입력/키 문제 → 400. */
    private void requireOk(JsonNode common) {
        String code = common.path("errorCode").asText("");
        if (!"0".equals(code)) {
            log.warn("[address-juso] error {} {}", code, common.path("errorMessage").asText(""));
            throw new BadRequestException();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
