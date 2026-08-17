package com.diving.pungdong.ota;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code crash_history}(JSON array of bundle id) 취급을 한 곳에 모은다.
 *
 * <p><b>왜 한 곳인가</b>: {@code crashRolledBack} 의 술어("컬럼 일치 <b>OR</b> 배열에 포함")가
 * <b>카운트(§2.1)는 Java 로, 목록(§2.2)은 SQL {@code LIKE} 로</b> 평가된다. 두 표현이 갈리면 어드민에서
 * 숫자를 눌렀을 때 다른 수가 나온다 — 그래서 양쪽 모두 {@link #quoted(String)} 를 통과시켜 같은 문자열
 * 매칭이 되게 한다.
 */
public final class OtaCrashHistory {

    /** 앱이 보내는 배열 상한. 초과분은 400 이 아니라 <b>앞쪽을 버리고</b> 최신 것만 남긴다. */
    public static final int MAX_ENTRIES = 20;

    private OtaCrashHistory() {
    }

    /**
     * 저장용 JSON 문자열로 정제. 잘못된 원소는 <b>버리고</b> 나머지를 살린다(400 을 내면
     * {@code appVersion}·{@code otaBundleId} 같은 주 신호까지 통째로 버려진다 — 보조 신호가 주 신호를
     * 죽이면 안 된다).
     *
     * <p><b>빈 배열은 null 로 정규화한다.</b> 앱은 대부분 {@code []} 를 보내는데 그걸 그대로 저장하면
     * 크래시 롤백 후보 조회({@code crash_history is not null})가 사실상 전체 스캔이 된다.
     */
    public static String toJson(ObjectMapper objectMapper, List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank() || entry.length() > 36) {
                continue; // 못 쓰는 원소만 버린다
            }
            cleaned.add(entry);
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        // 상한 초과 시 앞쪽(오래된 것)을 버린다. 라이브러리가 배열 순서를 문서화하지 않으므로
        // "최신이 뒤" 라고 단정하지 않고, 어느 쪽이든 상한만 지켜지면 집계(포함 여부)는 순서 무관이다.
        if (cleaned.size() > MAX_ENTRIES) {
            cleaned = cleaned.subList(cleaned.size() - MAX_ENTRIES, cleaned.size());
        }
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            return null; // 직렬화 실패도 주 신호를 죽이지 않는다
        }
    }

    /** 응답용 — 없거나 깨졌으면 <b>빈 배열</b>(null 아님. FE 가 null 체크를 안 하게). */
    public static List<String> toList(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** SQL {@code LIKE} 패턴 — 목록 조회(§2.2)가 쓴다. */
    public static String likePattern(String bundleId) {
        return "%" + quoted(bundleId) + "%";
    }

    /** Java 매칭 — 카운트 집계(§2.1)가 쓴다. {@link #likePattern} 과 같은 문자열을 본다. */
    public static boolean contains(String crashHistoryJson, String bundleId) {
        return crashHistoryJson != null && crashHistoryJson.contains(quoted(bundleId));
    }

    /**
     * JSON 안에서 그 id 가 <b>원소 전체</b>로 등장하는 형태. 따옴표로 감싸지 않으면 uuid 접두사가 겹치는
     * 다른 번들에 오탐이 난다.
     */
    private static String quoted(String bundleId) {
        return "\"" + bundleId + "\"";
    }
}
