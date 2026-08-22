package com.diving.pungdong.certificate;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * "이 계정은 이 종목의 <b>승인된 강사</b>인가" — Rule A/C 가 묻는 단 하나의 질문.
 *
 * <p>답은 {@code instructorapplication} 도메인이 안다(APPROVED 신청 보유). 그런데 그 도메인은 제출/승인 때 이
 * 도메인을 호출하므로, 여기서 그 레포를 import 하면 <b>양방향 의존</b>이 된다. 인터페이스를 이쪽에 두고 구현을
 * 저쪽에 두어 방향을 한쪽(instructorapplication → certificate)으로 유지한다.
 */
public interface InstructorApprovalLookup {
    boolean isApprovedInstructor(Long accountId, String disciplineCode);

    /** 여러 계정의 승인 종목 일괄 — 어드민 큐 목록의 "검증 자격증 0건" 플래그용(행마다 묻지 않는다). */
    Map<Long, Set<String>> approvedDisciplinesOf(Collection<Long> accountIds);
}
