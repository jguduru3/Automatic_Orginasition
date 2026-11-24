// Pack, dispatch and deliver many orders concurrently, including racing the same order from two users.
import { check } from "k6";
import http from "k6/http";
import { Counter } from "k6/metrics";
import { BASE, get, login, newCustomer, post } from "./lib.js";

const unexpected = new Counter("unexpected_errors");
export const options = {
  scenarios: { fulfil: { executor: "per-vu-iterations", vus: 30, iterations: 4, maxDuration: "2m" } },
  thresholds: { unexpected_errors: ["count==0"], checks: ["rate>0.98"] },
};

export function setup() {
  const owner = login();
  get(owner, "/api/inventory");
  const pid = get(owner, "/api/inventory").json().find(p => p.sku === "M16").id;
  post(owner, `/api/products/${pid}/stock`, { delta: 5000, reason: "load test" });
  const c = newCustomer(owner);
  const orders = [];
  for (let i = 0; i < 60; i++) {
    const r = post(owner, "/api/orders", { customerId: c.id, source: "PHONE", lines: [{ productId: pid, qty: 3 }] }, "create order");
    if (r.status === 200) orders.push(r.json("id"));
  }
  const vehicle = get(owner, "/api/orders/vehicles").json()[0].id;
  return { owner, driver: login("raju@demo.in"), orders, vehicle };
}

function step(token, id, path, body) {
  const r = post(token, `/api/orders/${id}/${path}`, body, path);
  const ok = r.status === 200 || r.status === 409; // 409 = someone else already moved it
  if (!ok) unexpected.add(1, { path, status: String(r.status) });
  return r;
}

export default function (d) {
  // two VUs share each order so pack/dispatch/deliver race
  const id = d.orders[((__VU - 1) * 2 + __ITER) % d.orders.length];
  const token = __VU % 2 ? d.owner : d.driver;
  step(token, id, "pack", {});
  step(token, id, "dispatch", { vehicleId: d.vehicle });
  const r = http.post(`${BASE}/api/orders/${id}/deliver`, {}, { headers: { Authorization: `Bearer ${token}` }, tags: { name: "deliver" } });
  if (![200, 409].includes(r.status)) unexpected.add(1, { path: "deliver", status: String(r.status) });
}

export function teardown(d) {
  const orders = get(d.owner, "/api/orders?status=ALL&limit=500").json().filter(o => d.orders.includes(o.id));
  const invoiced = orders.filter(o => o.invoice).map(o => o.invoice);
  check(invoiced, { "one invoice per order": x => new Set(x).size === x.length });
  const inv = get(d.owner, "/api/inventory").json();
  check(inv, { "stock consistent": x => x.every(p => p.onHand >= 0 && p.reserved >= 0 && p.reserved <= p.onHand) });
}
