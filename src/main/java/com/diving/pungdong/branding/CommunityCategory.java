package com.diving.pungdong.branding;

/**
 * 커뮤니티 카테고리 4종. 피드 상단 4-up 그리드이자 글의 분류축이다.
 *
 * <p><b>왜 community 패키지가 아니라 여기 있나.</b> 이 값을 갖는 {@link BrandingPost} 엔티티가 이 패키지에
 * 있기 때문이다. {@code community} 패키지는 {@code branding}(게시물·프로필)을 참조하는 방향이라,
 * 이 enum 을 저쪽에 두면 {@code branding → community → branding} 순환이 생긴다. enum 은 동작이 없는
 * 잎(leaf) 타입이라 엔티티와 같은 패키지에 두는 편이 의존 방향을 단순하게 유지한다.
 *
 * <p><b>라벨은 BE 가 갖지 않는다.</b> "투어 자랑 / 트레이닝 / 같이가요 / 궁금해요" 는 클라이언트 소유이고
 * BE 는 코드만 내려준다. 디자인 id 매핑: {@code tour→TOUR · train→TRAINING · match→MATCH · q→QNA}.
 *
 * <p>⚠️ {@code DIVE_LOG} 라는 값은 쓰지 않는다 — 수강 회차에 딸리는 "다이브로그"(훈련 기록)가 별도 로드맵에
 * 있어 이름이 정면 충돌한다.
 */
public enum CommunityCategory {

    /** 투어 자랑 — 사진·영상이 주인공. 강사는 상품 연결 가능. */
    TOUR,

    /** 트레이닝 — 강의 후기·훈련 팁. 강사는 상품 연결 가능. */
    TRAINING,

    /**
     * 같이가요 — 버디·동행 모집. 일정·정원·요구자격을 {@code community_post_match} 에 따로 갖는다.
     * 영리활동 금지 가드 때문에 이 카테고리 글에는 강의를 연결할 수 없다.
     */
    MATCH,

    /** 궁금해요 — 질문. 강사 답변이 프로필·강의로 이어지는 진입점이다. */
    QNA
}
