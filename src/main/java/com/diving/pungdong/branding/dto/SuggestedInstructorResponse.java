package com.diving.pungdong.branding.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 추천 강사 카드 1장 — 커뮤니티 사이드바("이 강사님은 어때요?")와 홈의 공식 강사 카드가 같이 쓴다.
 *
 * <p><b>{@code id} 를 싣지 않는다.</b> 카드가 여는 곳이 {@code GET /instructors/{nickName}} 이라
 * 이동에 필요한 건 닉네임뿐이고, 순차 id 를 공개 표면에 더 뿌릴 이유가 없다(anti-IDOR).
 * 기존 디렉토리 {@code PublicInstructorResponse} 는 id 를 싣지만 그건 이미 나간 계약이라 건드리지 않는다.
 *
 * <p>종목은 <b>코드</b>로만 준다({@code FREEDIVING}). 한글 라벨("프리다이빙")은 {@code GET /disciplines}
 * 로 매핑하는 게 이 레포의 기존 계약이고, 카드 하나 때문에 서버가 라벨을 직접 박기 시작하면
 * 라벨의 출처가 둘로 갈린다.
 */
@Getter
@Builder
public class SuggestedInstructorResponse {

    /** 공개 프로필 진입 키. `/instructors/{nickName}` 으로 이동한다. */
    private final String nickName;

    /** 없을 수 있다(프로필 사진 미등록) — 그때는 클라이언트 기본 아바타. */
    private final String avatarUrl;

    /** 승인된 종목 코드들. 여러 종목을 가진 강사도 카드는 1장이다. */
    private final List<String> disciplineCodes;
}
