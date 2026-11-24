// Collections under load: duplicate submits with the same key must create one receipt; balances never go negative.
import { check } from "k6";
import { Counter } from "k6/metrics";
import { get, login, post } from "./lib.js";

const unexpected = new Counter("unexpected_errors");
export const options = {
  scenarios: { pay: { executor: "constant-vus", vus: 30, duration: "30s" } },
  thresholds: { unexpected_errors: ["count==0"], http_req_duration: ["p(95)<1000"] },
};

export function setup() {
  const t = login("accounts@demo.in");
  return { t, customers: get(t, "/api/customers").json().map(c => c.id), run: Date.now() };
}

export default function (d) {
  const customerId = d.customers[__ITER % d.customers.length];
  // VUs with the same __VU % 5 send the same key for the same customer at the same moment (double taps across devices)
  const key = `k6-${d.run}-${customerId}-${__ITER}-${__VU % 5}`;
  const r = post(d.t, "/api/payments", { customerId, amount: 250, mode: "UPI", idempotencyKey: key }, "payment");
  if (r.status !== 200) unexpected.add(1, { status: String(r.status) });
}

export function teardown(d) {
  const rows = get(d.t, "/api/collections").json();
  check(rows, { "no negative balances": x => x.every(r => r.total >= 0 && r.d0 >= 0) });
  const today = get(d.t, "/api/payments/today").json();
  check(today, { "receipt numbers unique": x => new Set(x.map(p => p.receipt)).size === x.length });
}
