package com.samsung.gsre.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * G-SRE 서비스맵 포털 장애 지원 AI Agent.
 *
 * <p>LangGraph 대신 6단계 명시적 상태머신으로 질의 처리 흐름을 제어하고,
 * 검증된 SQL 시맨틱 캐시(RAG), NL2SQL 생성, Self-Repair 실행 루프,
 * 4대 지표 품질 평가 하네스를 하나의 Spring Boot 애플리케이션에 담았다.</p>
 */
@EnableAsync
@ConfigurationPropertiesScan
@SpringBootApplication
public class IncidentAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentAgentApplication.class, args);
    }
}
