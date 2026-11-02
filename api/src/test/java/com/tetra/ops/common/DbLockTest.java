package com.tetra.ops.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.tetra.ops.support.MySqlTestDatabase;

/**
 * 회차 작업의 전역 잠금(GET_LOCK, 기다리지 않음). 태스크 둘을 커넥션을 따로 주는 DataSource 둘로 흉내 낸다
 * (통합 테스트의 커넥션 풀은 1개라 쓰지 않는다).
 */
class DbLockTest {

	private static final String NAME = "tetra:test-lock";

	private static DbLock taskA;

	private static DbLock taskB;

	@BeforeAll
	static void locks() {
		MySqlTestDatabase.start();
		taskA = new DbLock(dataSource(), NAME);
		taskB = new DbLock(dataSource(), NAME);
	}

	private static DriverManagerDataSource dataSource() {
		return new DriverManagerDataSource(MySqlTestDatabase.appJdbcUrl("tetra_platform"), MySqlTestDatabase.APP_USER,
				MySqlTestDatabase.APP_PASSWORD);
	}

	@Test
	void onlyOneTaskRunsWhileLockIsHeld() {
		AtomicBoolean otherRan = new AtomicBoolean();
		AtomicBoolean otherAcquired = new AtomicBoolean(true);

		boolean ran = taskA.runIfAcquired(() -> otherAcquired.set(taskB.runIfAcquired(() -> otherRan.set(true))));

		// A가 도는 동안 B는 기다리지 않고 건너뛴다
		assertThat(ran).isTrue();
		assertThat(otherAcquired).isFalse();
		assertThat(otherRan).isFalse();
		// A가 끝나면(RELEASE_LOCK) B가 잡는다
		assertThat(taskB.runIfAcquired(() -> otherRan.set(true))).isTrue();
		assertThat(otherRan).isTrue();
	}

	@Test
	void lockIsReleasedWhenWorkFails() {
		assertThatThrownBy(() -> taskA.runIfAcquired(() -> {
			throw new IllegalStateException("작업 실패");
		})).isInstanceOf(IllegalStateException.class).hasMessage("작업 실패");

		assertThat(taskB.runIfAcquired(() -> {
		})).isTrue();
	}

	/** 자동화 확인 · Slack 발송처럼 이름이 다른 작업은 서로 막지 않는다 */
	@Test
	void differentNamesDoNotBlockEachOther() {
		DbLock other = new DbLock(dataSource(), "tetra:test-other-lock");
		AtomicBoolean otherRan = new AtomicBoolean();

		boolean ran = taskA.runIfAcquired(() -> otherRan.set(other.runIfAcquired(() -> {
		})));

		assertThat(ran).isTrue();
		assertThat(otherRan).isTrue();
	}

}
