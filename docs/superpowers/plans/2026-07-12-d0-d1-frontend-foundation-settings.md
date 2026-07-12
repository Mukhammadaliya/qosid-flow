# D0+D1 Frontend Foundation + Settings Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a React SPA (foundation + Settings module) served same-origin from Spring Boot, giving the existing B1/B3/C admin APIs a working, Smartup-branded web UI.

**Architecture:** A new `frontend/` (React+TS+Vite+Ant Design) is built by `frontend-maven-plugin` into the fat JAR's `static/`, served same-origin so B1's session cookie + SPA-CSRF work unchanged. A Spring forward-controller sends non-API navigation routes to `index.html` for client-side routing. The Settings module is hybrid: typed forms (Source Auth, Tenant Mapping) over B3/C endpoints plus a generic grouped key-value table over the B1 settings API.

**Tech Stack:** React 18, TypeScript, Vite 5, Ant Design 5, react-router-dom 6, axios, i18next/react-i18next, Vitest + React Testing Library, Playwright + MSW; backend Spring Boot 3.3.0 (Java 21).

## Global Constraints

- Backend package: `uz.greenwhite.gateway`; admin API base: `/qosid/admin/api`.
- Same-origin serving only — no CORS, no separate frontend host.
- CSRF: read `XSRF-TOKEN` cookie, echo verbatim in `X-XSRF-TOKEN` header (B1 `SpaCsrfTokenRequestHandler` resolves the raw value). Cookie is seeded by any GET on the admin chain (`CsrfCookieFilter`).
- Session auth endpoints (exact shapes): `POST /qosid/admin/api/login` `{username,password}` → 200 `{username,status}` / 401; `POST /logout` → 204; `GET /me` → 200 `{username}` / 401; `POST /change-password` `{oldPassword,newPassword}` → 204.
- Settings API: `GET /qosid/admin/api/settings` → `SettingsRecord[]` `{key,value,secret,updatedAt,updatedBy}` (secrets masked `***`); `PUT /qosid/admin/api/settings/{key}` `{value,secret}` → 204; `GET /qosid/admin/api/settings/audit` → `[{skey,old_value,new_value,changed_by,changed_at}]`.
- Source-auth API: `GET /qosid/admin/api/source-auth` → `{type,basicUsername,basicPassword,apikeyHeader,apikeyValue,oauth2TokenUrl,oauth2ClientId,oauth2ClientSecret,oauth2Scope,oauth2CacheTtlSeconds}` (secrets `***`); `PUT` same shape (see field rules in Task 5); `POST /source-auth/test` → `{ok,type,message}`.
- Tenant-mapping API: `POST /qosid/admin/api/tenant-mapping/test` (raw sample JSON body) → `{tenantId,extraTenantId,tenantField,extraField}` (tenantId/extraTenantId may be null).
- Smartup palette (exact): primary `#0095D9`, success `#2EC27E`, warning `#F5A623`, error `#E5484D`, heading `#1A2333`, textSecondary `#6B7689`, bgLayout `#F4F6F9`, border `#E6EDF3`, accentMagenta `#A2228D`.
- All UI copy goes through i18next `t('key')`; `uz.json` is the only locale now.
- Never render a plaintext secret — GET returns `***`; forms show a masked placeholder and only send a new value when the admin types one.
- Build produces ONE JAR; `mvn package` runs the frontend build by default; `mvn package -P '!frontend'` skips it. Existing 94 backend tests must stay green; the E1–E5 pipeline is not touched.
- Node `v20.17.0` / npm `10.8.2` pinned in the Maven plugin.

---

### Task 1: Frontend scaffold + Maven build integration + SPA fallback

**Files:**
- Create: `frontend/package.json`, `frontend/tsconfig.json`, `frontend/tsconfig.node.json`, `frontend/vite.config.ts`, `frontend/index.html`, `frontend/src/main.tsx`, `frontend/src/vite-env.d.ts`, `frontend/.gitignore`
- Create: `src/main/java/uz/greenwhite/gateway/config/SpaForwardController.java`
- Test: `src/test/java/uz/greenwhite/gateway/config/SpaForwardControllerTest.java`
- Modify: `pom.xml` (add `<profiles>` block after `</build>`), `.gitignore` (repo root — ignore `frontend/node_modules`, `frontend/dist`)

**Interfaces:**
- Produces: a built SPA at classpath `static/index.html`; `SpaForwardController` forwarding non-API GETs to `/index.html`. Later tasks add files under `frontend/src/`.

- [ ] **Step 1: Create the frontend scaffold files**

`frontend/package.json`:
```json
{
  "name": "qosid-flow-ui",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "@ant-design/icons": "^5.5.1",
    "antd": "^5.21.0",
    "axios": "^1.7.7",
    "i18next": "^23.15.1",
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-i18next": "^15.0.2",
    "react-router-dom": "^6.26.2"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^6.5.0",
    "@testing-library/react": "^16.0.1",
    "@testing-library/user-event": "^14.5.2",
    "@types/react": "^18.3.11",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.2",
    "jsdom": "^25.0.1",
    "typescript": "^5.6.2",
    "vite": "^5.4.8",
    "vitest": "^2.1.1"
  }
}
```

`frontend/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "types": ["vitest/globals", "@testing-library/jest-dom"]
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

`frontend/tsconfig.node.json`:
```json
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true,
    "strict": true
  },
  "include": ["vite.config.ts"]
}
```

`frontend/vite.config.ts`:
```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Same-origin: dev server proxies API calls to the Spring app on :8090.
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    proxy: {
      '/qosid': 'http://localhost:8090',
      '/actuator': 'http://localhost:8090',
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    css: false,
  },
});
```

`frontend/index.html`:
```html
<!doctype html>
<html lang="uz">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Qosid Flow</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`frontend/src/main.tsx`:
```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <div>Qosid Flow</div>
  </React.StrictMode>,
);
```

`frontend/src/vite-env.d.ts`:
```ts
/// <reference types="vite/client" />
```

`frontend/src/test-setup.ts`:
```ts
import '@testing-library/jest-dom/vitest';
```

`frontend/.gitignore`:
```
node_modules
dist
```

- [ ] **Step 2: Add the Maven build profile**

Append this `<profiles>` block to `pom.xml` immediately before `</project>` (after `</build>`):
```xml
    <profiles>
        <profile>
            <id>frontend</id>
            <activation><activeByDefault>true</activeByDefault></activation>
            <build>
                <plugins>
                    <plugin>
                        <groupId>com.github.eirslett</groupId>
                        <artifactId>frontend-maven-plugin</artifactId>
                        <version>1.15.1</version>
                        <configuration>
                            <workingDirectory>frontend</workingDirectory>
                            <installDirectory>target</installDirectory>
                        </configuration>
                        <executions>
                            <execution>
                                <id>install-node-and-npm</id>
                                <goals><goal>install-node-and-npm</goal></goals>
                                <phase>generate-resources</phase>
                                <configuration>
                                    <nodeVersion>v20.17.0</nodeVersion>
                                    <npmVersion>10.8.2</npmVersion>
                                </configuration>
                            </execution>
                            <execution>
                                <id>npm-ci</id>
                                <goals><goal>npm</goal></goals>
                                <phase>generate-resources</phase>
                                <configuration><arguments>ci</arguments></configuration>
                            </execution>
                            <execution>
                                <id>npm-build</id>
                                <goals><goal>npm</goal></goals>
                                <phase>generate-resources</phase>
                                <configuration><arguments>run build</arguments></configuration>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-resources-plugin</artifactId>
                        <executions>
                            <execution>
                                <id>copy-frontend-dist</id>
                                <phase>process-resources</phase>
                                <goals><goal>copy-resources</goal></goals>
                                <configuration>
                                    <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
                                    <resources>
                                        <resource><directory>frontend/dist</directory></resource>
                                    </resources>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```
