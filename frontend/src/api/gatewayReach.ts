/**
 * Whether the gateway's admin API answered the last session probe.
 *
 * Module-level rather than React state on purpose: the api layer has no access to context,
 * and mirroring `setUnauthorizedHandler` in `client.ts` keeps the pattern consistent.
 * `AuthContext.refresh()` is the single writer; the observability api modules are readers,
 * choosing between the gateway's admin routes and the ops sidecar's twins.
 */
let reachable = true;

export function setGatewayReachable(up: boolean): void {
  reachable = up;
}

export function isGatewayReachable(): boolean {
  return reachable;
}
