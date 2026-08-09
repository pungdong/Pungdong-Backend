package com.diving.pungdong.enrollment;

import com.diving.pungdong.course.RoundKind;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 회차 이력 정리 — <b>재신청으로 대체된 죽은 회차</b>를 hub 표시·파생에서 걸러낸다.
 *
 * <p><b>왜 필요한가</b>: 거절/취소는 그 회차만 무효로 만들고 자리를 비우므로({@link RoundGate} 는 활성 회차만
 * "이미 잡음"으로 본다) 학생은 <b>같은 회차를 다른 날짜로 다시 신청</b>할 수 있다. 그러면 한 회차 자리에 죽은 행
 * (REJECTED/CANCELLED)과 새 행이 공존한다 — {@code enrollment_round} 에 (수강, 회차번호) 유니크 제약이 없어
 * DB 는 이를 허용한다. 그대로 파생하면 {@code CourseScheduleStatus.derive} 가 "REJECTED 가 하나라도 있으면
 * RESCHEDULING(학생 액션)" 이므로 <b>재신청해서 확정한 뒤에도 강의 카드가 영원히 "일정 조정 필요"</b>로 굳는다.
 *
 * <p><b>규칙</b>: 같은 자리(정규는 {@code roundIndex}, EXTRA 는 하나의 그룹)에 <b>더 최근(id 큰) 활성/완료 회차</b>가
 * 있으면 죽은 회차는 뺀다. 반대로 죽음이 그 자리의 <i>가장 최근</i> 사건이면 남긴다 — 학생에게 "다시 잡아주세요"를
 * 띄워야 하기 때문. 즉 거절 직후엔 보이고, 재신청하면 사라진다.
 */
final class RoundHistory {

    private RoundHistory() {
    }

    /** 대체된 죽은 회차를 제외한 목록(입력 순서 유지). */
    static List<EnrollmentRound> current(Collection<EnrollmentRound> rounds) {
        Map<String, Long> latestLive = new HashMap<>();
        for (EnrollmentRound r : rounds) {
            if (alive(r)) {
                latestLive.merge(slotKey(r), id(r), Math::max);
            }
        }
        return rounds.stream()
                .filter(r -> alive(r) || id(r) > latestLive.getOrDefault(slotKey(r), Long.MIN_VALUE))
                .collect(Collectors.toList());
    }

    /** 살아있는 회차 = 활성(점유 중) 또는 수강 완료. */
    private static boolean alive(EnrollmentRound r) {
        return r.getStatus().isActive() || r.isDone();
    }

    /** 같은 "자리" 판정 — 정규는 회차번호, EXTRA 는 번호가 없으므로 한 그룹으로 본다. */
    private static String slotKey(EnrollmentRound r) {
        return r.getRoundKind() == RoundKind.EXTRA ? "EXTRA" : "REGULAR:" + r.getRoundIndex();
    }

    private static long id(EnrollmentRound r) {
        return r.getId() == null ? Long.MAX_VALUE : r.getId(); // 미저장(테스트) 은 가장 최근으로
    }
}
