import { api } from './client';

const BASE = '/qosid/admin/api';

/**
 * Changes the admin password. The backend invalidates the session on success (by design),
 * so the caller must re-login afterwards.
 *
 * <p>Server-side rules mirrored by the form: min 8 chars and never the default "admin".
 */
export const changePassword = (oldPassword: string, newPassword: string) =>
  api.post<void>(`${BASE}/change-password`, { oldPassword, newPassword }).then(() => undefined);
