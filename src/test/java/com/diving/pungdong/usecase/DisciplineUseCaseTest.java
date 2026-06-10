package com.diving.pungdong.usecase;

import com.diving.pungdong.discipline.Discipline;
import com.diving.pungdong.discipline.DisciplineJpaRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 종목(discipline) use-case. 부팅 시 {@link com.diving.pungdong.discipline.DisciplineSeeder} 가
 * 기본 종목(프리다이빙/스쿠버=자격증필요, 수영/서핑=불필요)을 seed 하고, 공개 GET /disciplines 로
 * 노출된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DisciplineUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DisciplineJpaRepo disciplineRepo;

    @Test
    @DisplayName("D1: GET /disciplines 는 인증 없이 200 + seed 된 활성 종목을 정렬 순서로 반환한다")
    void disciplines_arePublicAndSeeded() throws Exception {
        MvcResult res = mockMvc.perform(get("/disciplines"))
                .andExpect(status().isOk())
                .andReturn();

        // 임베디드 키 이름에 의존하지 않게 첫 배열을 직접 집는다
        JsonNode list = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").elements().next();
        assertThat(list).hasSizeGreaterThanOrEqualTo(4);
        // sortOrder 1 = 프리다이빙
        assertThat(list.get(0).get("code").asText()).isEqualTo("FREEDIVING");
        assertThat(list.get(0).get("requiresCertification").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("D2: 종목별 자격증 필요 여부 — 프리다이빙/스쿠버=true, 수영/서핑=false")
    void requiresCertification_flags() {
        assertThat(disciplineRepo.findByCode("FREEDIVING").orElseThrow().isRequiresCertification()).isTrue();
        assertThat(disciplineRepo.findByCode("SCUBA").orElseThrow().isRequiresCertification()).isTrue();
        assertThat(disciplineRepo.findByCode("SWIMMING").orElseThrow().isRequiresCertification()).isFalse();
        assertThat(disciplineRepo.findByCode("SURFING").orElseThrow().isRequiresCertification()).isFalse();
    }

    @Test
    @DisplayName("D3: seed 는 idempotent — 활성 종목 코드는 중복 없이 유일하다")
    void seed_isIdempotent() {
        long freediving = disciplineRepo.findAll().stream()
                .map(Discipline::getCode).filter("FREEDIVING"::equals).count();
        assertThat(freediving).isEqualTo(1);
    }
}
