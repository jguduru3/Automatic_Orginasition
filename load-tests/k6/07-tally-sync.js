// Concurrent manual syncs while new vouchers keep arriving: each voucher must be posted exactly once.
import { check } from "k6";
import { get, login, newCustomer, post } from "./lib.js";

export const options = {
  scenarios: {
    writers: { executor: "constant-vus", vus: 10, duration: "30s", exec: "write" },
    syncers: { executor: "constant-vus", vus: 10, duration: "30s", exec: "sync" },
  },
  thresholds: { checks: ["rate>0.99"] },
};

export function setup() {
  const t = login("accounts@demo.in");
  return { t, c: newCustomer(login()) };
}

export function write(d) {
  check(post(d.t, "/api/payments", { customerId: d.c.id, amount: 10, mode: "CASH" }, "payment"), { "payment 200": r => r.status === 200 });
}

export function sync(d) {
  check(post(d.t, "/api/tally/sync", {}, "tally sync"), { "sync 200": r => r.status === 200 });
}

export function teardown(d) {
  post(d.t, "/api/tally/sync", {});
  const s = get(d.t, "/api/tally").json();
  const refs = s.log.map(v => v.ref);
  check(refs, { "each voucher posted once": r => new Set(r).size === r.length });
  const vch = s.log.map(v => v.voucherNo);
  check(vch, { "voucher numbers unique": v => new Set(v).size === v.length });
}
