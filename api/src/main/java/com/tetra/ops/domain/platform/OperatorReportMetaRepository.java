package com.tetra.ops.domain.platform;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorReportMetaRepository extends JpaRepository<OperatorReportMeta, Long> {

	/** OPS-09 최근 생성순 (idx_operator_report_created). 페이지네이션 없음. */
	List<OperatorReportMeta> findAllByOrderByCreatedAtDescReportIdDesc();

}
