package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OperatorReportMetaRepository extends JpaRepository<OperatorReportMeta, Long> {

	/** OPS-09 최근 생성순 (idx_operator_report_created). 페이지네이션 없음. */
	List<OperatorReportMeta> findAllByOrderByCreatedAtDescReportIdDesc();

	/** 같은 리포트(같은 키)가 이미 있는지 — 다시 만들 때 메타 · 알림을 한 번만 남기려고. file_path 인덱스는 없다(한 달 몇 행) */
	boolean existsByFilePath(String filePath);

	/** 보관 2년: created_at이 cutoff보다 이른 행을 지운다(idx_operator_report_created). 지운 행 수 */
	@Transactional
	@Modifying
	@Query("delete from OperatorReportMeta r where r.createdAt < :cutoff")
	int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);

}
