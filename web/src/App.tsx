import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from '@/components/layout/AppLayout';
import LoginPage from '@/pages/auth/LoginPage';
import PasswordSetupPage from '@/pages/auth/PasswordSetupPage';
import DashboardPage from '@/pages/dashboard/DashboardPage';
import RegistrationEmailPreviewPage from '@/pages/email/RegistrationEmailPreviewPage';
import EventApplicationDetailPage from '@/pages/events/EventApplicationDetailPage';
import EventApplicationListPage from '@/pages/events/EventApplicationListPage';
import EventProgressDetailPage from '@/pages/events/EventProgressDetailPage';
import EventProgressListPage from '@/pages/events/EventProgressListPage';
import NotificationCenterPage from '@/pages/logs/NotificationCenterPage';
import ReportPage from '@/pages/logs/ReportPage';
import TrafficMonitoringPage from '@/pages/monitoring/TrafficMonitoringPage';
import SystemSettingsPage from '@/pages/settings/SystemSettingsPage';
import TenantDetailPage from '@/pages/tenants/TenantDetailPage';
import TenantListPage from '@/pages/tenants/TenantListPage';
import TenantRegisterPage from '@/pages/tenants/TenantRegisterPage';
import { RequireAuth } from '@/store/AuthContext';

export default function App() {
  return (
    <Routes>
      {/* 레이아웃 없는 화면 */}
      <Route path="/login" element={<LoginPage />} />
      <Route path="/password/setup" element={<PasswordSetupPage />} />
      <Route path="/preview/tenant-registration-email" element={<RegistrationEmailPreviewPage />} />

      {/* 운영 콘솔 — 로그인(GET /api/ops/me) 확인 후에만 */}
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="events">
          <Route path="applications" element={<EventApplicationListPage />} />
          <Route path="applications/:eventId" element={<EventApplicationDetailPage />} />
          <Route path="progress" element={<EventProgressListPage />} />
          <Route path="progress/:eventId" element={<EventProgressDetailPage />} />
        </Route>
        <Route path="monitoring/traffic" element={<TrafficMonitoringPage />} />
        <Route path="tenants">
          <Route index element={<TenantListPage />} />
          <Route path="new" element={<TenantRegisterPage />} />
          <Route path=":tenantId" element={<TenantDetailPage />} />
        </Route>
        <Route path="logs/notifications" element={<NotificationCenterPage />} />
        <Route path="logs/reports" element={<ReportPage />} />
        <Route path="settings" element={<SystemSettingsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
