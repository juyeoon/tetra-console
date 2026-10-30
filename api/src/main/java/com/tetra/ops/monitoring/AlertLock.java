package com.tetra.ops.monitoring;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 1분 주기 자동화 확인의 전역 잠금(MySQL GET_LOCK). Console 태스크가 여러 개여도(배포 중 겹침 포함) 잡은 하나만 돈다.
 * 기다리지 않는다(timeout 0) — 못 잡으면 다른 태스크가 돌고 있으니 이번 회차는 건너뛴다.
 * GET_LOCK은 커넥션(세션) 단위라 풀에서 전용 커넥션을 하나 빌려 잠금만 쥐고, 작업이 끝나면 RELEASE_LOCK 뒤 반납한다
 * (태스크가 죽어 커넥션이 끊기면 MySQL이 잠금을 푼다). 작업의 DB 읽기 · 쓰기는 각자 다른 커넥션(짧은 트랜잭션)을 쓴다 —
 * 회차 동안 풀 커넥션 1개를 더 쓴다. 승인 잠금(ApproveCapacityLock, 트랜잭션에 묶여 기다림)과는 이름 · 방식이 다르다.
 */
@Component
class AlertLock {

	private static final Logger log = LoggerFactory.getLogger(AlertLock.class);

	static final String NAME = "tetra:automation-alerts";

	private final DataSource dataSource;

	AlertLock(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/** 잡으면 work를 돌리고 true, 다른 태스크가 잡고 있으면 돌리지 않고 false. work의 예외는 잠금을 푼 뒤 그대로 던진다 */
	boolean runIfAcquired(Runnable work) {
		try (Connection connection = dataSource.getConnection()) {
			if (!acquire(connection)) {
				return false;
			}
			try {
				work.run();
			}
			finally {
				release(connection);
			}
			return true;
		}
		catch (SQLException ex) {
			throw new IllegalStateException("자동화 확인 잠금 커넥션 오류: " + ex.getClass().getSimpleName(), ex);
		}
	}

	private static boolean acquire(Connection connection) throws SQLException {
		try (PreparedStatement st = connection.prepareStatement("select get_lock(?, 0)")) {
			st.setString(1, NAME);
			try (ResultSet rs = st.executeQuery()) {
				rs.next();
				return rs.getInt(1) == 1 && !rs.wasNull();
			}
		}
	}

	private static void release(Connection connection) {
		try (PreparedStatement st = connection.prepareStatement("select release_lock(?)")) {
			st.setString(1, NAME);
			st.executeQuery().close();
		}
		catch (SQLException ex) {
			// 커넥션을 반납하면 세션이 남아 있는 동안 잠금도 남는다 — 다음 회차들이 건너뛰다가 커넥션이 닫힐 때 풀린다
			log.error("자동화 확인 잠금 해제 실패: {}", ex.getClass().getSimpleName());
		}
	}

}
