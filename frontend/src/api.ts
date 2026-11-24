export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

const KEY = "dos.token";
let memoryToken: string | null = null;

export function getToken(): string | null {
  try { return sessionStorage.getItem(KEY) ?? memoryToken; } catch { return memoryToken; }
}
export function setToken(t: string | null) {
  memoryToken = t;
  try { if (t) sessionStorage.setItem(KEY, t); else sessionStorage.removeItem(KEY); } catch { /* storage blocked */ }
}

type Opts = { method?: string; body?: unknown; form?: FormData; signal?: AbortSignal };

export async function api<T>(path: string, opts: Opts = {}): Promise<T> {
  const headers: Record<string, string> = {};
  const t = getToken();
  if (t) headers.Authorization = `Bearer ${t}`;
  let body: BodyInit | undefined;
  if (opts.form) body = opts.form;
  else if (opts.body !== undefined) { headers["Content-Type"] = "application/json"; body = JSON.stringify(opts.body); }
  let res: Response;
  try {
    res = await fetch(path, { method: opts.method ?? (body ? "POST" : "GET"), headers, body, signal: opts.signal });
  } catch (e) {
    if ((e as Error).name === "AbortError") throw e;
    throw new ApiError(0, "NETWORK", "Can't reach the server. Check your connection and try again.");
  }
  if (res.status === 401 && path !== "/api/auth/login") {
    setToken(null);
    window.dispatchEvent(new Event("auth:logout"));
  }
  const text = await res.text();
  let data: unknown = null;
  if (text) { try { data = JSON.parse(text); } catch { data = text; } }
  if (!res.ok) {
    const d = (data ?? {}) as { code?: string; message?: string };
    throw new ApiError(res.status, d.code ?? "HTTP_" + res.status, d.message ?? `Request failed (${res.status})`);
  }
  return data as T;
}

export type Role = "OWNER" | "ACCOUNTANT" | "SALES" | "DELIVERY";
export interface User { id: number; name: string; role: Role; tenantName: string; simulateEnabled?: boolean }

export const post = <T,>(path: string, body: unknown = {}) => api<T>(path, { method: "POST", body });
export const put = <T,>(path: string, body: unknown) => api<T>(path, { method: "PUT", body });

/** Random key so a retried request (double tap, flaky network) is recorded once. */
export function idempotencyKey(): string {
  const c = globalThis.crypto;
  if (c && "randomUUID" in c) return c.randomUUID();
  return Date.now().toString(36) + Math.random().toString(36).slice(2);
}

/** Downloads a protected file (POD photo, voice note) as an object URL. Caller revokes it. */
export async function fileUrl(path: string): Promise<string> {
  const t = getToken();
  const res = await fetch(`/api/files?path=${encodeURIComponent(path)}`, { headers: t ? { Authorization: `Bearer ${t}` } : {} });
  if (!res.ok) throw new ApiError(res.status, "FILE", res.status === 404 ? "File not found" : "Could not open the file");
  return URL.createObjectURL(await res.blob());
}
