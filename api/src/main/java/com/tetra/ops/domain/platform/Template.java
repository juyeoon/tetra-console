package com.tetra.ops.domain.platform;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tetra.ops.domain.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 이벤트 타입별 페이지 앱 빌드 경로. 타입당 1행(uk_template_event_type).
 * 요소 위치·배경 테마는 여기가 아니라 event.layout_config에 있다.
 */
@Entity
@Table(name = "template", catalog = "tetra_platform")
public class Template {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "template_id", nullable = false, columnDefinition = "int unsigned")
	private Long templateId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "event_type", length = 20, nullable = false)
	private EventType eventType;

	@Column(name = "description", length = 500)
	private String description;

	@Column(name = "file_path", length = 1024, nullable = false)
	private String filePath;

	protected Template() {
	}

	public Long getTemplateId() {
		return templateId;
	}

	public String getFilePath() {
		return filePath;
	}

}
