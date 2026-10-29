import type { EventExecution, EventExecutionsResponse, ExecutionFlow, ExecutionState, ExecutionStatus } from '../../api/types.ts';
import { formatClock } from '../../utils/datetime.ts';
import { clockText, durationText, EMPTY } from './trafficMonitoringView.ts';

/**
 * OPS-05 자동화 실행 상세(Step Functions) 응답 → 화면 모델(node --test 대상, 2026-10-29).
 * 흐름 4칸(배포 · 호스팅 시작 · 준비 · 호스팅 종료)은 실행이 없어도 자리를 두고 '실행 없음'.
 * 단계는 서버가 줄여 준다: 같은 이름 한 줄 · Wait 반복은 LOOP 한 줄 · Pass · Choice · Succeed는 실패 단계일 때만.
 */

type Tone = 'green' | 'amber' | 'red' | 'grey' | 'blue';

export const EXECUTION_PANEL_TITLE = '자동화 실행 상세';

export const FLOWS: ExecutionFlow[] = ['DEPLOY', 'HOSTING_START', 'PREPARE', 'END'];

/** 진행 단계 칸 이름과 같게(준비 · 입장의 T− 표기는 빼고) */
const FLOW_META: Record<ExecutionFlow, { label: string; workflow: string }> = {
  DEPLOY: { label: '배포', workflow: 'WF1-2' },
  HOSTING_START: { label: '호스팅 시작', workflow: 'WF2-1' },
  PREPARE: { label: '준비', workflow: 'WF2-2' },
  END: { label: '호스팅 종료', workflow: 'WF4' },
};

export const EXECUTION_STATUS_META: Record<ExecutionStatus, { label: string; tone: Tone }> = {
  RUNNING: { label: '실행 중', tone: 'blue' },
  SUCCEEDED: { label: '성공', tone: 'green' },
  FAILED: { label: '실패', tone: 'red' },
  TIMED_OUT: { label: '시간 초과', tone: 'red' },
  ABORTED: { label: '중단', tone: 'red' },
  PENDING_REDRIVE: { label: '재실행 대기', tone: 'amber' },
};

export const NO_EXECUTION_TEXT = '실행 없음';

export const EXECUTIONS_FAILED_TEXT = '실행 기록을 불러오지 못했어요';

const isRunning = (status: string) => status === 'RUNNING' || status === 'PENDING_REDRIVE';

/** 실행 중(재실행 대기 포함)이 있을 때만 15초마다 다시 부른다 */
export const hasRunningExecution = (res: EventExecutionsResponse) => res.executions.some((e) => isRunning(e.status));

export const stopExecutionPolling = (res: EventExecutionsResponse) => !hasRunningExecution(res);

export interface StateRowView {
  key: string;
  /** 'WaitEventReady → CheckEventApp → IsEventAppReady' */
  name: string;
  /** '반복 6회' · '3회' · '대기' · '병렬' · '항목별' */
  badge: string | null;
  /** '14:45:41~14:48:46' · '14:55:03~진행 중' */
  time: string;
  duration: string;
  retries: string | null;
  failed: boolean;
  running: boolean;
}

export interface ExecutionFailureView {
  /** '실패 단계 IsEventAppReady · EventAppSyncTimedOut' */
  title: string;
  reason: string | null;
  /** 가린 원문(펼쳐서 봄) */
  cause: string | null;
}

export interface ExecutionView {
  key: string;
  name: string;
  /** 같은 흐름의 두 번째 실행부터 '재실행 1' … , 이어서 실행(redrive)은 '이어서 1회' */
  badges: string[];
  status: { label: string; tone: Tone };
  /** '14:45~14:55 · 9분 47초' · '14:45 시작 · 3분 20초째' */
  time: string;
  failure: ExecutionFailureView | null;
  states: StateRowView[];
  note: string | null;
  /** 실패 · 실행 중이면 처음부터 펼침 */
  defaultOpen: boolean;
}

