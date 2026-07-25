# C-loyiha: Yagona SPA — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the React SPA from the gateway JAR into an nginx-served static frontend and add an Ops page (gateway start/stop + live log SSE) with a single login across the gateway and ops APIs.

**Architecture:** One origin via nginx: `/` → static `frontend/dist`, `/qosid/` → gateway :8090, `/ops/api/` → ops :8092. The SPA logs into both backends with one form (parallel POSTs); ops cookies are renamed (`OPS_SESSION`, `OPS-XSRF-TOKEN`) to avoid collisions. Gateway loses `SpaForwardController` + JAR-embedded static; ops loses its HTML page.

**Tech Stack:** Spring Boot 3.3 (gateway + ops sidecar), React 18 + Vite + Ant Design + react-i18next (uz), axios, EventSource (SSE), vitest + Playwright, nginx.

**Spec:** `docs/superpowers/specs/2026-07-17-c-unified-spa-design.md`

## Global Constraints

- E1-E5 pipeline code MUST NOT change (`ai-context/SAFETY-RULES.md`).
- Gateway cookies (`JSESSIONID`, `XSRF-TOKEN`) MUST NOT change — only ops cookies are renamed.
- Ops API paths stay exactly: `/ops/api/{login,logout,me,status,start,stop,logs/stream}`.
- `ControlResult` wire shape stays `{ok: boolean, exitCode: number, output: string}`.
- Ops `/ops/api/me` response shape stays `{"user": "<name>"}`; gateway `/qosid/admin/api/me` stays `{"username": "<name>"}`.
- All UI copy in Uzbek via `frontend/src/locales/uz.json` (flat keys like `"ops.title"`).
- Version bump to **1.13.0** only in the final task.
- All existing tests must stay green after every task.
- Run commands from repo root `C:\Users\muxammadali.ergashev\Documents\Projects\qosid-flow` unless stated.
- Backend tests: gateway `mvn -q test -P '!frontend'`, ops `mvn -q -f ops/pom.xml test`. Frontend: `cd frontend && npx vitest run`. E2E: `cd frontend && npx playwright test`.

---

### Task 1: Ops cookie rename (`OPS_SESSION` + `OPS-XSRF-TOKEN`)

**Files:**
- Modify: `ops/src/main/resources/application.yml`
- Modify: `ops/src/main/java/uz/greenwhite/ops/OpsSecurityConfig.java`
- Modify: `ops/src/test/java/uz/greenwhite/ops/OpsAuthTest.java` (rename cookie/header literals)
- Create: `ops/src/test/java/uz/greenwhite/ops/OpsSessionCookieTest.java`

**Interfaces:**
- Consumes: existing `OpsAuthController` (`POST /ops/api/login` is CSRF-ignored), `AdminAuthDao.verify(String,String)`.
- Produces: ops issues session cookie `OPS_SESSION`; CSRF cookie `OPS-XSRF-TOKEN` echoed back via header `X-OPS-XSRF-TOKEN`. Task 4's frontend client relies on these exact names.

- [ ] **Step 1: Write the failing session-cookie test**

Create `ops/src/test/java/uz/greenwhite/ops/OpsSessionCookieTest.java`:

```java
package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** The renamed session cookie is container-level, so assert over a real port (MockMvc can't see it). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsSessionCookieTest {

    @Autowired TestRestTemplate rest;
    @MockBean AdminAuthDao authDao;

    @Test
    void loginIssuesOpsSessionCookie_notJsessionId() {
        when(authDao.verify(any(), any())).thenReturn(true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> res = rest.postForEntity("/ops/api/login",
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"admin\"}", headers), Void.class);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<String> cookies = res.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of());
        assertThat(cookies).anyMatch(c -> c.startsWith("OPS_SESSION="));
        assertThat(cookies).noneMatch(c -> c.startsWith("JSESSIONID="));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -f ops/pom.xml test -Dtest=OpsSessionCookieTest`
Expected: FAIL — Set-Cookie contains `JSESSIONID=`, not `OPS_SESSION=`.

- [ ] **Step 3: Rename the session cookie in ops config**

In `ops/src/main/resources/application.yml`, change the `server:` block (currently only `port`) to:

```yaml
server:
  port: ${OPS_PORT:8092}
  servlet:
    session:
      cookie:
        name: OPS_SESSION   # bitta domen ostida gateway JSESSIONID bilan to'qnashmaslik uchun
```

- [ ] **Step 4: Rename the CSRF cookie + header**

In `ops/src/main/java/uz/greenwhite/ops/OpsSecurityConfig.java`, replace the `opsChain` body's CSRF repository setup. Change:

```java
        CsrfTokenRequestAttributeHandler csrf = new CsrfTokenRequestAttributeHandler(); // plain (no XOR) — avoids the SPA breakage seen in B1
        http
            .securityMatcher("/ops/**")
```

to:

```java
        CsrfTokenRequestAttributeHandler csrf = new CsrfTokenRequestAttributeHandler(); // plain (no XOR) — avoids the SPA breakage seen in B1
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieName("OPS-XSRF-TOKEN");      // gateway XSRF-TOKEN bilan to'qnashmasin
        csrfRepo.setHeaderName("X-OPS-XSRF-TOKEN");
        http
            .securityMatcher("/ops/**")
```

and change `.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())` to `.csrfTokenRepository(csrfRepo)`.

- [ ] **Step 5: Update OpsAuthTest literals**

In `ops/src/test/java/uz/greenwhite/ops/OpsAuthTest.java`, replace every literal `"XSRF-TOKEN"` with `"OPS-XSRF-TOKEN"` and every `"X-XSRF-TOKEN"` with `"X-OPS-XSRF-TOKEN"` (cookie names in `cookie().exists(...)`, `new Cookie(...)`, and header names in `.header(...)`).

