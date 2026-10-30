/**
 * Console API 응답 타입 — 서버 DTO(api/src/main/java/com/tetra/ops/**) 이름 그대로 손으로 옮긴다.
 * 일시는 ISO-8601(+09:00) 문자열. 화면 표시는 utils/datetime.ts로 바꾼다.
 * /v3/api-docs(로컬 API_DOCS_ENABLED=true)로 교차 확인한다.
 */

export type AccountStatus = 'ACTIVE' | 'INACTIVE';
export type ProvisioningStatus = 'PROVISIONING' | 'READY' | 'FAILED';
export type ApplicationStatus = 'APPLYING' | 'APPLIED' | 'APPLY_FAILED' | 'APPROVED' | 'REJECTED';
export type HostingStatus = 'PENDING' | 'DEPLOYED' | 'DEPLOY_FAILED' | 'HOSTING' | 'HOSTING_FAILED' | 'ENDED' | 'END_FAILED';
export type EventStatus = 'SCHEDULED' | 'OPEN' | 'OPEN_FAILED' | 'EXPIRED';
export type EventType = 'COUPON' | 'TIMESALE' | 'LIVE_COMMERCE';

// ---------------------------------------------------------------- 테넌트 (TenantQueryService · TenantCommandService)

/** GET /tenants — 가입일시 내림차순 */
export interface TenantListResponse {
  counts: Record<AccountStatus, number>;
  items: TenantItem[];
}

export interface TenantItem {
  tenantId: string;
  name: string;
  email: string;
  accountStatus: AccountStatus;
  provisioningStatus: ProvisioningStatus;
  createdAt: string;
}

/** GET /tenants/{id}의 basic, PATCH /tenants/{id} 응답 (문서 스키마 TenantBasic) */
export interface TenantBasic {
  tenantId: string;
  name: string;
  contactName: string;
  email: string;
  accountStatus: AccountStatus;
  createdAt: string;
  provisioningStatus: ProvisioningStatus;
  /** 비밀번호를 설정했는지(password IS NOT NULL) */
  passwordSet: boolean;
}

/** 계정 상태 로그 사유 배지. REGISTERED = 최초 기록(테넌트 등록) */
export type StatusLogBadge = 'REGISTERED' | 'APPLIED' | 'REJECTED' | 'ENDED';

export interface StatusLogItem {
  changedAt: string;
  /** 최초 기록(REGISTERED)은 null */
  prevStatus: AccountStatus | null;
  newStatus: AccountStatus;
  badge: StatusLogBadge;
  reason: string | null;
  relatedEvent: { eventId: number; name: string; applicationStatus: ApplicationStatus } | null;
}

/** GET /tenants/{id} */
export interface TenantOverviewResponse {
  basic: TenantBasic;
  buckets: { content: string; auditLog: string };
  /** 최신순 */
  statusLogs: StatusLogItem[];
}

/** GET /tenants/{id}/events — 신청시각 내림차순. 승인 전에는 hostingStatus · eventStatus가 null */
export interface TenantEventItem {
  eventId: number;
  name: string;
  eventType: EventType;
  applicationStatus: ApplicationStatus;
  hostingStatus: HostingStatus | null;
  eventStatus: EventStatus | null;
  trafficPlanCode: string;
  appliedAt: string;
}

/** GET /tenants/{id}/resources — 승인된 이벤트만. 만료(EXPIRED)면 subdomain · publicKey가 null */
export interface TenantResourceItem {
  eventId: number;
  name: string;
  eventStatus: EventStatus | null;
  subdomain: string | null;
  publicKey: string | null;
}

export interface RegisterRequest {
  name: string;
  email: string;
  rootDomain: string;
  contactName: string;
}

/** POST /tenants (201). 비밀번호 설정 메일은 환경 준비가 끝나면(READY) 서버가 보낸다 — 등록 응답에 메일 결과는 없다 */
export interface RegisterResponse {
  tenantId: string;
  /** 응답 시점의 환경 준비 상태. 스키마 생성 · StartExecution 실패면 FAILED(메일 없음) */
  provisioningStatus: ProvisioningStatus;
}

/** PATCH /tenants/{id} — 정확히 하나만 보낸다 */
export type UpdateRequest = { name: string } | { contactName: string } | { email: string };

// ---------------------------------------------------------------- 이벤트 (EventListService · EventDetailResponse · EventResultResponse)

