package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의 저장(북마크) — 수강생 행동. 요청 바디 없음, 응답은 {@code {count, active}}.
 *
 * <p>{@link CourseController} 와 경로({@code /courses})는 같지만 클래스를 나눴다 — 그쪽은 <b>강사</b>의
 * 작성·관리 트랙이고 이건 <b>수강생</b>의 개인 행동이라, 한 클래스에 섞이면 그 컨트롤러의 "강사 트랙"
 * 이라는 설명이 거짓이 된다. 클라이언트에는 차이가 없다(커뮤니티가 읽기/쓰기를 나눈 방식과 같다).
 *
 * <p>매처는 {@code /courses/**} → authenticated 가 이미 덮는다(공개 예외는 {@code GET browse}·
 * {@code GET *&#47;detail}·{@code GET level-labels} 뿐). 저장은 <b>내가 누구인지 알아야</b> 하는 행동이라
 * 공개로 열 여지가 없다 — 게스트는 FE 가 로그인 게이트로 보내고 호출 자체를 안 만든다.
 */
@RestController
@RequestMapping(value = "/courses", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class CourseBookmarkController {

    private final CourseBookmarkService bookmarkService;

    /** 저장한다. 이미 저장돼 있어도 200 + 같은 값(멱등). */
    @PostMapping("/{courseId}/bookmark")
    public ResponseEntity<?> bookmark(@CurrentUser Account account, @PathVariable Long courseId) {
        return ResponseEntity.ok().body(bookmarkService.bookmark(account, courseId));
    }

    /** 저장을 해제한다. 저장돼 있지 않아도 200 + 같은 값(멱등). */
    @DeleteMapping("/{courseId}/bookmark")
    public ResponseEntity<?> unbookmark(@CurrentUser Account account, @PathVariable Long courseId) {
        return ResponseEntity.ok().body(bookmarkService.unbookmark(account, courseId));
    }
}
