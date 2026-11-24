// Login burst, forged/expired tokens, role violations, malformed input. Nothing may 5xx.
import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";
import { BASE, login } from "./lib.js";

const fiveHundreds = new Counter("server_errors");
export const options = {
  scenarios: { abuse: { executor: "constant-vus", vus: 20, duration: "30s" } },
  thresholds: { server_errors: ["count==0"], checks: ["rate>0.99"] },
};

export function setup() { return { driver: login("raju@demo.in"), owner: login() }; }

function req(method, path, body, token, ct = "application/json") {
  const h = { "Content-Type": ct };
  if (token) h.Authorization = `Bearer ${token}`;
  const r = http.request(method, `${BASE}${path}`, body, { headers: h, tags: { name: "abuse" } });
  if (r.status >= 500) fiveHundreds.add(1, { path });
  return r;
}

export default function (d) {
  check(req("POST", "/api/auth/login", JSON.stringify({ email: "owner@demo.in", password: "wrong" })), { "bad password 401/429": r => r.status === 401 || r.status === 429 });
  check(req("GET", "/api/dashboard", null, "not.a.jwt"), { "forged token 401": r => r.status === 401 });
  check(req("GET", "/api/dashboard", null, "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwidGlkIjoxLCJyb2xlIjoiT1dORVIifQ.AAAA"), { "bad signature 401": r => r.status === 401 });
  check(req("GET", "/api/tally", null, d.driver), { "driver cannot see tally 403": r => r.status === 403 });
  check(req("POST", "/api/payments", JSON.stringify({ customerId: 1, amount: 10, mode: "UPI" }), d.driver), { "driver cannot take payments 403": r => r.status === 403 });
  check(req("POST", "/api/orders", "{not json", d.owner), { "malformed json 400": r => r.status === 400 });
  check(req("POST", "/api/orders", JSON.stringify({ customerId: 1, source: "PHONE", lines: [{ productId: 1, qty: -5 }] }), d.owner), { "negative qty 400": r => r.status === 400 });
  check(req("POST", "/api/payments", JSON.stringify({ customerId: 1, amount: 1e15, mode: "UPI" }), d.owner), { "absurd amount 400": r => r.status === 400 });
  check(req("GET", "/api/orders?status=1%27%20OR%201%3D1", null, d.owner), { "sql-ish filter 400": r => r.status === 400 });
  check(req("GET", "/api/customers?q=%27%3B%20DROP%20TABLE%20customers%3B--", null, d.owner), { "search injection is just text": r => r.status === 200 });
  check(req("GET", "/api/files?path=tenants/1/../../etc/passwd", null, d.owner), { "path traversal 404": r => r.status === 404 });
  check(req("GET", "/api/orders/999999999", null, d.owner), { "missing order 404": r => r.status === 404 });
  check(req("POST", "/api/inbox/simulate", JSON.stringify({ phone: "91", text: "x" }), d.owner), { "bad phone 400": r => r.status === 400 });
}
