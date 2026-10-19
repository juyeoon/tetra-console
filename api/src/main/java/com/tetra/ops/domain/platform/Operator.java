package com.tetra.ops.domain.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 운영자. DB CLI로 직접 등록하므로 이 앱은 읽기만 한다. */
@Entity
@Table(name = "operator", catalog = "tetra_platform")
public class Operator {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "operator_id", nullable = false, columnDefinition = "int unsigned")
	private Long operatorId;

	@Column(name = "email", length = 255, nullable = false)
	private String email;

	@Column(name = "contact_name", length = 100, nullable = false)
	private String contactName;

	@Column(name = "password", length = 255, nullable = false)
	private String password;

	protected Operator() {
	}

	public Long getOperatorId() {
		return operatorId;
	}

	public String getEmail() {
		return email;
	}

	public String getContactName() {
		return contactName;
	}

	/** 비밀번호 해시. 로그·응답에 내보내지 않는다. */
	public String getPassword() {
		return password;
	}

}