/** GET /applications — 승인 대기 중 → 신청됨(처리 중) → 나머지(신청시각 내림차순) */
export interface ApplicationListResponse {
  counts: Record<ApplicationStatus, number>;
  items: ApplicationItem[];
}

export interface ApplicationItem {
  eventId: number;
  tenantName: string;
  eventType: EventType;
  name: string;
  appliedAt: string;
  applicationStatus: ApplicationStatus;
  /** 승인 · 반려 처리 일시. 그 밖(신청 실패 포함)은 null */
  processedAt: string | null;
}

/** GET /live-events — 승인된 이벤트. counts는 PENDING · HOSTING · ENDED만 */
export interface LiveEventListResponse {
  counts: Partial<Record<HostingStatus, number>>;
  items: LiveEventItem[];
}

export interface LiveEventItem {
  eventId: number;
  tenantName: string;
  eventType: EventType;
  name: string;
  hostingStartAt: string;
  startAt: string;
  hostingStatus: HostingStatus | null;
  eventStatus: EventStatus | null;
  trafficPlanCode: string;
}

export type StatusType = 'APPLICATION' | 'HOSTING' | 'EVENT';

/**
 * 상태 로그 한 줄 (event_status_log 전체, changed_at 오름차순 · 같은 시각은 기록 순서).
 * detail = 반려 사유(신청 REJECTED) · 실패 원인 등. actor: 운영자(이름) · 테넌트(테넌트명) · 자동 처리(name null)
 */
export interface EventStatusLog {
  changedAt: string;
  statusType: StatusType;
  /** 최초 기록이면 null. 값은 statusType별 상태(신청/호스팅/이벤트) */
  prevStatus: string | null;
  newStatus: string;
  detail: string | null;
  actor: { type: 'OPERATOR' | 'TENANT' | 'SYSTEM'; name: string | null };
}

/** GET /events/{id} (문서 스키마 EventDetail*) */
export interface EventDetailResponse {
  basic: {
    eventId: number;
    name: string;
    eventType: EventType;
    tenantId: string;
    tenantName: string;
    hostingStartAt: string;
    hostingEndAt: string;
    startAt: string;
    endAt: string;
    trafficPlanCode: string;
    stockCount: number;
    appliedAt: string;
    endpointUrl: string;
  };
  /** 승인 전에는 hostingStatus · eventStatus가 null */
  status: {
    applicationStatus: ApplicationStatus;
    hostingStatus: HostingStatus | null;
    eventStatus: EventStatus | null;
    resultAvailable: boolean;
  };
  /** domain = {slug}.{tenant_id}, fullDomain = domain.{플랫폼 도메인}. 승인 전 · 만료면 null */
  resources: {
    templateId: number;
    domain: string | null;
    fullDomain: string | null;
    publicKey: string | null;
  };
  statusLogs: EventStatusLog[];
}

/** POST /events/{id}/approve — 409 APPLICATION_NOT_APPLIED · HOSTING_PERIOD_ENDED · TENANT_NOT_READY */
export interface ApproveResponse {
  eventId: number;
  applicationStatus: ApplicationStatus;
  hostingStatus: HostingStatus | null;
  eventStatus: EventStatus | null;
  /** false면 OPS-03-M1 "개인키 메일 발송 실패" 상태 (승인은 됨) */
  privateKeyMailSent: boolean;
}

/** POST /events/{id}/reject — 400 fields.reason, 409 APPLICATION_NOT_APPLIED */
export interface RejectResponse {
  eventId: number;
  applicationStatus: ApplicationStatus;
}

/** GET /events/{id}/result — state별로 쓰지 않는 값은 null */
export interface EventResultResponse {
  eventId: number;
  /** IN_PROGRESS = 이벤트가 끝나기 전(오픈 전 포함) */
  state: 'IN_PROGRESS' | 'AGGREGATING' | 'END_FAILED' | 'READY';
  eventOpenAt: string | null;
  aggregatedAt: string | null;
  hostingStatus: HostingStatus | null;
  eventStatus: EventStatus | null;
  endAt: string | null;
  failure: { cause: string | null; occurredAt: string | null } | null;
  result: {
    metrics: {
      successCount: number;
      totalStock: number;
      selloutRate: number;
      soldoutCount: number;
      peakConcurrent: number;
      maxCapacity: number;
      capacityUsageRate: number;
      peakAt: string | null;
    };
    composition: { successCount: number; soldoutCount: number; abandonedCount: number };
    perMinute: { minute: string; successCount: number; soldoutCount: number }[];
  } | null;
}

