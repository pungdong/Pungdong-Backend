package com.diving.pungdong.usecase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 종목별 자격 레벨 표시 라벨(GET /courses/level-labels) use-case = 실행 가능한 사양. 공개(비로그인) 정적
 * 메타 — 평탄화 코드 + 종목 통용 명칭(alias). {@code @DisplayName} 을 위→아래로 읽으면 규칙이 된다.
 *
 * <p>그룹: S* 종목별 라벨, V* 검증. 인증 불필요(필터 UI 메타).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseLevelLabelUseCaseTest {

    @Autowired MockMvc mockMvc;

    @Test
    @DisplayName("S1 스쿠버는 6레벨이 종목 통용 명칭(풀네임) alias 와 함께 온다 — 비로그인 공개")
    void s1_scuba_aliases() throws Exception {
        mockMvc.perform(get("/courses/level-labels?disciplineCode=SCUBA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.levelLabels", hasSize(6)))
                .andExpect(jsonPath("$._embedded.levelLabels[0].level").value("LEVEL_1"))
                .andExpect(jsonPath("$._embedded.levelLabels[0].label").value("레벨 1"))
                .andExpect(jsonPath("$._embedded.levelLabels[0].alias").value("Open Water Diver"))
                .andExpect(jsonPath("$._embedded.levelLabels[3].alias").value("Divemaster"))
                .andExpect(jsonPath("$._embedded.levelLabels[5].level").value("INSTRUCTOR_TRAINER"))
                .andExpect(jsonPath("$._embedded.levelLabels[5].alias").value("Instructor Trainer"));
    }

    @Test
    @DisplayName("S2 프리다이빙은 공통 명칭이 없어 alias 가 모두 null(단계명 label 만)")
    void s2_freediving_no_alias() throws Exception {
        mockMvc.perform(get("/courses/level-labels?disciplineCode=FREEDIVING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.levelLabels", hasSize(6)))
                .andExpect(jsonPath("$._embedded.levelLabels[0].label").value("레벨 1"))
                .andExpect(jsonPath("$._embedded.levelLabels[0].alias").value(nullValue()))
                .andExpect(jsonPath("$._embedded.levelLabels[4].alias").value(nullValue()));
    }

    @Test
    @DisplayName("V1 종목(disciplineCode) 없이 부르면 400")
    void v1_discipline_required() throws Exception {
        mockMvc.perform(get("/courses/level-labels"))
                .andExpect(status().isBadRequest());
    }
}
