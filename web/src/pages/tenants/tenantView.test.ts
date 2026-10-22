import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { eventPath } from '../../utils/event.ts';
import { passwordSetMeta, provisioningStatusMeta, registerOutcome, statusLogBadgeMeta } from './tenantView.ts';

describe('registerOutcome (OPS-07 등록 결과 화면 — 메일은 환경 준비 완료 뒤 서버가 발송)', () => {
  test('FAILED(스키마 생성 · StartExecution 실패) → 환경 준비 실패, 메일을 보내지 않음', () => {
    assert.deepEqual(registerOutcome('FAILED', 'E사', 'minho.park@e-corp.com'), {
      kind: 'failed',
      tone: 'red',
      title: '테넌트 환경 준비에 실패했습니다',
      body: 'E사 계정은 등록되었지만 환경 준비에 실패해 비밀번호 설정 메일을 보내지 않았습니다. 운영자 알림에서 원인을 확인해 주세요.',
    });
  });

  test('PROVISIONING · READY → 등록 완료, 환경 준비가 끝나면 메일 발송', () => {
    for (const status of ['PROVISIONING', 'READY'] as const) {
      assert.deepEqual(
        registerOutcome(status, 'E사', 'minho.park@e-corp.com'),
        {
          kind: 'done',
          tone: 'green',
          title: '테넌트 등록이 완료되었습니다',
          body: '환경 준비가 끝나면 minho.park@e-corp.com로 비밀번호 설정 메일이 발송됩니다.',
        },
        status,
      );
    }
  });
});

describe('비밀번호 설정 여부 (개요)', () => {
  test('설정 완료 / 비밀번호 미설정', () => {
    assert.deepEqual(passwordSetMeta(true), { label: '설정 완료', tone: 'green' });
    assert.deepEqual(passwordSetMeta(false), { label: '비밀번호 미설정', tone: 'grey' });
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
