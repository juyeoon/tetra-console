package com.tetra.ops.tenancy;

/** 테넌트 스키마 테이블의 행. 읽은 행의 tenant_id를 라우팅한 tenant_id와 비교하는 데 쓴다 (§4-6). */
public interface TenantOwned {

	String getTenantId();

}
