package com.diving.pungdong.instructorapplication.dto;

import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.util.List;

/**
 * 공개 강사 카드 — 수강생 둘러보기 홈 "풍덩 공식 강사" 노출용(비로그인 가능). 실가입(승인된 신청 보유) 강사만.
 * CollectionModel 키 = "instructors". 페이지의 {@code totalElements} 로 "N명" + 아바타 일부 + "+N" 을 FE 가 파생.
 *
 * <p>PII 노출 없음 — 공개 핸들({@code nickName})·아바타·종목만. 이름/이메일/연락처는 주지 않는다.
 */
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "instructors")
public class PublicInstructorResponse {
    private Long id;
    private String nickName;
    /** 프로필 사진 URL(미설정이면 null — FE 기본 아바타). */
    private String avatarUrl;
    /** 승인된 종목 코드들(예 ["FREEDIVING", "SCUBA"]). 한 강사가 여러 종목 승인 가능. */
    private List<String> disciplineCodes;
}
