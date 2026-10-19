-- 테스트 공통 seed (tetra_platform). init.sql에는 seed가 없으므로 테스트가 직접 넣는다.
-- 운영자 비밀번호 평문: ops-password-1 (BCrypt)
INSERT INTO operator (operator_id, email, contact_name, password)
VALUES (1, 'operator@tetra-operation.com', '홍길동', '$2a$10$okElfhEuxbaHWkowSCIryez9gAIoLsJ78Jxo9f/j1vYHXyif9Jlhu');

INSERT INTO traffic_plan (plan_code, max_capacity, hourly_price, hosting_hourly_price, is_visible) VALUES
    ('S', 10000, 10000.00, 1000.00, 1),
    ('M', 30000, 25000.00, 2000.00, 1),
    ('L', 50000, 40000.00, 3000.00, 1),
    ('XL', 100000, 70000.00, 5000.00, 0);

-- 템플릿은 이벤트 타입당 1행(앱 빌드 경로). 요소 위치·테마는 event.layout_config
INSERT INTO template (template_id, event_type, description, file_path)
VALUES (1, 'COUPON', '쿠폰 이벤트 앱', 'apps/coupon/');
