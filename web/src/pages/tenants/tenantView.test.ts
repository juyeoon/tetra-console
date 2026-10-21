import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { eventPath } from '../../utils/event.ts';
import { provisioningStatusMeta, registerOutcome, statusLogBadgeMeta } from './tenantView.ts';

describe('registerOutcome (OPS-07 등록 결과 화면)', () => {
  test('FAILED + 메일 안 보냄 → 환경 준비 실패, 메일 보내지 않음', () => {
    const o = registerOutcome('FAILED', false, 'E사');
    assert.equal(o.kind, 'schemaFailed');
    assert.equal(o.tone, 'red');
    assert.equal(o.title, '테넌트 환경 준비에 실패했습니다');
    assert.match(o.body ?? '', /E사 계정은 등록되었지만 환경 준비에 실패해 비밀번호 설정 메일을 보내지 않았습니다/);
  });

  test('FAILED + 메일 보냄 → 환경 준비 시작 실패(수동 재실행)', () => {
    const o = registerOutcome('FAILED', true, 'E사');
    assert.equal(o.kind, 'startFailed');
    assert.match(o.body ?? '', /메일은 발송되었습니다/);
  });

  test('PROVISIONING · READY + 메일 실패 → 메일 발송 실패, 테넌트 상세 안내', () => {
    for (const status of ['PROVISIONING', 'READY'] as const) {
      const o = registerOutcome(status, false, 'E사');
      assert.equal(o.kind, 'mailFailed', status);
      assert.equal(o.title, '비밀번호 설정 메일 발송에 실패했습니다');
      assert.equal(o.body, '계정은 등록되었고 환경 준비는 진행 중입니다. 메일을 받지 못한 경우 테넌트 상세에서 확인해 주세요.');
      // 미완성 기능을 드러내는 문구는 넣지 않는다
      assert.doesNotMatch(o.body ?? '', /재발송 기능/);
    }
  });

  test('메일 보냄 + 실패 아님 → 완료', () => {
    assert.deepEqual(registerOutcome('PROVISIONING', true, 'E사'), {
      kind: 'done',
      tone: 'green',
      title: '테넌트 등록이 완료되었습니다',
      body: null,
    });
  });
});

describe('칩 · 배지', () => {
  test('환경 준비: READY는 표시하지 않고 준비 중 · 실패만', () => {
    assert.equal(provisioningStatusMeta.READY, null);
    assert.deepEqual(provisioningStatusMeta.PROVISIONING, { label: '환경 준비 중', tone: 'grey' });
    assert.deepEqual(provisioningStatusMeta.FAILED, { label: '환경 준비 실패', tone: 'red' });
  });

  test('계정 상태 로그: REGISTERED는 배지 없음', () => {
    assert.equal(statusLogBadgeMeta.REGISTERED, null);
    assert.equal(statusLogBadgeMeta.APPLIED?.label, '신청 완료');
  });

  test('이벤트 상세 경로: 승인됨은 진행 현황, 그 외는 신청 현황', () => {
    assert.equal(eventPath(1044, 'APPROVED'), '/events/progress/1044');
    assert.equal(eventPath(1043, 'APPLIED'), '/events/applications/1043');
    assert.equal(eventPath(1045, 'REJECTED'), '/events/applications/1045');
  });
});