/** GET /events/{id}/result/history-csv — 결과 전이면 409 RESULT_NOT_READY */
export interface CsvDownloadResponse {
  url: string;
  expiresAt: string;
}

// ---------------------------------------------------------------- 신청 내용 (OPS-03-2 · 04-2)

export type HAlign = 'LEFT' | 'CENTER' | 'RIGHT';
export type VAlign = 'TOP' | 'MIDDLE' | 'BOTTOM';
export type ScreenWidget = 'title' | 'banner' | 'couponList' | 'issueButton';
export type ScreenTheme = 'WHITE' | 'WARM_ORANGE' | 'DARK' | 'BLUE';

/** event.layout_config — 요소 위치(h · v)와 테마 */
export type LayoutConfig = Record<ScreenWidget, { h: HAlign; v: VAlign }> & { theme: ScreenTheme };

/** GET /events/{id}/application-summary — 원본 값. 표시 문자열은 화면(applicationSummaryView)이 만든다 */
export interface ApplicationSummaryResponse {
  eventId: number;
  eventType: EventType;
  name: string;
  startAt: string;
  endAt: string;
  hostingStartAt: string;
  hostingEndAt: string;
  trafficPlan: { code: string; maxCapacity: number | null };
  /** 배너 문구 분기용(APPLYING · APPLY_FAILED) */
  applicationStatus: ApplicationStatus;
  /** 저장 경로의 파일명(원본 파일명 아님). 없으면 null */
  bannerFileName: string | null;
  endpointUrl: string;
  /** coupon_id 오름차순. 쿠폰 타입이 아니면 [] */
  coupons: { couponId: number; name: string }[];
  stockCount: number;
  layoutConfig: LayoutConfig;
}

// ---------------------------------------------------------------- 대시보드 (DashboardController)

export type NotificationPriority = 'URGENT' | 'WARNING' | 'INFO';

/** GET /dashboard — 최근 알림은 최신 5건 */
export interface DashboardResponse {
  tenants: { active: number; total: number; newThisMonth: number };
  openEvents: { count: number; asOf: string };
  recentNotifications: {
    notificationId: number;
    tenantId: string | null;
    eventId: number | null;
    category: string;
    priority: NotificationPriority | string;
    occurredAt: string;
    content: string;
    read: boolean;
  }[];
}

// ---------------------------------------------------------------- 홈 모니터링 (MonitoringController)

export interface MonitoringEventRef {
  eventId: number;
  name: string;
  startAt: string;
}

export interface MonitoringPodCount {
  ready: number | null;
  total: number | null;
}

/**
 * GET /monitoring/overview — 값은 Prometheus(15초 캐시). 시리즈가 없는 수치는 null.
 * stale이면 지난 값(Prometheus 실패)이거나 값 없음(asOf null, 미설정).
 */
export interface MonitoringOverviewResponse {
  asOf: string | null;
  stale: boolean;
  cluster: {
    /** 0~1 */
    cpuUsage: number | null;
    memoryUsage: number | null;
    /** 풀별 Ready 노드 수 */
    nodes: { app: number | null; redis: number | null; system: number | null };
    pods: { api: MonitoringPodCount; redis: MonitoringPodCount };
  };
  traffic: {
    rangeMinutes: number;
    stepSeconds: number;
    /** 시리즈가 없으면 0, Prometheus를 못 읽었으면 null */
    totalRps: number | null;
    /** points = [epoch 밀리초, 초당 요청 수 | null], 모든 시리즈가 같은 시각 격자 */
    series: { tenantId: string; tenantName: string | null; events: MonitoringEventRef[]; points: [number, number | null][] }[];
  };
  /** OPEN 이벤트가 있는 테넌트 전부 */
  tenants: {
    tenantId: string;
    tenantName: string | null;
    namespace: string;
    events: MonitoringEventRef[];
    rps: number | null;
    /** 지금 5xx 초당 요청 수 — 완료 · 실패 점 분배용 */
    errorRps: number | null;
    pods: { ready: number | null; max: number | null };
    /** since(입장 열림)부터 누적 */
    completed: number | null;
    failed: number | null;
    since: string;
  }[];
}

