import { vi } from "vitest";

type Handler = (body: unknown, url: string) => { status?: number; json?: unknown };
export type Routes = Record<string, Handler | unknown>;

/** Installs a fetch mock. Keys are "METHOD /path" (query string ignored unless included in the key). */
export function mockApi(routes: Routes) {
  const calls: { method: string; url: string; body: unknown }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? "GET").toUpperCase();
    let body: unknown = undefined;
    if (typeof init?.body === "string") body = JSON.parse(init.body);
    else if (init?.body instanceof FormData) body = init.body;
    calls.push({ method, url, body });
    const path = url.split("?")[0];
    const key = [`${method} ${url}`, `${method} ${path}`].find(k => k in routes);
    if (!key) return new Response(JSON.stringify({ code: "NOT_MOCKED", message: `No mock for ${method} ${url}` }), { status: 404 });
    const r = routes[key];
    const out = typeof r === "function" ? (r as Handler)(body, url) : { json: r };
    return new Response(out.json === undefined ? "" : JSON.stringify(out.json), { status: out.status ?? 200 });
  });
  vi.stubGlobal("fetch", fetchMock);
  return calls;
}
