// Many salespeople convert WhatsApp messages at once, all competing for the same scarce SKUs.
// Invariants checked at the end: no SKU reserved beyond stock, every confirm either succeeds or returns a clean 4xx.
import { check } from "k6";
import { Counter } from "k6/metrics";
import { get, login, newCustomer, post } from "./lib.js";

const unexpected = new Counter("unexpected_errors");
const MESSAGES = ["RCCB 40A 4 pole x 3\n1.5 wire 2 coil", "exhaust fan 2 pcs\n32A MCB 20 pcs", "DB 8 way - 2\n63A DP MCB 4 nos", "LED 9W 5 pkt, 6A switch 2 box"];

export const options = {
  scenarios: { storm: { executor: "constant-vus", vus: 40, duration: "45s" } },
  thresholds: { unexpected_errors: ["count==0"], http_req_duration: ["p(95)<1500"], checks: ["rate>0.98"] },
};

export function setup() {
  const owner = login();
  const customers = [];
  for (let i = 0; i < 40; i++) customers.push(newCustomer(owner));
  return { owner, customers };
}

export default function (d) {
  const c = d.customers[(__VU - 1) % d.customers.length];
  const sim = post(d.owner, "/api/inbox/simulate", { phone: c.phone, text: MESSAGES[__ITER % MESSAGES.length] }, "simulate");
  check(sim, { "simulate 200": r => r.status === 200 });
  const conv = get(d.owner, "/api/inbox", "inbox").json().find(x => x.phone === c.phone);
  if (!conv || conv.status !== "NEW") return;
  const draft = get(d.owner, `/api/inbox/${conv.id}/draft`, "draft").json();
  const lines = (draft.lines || []).filter(l => l.productId).map(l => ({ productId: l.productId, qty: l.qty }));
  if (!lines.length) return;
  const r = post(d.owner, `/api/inbox/${conv.id}/confirm`, { lines }, "confirm");
  // 200 ok; 409 = already handled or out of stock; 422 = credit hold. Anything else is a bug.
  const ok = [200, 409, 422].includes(r.status);
  if (!ok) unexpected.add(1, { status: String(r.status) });
  check(r, { "confirm clean outcome": () => ok });
}

export function teardown(d) {
  const inv = get(d.owner, "/api/inventory", "inventory").json();
  const bad = inv.filter(p => p.reserved > p.onHand || p.free < 0);
  check(bad, { "no SKU oversold": b => b.length === 0 });
  if (bad.length) console.error("OVERSOLD: " + JSON.stringify(bad));
}
