package com.diving.pungdong.course;

import com.diving.pungdong.course.dto.LevelLabelResponse;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 종목별 자격 레벨 표시 라벨 카탈로그 — 거의 고정이라 Sanity 콘텐츠가 아니라 코드 상수로 둔다(자주 안 바뀜).
 * 평탄화 코드({@link CertLevel})는 종목 무관 공통 사다리지만, <b>통용 명칭(alias)은 종목별로 다르다</b>:
 * 스쿠버는 OWD/AOW/Rescue/Divemaster 가 업계 표준이라 풀네임으로 덧붙이고, 프리다이빙/머메이드는 단체마다
 * 명칭이 갈려 공통 명칭이 없어 alias 없이 단계명만 쓴다.
 *
 * <p>약어(OWD) 대신 풀네임(Open Water Diver)을 쓰는 이유는 입문자 배려 — 업계인은 약어를 알지만 입문자는
 * 풀네임이라야 "오픈워터구나" 하고 와닿는다(대화체와도 일치).
 */
public final class CertLevelLabels {

    private CertLevelLabels() {
    }

    /** 스쿠버 통용 명칭(PADI 명칭이 사실상 업계 표준). */
    private static final Map<CertLevel, String> SCUBA_ALIAS = new EnumMap<>(CertLevel.class);
    static {
        SCUBA_ALIAS.put(CertLevel.LEVEL_1, "Open Water Diver");
        SCUBA_ALIAS.put(CertLevel.LEVEL_2, "Advanced Open Water Diver");
        SCUBA_ALIAS.put(CertLevel.LEVEL_3, "Rescue Diver");
        SCUBA_ALIAS.put(CertLevel.LEVEL_4, "Divemaster");
        SCUBA_ALIAS.put(CertLevel.INSTRUCTOR, "Instructor");
        SCUBA_ALIAS.put(CertLevel.INSTRUCTOR_TRAINER, "Instructor Trainer");
    }

    /** 종목코드 → alias 맵. 없는 종목(프리다이빙/머메이드)은 alias 전부 null. */
    private static final Map<String, Map<CertLevel, String>> BY_DISCIPLINE = Map.of(
            "SCUBA", SCUBA_ALIAS);

    /** 한 종목의 레벨 라벨 6종(평탄화 순서). 알 수 없는 종목코드면 alias 없이 단계명만. */
    public static List<LevelLabelResponse> forDiscipline(String disciplineCode) {
        Map<CertLevel, String> alias = BY_DISCIPLINE.getOrDefault(disciplineCode, Map.of());
        List<LevelLabelResponse> out = new ArrayList<>();
        for (CertLevel level : CertLevel.values()) {
            out.add(LevelLabelResponse.builder()
                    .level(level)
                    .label(level.getDisplayName())
                    .alias(alias.get(level))
                    .build());
        }
        return out;
    }
}
