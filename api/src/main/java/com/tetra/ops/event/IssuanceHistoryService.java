package com.tetra.ops.event;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.event.IssuanceHistoryStore.Snapshot;
import com.tetra.ops.external.ObjectStorage;
import com.tetra.ops.tenancy.TenantRouter;
import com.tetra.ops.tenancy.TenantRouter.RoutedEvent;
import com.tetra.ops.tenant.TenantBuckets;

/**
 * 발급 이력 저장(3-12). WF4 성공 결과를 반영한 뒤(상태 트랜잭션 커밋 후) 부른다.
 * 결과 수 · CSV를 DB에서 만들고 → CSV를 테넌트 로그 버킷 issuance-history/{event_id}.csv에 올리고(덮어쓰기) →
 * event_result_summary에 결과 수와 키를 쓴다. S3가 먼저라 DB에 키가 있으면 파일은 있다.
 * 실패하면 예외를 그대로 던진다(메시지 재전달 → 상태는 이미 처리됨 → 여기만 다시, 같은 키 · 같은 행에 덮어쓰기).
 */
@Service
public class IssuanceHistoryService {

	private static final Logger log = LoggerFactory.getLogger(IssuanceHistoryService.class);

	private final TenantRouter router;

	private final IssuanceHistoryStore store;

	private final ObjectStorage storage;

	private final TenantBuckets buckets;

	private final Clock clock;

	public IssuanceHistoryService(TenantRouter router, IssuanceHistoryStore store, ObjectStorage storage,
			TenantBuckets buckets, Clock clock) {
		this.router = router;
		this.store = store;
		this.storage = storage;
		this.buckets = buckets;
		this.clock = clock;
	}

	/** 테넌트 로그 버킷 안의 키. DB(history_file_path)에도 이 키만 저장한다 */
	public static String historyKey(Long eventId) {
		return "issuance-history/" + eventId + ".csv";
	}

	public void save(RoutedEvent routed) {
		Long eventId = routed.eventId();
		Snapshot snapshot = router.inTenant(routed, () -> store.snapshot(eventId));
		String key = historyKey(eventId);
		storage.put(buckets.auditLog(routed.tenantId()), key, snapshot.csv(), "text/csv; charset=utf-8");
		router.inTenant(routed, () -> store.saveSummary(eventId, snapshot, key, Kst.now(clock)));
		log.info("발급 이력 저장: eventId={}, total={}, success={}, soldout={}, key={}", eventId, snapshot.totalStock(),
				snapshot.successCount(), snapshot.soldoutCount(), key);
	}

}
