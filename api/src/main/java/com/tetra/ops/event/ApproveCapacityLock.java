package com.tetra.ops.event;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.config.TetraProperties;

import jakarta.persistence.EntityManager;

/**
 * 동시 이벤트 상한 검사용 전역 잠금(MySQL GET_LOCK). 두 승인이 동시에 "아직 3개 미만"을 보고 둘 다 승인하는 것을 막는다.
 * 승인 트랜잭션 안에서 잡고, 트랜잭션이 끝나면(커밋 · 롤백) 같은 커넥션에서 푼다.
 * GET_LOCK은 커넥션(세션) 단위라 같은 커넥션이어야 풀린다 — Spring JPA는 트랜잭션이 끝날 때까지 커넥션을 붙잡고 있다
 * (DELAYED_ACQUISITION_AND_HOLD), 그래서 잡을 때 쓴 커넥션을 afterCompletion에서 그대로 쓴다.
 */
@Component
class ApproveCapacityLock {

	private static final Logger log = LoggerFactory.getLogger(ApproveCapacityLock.class);

	static final String NAME = "tetra:approve-capacity";

	private final EntityManager entityManager;

	private final int timeoutSeconds;

	ApproveCapacityLock(EntityManager entityManager, TetraProperties properties) {
		this.entityManager = entityManager;
		this.timeoutSeconds = properties.approve().lockTimeoutSeconds();
	}

	/** 기다려도 못 잡으면 409 APPROVE_BUSY */
	@Transactional(propagation = Propagation.MANDATORY)
	public void acquire() {
		Session session = entityManager.unwrap(Session.class);
		Connection connection = session.doReturningWork(c -> {
			try (PreparedStatement st = c.prepareStatement("select get_lock(?, ?)")) {
				st.setString(1, NAME);
				st.setInt(2, timeoutSeconds);
				try (ResultSet rs = st.executeQuery()) {
					rs.next();
					return rs.getInt(1) == 1 && !rs.wasNull() ? c : null;
				}
			}
		});
		if (connection == null) {
			log.warn("승인 잠금을 {}초 안에 잡지 못했습니다", timeoutSeconds);
			throw new ApiException(ErrorCode.APPROVE_BUSY);
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				release(connection);
			}
		});
	}

	private static void release(Connection connection) {
		try (PreparedStatement st = connection.prepareStatement("select release_lock(?)")) {
			st.setString(1, NAME);
			st.executeQuery().close();
		}
		catch (SQLException ex) {
			// 못 풀면 그 커넥션이 닫힐 때까지 다른 승인이 APPROVE_BUSY가 된다
			log.error("승인 잠금 해제 실패: {}", ex.getClass().getSimpleName());
		}
	}

}
