import { apiRequest } from './client.ts';
import type {
  RegisterRequest,
  RegisterResponse,
  TenantBasic,
  TenantEventItem,
  TenantListResponse,
  TenantOverviewResponse,
  TenantResourceItem,
  UpdateRequest,
} from './types.ts';

/** OPS-06 테넌트 목록 + 계정상태별 건수 (가입일시 내림차순) */
export const fetchTenants = (signal?: AbortSignal) => apiRequest<TenantListResponse>('/tenants', { signal });

/** OPS-06-1 개요 (기본 정보 · S3 버킷 · 계정 상태 로그). 없으면 404 TENANT_NOT_FOUND */
export const fetchTenantOverview = (tenantId: string, signal?: AbortSignal) =>
  apiRequest<TenantOverviewResponse>(`/tenants/${encodeURIComponent(tenantId)}`, { signal });

/** OPS-06-2 이벤트 목록 */
export const fetchTenantEvents = (tenantId: string, signal?: AbortSignal) =>
  apiRequest<TenantEventItem[]>(`/tenants/${encodeURIComponent(tenantId)}/events`, { signal });

/** OPS-06-3 리소스 */
export const fetchTenantResources = (tenantId: string, signal?: AbortSignal) =>
  apiRequest<TenantResourceItem[]>(`/tenants/${encodeURIComponent(tenantId)}/resources`, { signal });

/** OPS-07 이메일 중복 확인. 형식이 틀리면 400 fields.email */
export const checkEmailAvailable = async (email: string) =>
  (await apiRequest<{ available: boolean }>(`/tenants/email-availability?email=${encodeURIComponent(email.trim())}`)).available;

/** OPS-07 등록. 400 fields, 409 EMAIL_DUPLICATED */
export const registerTenant = (request: RegisterRequest) =>
  apiRequest<RegisterResponse>('/tenants', { method: 'POST', body: request });

/** OPS-06-M3 정보 수정 (하나만). 400 fields, 409 EMAIL_DUPLICATED */
export const updateTenant = (tenantId: string, request: UpdateRequest) =>
  apiRequest<TenantBasic>(`/tenants/${encodeURIComponent(tenantId)}`, { method: 'PATCH', body: request });
