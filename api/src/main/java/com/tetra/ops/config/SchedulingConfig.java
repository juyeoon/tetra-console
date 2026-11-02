package com.tetra.ops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 회차로 도는 작업의 스케줄을 켠다(항상). 켜고 끄기는 작업마다 따로 — 작업 빈이 자기 스위치(@ConditionalOnProperty)로 있거나 없다:
 * 1분 주기 자동화 확인(AutomationAlertJob, tetra.alerts.enabled) · Slack 발송(SlackNotifyJob, tetra.slack.enabled).
 * 테스트 프로필은 둘 다 꺼 두고 테스트가 run()을 직접 부른다.
 * 스케줄러 스레드는 작업마다 하나 이상(spring.task.scheduling.pool.size, application.yml) — 자동화 확인 회차가 AWS 호출로 길어져도
 * Slack 발송이 기다리지 않는다. 같은 작업은 fixedDelay라 회차가 겹치지 않는다. 새 작업(리포트 등)은 같은 방식으로 붙인다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

}
