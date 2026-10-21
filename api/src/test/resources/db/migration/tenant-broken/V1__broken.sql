-- TenantSchemaCreationFailureTest 전용: 첫 테이블은 만들어지고 두 번째 문장에서 실패한다 (부분 생성 후 DROP 확인용)
CREATE TABLE created_before_failure (id INT PRIMARY KEY);
CREATE TABLE broken (id NOT_A_TYPE);