Note: `activeByDefault` deactivates if another Maven profile is activated explicitly; this repo defines no other Maven profiles, so `frontend` stays on for a plain `mvn package`. To skip: `mvn package -P '!frontend'`.

Append to the repo-root `.gitignore`:
```
frontend/node_modules
frontend/dist
```

- [ ] **Step 3: Write the failing SPA fallback test**

`src/test/java/uz/greenwhite/gateway/config/SpaForwardControllerTest.java`:
```java
package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaForwardControllerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new SpaForwardController())
            .build();

    @Test
    void navigationRouteForwardsToIndex() throws Exception {
        mvc.perform(get("/settings")).andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void nestedNavigationRouteForwardsToIndex() throws Exception {
        mvc.perform(get("/settings/source-auth")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void assetPathIsNotForwarded() throws Exception {
        // A path whose last segment contains a dot (bundle.js) must NOT be forwarded —
        // the resource handler serves it. standaloneSetup has no such handler, so 404.
        mvc.perform(get("/assets/app.js")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" -P '!frontend' -Dtest=SpaForwardControllerTest test` (with `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"`)
Expected: FAIL — `SpaForwardController` does not exist (compilation error).

- [ ] **Step 5: Implement the SPA forward controller**

`src/main/java/uz/greenwhite/gateway/config/SpaForwardController.java`:
```java
package uz.greenwhite.gateway.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Client-side-routing fallback: forward navigation routes (any GET whose last path
 * segment has no '.') to the SPA shell so React Router can render them on reload/deep-link.
 *
 * Paths whose last segment contains a dot (assets like main-abc.js) are excluded, so the
 * static resource handler serves them. Real API mappings (/qosid/**, /actuator/**) are more
 * specific handler mappings and take precedence over this catch-all, so they are never forwarded.
 */
@Controller
public class SpaForwardController {

    @GetMapping(value = "/**/{path:[^\\.]*}")
    public String forward() {
        return "forward:/index.html";
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `& "...mvn.cmd" -P '!frontend' -Dtest=SpaForwardControllerTest test`
Expected: PASS (3 tests).

- [ ] **Step 7: Verify the frontend build + full backend suite**

Run: `& "...mvn.cmd" -Dtest=SpaForwardControllerTest -DfailIfNoTests=false package` (frontend profile ON — installs node, builds SPA)
Expected: BUILD SUCCESS; `target/classes/static/index.html` exists; `target/qosid-flow-1.0.0-SNAPSHOT.jar` contains `BOOT-INF/classes/static/index.html`.
Then run the full backend suite unaffected: `& "...mvn.cmd" -P '!frontend' test` → Expected: `Tests run: 97` (94 existing + 3 new), 0 failures.

- [ ] **Step 8: Commit**

```bash
git add frontend/ pom.xml .gitignore src/main/java/uz/greenwhite/gateway/config/SpaForwardController.java src/test/java/uz/greenwhite/gateway/config/SpaForwardControllerTest.java
git commit -m "feat(ui): React+Vite scaffold, Maven frontend build, SPA fallback controller"
```

---

### Task 2: Theme, i18n, and API client foundation

**Files:**
- Create: `frontend/src/theme.ts`, `frontend/src/i18n.ts`, `frontend/src/locales/uz.json`, `frontend/src/api/client.ts`, `frontend/src/api/cookies.ts`
- Test: `frontend/src/api/cookies.test.ts`, `frontend/src/api/client.test.ts`, `frontend/src/theme.test.ts`

**Interfaces:**
- Produces:
  - `smartupTheme` (Ant `ThemeConfig`) from `theme.ts`.
  - `i18n` default export from `i18n.ts` (initialized i18next instance); `t` used via `useTranslation()`.
  - `api` (axios instance) + `setUnauthorizedHandler(fn: () => void)` from `client.ts`.
  - `getCookie(name: string): string | null` from `cookies.ts`.

- [ ] **Step 1: Write the failing cookie + client + theme tests**

`frontend/src/api/cookies.test.ts`:
```ts
import { describe, it, expect, afterEach } from 'vitest';
import { getCookie } from './cookies';