// ---------------------------------------------------------------- 트래픽 모니터링 OPS-05 (TrafficMonitoringController)

/** 상태(명세 2절). UNKNOWN = 판단할 데이터가 없음(조회 실패 · 미설정) */
export type MonitoringLevel = 'OK' | 'WARN' | 'DANGER' | 'UNKNOWN';

/**
 * 이벤트 구역 상태(명세 4절). ENTRY_OPEN = 입장 ~ 시작 전(발급 전), LIVE = 시작 ~ 이벤트 종료(end_at), WRAP_UP(마무리) = end_at ~ 호스팅 종료(hosting_end_at),
 * ENDING(종료 처리 중) = 호스팅 종료 ~ 집계 완료, ENDED = 집계 완료, END_FAILED = WF4 실패. 실패는 배포 → 호스팅 → 준비 → WF4 순으로 먼저 본다
 */
export type EventPhase =
  | 'SCHEDULED'
  | 'PRE_ENTRY'
  | 'ENTRY_OPEN'
  | 'LIVE'
  | 'WRAP_UP'
  | 'ENDING'
  | 'ENDED'
  | 'END_FAILED'
  | 'PREPARE_FAILED'
  | 'DEPLOY_FAILED'
  | 'HOSTING_FAILED';

/** E 목록 단계. DELAY(단계 지연) · SYSTEM(DLQ 등) 알림은 목록에만 보이고 자동화 칸 24시간 실패 수에는 들지 않는다 */
export type FailureStep = 'DEPLOY' | 'HOSTING_START' | 'PREPARE' | 'END' | 'PROVISIONING' | 'MAIL' | 'DELAY' | 'SYSTEM' | 'OTHER';

/** 출처별 기준 시각. stale = 지난 값(조회 실패)이거나 값 없음(asOf null, 미설정) */
export interface MonitoringSource {
  asOf: string | null;
  stale: boolean;
  /** stale 원인. SOURCE = 출처 자체 장애(연결 실패 · 5xx · 시간 초과 · 미설정), INTERNAL = 그 밖의 처리 오류. stale이 아니면 null */
  staleReason: 'SOURCE' | 'INTERNAL' | null;
}

/** [epoch 밀리초, 값 | null] */
export type MonitoringPoint = [number, number | null];

export interface TrafficNode {
  name: string;
  /** 인스턴스 타입(kube-state-metrics 라벨 허용 전에는 null) */
  type: string | null;
  ready: boolean;
  /** 0~1 */
  cpu: number | null;
  memory: number | null;
  createdAt: string | null;
  /** 최근 10분 안에 생김 */
  recent: boolean;
}

export interface TrafficPool {
  pool: string;
  /** Ready 노드 수. 쿼리 오류 · 미수집이면 null */
  count: number | null;
  addedRecently: number;
  cpu: { avg: number | null; max: number | null };
  memory: { avg: number | null; max: number | null };
  nodes: TrafficNode[];
}

export interface TrafficServiceStatus {
  name: string;
  running: number | null;
  desired: number | null;
  healthyTargets: number | null;
  totalTargets: number | null;
  /** 최근 5분 대상 5xx + ALB 5xx */
  fiveXx: number | null;
}

export interface TrafficFailure {
  occurredAt: string;
  eventId: number | null;
  tenantId: string | null;
  tenantName: string | null;
  eventName: string | null;
  step: FailureStep;
  reason: string | null;
}

export interface TrafficUpcoming {
  eventId: number;
  tenantId: string;
  tenantName: string | null;
  name: string;
  prepareAt: string;
  entryAt: string;
  startAt: string;
  /** hosting_end_at */
  endAt: string | null;
}