- [ ] **Step 6: Run the ops test suite**

Run: `mvn -q -f ops/pom.xml test`
Expected: ALL PASS (including `OpsSessionCookieTest`).

- [ ] **Step 7: Commit**

```bash
git add ops/src/main/resources/application.yml ops/src/main/java/uz/greenwhite/ops/OpsSecurityConfig.java ops/src/test/java/uz/greenwhite/ops/OpsAuthTest.java ops/src/test/java/uz/greenwhite/ops/OpsSessionCookieTest.java
git commit -m "feat(ops): rename session/CSRF cookies to OPS_SESSION / OPS-XSRF-TOKEN (single-origin prep)"
```

---

### Task 2: Remove the ops HTML page (ops becomes API-only)

**Files:**
- Delete: `ops/src/main/java/uz/greenwhite/ops/OpsPageController.java`
- Delete: `ops/src/main/resources/static/ops/index.html`
- Modify: `ops/src/main/java/uz/greenwhite/ops/OpsSecurityConfig.java` (permitAll list)
- Modify: any test referencing `/ops` page or `/ops/index.html` (check `OpsAuthTest`, `OpsApplicationTest`)

**Interfaces:**
- Consumes: Task 1's security chain.
- Produces: `GET /ops` and `/ops/index.html` return 401 (secured, nothing serves them); only `/ops/api/login` stays permitAll. The SPA (Task 7) is the only ops UI.

- [ ] **Step 1: Write the failing test**

Add to `ops/src/test/java/uz/greenwhite/ops/OpsAuthTest.java`:

```java
    @Test
    void opsHtmlPageIsGone() throws Exception {
        mvc.perform(get("/ops")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/index.html")).andExpect(status().isUnauthorized());
    }
```