export interface FlowView {
  key: ExecutionFlow;
  /** '준비 · WF2-2' */
  title: string;
  executions: ExecutionView[];
}

export interface ExecutionPanelView {
  /** 지연 · 실패 안내(정상이면 null) */
  notice: { text: string; tone: 'amber' | 'red' } | null;
  flows: FlowView[];
}

/** 응답 전(펼친 직후)에는 flows가 빈 배열 — 화면은 불러오는 중 */
export function executionPanel(res: EventExecutionsResponse | undefined, requestFailed: boolean, now: Date): ExecutionPanelView {
  if (!res) {
    return { notice: requestFailed ? { text: EXECUTIONS_FAILED_TEXT, tone: 'red' } : null, flows: [] };
  }
  let notice: ExecutionPanelView['notice'] = null;
  if (res.aws.stale) {
    notice = res.aws.asOf ? { text: `지연 · 마지막 정상 ${formatClock(res.aws.asOf)}`, tone: 'amber' } : { text: EXECUTIONS_FAILED_TEXT, tone: 'red' };
  } else if (requestFailed) {
    notice = { text: '새로 고치지 못해 지난 값을 보여요', tone: 'amber' };
  }
  const flows = FLOWS.map((flow) => ({
    key: flow,
    title: `${FLOW_META[flow].label} · ${FLOW_META[flow].workflow}`,
    executions: res.executions.filter((e) => e.flow === flow).map((e) => executionView(e, now)),
  }));
  return { notice, flows };
}

export function executionView(e: EventExecution, now: Date): ExecutionView {
  const badges: string[] = [];
  if (e.attempt > 1) badges.push(`재실행 ${e.attempt - 1}`);
  if (e.redriveCount > 0) badges.push(`이어서 ${e.redriveCount}회`);
  const running = isRunning(e.status);
  const duration = durationText(e.durationSeconds);
  const time = running || !e.stoppedAt
    ? `${clockText(e.startedAt, now)} 시작${duration ? ` · ${duration}째` : ''}`
    : `${clockText(e.startedAt, now)}~${clockText(e.stoppedAt, now)}${duration ? ` · ${duration}` : ''}`;
  return {
    key: e.name,
    name: e.name,
    badges,
    status: EXECUTION_STATUS_META[e.status] ?? { label: e.status, tone: 'grey' },
    time,
    failure: e.failure ? failureView(e) : null,
    states: e.states.map((s, i) => stateRow(s, i)),
    note: e.truncated ? '기록이 길어 앞부분만 보여요' : null,
    defaultOpen: e.status !== 'SUCCEEDED',
  };
}

function failureView(e: EventExecution): ExecutionFailureView {
  const f = e.failure!;
  const head = f.state ? `실패 단계 ${f.state}` : `${EXECUTION_STATUS_META[e.status]?.label ?? e.status} · 단계 확인 불가`;
  return { title: f.error ? `${head} · ${f.error}` : head, reason: f.reason, cause: f.cause };
}

const KIND_BADGE: Partial<Record<ExecutionState['kind'], string>> = { WAIT: '대기', PARALLEL: '병렬', MAP: '항목별' };

export function stateRow(s: ExecutionState, index: number): StateRowView {
  const badge = s.kind === 'LOOP' ? `반복 ${s.count}회` : s.count > 1 ? `${s.count}회` : (KIND_BADGE[s.kind] ?? null);
  const start = s.startedAt ? formatClock(s.startedAt) : EMPTY;
  const end = s.endedAt ? formatClock(s.endedAt) : s.status === 'RUNNING' ? '진행 중' : EMPTY;
  return {
    key: `${index}-${s.names.join('-')}`,
    name: s.names.join(' → '),
    badge,
    time: `${start}~${end}`,
    duration: durationText(s.durationSeconds) ?? EMPTY,
    retries: s.retries > 0 ? `재시도 ${s.retries}회` : null,
    failed: s.status === 'FAILED',
    running: s.status === 'RUNNING',
  };
}
