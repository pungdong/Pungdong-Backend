package com.diving.pungdong;

import org.modelmapper.ModelMapper;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.TimeZone;

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
        // 컨테이너 기본 TZ 는 UTC — 그러면 TZ 없는 LocalDateTime(예: 본인인증 otpExpiresAt, verifiedAt)이
        // UTC wall-clock 으로 직렬화되고, JS 가 이를 로컬(KST)로 읽어 9시간 어긋난다(FE 카운트다운 즉시 만료).
        // JVM 기본 TZ 를 KST 로 고정해 LocalDateTime.now() wall-clock·로그·DB serverTimezone(Asia/Seoul)과 정합.
        // (로컬 dev 는 이미 KST 라 우연히 맞았던 것 → 컨테이너에서만 재현되던 버그의 근본 봉합.)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
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
