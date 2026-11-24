import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, getToken, setToken } from "../api";
import { mockApi } from "./mockApi";

describe("api client", () => {
  beforeEach(() => setToken(null));

  it("sends the bearer token and JSON body", async () => {
    setToken("abc");
    const calls = mockApi({ "POST /api/x": { ok: true } });
    await api("/api/x", { method: "POST", body: { a: 1 } });
    expect(calls[0].body).toEqual({ a: 1 });
    const init = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][1];
    expect(init.headers.Authorization).toBe("Bearer abc");
  });

  it("maps server errors to ApiError with message and code", async () => {
    mockApi({ "GET /api/y": () => ({ status: 422, json: { code: "CREDIT_HOLD", message: "Over limit" } }) });
    await expect(api("/api/y")).rejects.toMatchObject({ status: 422, code: "CREDIT_HOLD", message: "Over limit" });
  });

  it("clears the session on 401 and fires logout", async () => {
    setToken("expired");
    const spy = vi.fn();
    window.addEventListener("auth:logout", spy);
    mockApi({ "GET /api/me": () => ({ status: 401 }) });
    await expect(api("/api/me")).rejects.toBeInstanceOf(ApiError);
    expect(getToken()).toBeNull();
    expect(spy).toHaveBeenCalled();
  });

  it("reports network failures in plain words", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))));
    await expect(api("/api/z")).rejects.toMatchObject({ code: "NETWORK" });
  });

  it("tolerates non-JSON error bodies", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html>502 Bad Gateway</html>", { status: 502 })));
    await expect(api("/api/z")).rejects.toMatchObject({ status: 502 });
  });
});