(Adjust to the class's existing MockMvc field name — it is `mvc` or `mockMvc`; keep whatever the file already uses.)

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -f ops/pom.xml test -Dtest=OpsAuthTest`
Expected: FAIL — `/ops` currently returns 200 (permitAll + forward).

- [ ] **Step 3: Delete the page and its permitAll entries**

```bash
git rm ops/src/main/java/uz/greenwhite/ops/OpsPageController.java
git rm ops/src/main/resources/static/ops/index.html
```

In `OpsSecurityConfig.java` change:

```java
                .requestMatchers("/ops", "/ops/", "/ops/index.html", "/ops/api/login").permitAll()
```

to:

```java
                .requestMatchers("/ops/api/login").permitAll()
```

If any existing test asserted the page loads (search: `grep -rn "index.html\|forward" ops/src/test`), delete that assertion/test — the page is intentionally gone.

- [ ] **Step 4: Run the ops test suite**

Run: `mvn -q -f ops/pom.xml test`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add -A ops/
git commit -m "feat(ops): remove built-in HTML page — ops is API-only, UI moves to the SPA"
```

---

### Task 3: Gateway — remove SpaForwardController and the JAR static embed

**Files:**
- Delete: `src/main/java/uz/greenwhite/gateway/config/SpaForwardController.java`
- Delete: `src/test/java/uz/greenwhite/gateway/config/SpaForwardControllerTest.java`
- Modify: `pom.xml` (remove the `copy-frontend-dist` maven-resources-plugin execution, lines ~227-243)

**Interfaces:**
- Consumes: nothing from other tasks (independent).
- Produces: gateway JAR no longer contains `BOOT-INF/classes/static/**`; `npm run build` (frontend profile) still produces `frontend/dist` for nginx.

- [ ] **Step 1: Delete controller + test**

```bash
git rm src/main/java/uz/greenwhite/gateway/config/SpaForwardController.java
git rm src/test/java/uz/greenwhite/gateway/config/SpaForwardControllerTest.java
```

- [ ] **Step 2: Remove the copy-to-JAR step from pom.xml**

In `pom.xml`, inside the `frontend` profile, delete the entire `maven-resources-plugin` `<plugin>` block whose execution id is `copy-frontend-dist`:

```xml
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-resources-plugin</artifactId>
                        <executions>
                            <execution>
                                <id>copy-frontend-dist</id>
                                ...
                            </execution>
                        </executions>
                    </plugin>
```

Keep the `frontend-maven-plugin` (node install / `npm ci` / `npm run build`) — prod still builds `frontend/dist` through Maven.

- [ ] **Step 3: Verify no ghost references**

Run: `grep -rn "SpaForwardController\|copy-frontend-dist" src/ pom.xml ai-context/ CLAUDE.md`
Expected: hits only in `ai-context/`/docs (updated in Task 10) — no hits in `src/` or `pom.xml`.

- [ ] **Step 4: Run gateway tests, then verify the JAR is SPA-free**

Run: `mvn -q test`
Expected: ALL PASS (SpaForwardControllerTest is gone).

Run: `mvn -q package -DskipTests && unzip -l target/qosid-flow-1.0.0-SNAPSHOT.jar | grep -c "static/index.html" || echo "no static"`
Expected: `no static` (grep finds nothing).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: extract SPA from gateway JAR — drop SpaForwardController + copy-frontend-dist"
```

---

### Task 4: Frontend — ops API client (`src/api/ops.ts`)

**Files:**
- Create: `frontend/src/api/ops.ts`
- Create: `frontend/src/api/ops.test.ts`

**Interfaces:**
- Consumes: `getCookie` from `frontend/src/api/cookies.ts`; ops endpoints from Tasks 1-2 (cookie `OPS-XSRF-TOKEN`, header `X-OPS-XSRF-TOKEN`).
- Produces (used by Tasks 5-7):
  - `opsApi: AxiosInstance`
  - `opsLogin(username: string, password: string): Promise<AxiosResponse<void>>`
  - `opsLogout(): Promise<AxiosResponse<void>>`
  - `opsMe(): Promise<AxiosResponse<{ user: string }>>`
  - `getOpsStatus(): Promise<AxiosResponse<{ up: boolean }>>`
  - `startGateway(): Promise<AxiosResponse<ControlResult>>`
  - `stopGateway(): Promise<AxiosResponse<ControlResult>>`
  - `interface ControlResult { ok: boolean; exitCode: number; output: string }`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/api/ops.test.ts` (mirror the style of `client.test.ts`):

```ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { opsApi } from './ops';

describe('opsApi CSRF interceptor', () => {
  beforeEach(() => { document.cookie = 'OPS-XSRF-TOKEN=tok123'; });
  afterEach(() => { document.cookie = 'OPS-XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'; });

  it('echoes OPS-XSRF-TOKEN cookie in X-OPS-XSRF-TOKEN header', async () => {
    const handler = opsApi.interceptors.request as unknown as {
      handlers: Array<{ fulfilled: (c: { headers: Record<string, string> }) => { headers: Record<string, string> } }>;
    };
    const config = handler.handlers[0].fulfilled({ headers: {} });
    expect(config.headers['X-OPS-XSRF-TOKEN']).toBe('tok123');
  });

  it('does not set the gateway header name', async () => {
    const handler = opsApi.interceptors.request as unknown as {
      handlers: Array<{ fulfilled: (c: { headers: Record<string, string> }) => { headers: Record<string, string> } }>;
    };
    const config = handler.handlers[0].fulfilled({ headers: {} });
    expect(config.headers['X-XSRF-TOKEN']).toBeUndefined();
  });
});
```

(If `client.test.ts` accesses interceptors differently, copy that file's exact technique instead — the assertion targets are what matter.)

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/api/ops.test.ts`
Expected: FAIL — `./ops` module not found.

- [ ] **Step 3: Implement the client**

Create `frontend/src/api/ops.ts`:

```ts
import axios from 'axios';
import { getCookie } from './cookies';

/**
 * Ops sidecar client. Separate axios instance: ops uses renamed cookies
 * (OPS_SESSION / OPS-XSRF-TOKEN) so it can coexist with the gateway on one origin.
 * NOTE: deliberately NO global 401 handler here — degraded-mode logic in
 * AuthContext interprets ops failures itself.
 */
export const opsApi = axios.create({ withCredentials: true });

opsApi.interceptors.request.use((config) => {
  const token = getCookie('OPS-XSRF-TOKEN');
  if (token) config.headers['X-OPS-XSRF-TOKEN'] = token;
  return config;
});

export interface ControlResult { ok: boolean; exitCode: number; output: string; }

const BASE = '/ops/api';

export const opsLogin = (username: string, password: string) =>
  opsApi.post<void>(`${BASE}/login`, { username, password });
export const opsLogout = () => opsApi.post<void>(`${BASE}/logout`);
export const opsMe = () => opsApi.get<{ user: string }>(`${BASE}/me`);
export const getOpsStatus = () => opsApi.get<{ up: boolean }>(`${BASE}/status`);
export const startGateway = () => opsApi.post<ControlResult>(`${BASE}/start`);
export const stopGateway = () => opsApi.post<ControlResult>(`${BASE}/stop`);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/api/ops.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/ops.ts frontend/src/api/ops.test.ts
git commit -m "feat(ui): ops API client with OPS-XSRF-TOKEN CSRF echo"
```

---

### Task 5: Frontend — single login across both backends (AuthContext)

**Files:**
- Modify: `frontend/src/auth/AuthContext.tsx`
- Modify: `frontend/src/auth/LoginPage.tsx`
- Modify: `frontend/src/auth/AuthContext.test.tsx` (extend)
- Modify: `frontend/src/locales/uz.json` (2 new keys)

**Interfaces:**
- Consumes: `opsLogin/opsLogout/opsMe` from Task 4; existing gateway `api` client.
- Produces (used by Tasks 6-7): extended `AuthState`:

```ts
interface AuthState {
  user: string | null;
  loading: boolean;
  gatewayUp: boolean;   // gateway session valid
  opsUp: boolean;       // ops session valid
  login: (username: string, password: string) => Promise<void>;  // throws Error('badcreds') | Error('down')
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}
```

- [ ] **Step 1: Write the failing tests**

Extend `frontend/src/auth/AuthContext.test.tsx` with the login matrix. Follow the file's existing mocking style (it already mocks `../api/client`); additionally mock `../api/ops`:

```ts
vi.mock('../api/ops', () => ({
  opsLogin: vi.fn(),
  opsLogout: vi.fn().mockResolvedValue(undefined),
  opsMe: vi.fn(),
}));
```

Test cases (adapt render/act helpers to the file's existing pattern):

```ts
const axios401 = { isAxiosError: true, response: { status: 401 } };
const axiosDown = { isAxiosError: true, response: undefined, code: 'ERR_NETWORK' };

it('full mode: both logins succeed -> user set, gatewayUp && opsUp', async () => {
  (api.post as Mock).mockResolvedValue({ data: {} });          // gateway login
  (api.get as Mock).mockResolvedValue({ data: { username: 'admin' } }); // health + me
  (opsLogin as Mock).mockResolvedValue({ data: {} });
  (opsMe as Mock).mockResolvedValue({ data: { user: 'admin' } });
  // login('admin','admin') -> resolves; state: user='admin', gatewayUp=true, opsUp=true
});

it('degraded: gateway down, ops ok -> login resolves, user set, gatewayUp=false', async () => {
  (api.post as Mock).mockRejectedValue(axiosDown);
  (api.get as Mock).mockRejectedValue(axiosDown);
  (opsLogin as Mock).mockResolvedValue({ data: {} });
  (opsMe as Mock).mockResolvedValue({ data: { user: 'admin' } });
  // login resolves; user='admin', gatewayUp=false, opsUp=true
});

it('bad credentials: any 401 -> login throws badcreds', async () => {
  (api.post as Mock).mockRejectedValue(axios401);
  (opsLogin as Mock).mockRejectedValue(axios401);
  // await expect(login(...)).rejects.toThrow('badcreds')
});

it('both down -> login throws down', async () => {
  (api.post as Mock).mockRejectedValue(axiosDown);
  (opsLogin as Mock).mockRejectedValue(axiosDown);
  // await expect(login(...)).rejects.toThrow('down')
});
```

- [ ] **Step 2: Run to verify new tests fail**

Run: `cd frontend && npx vitest run src/auth/AuthContext.test.tsx`
Expected: new tests FAIL (no `gatewayUp`/`opsUp`, single-target login).

- [ ] **Step 3: Implement the dual login in AuthContext**

Replace `frontend/src/auth/AuthContext.tsx` content with:

```tsx
import { createContext, useContext, useEffect, useState, ReactNode, useCallback } from 'react';
import axios from 'axios';
import { api, setUnauthorizedHandler } from '../api/client';
import { opsLogin, opsLogout, opsMe } from '../api/ops';

interface AuthState {
  user: string | null;
  loading: boolean;
  gatewayUp: boolean;
  opsUp: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

export const AuthContext = createContext<AuthState>(null as unknown as AuthState);
export const useAuth = () => useContext(AuthContext);

const BASE = '/qosid/admin/api';

/** 401 => wrong credentials / no session; anything else rejected => backend unreachable. */
function is401(err: unknown): boolean {
  return axios.isAxiosError(err) && err.response?.status === 401;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [gatewayUp, setGatewayUp] = useState(false);
  const [opsUp, setOpsUp] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    // Seed the gateway XSRF-TOKEN cookie (any admin-chain GET renders it), then probe both sessions.
    await api.get(`${BASE}/health`).catch(() => {});
    const [gw, ops] = await Promise.allSettled([api.get(`${BASE}/me`), opsMe()]);
    const gwUser = gw.status === 'fulfilled' ? (gw.value.data.username ?? null) : null;
    const opsUser = ops.status === 'fulfilled' ? (ops.value.data.user || null) : null;
    setGatewayUp(gw.status === 'fulfilled');
    setOpsUp(ops.status === 'fulfilled');
    setUser(gwUser ?? opsUser);
    setLoading(false);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const [gw, ops] = await Promise.allSettled([
      api.post(`${BASE}/login`, { username, password }),
      opsLogin(username, password),
    ]);
    const ok = gw.status === 'fulfilled' || ops.status === 'fulfilled';
    if (!ok) {
      // A 401 from either backend is authoritative (both check the same H2 account).
      const badCreds = (gw.status === 'rejected' && is401(gw.reason))
                    || (ops.status === 'rejected' && is401(ops.reason));
      throw new Error(badCreds ? 'badcreds' : 'down');
    }
    await refresh();
  }, [refresh]);

  const logout = useCallback(async () => {
    await Promise.allSettled([api.post(`${BASE}/logout`), opsLogout()]);
    setUser(null);
    setGatewayUp(false);
    setOpsUp(false);
  }, []);

  // Mid-session gateway 401 (expired/invalidated session): re-probe instead of hard logout,
  // so an alive ops session keeps the user in degraded mode rather than kicking to /login.
  useEffect(() => { setUnauthorizedHandler(() => { refresh(); }); }, [refresh]);

  useEffect(() => { refresh(); }, [refresh]);

  return (
    <AuthContext.Provider value={{ user, loading, gatewayUp, opsUp, login, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}
```

- [ ] **Step 4: Route the login error kinds in LoginPage**

In `frontend/src/auth/LoginPage.tsx`:
- change `const [error, setError] = useState(false);` to `const [error, setError] = useState<'badcreds' | 'down' | null>(null);`
- change the catch block of `onFinish` to:

```tsx
    } catch (e) {
      setError((e as Error).message === 'down' ? 'down' : 'badcreds');
    } finally {
```

- change `setBusy(true); setError(false);` to `setBusy(true); setError(null);`
- change the Alert line to:

```tsx
        {error && <Alert type="error" message={t(error === 'down' ? 'auth.allDown' : 'auth.loginFailed')} style={{ marginBottom: 16 }} />}
```

- change the success navigation from `navigate('/settings', { replace: true });` to `navigate('/', { replace: true });` (the home redirect added in Task 6 picks the right page per mode).

- [ ] **Step 5: Add the i18n keys**

In `frontend/src/locales/uz.json` add (flat keys, keep alphabetical grouping with the other `auth.*` keys):

```json
  "auth.allDown": "Serverlarga ulanib bo'lmadi — gateway ham, ops sidecar ham javob bermayapti",
  "auth.gatewayDown": "Gateway o'chiq — faqat Ops sahifasi ishlaydi",
```

- [ ] **Step 6: Run the frontend suite**

Run: `cd frontend && npx vitest run`
Expected: ALL PASS (existing AuthContext tests may need their mocks extended with `opsMe` rejection defaults — set `(opsMe as Mock).mockRejectedValue(axiosDown)` in shared `beforeEach` so old tests keep their previous single-backend semantics).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/auth/AuthContext.tsx frontend/src/auth/LoginPage.tsx frontend/src/auth/AuthContext.test.tsx frontend/src/locales/uz.json
git commit -m "feat(ui): single login form authenticates gateway + ops sessions in parallel"
```

---

### Task 6: Frontend — degraded mode (nav gating + home redirect + banner)

**Files:**
- Modify: `frontend/src/layout/AppLayout.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/layout/AppLayout.test.tsx` (extend)
- Modify: `frontend/src/locales/uz.json` (nav keys)

**Interfaces:**
- Consumes: `gatewayUp`, `opsUp` from Task 5's `useAuth()`.
- Produces: nav item with key `/ops` (page arrives in Task 7); `<HomeRedirect />` component inside `App.tsx`.

- [ ] **Step 1: Write the failing tests**

Extend `frontend/src/layout/AppLayout.test.tsx` (follow its existing render/mock pattern for `useAuth`):

```tsx
it('disables gateway pages and shows banner when gateway is down', () => {
  // mock useAuth -> { user: 'admin', gatewayUp: false, opsUp: true, ... }
  // render AppLayout
  // expect menu items 'Sozlamalar', 'Xatolar', 'Statistika' to have aria-disabled
  // expect screen.getByText(/Gateway o'chiq/) banner
});

it('shows enabled Ops nav item when ops is up', () => {
  // mock useAuth -> { user: 'admin', gatewayUp: true, opsUp: true, ... }
  // expect an enabled menu item labeled 'Ops'
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/layout/AppLayout.test.tsx`
Expected: FAIL — no Ops item, no banner.

- [ ] **Step 3: Implement AppLayout changes**

In `frontend/src/layout/AppLayout.tsx`:

- add `ControlOutlined` to the `@ant-design/icons` import;
- add `Alert` to the `antd` import;
- destructure `const { user, logout, gatewayUp, opsUp } = useAuth();`
- replace the `navItems` array with:

```tsx
  const gwDisabled = !gatewayUp;
  const gwLabel = (label: string) =>
    gwDisabled ? <Tooltip title={t('auth.gatewayDown')}>{label}</Tooltip> : label;

  const navItems = [
    { key: '/settings', icon: <SettingOutlined />, disabled: gwDisabled, label: gwLabel(t('nav.settings')) },
    { key: '/errors', icon: <WarningOutlined />, disabled: gwDisabled, label: gwLabel(t('nav.errors')), children: [
        { key: '/errors/dlq', label: t('errors.dlqNav') },
        { key: '/errors/jar', label: t('errors.jarNav') },
        { key: '/errors/docker', label: t('errors.dockerNav') },
    ] },
    { key: '/statistics', icon: <BarChartOutlined />, disabled: gwDisabled, label: gwLabel(t('nav.statistics')) },
    { key: '/ops', icon: <ControlOutlined />, disabled: !opsUp, label: t('nav.ops') },
  ];
```

  (The old placeholder `logs` "coming soon" item is superseded by the real Ops page — remove it and drop `FileTextOutlined` from imports if now unused.)

- extend the menu `onClick` allowlist:

```tsx
            onClick={({ key }) => { if (key === '/settings' || key === '/statistics' || key === '/ops' || key.startsWith('/errors/')) navigate(key); }}
```

- render a banner just under `<Header>` (inside the outer `<Layout>`, before the inner `<Layout>`):

```tsx
      {!gatewayUp && <Alert banner type="warning" showIcon message={t('auth.gatewayDown')} />}
```

- [ ] **Step 4: Home redirect by mode in App.tsx**

In `frontend/src/App.tsx` add above `export default function App()`:

```tsx
import { useAuth } from './auth/AuthContext';

function HomeRedirect() {
  const { gatewayUp } = useAuth();
  return <Navigate to={gatewayUp ? '/settings' : '/ops'} replace />;
}
```

and change both fallback routes:

```tsx
                    <Route index element={<HomeRedirect />} />
                    ...
                    <Route path="*" element={<HomeRedirect />} />
```

- [ ] **Step 5: Add the i18n key**

In `frontend/src/locales/uz.json` add `"nav.ops": "Ops"` and remove the now-unused `"nav.logs"` / `"nav.comingSoon"` keys (verify no other usage first: `grep -rn "nav.logs\|nav.comingSoon" frontend/src`).

- [ ] **Step 6: Run the frontend suite**

Run: `cd frontend && npx vitest run`
Expected: ALL PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/layout/AppLayout.tsx frontend/src/layout/AppLayout.test.tsx frontend/src/App.tsx frontend/src/locales/uz.json
git commit -m "feat(ui): degraded mode — gateway-down banner, nav gating, mode-aware home redirect"
```

---

### Task 7: Frontend — Ops page (status, start/stop, live log)

**Files:**
- Create: `frontend/src/pages/ops/OpsPage.tsx`
- Create: `frontend/src/pages/ops/OpsPage.test.tsx`
- Modify: `frontend/src/App.tsx` (route)
- Modify: `frontend/src/locales/uz.json` (ops keys)

**Interfaces:**
- Consumes: `getOpsStatus/startGateway/stopGateway` + `ControlResult` from Task 4; `EventSource('/ops/api/logs/stream')` (server sends plain `data:` lines).
- Produces: route `/ops` inside the authenticated layout.

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/pages/ops/OpsPage.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { OpsPage } from './OpsPage';

vi.mock('../../api/ops', () => ({
  getOpsStatus: vi.fn().mockResolvedValue({ data: { up: true } }),
  startGateway: vi.fn().mockResolvedValue({ data: { ok: true, exitCode: 0, output: '' } }),
  stopGateway: vi.fn().mockResolvedValue({ data: { ok: true, exitCode: 0, output: '' } }),
}));
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ opsUp: true }) }));

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  onmessage: ((e: { data: string }) => void) | null = null;
  constructor(public url: string) { FakeEventSource.instances.push(this); }
  close() {}
  emit(line: string) { this.onmessage?.({ data: line }); }
}

beforeEach(() => { vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource); FakeEventSource.instances = []; });
afterEach(() => { vi.unstubAllGlobals(); });

it('shows UP badge from status poll', async () => {
  render(<OpsPage />);
  await waitFor(() => expect(screen.getByText('Ishlayapti')).toBeInTheDocument());
});

it('renders streamed log lines', async () => {
  render(<OpsPage />);
  await waitFor(() => expect(FakeEventSource.instances.length).toBe(1));
  FakeEventSource.instances[0].emit('hello-log-line');
  await waitFor(() => expect(screen.getByText('hello-log-line')).toBeInTheDocument());
});

it('stop asks for confirmation', async () => {
  render(<OpsPage />);
  await waitFor(() => screen.getByText('Ishlayapti'));
  (await screen.findByRole('button', { name: /To'xtatish/ })).click();
  await waitFor(() => expect(screen.getByText(/Gateway to'xtatilsinmi/)).toBeInTheDocument());
});
```

(If the project's test-setup lacks `@testing-library/jest-dom`, mirror whatever assertion style existing page tests like `frontend/src/pages/settings/*.test.tsx` use.)

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/pages/ops`
Expected: FAIL — module `./OpsPage` not found.

- [ ] **Step 3: Implement OpsPage**

Create `frontend/src/pages/ops/OpsPage.tsx`:

```tsx
import { useCallback, useEffect, useRef, useState } from 'react';
import { Badge, Button, Card, Modal, Space, Switch, Typography, message } from 'antd';
import { PoweroffOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getOpsStatus, startGateway, stopGateway, ControlResult } from '../../api/ops';

const MAX_LINES = 500;
const POLL_MS = 5000;

export function OpsPage() {
  const { t } = useTranslation();
  const [up, setUp] = useState<boolean | null>(null); // null = hali noma'lum
  const [busy, setBusy] = useState(false);
  const [paused, setPaused] = useState(false);
  const [lines, setLines] = useState<string[]>([]);
  const boxRef = useRef<HTMLDivElement>(null);
  const pausedRef = useRef(false);
  pausedRef.current = paused;

  // Gateway holati: 5s poll
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try { const r = await getOpsStatus(); if (alive) setUp(r.data.up); }
      catch { if (alive) setUp(null); }
    };
    tick();
    const id = setInterval(tick, POLL_MS);
    return () => { alive = false; clearInterval(id); };
  }, []);

  // Jonli log (SSE). EventSource uzilganda o'zi qayta ulanadi.
  useEffect(() => {
    const es = new EventSource('/ops/api/logs/stream');
    es.onmessage = (e) => {
      if (pausedRef.current) return;
      setLines((prev) => {
        const next = prev.length >= MAX_LINES ? prev.slice(prev.length - MAX_LINES + 1) : prev.slice();
        next.push(e.data);
        return next;
      });
    };
    return () => es.close();
  }, []);

  // Smart auto-scroll: faqat foydalanuvchi pastda turganda ergashamiz.
  useEffect(() => {
    const el = boxRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
    if (nearBottom) el.scrollTop = el.scrollHeight;
  }, [lines]);

  const runAction = useCallback(async (fn: () => Promise<{ data: ControlResult }>) => {
    setBusy(true);
    try {
      const r = await fn();
      if (!r.data.ok) message.error(r.data.output || t('ops.actionFailed'));
    } catch {
      message.error(t('ops.actionFailed'));
    } finally {
      setBusy(false);
    }
  }, [t]);

  const onToggle = () => {
    if (up) {
      Modal.confirm({
        title: t('ops.confirmStopTitle'),
        content: t('ops.confirmStopBody'),
        okText: t('ops.stop'),
        okButtonProps: { danger: true },
        cancelText: t('common.cancel'),
        onOk: () => runAction(stopGateway),
      });
    } else {
      runAction(startGateway);
    }
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card>
        <Space size={24} wrap>
          <Badge
            status={up === true ? 'success' : up === false ? 'error' : 'default'}
            text={up === true ? t('ops.statusUp') : up === false ? t('ops.statusDown') : t('ops.statusUnknown')}
          />
          <Button
            type="primary"
            danger={!!up}
            icon={<PoweroffOutlined />}
            loading={busy}
            onClick={onToggle}
          >
            {up ? t('ops.stop') : t('ops.start')}
          </Button>
        </Space>
      </Card>
      <Card
        title={t('ops.logTitle')}
        extra={<Space>{t('ops.pause')}<Switch size="small" checked={paused} onChange={setPaused} /></Space>}
      >
        <div
          ref={boxRef}
          data-testid="ops-log"
          style={{
            height: '55vh', overflow: 'auto', background: '#0b1220', color: '#d7e3f4',
            fontFamily: 'monospace', fontSize: 12, padding: 12, borderRadius: 6, whiteSpace: 'pre-wrap',
          }}
        >
          {lines.map((l, i) => <div key={i}>{l}</div>)}
          {lines.length === 0 && <Typography.Text type="secondary">{t('ops.logEmpty')}</Typography.Text>}
        </div>
      </Card>
    </Space>
  );
}
```

- [ ] **Step 4: Register the route**

In `frontend/src/App.tsx` add the import and route:

```tsx
import { OpsPage } from './pages/ops/OpsPage';
```

```tsx
                    <Route path="ops" element={<OpsPage />} />
```

(next to the `statistics` route).

- [ ] **Step 5: Add the i18n keys**

In `frontend/src/locales/uz.json` add:

```json
  "ops.title": "Ops — gateway boshqaruvi",
  "ops.statusUp": "Ishlayapti",
  "ops.statusDown": "O'chiq",
  "ops.statusUnknown": "Noma'lum",
  "ops.start": "Ishga tushirish",
  "ops.stop": "To'xtatish",
  "ops.confirmStopTitle": "Gateway to'xtatilsinmi?",
  "ops.confirmStopBody": "To'xtatilgan vaqtda kelgan so'rovlar Kafka navbatida kutib turadi va gateway qayta yoqilganda qayta ishlanadi.",
  "ops.actionFailed": "Amal bajarilmadi — ops jurnalini tekshiring",
  "ops.logTitle": "Jonli log (gateway.log)",
  "ops.pause": "Pauza",
  "ops.logEmpty": "Hozircha log satrlari yo'q",
  "common.cancel": "Bekor qilish"
```

(`common.cancel` — check first with `grep -n '"common.cancel"' frontend/src/locales/uz.json`; add only if missing.)

- [ ] **Step 6: Run the full frontend suite**

Run: `cd frontend && npx vitest run`
Expected: ALL PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/ops/ frontend/src/App.tsx frontend/src/locales/uz.json
git commit -m "feat(ui): Ops page — status badge, start/stop with confirm, live log via SSE"
```

---

### Task 8: Playwright E2E — ops flow over mocked APIs

**Files:**
- Create: `frontend/e2e/ops.spec.ts`

**Interfaces:**
- Consumes: routes/pages from Tasks 5-7. Mirrors the mocking style of `frontend/e2e/settings.spec.ts` (read it first and reuse its login-mock helper pattern).

- [ ] **Step 1: Read the existing E2E pattern**

Read `frontend/e2e/settings.spec.ts` and copy its route-mock + login helper approach (how it mocks `/qosid/admin/api/health`, `/me`, `/login`).

- [ ] **Step 2: Write the spec**

Create `frontend/e2e/ops.spec.ts`:

```ts
import { test, expect, Page } from '@playwright/test';

async function mockBackends(page: Page, { gatewayRunning = true } = {}) {
  // Gateway admin API (same-origin mocks)
  await page.route('**/qosid/admin/api/health', (r) => r.fulfill({ json: { status: 'UP' } }));
  await page.route('**/qosid/admin/api/login', (r) => r.fulfill({ status: 200, json: {} }));
  await page.route('**/qosid/admin/api/me', (r) => r.fulfill({ json: { username: 'admin' } }));
  // Ops API
  await page.route('**/ops/api/login', (r) => r.fulfill({ status: 200 }));
  await page.route('**/ops/api/me', (r) => r.fulfill({ json: { user: 'admin' } }));
  await page.route('**/ops/api/status', (r) => r.fulfill({ json: { up: gatewayRunning } }));
  await page.route('**/ops/api/stop', (r) => r.fulfill({ json: { ok: true, exitCode: 0, output: '' } }));
  await page.route('**/ops/api/start', (r) => r.fulfill({ json: { ok: true, exitCode: 0, output: '' } }));
  await page.route('**/ops/api/logs/stream', (r) => r.fulfill({
    status: 200,
    headers: { 'content-type': 'text/event-stream' },
    body: 'data: e2e-log-line\n\n',
  }));
}

async function login(page: Page) {
  await page.goto('/login');
  await page.getByLabel(/Login|Foydalanuvchi/i).fill('admin');
  await page.getByLabel(/Parol/i).fill('admin');
  await page.getByRole('button', { name: /Kirish/i }).click();
}

test('ops page: status, log stream, stop with confirmation', async ({ page }) => {
  await mockBackends(page);
  await login(page);
  await page.getByText('Ops', { exact: true }).click();

  await expect(page.getByText('Ishlayapti')).toBeVisible();
  await expect(page.getByText('e2e-log-line')).toBeVisible();

  await page.getByRole('button', { name: /To'xtatish/ }).click();
  await expect(page.getByText(/Gateway to'xtatilsinmi/)).toBeVisible();
  await page.getByRole('button', { name: /To'xtatish/ }).last().click();
});

test('degraded mode: gateway down -> lands on /ops with banner', async ({ page }) => {
  await mockBackends(page, { gatewayRunning: false });
  // Gateway is unreachable: override its routes to abort.
  await page.route('**/qosid/admin/api/**', (r) => r.abort());
  await login(page);

  await expect(page).toHaveURL(/\/ops$/);
  await expect(page.getByText(/Gateway o'chiq/)).toBeVisible();
});
```

Adjust the `login()` selectors to the exact labels used in `settings.spec.ts` — reuse its helper verbatim if one exists.

- [ ] **Step 3: Run the E2E suite**

Run: `cd frontend && npx playwright test e2e/ops.spec.ts`
Expected: PASS (2 tests). If the label-based selectors differ, fix selectors, not the app.

- [ ] **Step 4: Run all E2E to catch regressions**

Run: `cd frontend && npx playwright test`
Expected: ALL PASS (existing specs unaffected — their login mocks now also need `**/ops/api/me` + `**/ops/api/login` route mocks if AuthContext probing makes them flaky; add the two-line mock to their shared helper where needed).

- [ ] **Step 5: Commit**

```bash
git add frontend/e2e/ops.spec.ts frontend/e2e/*.spec.ts
git commit -m "test(ui): E2E for ops page + degraded mode over mocked APIs"
```

---

### Task 9: Vite proxy + nginx config + local demo scripts

**Files:**
- Modify: `frontend/vite.config.ts`
- Create: `deploy/nginx-qosid.conf`

**Interfaces:**
- Consumes: nothing new.
- Produces: dev proxy for `/ops/api`; committed reference nginx config for prod.

- [ ] **Step 1: Add the dev proxy**

In `frontend/vite.config.ts` change the `server.proxy` block to:

```ts
  server: {
    proxy: {
      '/qosid': 'http://localhost:8090',
      '/actuator': 'http://localhost:8090',
      // ATAYLAB '/ops/api' — '/ops' SPA'ning o'z sahifa-route'i, proxy qilinmasligi kerak.
      '/ops/api': 'http://localhost:8092',
    },
  },
```

- [ ] **Step 2: Write the nginx reference config**

Create `deploy/nginx-qosid.conf`:

```nginx
# Qosid Flow — yagona origin (C-loyiha, v1.13.0)
# SPA statik fayllardan, gateway :8090, ops sidecar :8092.
# Prod'da mavjud server-blokka moslashtiring (TLS sozlamalari o'zgarmaydi).

server {
    listen 443 ssl;
    server_name _;

    # ssl_certificate / ssl_certificate_key — mavjud qiymatlar saqlanadi

    # --- Statik SPA (React Router fallback bilan) ---
    root /opt/services/qosid-flow/frontend/dist;
    index index.html;

    location / {
        try_files $uri /index.html;
    }

    # --- Gateway API (tashqi /qosid/api/v1/queue klientlari ham shu yo'ldan) ---
    location /qosid/ {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 25m;   # BiruniUpload multipart
    }

    # --- Ops sidecar API (faqat /ops/api — /ops sahifasi SPA'niki) ---
    location /ops/api/ {
        proxy_pass http://127.0.0.1:8092;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # SSE (logs/stream) uchun bufferlashni o'chirish shart:
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 1h;
        proxy_set_header Connection '';
        proxy_http_version 1.1;
    }
}
```

- [ ] **Step 3: Smoke-check the dev proxy locally**

Run (all three running: gateway JAR, ops JAR, vite):
`cd frontend && npm run dev &` then `curl -s -o /dev/null -w '%{http_code}' http://localhost:5173/ops/api/status`
Expected: `401` (proxied to ops, unauthenticated) — NOT the SPA's HTML. Then stop the dev server.

- [ ] **Step 4: Commit**

```bash
git add frontend/vite.config.ts deploy/nginx-qosid.conf
git commit -m "feat(deploy): /ops/api dev proxy + reference nginx config for single-origin serving"
```

---

### Task 10: Docs + version 1.13.0

**Files:**
- Modify: `CLAUDE.md` (ops paragraph + endpoint table row for `/ops`)
- Modify: `ai-context/ARCHITECTURE.md` (Ops console section: SPA now nginx-served; ops API-only; cookie names)
- Modify: `ai-context/CONFIGURATION.md` (ops cookie config, nginx deploy note)
- Modify: `VERSION.md` (→ 1.13.0)

**Interfaces:** none — documentation of everything above.

- [ ] **Step 1: Ghost-reference sweep**

Run: `grep -rn "SpaForwardController\|static/ops/index.html\|OpsPageController\|copy-frontend-dist" ai-context/ CLAUDE.md docs/ --include=*.md | grep -v superpowers`
Fix every hit in `ai-context/` and `CLAUDE.md` (spec/plan files under `docs/superpowers/` stay as historical record).

- [ ] **Step 2: Update CLAUDE.md**

Rewrite the "Ops console sidecar" paragraph to state: SPA is served by nginx from `frontend/dist` (no longer embedded in the gateway JAR); ops sidecar is API-only (`/ops/api/*`, cookies `OPS_SESSION`/`OPS-XSRF-TOKEN`); the SPA's `/ops` page provides start/stop + live log; single login hits both backends; reference `deploy/nginx-qosid.conf`.

- [ ] **Step 3: Update ai-context docs**

- `ARCHITECTURE.md` → "Ops console (sidecar)" section: remove the HTML-page sentence, add the single-origin diagram (nginx `/` → dist, `/qosid/` → 8090, `/ops/api/` → 8092), degraded-mode login matrix summary, cookie rename rationale.
- `CONFIGURATION.md` → under `ops.*`: document `server.servlet.session.cookie.name=OPS_SESSION` default and the CSRF cookie/header names; add deploy note pointing at `deploy/nginx-qosid.conf`.

- [ ] **Step 4: Bump version**

Update `VERSION.md` to `1.13.0` following its existing format (read it first; keep the changelog style used by previous entries — one line: unified SPA + ops page + single login).

- [ ] **Step 5: Full verification**

Run: `mvn -q test && mvn -q -f ops/pom.xml test && cd frontend && npx vitest run && npx playwright test`
Expected: ALL PASS everywhere.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md ai-context/ VERSION.md
git commit -m "docs: unified SPA architecture + ops cookies; bump version to 1.13.0"
```

---

## Manual smoke checklist (after all tasks, local)

1. `docker-compose up -d`; run gateway JAR + ops JAR; `cd frontend && npm run dev`.
2. Open `http://localhost:5173/` → login `admin/admin` → lands on Settings (full mode).
3. Open Ops page → green badge, live log lines flowing.
4. Stop → confirm modal → gateway goes red; Settings/Errors/Statistics nav disabled; warning banner appears.
5. Start → badge green within ~20s; nav re-enables after next refresh/login probe.
6. Kill the gateway process manually, reload the SPA, login again → lands directly on `/ops` (degraded mode).
