package com.diving.pungdong.discipline;

import com.diving.pungdong.discipline.dto.DisciplineResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * 종목 목록 (공개). 홈 셀렉터 · 강사 신청 종목 선택 · (추후) 강의 생성 공용.
 * 매처: {@code GET /disciplines} → permitAll (로그인 전 홈에서도 필요).
 */
@RestController
@RequestMapping(value = "/disciplines", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class DisciplineController {

    private final DisciplineService disciplineService;

    @GetMapping
    public ResponseEntity<?> getDisciplines() {
        List<DisciplineResponse> disciplines = disciplineService.getActiveDisciplines();

        CollectionModel<DisciplineResponse> model = CollectionModel.of(disciplines);
        model.add(linkTo(methodOn(DisciplineController.class).getDisciplines()).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-disciplines").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
