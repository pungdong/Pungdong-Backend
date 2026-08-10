package com.diving.pungdong.branding;

/**
 * 게시물 미디어 종류.
 *
 * <p>{@code VIDEO} 는 <b>스키마 자리만 예약</b>돼 있고 업로드는 거부한다(사용자 결정 D1). 지금 넣어두면
 * 나중에 영상을 붙일 때 마이그레이션이 필요 없다. 왜 미루는지는 GitHub 이슈 #207 — 요지는 트랜스코딩·큐
 * 인프라가 전무하고 Fargate 0.5vCPU/1GB 에서 트랜스코딩하면 API 가 같이 죽으며, 디자인에도 영상 전용
 * 화면이 0개라 FE 도 현 상태로는 구현할 수 없다는 것.
 */
public enum BrandingMediaKind {
    PHOTO, VIDEO
}
