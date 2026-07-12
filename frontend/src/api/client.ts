import axios from 'axios';
import { getCookie } from './cookies';

/** Shared axios instance. Same-origin, so cookies + CSRF header are enough. */
export const api = axios.create({ withCredentials: true });

let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn; }

// Echo the raw XSRF-TOKEN cookie in the X-XSRF-TOKEN header (B1 SpaCsrfTokenRequestHandler
// resolves the plain value). Only matters for state-changing calls, harmless on GETs.
api.interceptors.request.use((config) => {
  const token = getCookie('XSRF-TOKEN');
  if (token) config.headers['X-XSRF-TOKEN'] = token;
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (error) => {
    if (error?.response?.status === 401) onUnauthorized();
    return Promise.reject(error);
  },
);