/** GET /monitoring/platform — A 상태 요약 · B 노드 · 플랫폼 · 다가오는 이벤트 · E 최근 자동화 실패 */
export interface TrafficPlatformResponse {
  prometheus: MonitoringSource;
  aws: MonitoringSource;
  /** 4칸 고정 순서: CLUSTER · EVENT · AUTOMATION · PLATFORM */
  summary: {
    area: 'CLUSTER' | 'EVENT' | 'AUTOMATION' | 'PLATFORM';
    level: MonitoringLevel;
    reason: string;
    /** EVENT 칸만 */
    openCount: number | null;
    eventId: number | null;
  }[];
  nodes: {
    /** 0~1 */
    cpu: number | null;
    memory: number | null;
    /** [epoch 밀리초, CPU, 메모리] 최근 1시간 */
    history: { stepSeconds: number; points: [number, number | null, number | null][] };
    pools: TrafficPool[];
  };
  platform: {
    addons: {
      /** 정상 그룹 수(모르면 null) */
      healthy: number | null;
      total: number;
      groups: { name: string; healthy: boolean | null; problems: { namespace: string; name: string; ready: number | null; desired: number | null }[] }[];
    };
    /** MONITORING_RDS_ID가 없거나 AWS를 못 읽었으면 null. cpuPercent 0~100 */
    rds: { status: string | null; cpuPercent: number | null; connections: number | null; freeStorageBytes: number | null } | null;
    services: TrafficServiceStatus[];
  };
  upcoming: TrafficUpcoming[];
  failures: TrafficFailure[];
}

/** GET /monitoring/events — 드롭다운. 진행 중 → 오늘 예정 → 최근 7일 종료 · 준비 실패 */
export interface TrafficEventsResponse {
  defaultEventId: number | null;
  items: TrafficEventItem[];
}

export interface TrafficEventItem {
  eventId: number;
  tenantId: string;
  tenantName: string | null;
  name: string;
  group: 'OPEN' | 'TODAY' | 'ENDED';
  phase: EventPhase;
  /** OPEN만 */
  level: MonitoringLevel | null;
  reason: string | null;
  prepareAt: string;
  entryAt: string;
  startAt: string;
  /** hosting_end_at */
  endAt: string | null;
  eventStatus: EventStatus | null;
  hostingStatus: HostingStatus | null;
  /** 지금 실패 상태(DEPLOY_FAILED · HOSTING_FAILED · OPEN_FAILED)로 바뀐 마지막 로그 시각(최근 7일, 없으면 null) */
  failedAt: string | null;
}

export type TrafficStepKey = 'APPROVE' | 'DEPLOY' | 'HOSTING_START' | 'PREPARE' | 'ENTRY' | 'START' | 'END' | 'AGGREGATE';
export type TrafficStepState = 'DONE' | 'ACTIVE' | 'WAITING' | 'FAILED' | 'SKIPPED';

export interface TrafficStep {
  key: TrafficStepKey;
  plannedAt: string | null;
  /** 끝난 단계는 끝난 시각(이벤트 진행은 시작 시각), 진행 중인 단계는 시작한 시각(모르면 null) */
  actualAt: string | null;
  /** 이벤트 진행(START)만 — 이벤트 종료(end_at) */
  endAt: string | null;
  /** 준비만 — Pod 시작 → Ready */
  durationSeconds: number | null;
  state: TrafficStepState;
  /** 실패 사유 요약(앞부분 · 비밀값 가림) */
  reason: string | null;
}

/** 지금 값. PRE_ENTRY는 rps · errorRatio · p95 · waiting이 null */
export interface TrafficLiveKpi {
  rps: number | null;
  /** 0~1 */
  errorRatio: number | null;
  p95Ms: number | null;
  p95IssuanceMs: number | null;
  p95TicketMs: number | null;
  podsReady: number | null;
  podsMax: number | null;
  podsMin: number | null;
  podsPending: number | null;
  cpuCores: number | null;
  cpuRequestCores: number | null;
  memoryBytes: number | null;
  memoryRequestBytes: number | null;
  waiting: number | null;
  stockRemaining: number | null;
  stockTotal: number | null;
  /** 0~1 */
  redisMemoryRatio: number | null;
  redisMemoryUsedBytes: number | null;
  redisMemoryMaxBytes: number | null;
  redisOpsPerSec: number | null;
  redisRestarts: number | null;
  redisConnections: number | null;
  redisUp: boolean | null;
}

export interface TrafficPeak {
  value: number | null;
  at: string | null;
}

/** 호스팅이 끝난 이벤트 최종값(입장 ~ 이벤트 종료). 시각은 1분 구간의 시작, 최대가 0이면 at null */
export interface TrafficSummaryKpi {
  peakRps: TrafficPeak;
  errorRatioAvg: number | null;
  errorRatioMax: number | null;
  p95MaxMs: number | null;
  p95AvgMs: number | null;
  podsMax: number | null;
  podsLimit: number | null;
  peakWaiting: TrafficPeak;
  issued: number | null;
  stockTotal: number | null;
  soldOutAt: string | null;
  cpuMaxCores: number | null;
  cpuRequestCores: number | null;
  memoryMaxBytes: number | null;
  memoryRequestBytes: number | null;
  redisMemoryMax: number | null;
  redisRestarts: number | null;
}

