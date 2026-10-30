package com.tetra.ops.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 1분 주기 자동화 확인(AutomationAlertJob)의 스케줄을 켠다. tetra.alerts.enabled=false(테스트 프로필)면 @Scheduled가 돌지 않는다 —
 * 작업 빈은 그대로 있어 테스트가 run()을 직접 부른다. 스케줄러 스레드는 Boot 기본(1개)이라 회차가 겹치지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "tetra.alerts", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

}
