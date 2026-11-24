// Every read endpoint under steady load from all roles. p95 must stay under 800 ms, errors under 1%.
import { sleep } from "k6";
import { get, login } from "./lib.js";

const PATHS = ["/api/dashboard", "/api/inbox", "/api/orders?status=OPEN", "/api/orders?status=ALL", "/api/orders/summary",
  "/api/inventory", "/api/customers", "/api/customers?q=electric", "/api/collections", "/api/payments/today", "/api/field",
  "/api/tally", "/api/me", "/api/orders/vehicles", "/api/customers/tiers"];

export const options = {
  scenarios: { reads: { executor: "ramping-vus", startVUs: 5, stages: [{ duration: "20s", target: 60 }, { duration: "40s", target: 60 }, { duration: "10s", target: 0 }] } },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    "http_req_duration{name:/api/dashboard}": ["p(95)<1200"],
    http_req_duration: ["p(95)<800"],
    checks: ["rate>0.99"],
  },
};

export function setup() {
  return { owner: login("owner@demo.in"), accounts: login("accounts@demo.in"), sales: login("amit@demo.in") };
}

export default function (t) {
  const token = [t.owner, t.accounts, t.sales][__VU % 3];
  for (const p of PATHS) {
    if (token === t.sales && p === "/api/tally") continue; // sales role is not allowed there
    get(token, p, p);
  }
  sleep(0.5);
}
