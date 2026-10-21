/**
 * 대시보드 예시 데이터 — API가 아직 없는 카드(클러스터 사용량 · 실시간 트래픽 · 테넌트 별 처리 현황)만.
 * 화면에는 "예시 데이터" 칩을 붙인다. 테넌트 수 · 진행 중 이벤트 · 최근 알림은 GET /api/ops/dashboard.
 */
export const mockDashboard = {
  cluster: { cpu: 62, mem: 58, nodes: 12, maxNodes: 20, autoscale: '오토스케일 대기중' },
  topTenants: [
    { tenant: 'A사', namespace: 'tenant-a', pods: 2, maxPods: 4, done: 59, waitingOrFailed: 8 },
    { tenant: 'B사', namespace: 'tenant-b', pods: 1, maxPods: 3, done: 8, waitingOrFailed: 3 },
  ],
  /** 실시간 트래픽 초기값 (초당 요청 수) */
  traffic: {
    series: [
      { label: '테넌트 A', base: 720, data: [520, 560, 600, 590, 700, 680, 720, 700, 730, 705, 715, 690] },
      { label: '테넌트 B', base: 260, data: [330, 320, 335, 290, 300, 270, 280, 255, 265, 250, 258, 240] },
      { label: '테넌트 C', base: 190, data: [180, 182, 176, 185, 182, 186, 183, 188, 185, 190, 186, 192] },
    ],
  },
};
