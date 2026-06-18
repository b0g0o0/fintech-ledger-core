package com.bugeon.fintechledger.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 설정.
 *
 * @EnableJpaAuditing을 @SpringBootApplication에서 분리한 이유:
 *   @EnableJpaAuditing이 @SpringBootApplication에 있으면 @WebMvcTest 실행 시
 *   JpaAuditingHandler → JpaMappingContext → JPA 메타모델 순서로 초기화를 시도한다.
 *   @WebMvcTest는 JPA 엔티티를 로드하지 않으므로
 *   "JPA metamodel must not be empty" 예외가 발생한다.
 *
 *   별도 @Configuration 클래스로 분리하면 @WebMvcTest가 이 클래스를 로드하지 않아
 *   JPA 관련 초기화가 일어나지 않는다.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
