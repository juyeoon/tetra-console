export const mockDashboard = {
  asOf: '2026.09.22 14:32',
  tenants: { active: 18, total: 24, newThisMonth: 2 },
  ongoingEvents: 5,
  cluster: { cpu: 62, mem: 58, nodes: 12, maxNodes: 20, autoscale: '오토스케일 대기중' },
  alerts: [
    { id: 1, title: 'A사 트래픽 급증', ago: '2분 전', level: 'warning' as const },
    { id: 2, title: 'C사 이벤트 신청 접수', ago: '18분 전', level: 'info' as const },
    { id: 3, title: 'D사 연동키 재발급 요청', ago: '41분 전', level: 'info' as const },
    { id: 4, title: '클러스터 노드 스케일아웃', ago: '1시간 전', level: 'warning' as const },
    { id: 5, title: 'B사 이벤트 승인 완료', ago: '2시간 전', level: 'success' as const },
  ],
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
