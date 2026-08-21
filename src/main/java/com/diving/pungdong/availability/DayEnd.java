package com.diving.pungdong.availability;

import java.time.LocalTime;

/**
 * "하루 끝" 의 정식 표현 — <b>23:59:59</b>.
 *
 * <p>{@link LocalTime} 은 24:00 을 표현할 수 없다(시 0~23). 그래서 타임라인 격자 끝까지 끌어 만든
 * {@code "24:00"} 은 역직렬화 단계에서 400 이고, FE 는 대신 {@code "23:59:59"} 를 보낸다. 이 값을
 * 끝시각의 canonical 로 두고, <b>23:59 이상으로 끝나는 구간은 전부 이 값으로 정규화</b>한다
 * (휠 피커의 "23:59", 시더의 옛 23:59:00 …). 누구도 "23:59:00 까지만, 그 59초는 빼고" 를 의도하지 않는다.
 *
 * <p><b>왜 정규화까지 하나</b>: 수강신청 슬롯은 venue 운영블록이 강사 coverage 에 <i>통째로</i> 들어갈 때만
 * 생긴다({@code CoverageMerger.containsWhole}, 경계 같으면 포함). 끝 표현이 23:59:00 / 23:59:59 로 섞이면
 * "하루 끝까지 연 강사의 늦은 블록" 이 59초 차이로 조용히 탈락한다. coverage·session·커스텀 위치 블록이
 * 모두 같은 상수로 수렴하면 그 함정이 없다. (OFFICIAL 위치는 Sanity 스키마가 {@code HH:mm} 으로 최대 23:59 라
 * 언제나 이 값 이하 — 포함 성립.)
 */
public final class DayEnd {

    /** 하루 끝 canonical 끝시각. */
    public static final LocalTime TIME = LocalTime.of(23, 59, 59);

    /** 이 시각 이상으로 끝나면 하루 끝으로 본다(23:59:00 ~ 23:59:59.999…). */
    private static final LocalTime THRESHOLD = LocalTime.of(23, 59);

    private DayEnd() {
    }

    /** 끝시각 정규화 — 23:59 이상이면 {@link #TIME}, 아니면 그대로. null 은 null. */
    public static LocalTime normalizeEnd(LocalTime end) {
        if (end == null) {
            return null;
        }
        return end.isBefore(THRESHOLD) ? end : TIME;
    }
}
