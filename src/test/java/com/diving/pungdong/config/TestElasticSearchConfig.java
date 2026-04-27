package com.diving.pungdong.config;

import com.diving.pungdong.repo.elasticSearch.LectureEsRepo;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test")
public class TestElasticSearchConfig {

    @Bean
    public LectureEsRepo lectureEsRepo() {
        return Mockito.mock(LectureEsRepo.class);
    }
}
