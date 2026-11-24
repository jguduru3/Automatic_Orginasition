import http from "k6/http";
import { check, fail } from "k6";

export const BASE = __ENV.BASE_URL || "http://localhost:8080";

export function login(email = "owner@demo.in", password = "Demo@1234") {
  const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password }),
    { headers: { "Content-Type": "application/json" }, tags: { name: "login" } });
  if (r.status !== 200) fail(`login ${email} failed: ${r.status} ${r.body}`);
  return r.json("token");
}

export const auth = (token, name) => ({ headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, tags: { name } });

export function get(token, path, name = path) {
  const r = http.get(`${BASE}${path}`, auth(token, name));
  check(r, { [`${name} 200`]: x => x.status === 200 });
  return r;
}

export function post(token, path, body, name = path) {
  return http.post(`${BASE}${path}`, JSON.stringify(body ?? {}), auth(token, name));
}

let phoneSeq = 0;
export function uniquePhone() {
  phoneSeq++;
  return "9180" + String(Date.now() % 1e6).padStart(6, "0") + String((__VU * 1000 + phoneSeq) % 100).padStart(2, "0");
}

export function newCustomer(token, tier = "A", limit = 100000000) {
  for (let i = 0; i < 5; i++) {
    const phone = "91" + String(7000000000 + Math.floor(Math.random() * 2999999999));
    const r = post(token, "/api/customers", { name: `Load ${phone}`, town: "Pune", phone, tierCode: tier, creditLimit: limit }, "create customer");
    if (r.status === 200) return { id: r.json("id"), phone };
  }
  fail("could not create customer");
}

export function product(token, sku) {
  return get(token, "/api/inventory", "inventory").json().find(p => p.sku === sku);
}