describe('getCookie', () => {
  afterEach(() => { document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'; });

  it('returns the value of a named cookie', () => {
    document.cookie = 'XSRF-TOKEN=abc123';
    expect(getCookie('XSRF-TOKEN')).toBe('abc123');
  });

  it('returns null when absent', () => {
    expect(getCookie('MISSING')).toBeNull();
  });
});
```

`frontend/src/api/client.test.ts`:
```ts
import { describe, it, expect, vi, afterEach } from 'vitest';
import { api, setUnauthorizedHandler } from './client';

describe('api client', () => {
  afterEach(() => { document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'; });

  it('copies the XSRF-TOKEN cookie into the X-XSRF-TOKEN header', async () => {
    document.cookie = 'XSRF-TOKEN=tok-42';
    const cfg = await (api.interceptors.request as any).handlers[0].fulfilled({ headers: {} });
    expect(cfg.headers['X-XSRF-TOKEN']).toBe('tok-42');
  });

  it('invokes the unauthorized handler on a 401 response', async () => {
    const spy = vi.fn();
    setUnauthorizedHandler(spy);
    const rejected = (api.interceptors.response as any).handlers[0].rejected;
    await rejected({ response: { status: 401 } }).catch(() => {});
    expect(spy).toHaveBeenCalledOnce();
  });
});
```

`frontend/src/theme.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { smartupTheme } from './theme';

describe('smartupTheme', () => {
  it('uses the Smartup brand primary and success colors', () => {
    expect(smartupTheme.token?.colorPrimary).toBe('#0095D9');
    expect(smartupTheme.token?.colorSuccess).toBe('#2EC27E');
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `frontend/`): `npm test`
Expected: FAIL — modules `./cookies`, `./client`, `./theme` not found.

- [ ] **Step 3: Implement cookies, client, theme, i18n, locale**

`frontend/src/api/cookies.ts`:
```ts
export function getCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[1]) : null;
}
```

`frontend/src/api/client.ts`:
```ts
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
```

`frontend/src/i18n.ts`:
```ts
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import uz from './locales/uz.json';

i18n.use(initReactI18next).init({
  resources: { uz: { translation: uz } },
  lng: 'uz',
  fallbackLng: 'uz',
  interpolation: { escapeValue: false },
});

export default i18n;
```

`frontend/src/locales/uz.json`:
```json
{
  "app.title": "Qosid Flow",
  "nav.settings": "Sozlamalar",
  "nav.errors": "Xatolar",
  "nav.statistics": "Statistika",
  "nav.logs": "Loglar",
  "nav.comingSoon": "Keyingi fazada",
  "auth.login": "Kirish",
  "auth.logout": "Chiqish",
  "auth.username": "Foydalanuvchi",
  "auth.password": "Parol",
  "auth.changePassword": "Parolni o'zgartirish",
  "auth.oldPassword": "Eski parol",
  "auth.newPassword": "Yangi parol",
  "auth.loginFailed": "Login yoki parol noto'g'ri",
  "settings.title": "Sozlamalar",
  "settings.sourceAuth": "Source Auth",
  "settings.tenantMapping": "Tenant Mapping",
  "settings.advanced": "Qo'shimcha",
  "settings.save": "Saqlash",
  "settings.saved": "Saqlandi",
  "settings.test": "Sinash",
  "settings.type": "Turi",
  "settings.key": "Kalit",
  "settings.value": "Qiymat",
  "settings.secret": "Maxfiy",
  "settings.audit": "O'zgarishlar tarixi",
  "sourceAuth.basicUsername": "Foydalanuvchi",
  "sourceAuth.basicPassword": "Parol",
  "sourceAuth.apikeyHeader": "Header nomi",
  "sourceAuth.apikeyValue": "API kalit",
  "sourceAuth.oauth2TokenUrl": "Token URL",
  "sourceAuth.oauth2ClientId": "Client ID",
  "sourceAuth.oauth2ClientSecret": "Client Secret",
  "sourceAuth.oauth2Scope": "Scope",
  "sourceAuth.oauth2CacheTtl": "Kesh TTL (sekund)",
  "tenant.sampleJson": "Namuna Biruni-pull JSON",
  "tenant.resolvedTenant": "Aniqlangan tenantId",
  "tenant.resolvedExtra": "Aniqlangan extraTenantId",
  "common.secretPlaceholder": "(o'zgartirmaslik uchun bo'sh qoldiring)"
}
```

`frontend/src/theme.ts`:
```ts
import type { ThemeConfig } from 'antd';

export const smartupTheme: ThemeConfig = {
  token: {
    colorPrimary: '#0095D9',
    colorSuccess: '#2EC27E',
    colorWarning: '#F5A623',
    colorError: '#E5484D',
    colorTextHeading: '#1A2333',
    colorTextSecondary: '#6B7689',
    colorBgLayout: '#F4F6F9',
    colorBorder: '#E6EDF3',
  },
};
```

- [ ] **Step 4: Run the tests to verify they pass**

Run (from `frontend/`): `npm test`
Expected: PASS (cookies 2, client 2, theme 1).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/theme.ts frontend/src/theme.test.ts frontend/src/i18n.ts frontend/src/locales/ frontend/src/api/
git commit -m "feat(ui): Smartup theme, uz i18n, axios client with CSRF + 401 handling"
```

---

### Task 3: Auth context, login page, route guard

**Files:**
- Create: `frontend/src/auth/AuthContext.tsx`, `frontend/src/auth/LoginPage.tsx`, `frontend/src/auth/RequireAuth.tsx`
- Test: `frontend/src/auth/AuthContext.test.tsx`, `frontend/src/auth/RequireAuth.test.tsx`

**Interfaces:**
- Consumes: `api`, `setUnauthorizedHandler` (Task 2).
- Produces:
  - `AuthProvider` (wraps app), `useAuth(): { user: string | null; loading: boolean; login(u,p): Promise<void>; logout(): Promise<void>; refresh(): Promise<void> }`.
  - `LoginPage` component.
  - `RequireAuth` wrapper that renders children when `user` is set, else `<Navigate to="/login" />`.
- Auth bootstrap: `refresh()` first GETs `/qosid/admin/api/health` (seeds the `XSRF-TOKEN` cookie via `CsrfCookieFilter`), then GETs `/me`; 200 sets `user`, 401 clears it.

- [ ] **Step 1: Write the failing auth tests**

`frontend/src/auth/AuthContext.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { AuthProvider, useAuth } from './AuthContext';
import { api } from '../api/client';

function Probe() {
  const { user, loading } = useAuth();
  return <div>{loading ? 'loading' : `user:${user ?? 'none'}`}</div>;
}

describe('AuthProvider', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('resolves the current user from /me after bootstrap', async () => {
    vi.spyOn(api, 'get').mockImplementation((url: string) =>
      url.endsWith('/health')
        ? Promise.resolve({ data: {} } as any)
        : Promise.resolve({ data: { username: 'admin' } } as any));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('user:admin')).toBeInTheDocument());
  });

  it('reports no user when /me returns 401', async () => {
    vi.spyOn(api, 'get').mockImplementation((url: string) =>
      url.endsWith('/health')
        ? Promise.resolve({ data: {} } as any)
        : Promise.reject({ response: { status: 401 } }));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('user:none')).toBeInTheDocument());
  });
});
```

`frontend/src/auth/RequireAuth.test.tsx`:
```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { RequireAuth } from './RequireAuth';
import { AuthContext } from './AuthContext';

function renderWithUser(user: string | null) {
  return render(
    <AuthContext.Provider value={{ user, loading: false, login: async () => {}, logout: async () => {}, refresh: async () => {} }}>
      <MemoryRouter initialEntries={['/secret']}>
        <Routes>
          <Route path="/login" element={<div>login-page</div>} />
          <Route path="/secret" element={<RequireAuth><div>secret-page</div></RequireAuth>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('RequireAuth', () => {
  it('renders children when authenticated', () => {
    renderWithUser('admin');
    expect(screen.getByText('secret-page')).toBeInTheDocument();
  });
  it('redirects to /login when not authenticated', () => {
    renderWithUser(null);
    expect(screen.getByText('login-page')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `frontend/`): `npm test -- auth`
Expected: FAIL — `./AuthContext`, `./RequireAuth` not found.

- [ ] **Step 3: Implement AuthContext, RequireAuth, LoginPage**

`frontend/src/auth/AuthContext.tsx`:
```tsx
import { createContext, useContext, useEffect, useState, ReactNode, useCallback } from 'react';
import { api } from '../api/client';

interface AuthState {
  user: string | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

export const AuthContext = createContext<AuthState>(null as unknown as AuthState);
export const useAuth = () => useContext(AuthContext);

const BASE = '/qosid/admin/api';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      // Seed the XSRF-TOKEN cookie (any admin-chain GET renders it), then check session.
      await api.get(`${BASE}/health`).catch(() => {});
      const res = await api.get(`${BASE}/me`);
      setUser(res.data.username ?? null);
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    await api.post(`${BASE}/login`, { username, password });
    await refresh();
  }, [refresh]);

  const logout = useCallback(async () => {
    await api.post(`${BASE}/logout`).catch(() => {});
    setUser(null);
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}
```

`frontend/src/auth/RequireAuth.tsx`:
```tsx
import { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { Spin } from 'antd';
import { useAuth } from './AuthContext';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return <Spin style={{ display: 'block', marginTop: '20vh' }} />;
  if (!user) return <Navigate to="/login" replace />;
  return <>{children}</>;
}
```

`frontend/src/auth/LoginPage.tsx`:
```tsx
import { useState } from 'react';
import { Card, Form, Input, Button, Alert, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from './AuthContext';

export function LoginPage() {
  const { login } = useAuth();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState(false);

  const onFinish = async (v: { username: string; password: string }) => {
    setBusy(true); setError(false);
    try {
      await login(v.username, v.password);
      navigate('/settings', { replace: true });
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'center', marginTop: '15vh' }}>
      <Card style={{ width: 360 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>{t('app.title')}</Typography.Title>
        {error && <Alert type="error" message={t('auth.loginFailed')} style={{ marginBottom: 16 }} />}
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="username" label={t('auth.username')} rules={[{ required: true }]}>
            <Input autoFocus />
          </Form.Item>
          <Form.Item name="password" label={t('auth.password')} rules={[{ required: true }]}>
            <Input.Password />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={busy}>{t('auth.login')}</Button>
        </Form>
      </Card>
    </div>
  );
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run (from `frontend/`): `npm test -- auth`
Expected: PASS (AuthProvider 2, RequireAuth 2).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/auth/
git commit -m "feat(ui): auth context, login page, route guard with CSRF bootstrap"
```

---

### Task 4: Layout shell, routing, Qosid logo

**Files:**
- Create: `frontend/src/layout/Logo.tsx`, `frontend/src/layout/AppLayout.tsx`, `frontend/src/App.tsx`
- Modify: `frontend/src/main.tsx` (mount `App` with providers)
- Test: `frontend/src/layout/AppLayout.test.tsx`

**Interfaces:**
- Consumes: `useAuth` (Task 3), `smartupTheme`, `i18n` (Task 2), `LoginPage`/`RequireAuth` (Task 3), settings pages (Tasks 5-7, imported lazily; until they exist, route to a placeholder — see Step 3 note).
- Produces: `App` (router + providers + ConfigProvider), `AppLayout` (Sider+Header+Content), `Logo`.

- [ ] **Step 1: Write the failing layout test**

`frontend/src/layout/AppLayout.test.tsx`:
```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '../i18n';
import { AppLayout } from './AppLayout';
import { AuthContext } from '../auth/AuthContext';

function renderLayout() {
  return render(
    <AuthContext.Provider value={{ user: 'admin', loading: false, login: async () => {}, logout: async () => {}, refresh: async () => {} }}>
      <MemoryRouter initialEntries={['/settings']}>
        <AppLayout><div>content-here</div></AppLayout>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('AppLayout', () => {
  it('renders the Settings nav item as enabled', () => {
    renderLayout();
    expect(screen.getByText('Sozlamalar')).toBeInTheDocument();
  });

  it('shows future nav items (Errors) as disabled', () => {
    renderLayout();
    const errors = screen.getByText('Xatolar').closest('.ant-menu-item');
    expect(errors?.className).toContain('ant-menu-item-disabled');
  });

  it('shows the logged-in username', () => {
    renderLayout();
    expect(screen.getByText('admin')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `frontend/`): `npm test -- AppLayout`
Expected: FAIL — `./AppLayout`, `./Logo` not found.

- [ ] **Step 3: Implement Logo, AppLayout, App, main.tsx**

`frontend/src/layout/Logo.tsx`:
```tsx
// Qosid logo: an interlocking "flow" chevron mark (courier/messenger motif) in the
// Smartup blue→green pair, next to the "Qosid" wordmark in ink. Iterate on the mark
// during design review; this is a clean first version.
export function Logo() {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '0 8px' }}>
      <svg width="28" height="28" viewBox="0 0 32 32" aria-label="Qosid" role="img">
        <path d="M6 16 L14 8 L14 13 L22 13 L22 19 L14 19 L14 24 Z" fill="#0095D9" />
        <path d="M26 16 L18 24 L18 19 L10 19 L10 13 L18 13 L18 8 Z" fill="#2EC27E" opacity="0.85" />
      </svg>
      <span style={{ fontSize: 18, fontWeight: 700, color: '#1A2333', letterSpacing: 0.2 }}>Qosid</span>
    </div>
  );
}
```

`frontend/src/layout/AppLayout.tsx`:
```tsx
import { ReactNode } from 'react';
import { Layout, Menu, Dropdown, Space, Tooltip } from 'antd';
import { UserOutlined, SettingOutlined, WarningOutlined, BarChartOutlined, FileTextOutlined, DownOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { Logo } from './Logo';

const { Header, Sider, Content } = Layout;

export function AppLayout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { user, logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  const selectedKey = location.pathname.startsWith('/settings') ? '/settings' : location.pathname;

  const navItems = [
    { key: '/settings', icon: <SettingOutlined />, label: t('nav.settings') },
    { key: 'errors', icon: <WarningOutlined />, disabled: true, label: <Tooltip title={t('nav.comingSoon')}>{t('nav.errors')}</Tooltip> },
    { key: 'statistics', icon: <BarChartOutlined />, disabled: true, label: <Tooltip title={t('nav.comingSoon')}>{t('nav.statistics')}</Tooltip> },
    { key: 'logs', icon: <FileTextOutlined />, disabled: true, label: <Tooltip title={t('nav.comingSoon')}>{t('nav.logs')}</Tooltip> },
  ];

  const userMenu = {
    items: [
      { key: 'change-password', label: t('auth.changePassword'), onClick: () => navigate('/settings') },
      { key: 'logout', label: t('auth.logout'), onClick: () => { logout().then(() => navigate('/login')); } },
    ],
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ background: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingInline: 16, borderBottom: '1px solid #E6EDF3' }}>
        <Logo />
        <Dropdown menu={userMenu}>
          <Space style={{ cursor: 'pointer' }}><UserOutlined /> {user} <DownOutlined /></Space>
        </Dropdown>
      </Header>
      <Layout>
        <Sider theme="light" width={220} breakpoint="lg" collapsedWidth={0}>
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            items={navItems}
            onClick={({ key }) => { if (key === '/settings') navigate('/settings'); }}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ margin: 24 }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
```

`frontend/src/App.tsx`:
```tsx
import { ConfigProvider } from 'antd';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { smartupTheme } from './theme';
import { AuthProvider } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import { LoginPage } from './auth/LoginPage';
import { AppLayout } from './layout/AppLayout';
import { SettingsPage } from './pages/settings/SettingsPage';

export default function App() {
  return (
    <ConfigProvider theme={smartupTheme}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/*" element={
              <RequireAuth>
                <AppLayout>
                  <Routes>
                    <Route index element={<Navigate to="/settings" replace />} />
                    <Route path="settings/*" element={<SettingsPage />} />
                    <Route path="*" element={<Navigate to="/settings" replace />} />
                  </Routes>
                </AppLayout>
              </RequireAuth>
            } />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </ConfigProvider>
  );
}
```

`frontend/src/main.tsx` (replace entire file):
```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './i18n';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
```

Note: `App.tsx` imports `SettingsPage` (Task 5). To keep this task independently buildable, create a temporary stub `frontend/src/pages/settings/SettingsPage.tsx` returning `export function SettingsPage() { return null; }` in this task; Task 5 replaces it with the real tabbed page. The stub lets `npm run build` and the layout test pass now.

- [ ] **Step 4: Run the test + build to verify they pass**

Run (from `frontend/`): `npm test -- AppLayout` → Expected: PASS (3).
Run (from `frontend/`): `npm run build` → Expected: succeeds (no TS/bundle errors).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/layout/ frontend/src/App.tsx frontend/src/main.tsx frontend/src/pages/settings/SettingsPage.tsx
git commit -m "feat(ui): app shell (layout, nav, logo, routing) with Smartup theme"
```

---

### Task 5: Settings module — Source Auth page

**Files:**
- Create: `frontend/src/pages/settings/SettingsPage.tsx` (replaces the Task 4 stub — tabbed container), `frontend/src/pages/settings/SourceAuthPage.tsx`, `frontend/src/api/settings.ts`
- Test: `frontend/src/pages/settings/SourceAuthPage.test.tsx`

**Interfaces:**
- Consumes: `api` (Task 2), `useTranslation`.
- Produces:
  - `frontend/src/api/settings.ts`: `getSourceAuth()`, `putSourceAuth(body)`, `testSourceAuth()`, `getSettings()`, `putSetting(key, value, secret)`, `getAudit()`, `testTenantMapping(sampleObj)` (typed wrappers; used by Tasks 5-7).
  - `SettingsPage` (tabs: Source Auth / Tenant Mapping / Advanced — Tenant + Advanced tabs render placeholders here, filled by Tasks 6-7).
  - `SourceAuthPage`.
- PUT `/source-auth` field rules (from backend): `type=basic` requires `basicUsername`, and requires `basicPassword` only when none is currently stored (a masked `***` GET value means one is stored — do NOT resend it); `type=apikey` requires `apikeyHeader`+`apikeyValue`; `type=oauth2` requires `oauth2TokenUrl`+`oauth2ClientId`+`oauth2ClientSecret`, optional numeric `oauth2CacheTtlSeconds`.

- [ ] **Step 1: Write the failing Source Auth test**

`frontend/src/pages/settings/SourceAuthPage.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { SourceAuthPage } from './SourceAuthPage';
import * as settingsApi from '../../api/settings';

beforeEach(() => vi.restoreAllMocks());

const baseGet = {
  type: 'basic', basicUsername: 'svc', basicPassword: '***',
  apikeyHeader: 'X-Api-Key', apikeyValue: '', oauth2TokenUrl: '', oauth2ClientId: '',
  oauth2ClientSecret: '', oauth2Scope: '', oauth2CacheTtlSeconds: '',
};

describe('SourceAuthPage', () => {
  it('shows apikey fields when type switches to apikey', async () => {
    vi.spyOn(settingsApi, 'getSourceAuth').mockResolvedValue({ ...baseGet } as any);
    render(<SourceAuthPage />);
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.click(await screen.findByTitle('apikey'));
    expect(screen.getByLabelText('Header nomi')).toBeInTheDocument();
  });

  it('renders the test result returned by the backend', async () => {
    vi.spyOn(settingsApi, 'getSourceAuth').mockResolvedValue({ ...baseGet } as any);
    vi.spyOn(settingsApi, 'testSourceAuth').mockResolvedValue({ ok: true, type: 'basic', message: 'Auth configured' } as any);
    render(<SourceAuthPage />);
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Sinash' }));
    await waitFor(() => expect(screen.getByText('Auth configured')).toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `frontend/`): `npm test -- SourceAuthPage`
Expected: FAIL — `./SourceAuthPage`, `../../api/settings` not found.

- [ ] **Step 3: Implement the settings API wrappers and Source Auth page**

`frontend/src/api/settings.ts`:
```ts
import { api } from './client';

const BASE = '/qosid/admin/api';

export interface SourceAuth {
  type: string; basicUsername: string; basicPassword: string;
  apikeyHeader: string; apikeyValue: string;
  oauth2TokenUrl: string; oauth2ClientId: string; oauth2ClientSecret: string;
  oauth2Scope: string; oauth2CacheTtlSeconds: string;
}
export interface TestResult { ok: boolean; type?: string; message: string; }
export interface SettingRow { key: string; value: string; secret: boolean; updatedAt: string; updatedBy: string; }
export interface TenantTestResult { tenantId: number | null; extraTenantId: number | null; tenantField: string; extraField: string; }

export const getSourceAuth = () => api.get<SourceAuth>(`${BASE}/source-auth`).then(r => r.data);
export const putSourceAuth = (body: Partial<SourceAuth>) => api.put(`${BASE}/source-auth`, body);
export const testSourceAuth = () => api.post<TestResult>(`${BASE}/source-auth/test`).then(r => r.data);

export const getSettings = () => api.get<SettingRow[]>(`${BASE}/settings`).then(r => r.data);
export const putSetting = (key: string, value: string, secret: boolean) =>
  api.put(`${BASE}/settings/${encodeURIComponent(key)}`, { value, secret });
export const getAudit = () => api.get<any[]>(`${BASE}/settings/audit`).then(r => r.data);

export const testTenantMapping = (sample: unknown) =>
  api.post<TenantTestResult>(`${BASE}/tenant-mapping/test`, sample).then(r => r.data);
```

`frontend/src/pages/settings/SourceAuthPage.tsx`:
```tsx
import { useEffect, useState } from 'react';
import { Form, Select, Input, Button, Alert, Space, Card, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getSourceAuth, putSourceAuth, testSourceAuth, SourceAuth, TestResult } from '../../api/settings';

const MASK = '***';

export function SourceAuthPage() {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [type, setType] = useState('basic');
  const [loaded, setLoaded] = useState<SourceAuth | null>(null);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    getSourceAuth().then((d) => {
      setLoaded(d); setType(d.type);
      form.setFieldsValue(d);
    });
  }, [form]);

  const onSave = async () => {
    const v = form.getFieldsValue();
    setSaving(true);
    try {
      const body: Partial<SourceAuth> = { type };
      if (type === 'basic') {
        body.basicUsername = v.basicUsername;
        // Only send the password if the admin typed a new one (masked value means unchanged).
        if (v.basicPassword && v.basicPassword !== MASK) body.basicPassword = v.basicPassword;
      } else if (type === 'apikey') {
        body.apikeyHeader = v.apikeyHeader;
        if (v.apikeyValue && v.apikeyValue !== MASK) body.apikeyValue = v.apikeyValue;
      } else {
        body.oauth2TokenUrl = v.oauth2TokenUrl;
        body.oauth2ClientId = v.oauth2ClientId;
        if (v.oauth2ClientSecret && v.oauth2ClientSecret !== MASK) body.oauth2ClientSecret = v.oauth2ClientSecret;
        body.oauth2Scope = v.oauth2Scope;
        body.oauth2CacheTtlSeconds = v.oauth2CacheTtlSeconds;
      }
      await putSourceAuth(body);
      message.success(t('settings.saved'));
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Error');
    } finally {
      setSaving(false);
    }
  };

  const onTest = async () => setTestResult(await testSourceAuth());

  return (
    <Card title={t('settings.sourceAuth')} loading={!loaded}>
      <Form form={form} layout="vertical" style={{ maxWidth: 480 }}>
        <Form.Item label={t('settings.type')}>
          <Select
            value={type}
            onChange={setType}
            options={[{ value: 'basic' }, { value: 'apikey' }, { value: 'oauth2' }].map(o => ({ value: o.value, label: o.value, title: o.value }))}
          />
        </Form.Item>

        {type === 'basic' && <>
          <Form.Item name="basicUsername" label={t('sourceAuth.basicUsername')}><Input /></Form.Item>
          <Form.Item name="basicPassword" label={t('sourceAuth.basicPassword')}>
            <Input.Password placeholder={t('common.secretPlaceholder')} />
          </Form.Item>
        </>}

        {type === 'apikey' && <>
          <Form.Item name="apikeyHeader" label={t('sourceAuth.apikeyHeader')}><Input /></Form.Item>
          <Form.Item name="apikeyValue" label={t('sourceAuth.apikeyValue')}>
            <Input.Password placeholder={t('common.secretPlaceholder')} />
          </Form.Item>
        </>}

        {type === 'oauth2' && <>
          <Form.Item name="oauth2TokenUrl" label={t('sourceAuth.oauth2TokenUrl')}><Input /></Form.Item>
          <Form.Item name="oauth2ClientId" label={t('sourceAuth.oauth2ClientId')}><Input /></Form.Item>
          <Form.Item name="oauth2ClientSecret" label={t('sourceAuth.oauth2ClientSecret')}>
            <Input.Password placeholder={t('common.secretPlaceholder')} />
          </Form.Item>
          <Form.Item name="oauth2Scope" label={t('sourceAuth.oauth2Scope')}><Input /></Form.Item>
          <Form.Item name="oauth2CacheTtlSeconds" label={t('sourceAuth.oauth2CacheTtl')}><Input /></Form.Item>
        </>}

        <Space>
          <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
          <Button onClick={onTest}>{t('settings.test')}</Button>
        </Space>
      </Form>

      {testResult && (
        <Alert
          style={{ marginTop: 16 }}
          type={testResult.ok ? 'success' : 'error'}
          message={testResult.message}
        />
      )}
    </Card>
  );
}
```

`frontend/src/pages/settings/SettingsPage.tsx` (replace the Task 4 stub):
```tsx
import { Tabs } from 'antd';
import { useTranslation } from 'react-i18next';
import { SourceAuthPage } from './SourceAuthPage';
import { TenantMappingPage } from './TenantMappingPage';
import { AdvancedSettingsPage } from './AdvancedSettingsPage';

export function SettingsPage() {
  const { t } = useTranslation();
  return (
    <Tabs
      defaultActiveKey="source-auth"
      items={[
        { key: 'source-auth', label: t('settings.sourceAuth'), children: <SourceAuthPage /> },
        { key: 'tenant', label: t('settings.tenantMapping'), children: <TenantMappingPage /> },
        { key: 'advanced', label: t('settings.advanced'), children: <AdvancedSettingsPage /> },
      ]}
    />
  );
}
```
Note: `SettingsPage` imports `TenantMappingPage` (Task 6) and `AdvancedSettingsPage` (Task 7). To keep THIS task buildable, create stub files for both now — `export function TenantMappingPage() { return null; }` and `export function AdvancedSettingsPage() { return null; }` — which Tasks 6 and 7 replace.

- [ ] **Step 4: Run the test to verify it passes**

Run (from `frontend/`): `npm test -- SourceAuthPage` → Expected: PASS (2).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/settings.ts frontend/src/pages/settings/
git commit -m "feat(ui): settings tabs + Source Auth typed form with test action"
```

---

### Task 6: Settings module — Tenant Mapping page

**Files:**
- Create: `frontend/src/pages/settings/TenantMappingPage.tsx` (replaces Task 5 stub)
- Test: `frontend/src/pages/settings/TenantMappingPage.test.tsx`

**Interfaces:**
- Consumes: `getSettings`, `putSetting`, `testTenantMapping` (Task 5's `api/settings.ts`).
- Produces: `TenantMappingPage`. Reads/writes the four keys `gateway.tenant.field-name`, `gateway.tenant.extra-field-name`, `gateway.tenant.default-rest-tenant`, `gateway.tenant.trust-extra-tenant-header` via the generic settings API; the Test button posts a sample JSON object to `/tenant-mapping/test` and shows the resolved `tenantId`/`extraTenantId`.

- [ ] **Step 1: Write the failing Tenant Mapping test**

`frontend/src/pages/settings/TenantMappingPage.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { TenantMappingPage } from './TenantMappingPage';
import * as settingsApi from '../../api/settings';

beforeEach(() => vi.restoreAllMocks());

const rows = [
  { key: 'gateway.tenant.field-name', value: 'company_id', secret: false, updatedAt: '', updatedBy: '' },
  { key: 'gateway.tenant.extra-field-name', value: 'filial_id', secret: false, updatedAt: '', updatedBy: '' },
  { key: 'gateway.tenant.default-rest-tenant', value: '0', secret: false, updatedAt: '', updatedBy: '' },
  { key: 'gateway.tenant.trust-extra-tenant-header', value: 'false', secret: false, updatedAt: '', updatedBy: '' },
];

describe('TenantMappingPage', () => {
  it('loads the current tenant field-name value', async () => {
    vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    render(<TenantMappingPage />);
    await waitFor(() => expect(screen.getByDisplayValue('company_id')).toBeInTheDocument());
  });

  it('shows the resolved tenantId from a sample after Test', async () => {
    vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    vi.spyOn(settingsApi, 'testTenantMapping').mockResolvedValue({ tenantId: 100, extraTenantId: 5, tenantField: 'company_id', extraField: 'filial_id' } as any);
    render(<TenantMappingPage />);
    await waitFor(() => expect(screen.getByDisplayValue('company_id')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Sinash' }));
    await waitFor(() => expect(screen.getByText('100')).toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `frontend/`): `npm test -- TenantMappingPage`
Expected: FAIL — stub `TenantMappingPage` renders null, assertions fail.

- [ ] **Step 3: Implement the Tenant Mapping page**

`frontend/src/pages/settings/TenantMappingPage.tsx`:
```tsx
import { useEffect, useState } from 'react';
import { Card, Form, Input, Switch, Button, Space, Alert, Descriptions, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getSettings, putSetting, testTenantMapping, TenantTestResult } from '../../api/settings';

const KEYS = {
  field: 'gateway.tenant.field-name',
  extra: 'gateway.tenant.extra-field-name',
  def: 'gateway.tenant.default-rest-tenant',
  trust: 'gateway.tenant.trust-extra-tenant-header',
};

export function TenantMappingPage() {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [loaded, setLoaded] = useState(false);
  const [sample, setSample] = useState('{\n  "company_id": 100,\n  "filial_id": 5\n}');
  const [result, setResult] = useState<TenantTestResult | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getSettings().then((all) => {
      const byKey = Object.fromEntries(all.map((r) => [r.key, r.value]));
      form.setFieldsValue({
        field: byKey[KEYS.field] ?? 'company_id',
        extra: byKey[KEYS.extra] ?? 'filial_id',
        def: byKey[KEYS.def] ?? '0',
        trust: (byKey[KEYS.trust] ?? 'false') === 'true',
      });
      setLoaded(true);
    });
  }, [form]);

  const onSave = async () => {
    const v = form.getFieldsValue();
    setSaving(true);
    try {
      await putSetting(KEYS.field, v.field, false);
      await putSetting(KEYS.extra, v.extra, false);
      await putSetting(KEYS.def, v.def, false);
      await putSetting(KEYS.trust, String(v.trust), false);
      message.success(t('settings.saved'));
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Error');
    } finally {
      setSaving(false);
    }
  };

  const onTest = async () => {
    setError(null); setResult(null);
    try {
      setResult(await testTenantMapping(JSON.parse(sample)));
    } catch (e: any) {
      setError(e instanceof SyntaxError ? 'Invalid JSON' : (e?.response?.data?.message ?? 'Error'));
    }
  };

  return (
    <Card title={t('settings.tenantMapping')} loading={!loaded}>
      <Form form={form} layout="vertical" style={{ maxWidth: 480 }}>
        <Form.Item name="field" label="field-name"><Input /></Form.Item>
        <Form.Item name="extra" label="extra-field-name"><Input /></Form.Item>
        <Form.Item name="def" label="default-rest-tenant"><Input /></Form.Item>
        <Form.Item name="trust" label="trust-extra-tenant-header" valuePropName="checked"><Switch /></Form.Item>
        <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
      </Form>

      <div style={{ marginTop: 24, maxWidth: 480 }}>
        <Form.Item label={t('tenant.sampleJson')}>
          <Input.TextArea rows={5} value={sample} onChange={(e) => setSample(e.target.value)} />
        </Form.Item>
        <Button onClick={onTest}>{t('settings.test')}</Button>
        {error && <Alert style={{ marginTop: 12 }} type="error" message={error} />}
        {result && (
          <Descriptions style={{ marginTop: 12 }} column={1} bordered size="small">
            <Descriptions.Item label={t('tenant.resolvedTenant')}>{String(result.tenantId)}</Descriptions.Item>
            <Descriptions.Item label={t('tenant.resolvedExtra')}>{String(result.extraTenantId)}</Descriptions.Item>
          </Descriptions>
        )}
      </div>
    </Card>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run (from `frontend/`): `npm test -- TenantMappingPage` → Expected: PASS (2).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/settings/TenantMappingPage.tsx frontend/src/pages/settings/TenantMappingPage.test.tsx
git commit -m "feat(ui): Tenant Mapping settings form + diagnostics test"
```

---

### Task 7: Settings module — Advanced (generic table) + Audit

**Files:**
- Create: `frontend/src/pages/settings/AdvancedSettingsPage.tsx` (replaces Task 5 stub)
- Test: `frontend/src/pages/settings/AdvancedSettingsPage.test.tsx`

**Interfaces:**
- Consumes: `getSettings`, `putSetting`, `getAudit` (Task 5's `api/settings.ts`).
- Produces: `AdvancedSettingsPage` — a table of all settings grouped by key prefix (segment before the last dot). Secret rows show `***`; editing sends the typed value with `secret` preserved from the row. Includes an Audit sub-view from `getAudit()`.

- [ ] **Step 1: Write the failing Advanced page test**

`frontend/src/pages/settings/AdvancedSettingsPage.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { AdvancedSettingsPage } from './AdvancedSettingsPage';
import * as settingsApi from '../../api/settings';

beforeEach(() => vi.restoreAllMocks());

const rows = [
  { key: 'gateway.response.threshold-size', value: '1024', secret: false, updatedAt: '', updatedBy: 'admin' },
  { key: 'gateway.source.auth.basic.password', value: '***', secret: true, updatedAt: '', updatedBy: 'admin' },
];

describe('AdvancedSettingsPage', () => {
  it('lists settings keys and values', async () => {
    vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    vi.spyOn(settingsApi, 'getAudit').mockResolvedValue([]);
    render(<AdvancedSettingsPage />);
    await waitFor(() => expect(screen.getByText('gateway.response.threshold-size')).toBeInTheDocument());
    expect(screen.getByText('1024')).toBeInTheDocument();
  });

  it('saves an edited value via putSetting preserving the secret flag', async () => {
    vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    vi.spyOn(settingsApi, 'getAudit').mockResolvedValue([]);
    const put = vi.spyOn(settingsApi, 'putSetting').mockResolvedValue({} as any);
    render(<AdvancedSettingsPage />);
    await waitFor(() => expect(screen.getByText('gateway.response.threshold-size')).toBeInTheDocument());
    await userEvent.click(screen.getAllByRole('button', { name: 'Saqlash' })[0]);
    // Edit modal opens with the row; change value then confirm.
    const input = await screen.findByLabelText('Qiymat');
    await userEvent.clear(input);
    await userEvent.type(input, '2048');
    await userEvent.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.response.threshold-size', '2048', false));
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `frontend/`): `npm test -- AdvancedSettingsPage`
Expected: FAIL — stub renders null.

- [ ] **Step 3: Implement the Advanced settings page**

`frontend/src/pages/settings/AdvancedSettingsPage.tsx`:
```tsx
import { useEffect, useMemo, useState } from 'react';
import { Card, Table, Button, Modal, Input, Tag, Tabs, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getSettings, putSetting, getAudit, SettingRow } from '../../api/settings';

function groupOf(key: string): string {
  const i = key.lastIndexOf('.');
  return i > 0 ? key.slice(0, i) : key;
}

export function AdvancedSettingsPage() {
  const { t } = useTranslation();
  const [rows, setRows] = useState<SettingRow[]>([]);
  const [audit, setAudit] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<SettingRow | null>(null);
  const [draft, setDraft] = useState('');

  const load = () => {
    setLoading(true);
    Promise.all([getSettings(), getAudit()]).then(([s, a]) => {
      setRows(s); setAudit(a); setLoading(false);
    });
  };
  useEffect(load, []);

  const grouped = useMemo(() => {
    const map = new Map<string, SettingRow[]>();
    for (const r of [...rows].sort((a, b) => a.key.localeCompare(b.key))) {
      const g = groupOf(r.key);
      (map.get(g) ?? map.set(g, []).get(g)!).push(r);
    }
    return map;
  }, [rows]);

  const openEdit = (row: SettingRow) => { setEditing(row); setDraft(row.secret ? '' : row.value); };

  const save = async () => {
    if (!editing) return;
    try {
      await putSetting(editing.key, draft, editing.secret);
      message.success(t('settings.saved'));
      setEditing(null);
      load();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Error');
    }
  };

  const columns = [
    { title: t('settings.key'), dataIndex: 'key', key: 'key' },
    { title: t('settings.value'), dataIndex: 'value', key: 'value',
      render: (v: string, r: SettingRow) => (r.secret ? <Tag>***</Tag> : v) },
    { title: '', key: 'action',
      render: (_: unknown, r: SettingRow) => <Button size="small" onClick={() => openEdit(r)}>{t('settings.save')}</Button> },
  ];

  const settingsTab = (
    <Card loading={loading}>
      {[...grouped.entries()].map(([group, groupRows]) => (
        <div key={group} style={{ marginBottom: 20 }}>
          <h4 style={{ color: '#1A2333' }}>{group}</h4>
          <Table rowKey="key" size="small" pagination={false} columns={columns} dataSource={groupRows} />
        </div>
      ))}
    </Card>
  );

  const auditColumns = [
    { title: 'key', dataIndex: 'skey', key: 'skey' },
    { title: 'old', dataIndex: 'old_value', key: 'old_value' },
    { title: 'new', dataIndex: 'new_value', key: 'new_value' },
    { title: 'by', dataIndex: 'changed_by', key: 'changed_by' },
    { title: 'at', dataIndex: 'changed_at', key: 'changed_at' },
  ];

  return (
    <>
      <Tabs items={[
        { key: 'settings', label: t('settings.advanced'), children: settingsTab },
        { key: 'audit', label: t('settings.audit'),
          children: <Table rowKey={(_r, i) => String(i)} size="small" columns={auditColumns} dataSource={audit} /> },
      ]} />

      <Modal
        open={!!editing}
        title={editing?.key}
        okText="OK"
        onOk={save}
        onCancel={() => setEditing(null)}
      >
        <label htmlFor="adv-value">{t('settings.value')}</label>
        <Input
          id="adv-value"
          aria-label={t('settings.value')}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={editing?.secret ? t('common.secretPlaceholder') : ''}
        />
      </Modal>
    </>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run (from `frontend/`): `npm test -- AdvancedSettingsPage` → Expected: PASS (2).
Then run the whole frontend suite: `npm test` → Expected: all green (cookies, client, theme, auth, layout, source-auth, tenant, advanced).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/settings/AdvancedSettingsPage.tsx frontend/src/pages/settings/AdvancedSettingsPage.test.tsx
git commit -m "feat(ui): Advanced grouped settings table + inline edit + audit view"
```

---

### Task 8: End-to-end smoke (Playwright over the built SPA) + full-build verification

**Files:**
- Create: `frontend/playwright.config.ts`, `frontend/e2e/settings.spec.ts`
- Modify: `frontend/package.json` (add `@playwright/test` + `msw` devDeps and an `e2e` script)

**Interfaces:**
- Consumes: the built SPA (`npm run build` → `dist`) served by `vite preview`; MSW mocks the admin API so no live backend is needed.
- Produces: a Playwright spec proving login → Settings → Source Auth Test renders end-to-end. This is the automated safety net; the live full-app smoke is done at review time (see "Phase-end verification").

- [ ] **Step 1: Add Playwright + MSW deps and scripts**

In `frontend/package.json` add to `devDependencies`: `"@playwright/test": "^1.47.2"`, `"msw": "^2.4.9"`; add to `scripts`: `"e2e": "playwright test"`.
Then run (from `frontend/`): `npm install` and `npx playwright install chromium`.

- [ ] **Step 2: Write the Playwright config and failing E2E spec**

`frontend/playwright.config.ts`:
```ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30000,
  webServer: {
    command: 'npm run build && npm run preview -- --port 4173',
    port: 4173,
    reuseExistingServer: false,
  },
  use: { baseURL: 'http://localhost:4173' },
});
```

`frontend/e2e/settings.spec.ts`:
```ts
import { test, expect } from '@playwright/test';

// Mock the admin API at the network layer so the E2E needs no live backend.
test.beforeEach(async ({ page }) => {
  await page.route('**/qosid/admin/api/health', (r) => r.fulfill({ status: 200, body: '{}', headers: { 'content-type': 'application/json' } }));
  let loggedIn = false;
  await page.route('**/qosid/admin/api/me', (r) =>
    r.fulfill({ status: loggedIn ? 200 : 401, body: JSON.stringify({ username: 'admin' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/login', (r) => { loggedIn = true; return r.fulfill({ status: 200, body: JSON.stringify({ username: 'admin', status: 'AUTHENTICATED' }), headers: { 'content-type': 'application/json' } }); });
  await page.route('**/qosid/admin/api/source-auth', (r) =>
    r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({
      type: 'basic', basicUsername: 'svc', basicPassword: '***', apikeyHeader: 'X-Api-Key', apikeyValue: '',
      oauth2TokenUrl: '', oauth2ClientId: '', oauth2ClientSecret: '', oauth2Scope: '', oauth2CacheTtlSeconds: '' }) }));
  await page.route('**/qosid/admin/api/source-auth/test', (r) =>
    r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({ ok: true, type: 'basic', message: 'Auth configured' }) }));
  await page.route('**/qosid/admin/api/settings', (r) => r.fulfill({ status: 200, body: '[]', headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/settings/audit', (r) => r.fulfill({ status: 200, body: '[]', headers: { 'content-type': 'application/json' } }));
});

test('login then run Source Auth test', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Foydalanuvchi').fill('admin');
  await page.getByLabel('Parol').fill('admin');
  await page.getByRole('button', { name: 'Kirish' }).click();
  await expect(page.getByText('Sozlamalar')).toBeVisible();
  await page.getByRole('button', { name: 'Sinash' }).first().click();
  await expect(page.getByText('Auth configured')).toBeVisible();
});
```

- [ ] **Step 3: Run the E2E to verify it passes**

Run (from `frontend/`): `npm run e2e`
Expected: 1 passed (Playwright builds, previews, drives login → settings → test). If it fails first due to missing chromium, run `npx playwright install chromium` and retry.

- [ ] **Step 4: Full single-JAR build verification**

Run: `& "...mvn.cmd" package -DskipTests` (frontend profile ON)
Expected: BUILD SUCCESS; `jar tf target/qosid-flow-1.0.0-SNAPSHOT.jar | grep static/index.html` shows the entry.
Run: `& "...mvn.cmd" -P '!frontend' test` → Expected: 97 backend tests green (unchanged pipeline).

- [ ] **Step 5: Commit**

```bash
git add frontend/playwright.config.ts frontend/e2e/ frontend/package.json frontend/package-lock.json
git commit -m "test(ui): Playwright E2E (login -> settings -> source-auth test) over mocked API"
```

---

## Phase-end verification (controller runs after all tasks)

1. `& "...mvn.cmd" -P '!frontend' test` — 97 backend tests green (pipeline untouched).
2. `cd frontend && npm test` — full Vitest suite green.
3. `cd frontend && npm run e2e` — Playwright green.
4. `& "...mvn.cmd" package -DskipTests` — single JAR contains `static/index.html`.
5. **Live smoke:** boot the JAR with `SETTINGS_ENCRYPTION_KEY`, `POLLING_ENABLED=false`; open `http://localhost:8090/` in a browser (Playwright/chromium), log in as `admin`/`admin`, open each of the three settings tabs, run Source Auth Test, screenshot each — confirm the Smartup theme renders and the real backend answers. Report screenshots.

## Self-Review notes (author)

- **Spec coverage:** stack/serving/build → Task 1; theme/i18n/client → Task 2; auth (login/session/CSRF/change-password entry) → Task 3; layout/nav/logo/routing → Task 4; hybrid settings: Source Auth → Task 5, Tenant Mapping → Task 6, Advanced+audit → Task 7; testing (Vitest+Playwright+Spring) → Tasks 1-8 + phase-end. D2-D5 remain nav placeholders (Task 4). All spec §4-§6 items map to a task.
- **Secret handling:** GET returns `***`; forms send a new secret only when the typed value ≠ `***` (Tasks 5, 7) — never re-sends the mask.
- **Type consistency:** `api/settings.ts` interfaces (`SourceAuth`, `TestResult`, `SettingRow`, `TenantTestResult`) are defined in Task 5 and consumed by Tasks 6-7 by the same names; `useAuth` shape identical across Tasks 3-4.
- **Stub sequencing:** Task 4 stubs `SettingsPage`; Task 5 stubs `TenantMappingPage`/`AdvancedSettingsPage` so each task builds green before the next replaces the stub. Called out inline in the affected steps.
