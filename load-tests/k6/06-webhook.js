// WhatsApp webhook flood with retries (same message id) and bad signatures. Dedup must hold, nothing may 5xx.
import http from "k6/http";
import crypto from "k6/crypto";
import { check } from "k6";
import { Counter } from "k6/metrics";
import { BASE, get, login } from "./lib.js";

const SECRET = __ENV.WHATSAPP_APP_SECRET || "local-webhook-secret";
const errors = new Counter("unexpected_errors");
export const options = {
  scenarios: { hooks: { executor: "constant-arrival-rate", rate: 80, timeUnit: "1s", duration: "30s", preAllocatedVUs: 40 } },
  thresholds: { unexpected_errors: ["count==0"], http_req_duration: ["p(95)<800"] },
};

export function setup() { return { run: Date.now() % 1e6, owner: login() }; }

export default function (d) {
  const n = __ITER % 400; // each message id is sent several times (Meta retries)
  const phone = "9186" + String(d.run).padStart(6, "0") + String(n % 50).padStart(2, "0");
  const body = JSON.stringify({ entry: [{ changes: [{ value: { metadata: { phone_number_id: "demo-phone-id" },
    messages: [{ from: phone, id: `wamid.k6.${d.run}.${n}`, type: "text", text: { body: `32A MCB ${1 + (n % 9)} pcs\nनमस्ते` } }] } }] }] });
  const bad = __ITER % 10 === 0;
  const sig = "sha256=" + crypto.hmac("sha256", bad ? "wrong" : SECRET, body, "hex");
  const r = http.post(`${BASE}/api/webhooks/whatsapp`, body, { headers: { "Content-Type": "application/json", "X-Hub-Signature-256": sig }, tags: { name: "webhook" } });
  const expected = bad ? 401 : 200;
  if (r.status !== expected) errors.add(1, { status: String(r.status) });
}

export function teardown(d) {
  const convs = get(d.owner, "/api/inbox").json().filter(c => c.phone.startsWith("9186" + String(d.run).padStart(6, "0")));
  check(convs, { "one conversation per sender": c => new Set(c.map(x => x.phone)).size === c.length });
  if (convs.length) {
    const msgs = get(d.owner, `/api/inbox/${convs[0].id}`).json().messages;
    check(msgs, { "retried messages stored once": m => m.length <= 8 });
  }
}
