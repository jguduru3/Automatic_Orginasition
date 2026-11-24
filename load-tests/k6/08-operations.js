// Godown transfers racing orders, FEFO batch orders on the pharma tenant, purchase receipts and GPS pings, all at once.
import { check } from "k6";
import { Counter } from "k6/metrics";
import { get, login, post } from "./lib.js";

const unexpected = new Counter("unexpected_errors");
export const options = {
  scenarios: {
    transfers: { executor: "constant-vus", vus: 10, duration: "30s", exec: "transfers" },
    pharmaOrders: { executor: "constant-vus", vus: 15, duration: "30s", exec: "pharmaOrders" },
    receipts: { executor: "per-vu-iterations", vus: 5, iterations: 4, exec: "receipts", maxDuration: "60s" },
    gps: { executor: "constant-arrival-rate", rate: 40, timeUnit: "1s", duration: "30s", preAllocatedVUs: 20, exec: "gps" },
  },
  thresholds: { unexpected_errors: ["count==0"], checks: ["rate>0.98"], http_req_duration: ["p(95)<1500"] },
};

const ok = (r, allowed, name) => { if (!allowed.includes(r.status)) unexpected.add(1, { name, status: String(r.status) }); };

export function setup() {
  const owner = login(), pharma = login("owner@pharma.demo.in");
  const g = get(owner, "/api/godowns").json();
  const items = get(owner, "/api/inventory").json();
  const pItems = get(pharma, "/api/inventory").json();
  const pCustomers = get(pharma, "/api/customers").json().filter(c => c.name === "Shree Ganesh Medical" || c.name === "Sahyadri Nursing Home");
  for (const p of pItems.filter(x => x.batchTracked)) {
    post(pharma, `/api/products/${p.id}/stock`, { delta: 5000, reason: "load", batchNo: "LOAD-" + p.sku, expiryDate: "2028-12-31" });
  }
  const supplier = get(pharma, "/api/suppliers").json()[0].id;
  const pcm = pItems.find(p => p.sku === "PCM650").id;
  const pos = [];
  for (let i = 0; i < 5; i++) {
    const r = post(pharma, "/api/purchases", { supplierId: supplier, lines: [{ productId: pcm, qty: 100, rate: 21 }] });
    post(pharma, `/api/purchases/${r.json("id")}/send`, {});
    pos.push(r.json("id"));
  }
  return { owner, pharma, godowns: g.map(x => x.id), w15: items.find(i => i.sku === "W15").id, pItems, pCustomers, pos,
    sales: login("amit@demo.in"), driver: login("raju@demo.in") };
}

export function transfers(d) {
  const [a, b] = __ITER % 2 ? [d.godowns[0], d.godowns[1]] : [d.godowns[1], d.godowns[0]];
  ok(post(d.owner, "/api/transfers", { fromGodownId: a, toGodownId: b, lines: [{ productId: d.w15, qty: 1 }] }, "transfer"), [200, 409], "transfer");
}

export function pharmaOrders(d) {
  const c = d.pCustomers[__ITER % d.pCustomers.length];
  const lines = d.pItems.filter(p => p.sku === "PCM650" || p.sku === "AMX500").map(p => ({ productId: p.id, qty: 1 + (__ITER % 7) }));
  ok(post(d.pharma, "/api/orders", { customerId: c.id, source: "PHONE", lines }, "pharma order"), [200, 409, 422], "pharma order");
}

export function receipts(d) {
  const id = d.pos[(__VU - 1) % d.pos.length];
  const po = get(d.pharma, `/api/purchases/${id}`).json();
  const line = po.lines[0];
  ok(post(d.pharma, `/api/purchases/${id}/receive`, { lines: [{ poLineId: line.id, qty: 25, batchNo: `K6-${id}`, expiryDate: "2028-06-30" }] }, "receive"),
    [200, 400, 409], "receive");
}

export function gps(d) {
  const token = __ITER % 2 ? d.sales : d.driver;
  ok(post(token, "/api/field/location", { lat: 18.5 + Math.random() / 50, lng: 73.85 + Math.random() / 50, accuracy: 15 }, "gps"), [200], "gps");
}

export function teardown(d) {
  for (const [t, name] of [[d.owner, "electricals"], [d.pharma, "pharma"]]) {
    const inv = get(t, "/api/inventory").json();
    check(inv, { [`${name}: stock consistent`]: x => x.every(p => p.onHand >= 0 && p.reserved >= 0 && p.free >= 0) });
  }
  for (const id of d.pos) {
    const po = get(d.pharma, `/api/purchases/${id}`).json();
    check(po, { "PO never over-received": p => p.lines.every(l => l.receivedQty <= l.qty) });
  }
  const exp = get(d.pharma, "/api/inventory/expiring?days=0").json();
  check(exp, { "expired batches untouched by orders": x => x.every(b => b.reserved === 0) });
}