export interface TrafficKpi {
  /** 입장 직전 ~ 마무리만(호스팅 종료 뒤는 null) */
  level: MonitoringLevel | null;
  reason: string | null;
  /** 주의 값인 KPI 키: errorRatio · p95 · pods · redisMemory · redisRestarts · redisUp */
  warn: string[];
  live: TrafficLiveKpi | null;
  summary: TrafficSummaryKpi | null;
}

/** 그래프 범위 key */
export type TrafficRangeKey = 'entry' | '15m' | '1h' | 'full' | 'start';

export interface TrafficRange {
  key: TrafficRangeKey;
  options: TrafficRangeKey[];
  from: string;
  to: string;
  stepSeconds: number;
  entryAt: string;
  startAt: string;
}

/** Prometheus 값은 timestamps와 같은 길이 배열, AWS 값(errors.alb · eventTarget · rds)은 점 목록 */
export interface TrafficCharts {
  timestamps: number[];
  /** rps = 초당 요청(health 제외) */
  traffic: { rps: (number | null)[] };
  /** 0~1. eventTarget은 화면에서 쓰지 않음(참고용) */
  errors: { app: (number | null)[]; alb: MonitoringPoint[]; eventTarget: MonitoringPoint[] };
  /** ms */
  latency: { issuance: (number | null)[]; ticket: (number | null)[] };
  pods: { ready: (number | null)[]; pending: (number | null)[]; min: number | null; max: number | null };
  /** waiting = 번호표 끝 − 서빙 커서(0 하한) */
  progress: { waiting: (number | null)[]; stock: (number | null)[] };
  /**
   * 입장 그래프(요청 수 아님). admittedPerSec = 입장(명/초, 추정) — min(서빙 커서, 번호표 끝)의 기울기, 번호표를 받은 뒤 떠난 사람도 포함.
   * cursorPerSec = 입장 허용(커서 전진, 번호/초) — 번호표 끝보다 앞설 수 있다
   */
  admission: { admittedPerSec: (number | null)[]; cursorPerSec: (number | null)[] };
  /** 0~1 */
  redis: { memory: (number | null)[]; cpu: (number | null)[]; restarts: (number | null)[] };
  resources: { cpu: (number | null)[]; memory: (number | null)[]; cpuRequest: (number | null)[]; memoryRequest: (number | null)[] };
  /** cpu 0~100(%), connections 개수 */
  rds: { cpu: MonitoringPoint[]; connections: MonitoringPoint[] };
}

/** GET /monitoring/events/{eventId}?range= — C 진행 단계 · D KPI · 그래프 */
export interface TrafficEventResponse {
  prometheus: MonitoringSource;
  aws: MonitoringSource;
  event: {
    eventId: number;
    tenantId: string;
    tenantName: string | null;
    name: string;
    phase: EventPhase;
    eventStatus: EventStatus | null;
    hostingStatus: HostingStatus | null;
    prepareAt: string;
    entryAt: string;
    startAt: string;
    /** 호스팅 종료(hosting_end_at, WF4) */
    endAt: string | null;
    /** 이벤트 종료(end_at) */
    eventEndAt: string | null;
    stockCount: number | null;
  };
  steps: TrafficStep[];
  /** SCHEDULED · 실패(PREPARE_FAILED · DEPLOY_FAILED · HOSTING_FAILED)면 null */
  kpi: TrafficKpi | null;
  range: TrafficRange | null;
  charts: TrafficCharts | null;
}

/** 자동화 실행 상세의 흐름 — 진행 단계 칸과 같은 이름(배포 WF1-2 · 호스팅 시작 WF2-1 · 준비 WF2-2 · 호스팅 종료 WF4) */
export type ExecutionFlow = 'DEPLOY' | 'HOSTING_START' | 'PREPARE' | 'END';

export type ExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'ABORTED' | 'PENDING_REDRIVE';

/** LOOP = Wait로 시작하는 반복 묶음(names가 Wait부터 차례, count = 반복 횟수). Pass · Choice · Succeed는 실패 단계일 때만 온다 */
export type ExecutionStateKind = 'TASK' | 'WAIT' | 'CHOICE' | 'PASS' | 'PARALLEL' | 'MAP' | 'SUCCEED' | 'LOOP';

