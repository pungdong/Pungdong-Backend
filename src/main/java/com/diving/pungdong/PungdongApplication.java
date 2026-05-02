package com.diving.pungdong;

import org.modelmapper.ModelMapper;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
@EnableScheduling
public class PungdongApplication {

    // classpath: 항목은 필수 (사용자가 *.yml.example 을 *.yml로 카피해야 함)
    // optional:file: 항목은 운영 EC2에서만 존재. 로컬에선 없는 게 정상이므로 missing OK.
    public static final String APPLICATION_LOCATIONS = "spring.config.location="
            + "classpath:application.yml,"
            + "classpath:database.yml,"
            + "classpath:redis.yml,"
            + "classpath:aws.yml,"
            + "optional:file:/home/ubuntu/config/project/pungdong/database.yml,"
            + "optional:file:/home/ubuntu/config/project/pungdong/redis.yml,"
            + "optional:file:/home/ubuntu/config/project/pungdong/aws.yml";

    public static void main(String[] args) {
        new SpringApplicationBuilder(PungdongApplication.class)
                .properties(APPLICATION_LOCATIONS)
                .run(args);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }
}
