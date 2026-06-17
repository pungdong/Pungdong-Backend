package com.diving.pungdong.availability;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 예약가능시간(coverage) 구간 대수 — 순수 함수. 하루치 구간들을 비겹침·비인접으로 정규화(머지)하고,
 * 구간 빼기(subtract)·포함 판정(containsWhole)·겹침 판정(overlapsAny)을 제공한다.
 *
 * <p>머지 규칙: <b>겹치거나 맞닿으면(end == 다음 start) 합친다</b> — 10–12 + 12–14 → 10–14.
 * 포함/겹침 규칙은 strict(맞닿음은 겹침 아님) — venue 부가 coverage 에 통째로 들어가야 신청 가능, coverage
 * 축소는 session 을 strict 하게 가로지를 때만 거부.
 */
public final class CoverageMerger {

    private CoverageMerger() {}

    /** 시간 구간 [start, end). start &lt; end. */
    public record Span(LocalTime start, LocalTime end) {
        public boolean valid() {
            return start != null && end != null && start.isBefore(end);
        }
        /** q 가 이 구간에 통째로 들어가나. */
        public boolean containsWhole(Span q) {
            return !q.start.isBefore(start) && !q.end.isAfter(end);
        }
        /** q 와 strict 하게 겹치나(맞닿음 제외). */
        public boolean overlaps(Span q) {
            return start.isBefore(q.end) && q.start.isBefore(end);
        }
    }

    /** 기존 구간들에 add 를 합쳐 정규화. */
    public static List<Span> union(List<Span> existing, Span add) {
        List<Span> all = new ArrayList<>(existing);
        all.add(add);
        return normalize(all);
    }

    /** 정렬 + 겹침/맞닿음 머지 → 비겹침·비인접 구간 리스트. */
    public static List<Span> normalize(List<Span> spans) {
        List<Span> sorted = spans.stream()
                .filter(Span::valid)
                .sorted(Comparator.comparing(Span::start).thenComparing(Span::end))
                .collect(Collectors.toList());
        List<Span> out = new ArrayList<>();
        for (Span s : sorted) {
            if (out.isEmpty()) {
                out.add(s);
                continue;
            }
            Span last = out.get(out.size() - 1);
            if (!s.start().isAfter(last.end())) { // s.start <= last.end → 겹치거나 맞닿음
                if (s.end().isAfter(last.end())) {
                    out.set(out.size() - 1, new Span(last.start(), s.end()));
                }
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** 기존 구간들에서 cut 을 제거(축소·분할·삭제). 정규화된 결과 반환. */
    public static List<Span> subtract(List<Span> existing, Span cut) {
        List<Span> out = new ArrayList<>();
        for (Span s : normalize(existing)) {
            if (!s.overlaps(cut)) { // strict 비겹침(맞닿음 포함) → 그대로 둠
                out.add(s);
                continue;
            }
            if (s.start().isBefore(cut.start())) {
                out.add(new Span(s.start(), cut.start())); // 왼쪽 잔여
            }
            if (cut.end().isBefore(s.end())) {
                out.add(new Span(cut.end(), s.end())); // 오른쪽 잔여
            }
        }
        return out;
    }

    /** q 가 어떤 구간에 통째로 포함되나(학생 신청 자격: venue 부 ⊆ coverage). */
    public static boolean containsWhole(List<Span> spans, Span q) {
        return normalize(spans).stream().anyMatch(s -> s.containsWhole(q));
    }

    /** q 와 strict 하게 겹치는 구간이 있나(coverage 축소 vs session 충돌 판정). */
    public static boolean overlapsAny(List<Span> spans, Span q) {
        return spans.stream().anyMatch(s -> s.overlaps(q));
    }
}