export interface ExecutionState {
  kind: ExecutionStateKind;
  names: string[];
  /** 들어간 횟수(LOOP는 반복 횟수) */
  count: number;
  startedAt: string | null;
  /** 진행 중이면 null */
  endedAt: string | null;
  /** 진행 중이면 지금까지 */
  durationSeconds: number | null;
  retries: number;
  status: 'SUCCEEDED' | 'FAILED' | 'RUNNING';
}

/** state = 실패한 단계, reason = 요약 120자, cause = 비밀값을 가린 원문 500자 */
export interface ExecutionFailure {
  state: string | null;
  error: string | null;
  reason: string | null;
  cause: string | null;
}

export interface EventExecution {
  flow: ExecutionFlow;
  workflow: string;
  /** approve-13, 스케줄러가 시작한 실행은 UUID */
  name: string;
  /** 같은 흐름 안의 순번(1부터) — 2 이상이면 재실행 */
  attempt: number;
  status: ExecutionStatus;
  startedAt: string;
  stoppedAt: string | null;
  durationSeconds: number | null;
  redriveCount: number;
  failure: ExecutionFailure | null;
  states: ExecutionState[];
  /** 기록이 길어 뒤쪽을 받지 않음 */
  truncated: boolean;
}

/** GET /monitoring/events/{eventId}/executions — 자동화 실행 상세(Step Functions). 실행 입력 · 출력 · ARN은 없다 */
export interface EventExecutionsResponse {
  aws: MonitoringSource;
  eventId: number;
  /** 흐름 순 → 시작 순. 실행이 없는 흐름은 빠진다 */
  executions: EventExecution[];
}

// ---------------------------------------------------------------- 알림센터 (NotificationController)

/** 알림센터 칩 — 신청 · 이벤트 · 테넌트 · 리포트 · 시스템(category를 API가 묶음) */
export type NotificationChip = 'APPLICATION' | 'EVENT' | 'TENANT' | 'REPORT' | 'SYSTEM';

/** 현재 상태가 가리키는 값 — 상태 열 4종은 DB 값 그대로, PASSWORD_SETUP은 SET · NOT_SET, DLQ_MESSAGES는 메시지 수(10진 문자열) */
export type NotificationStateKind =
  | 'APPLICATION_STATUS'
  | 'HOSTING_STATUS'
  | 'EVENT_STATUS'
  | 'PROVISIONING_STATUS'
  | 'ACCOUNT_STATUS'
  | 'PASSWORD_SETUP'
  | 'DLQ_MESSAGES';

/** 관련 상태의 지금 값(복구 여부는 판정하지 않음). alert = 실패 값(FAILED · *_FAILED)이거나 DLQ 메시지 1건 이상 */
export interface NotificationState {
  kind: NotificationStateKind | string;
  value: string;
  alert: boolean;
}

/** GET /notifications 항목. 테넌트 · 이벤트와 무관한 알림이면 tenantId · tenantName · eventId가 null */
export interface NotificationItem {
  notificationId: number;
  tenantId: string | null;
  tenantName: string | null;
  eventId: number | null;
  category: string;
  priority: NotificationPriority | string;
  occurredAt: string;
  content: string;
  read: boolean;
  /** 모르는 category면 null — 칩 필터 '전체'에서만 보인다 */
  chip: NotificationChip | null;
  /** 누르면 갈 화면 경로('/'로 시작). 필요한 id가 없으면 null(읽음만) */
  link: string | null;
  /** 보여 줄 값이 없으면 null */
  state: NotificationState | null;
}

/** GET /notifications — 전체, 최신순(페이지네이션 없음). 필터 · 검색 · 건수는 화면이 계산 */
export interface NotificationListResponse {
  items: NotificationItem[];
}

// ---------------------------------------------------------------- 리포트 (ReportController)

/** GET /reports 항목. 기간 · 생성일은 ISO-8601(+09:00) */
export interface ReportItem {
  reportId: number;
  name: string;
  category: string;
  periodStart: string;
  periodEnd: string;
  createdAt: string;
}

/** GET /reports — 최근 생성순(페이지네이션 없음) */
export interface ReportListResponse {
  items: ReportItem[];
}

/** GET /reports/{id}/download — presigned URL(만료 있음) */
export interface ReportDownloadResponse {
  url: string;
  expiresAt: string;
}
