import { FormEvent, useMemo, useState } from "react";
import { api, idempotencyKey, post } from "../api";
import { Kpi, Loading, Modal, PageTop, Pill, useAction, useApi } from "../components/ui";
import { inr, lakh, n, time } from "../format";

interface Aging { customerId: number; customer: string; salesperson?: string; d0: number; d30: number; d60: number; d90: number; total: number; oldestDays: number }
interface Receipt { id: number; receipt: string; customer: string; amount: number; mode: string; receivedAt: string; appliedTo?: string }

export default function Collections() {
  const aging = useApi(s => api<Aging[]>("/api/collections", { signal: s }));
  const today = useApi(s => api<Receipt[]>("/api/payments/today", { signal: s }));
  const [pay, setPay] = useState<Aging | null>(null);
  const { busy, run } = useAction();
  const reload = () => { aging.reload(); today.reload(); };
  if (!aging.data) return <Loading error={aging.error} onRetry={aging.reload} />;
  const rows = aging.data;
  const total = rows.reduce((s, r) => s + n(r.total), 0);
  const over60 = rows.reduce((s, r) => s + n(r.d60) + n(r.d90), 0);
  const todayTotal = (today.data ?? []).reduce((s, r) => s + n(r.amount), 0);
  return (
    <>
      <PageTop title="Collections" sub="Sorted by amount overdue. Receipts clear the oldest bills first." />
      <div className="grid g-kpi" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(180px,1fr))" }}>
        <Kpi label="Total receivable" value={lakh(total)} detail={`${rows.length} customers`} />
        <Kpi label="Over 60 days" value={lakh(over60)} detail={over60 > 0 ? <Pill tone="bad">Follow up today</Pill> : <Pill tone="good">None</Pill>} />
        <Kpi label="Collected today" value={inr(todayTotal)} detail={`${today.data?.length ?? 0} receipts`} />
      </div>
      <div className="card"><div className="tbl-wrap"><table>
        <thead><tr><th>Customer</th><th className="r">0–30 d</th><th className="r">31–60 d</th><th className="r">61–90 d</th><th className="r">90+ d</th><th className="r">Total</th><th /></tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={7} className="empty">Nothing outstanding.</td></tr>}
          {rows.map(r => (<tr key={r.customerId}>
            <td>{r.customer}<span className="sub">{r.salesperson}</span></td>
            {[r.d0, r.d30, r.d60, r.d90].map((v, i) => <td key={i} className="r num" style={n(v) && i >= 2 ? { color: "var(--bad)", fontWeight: 600 } : undefined}>{n(v) ? inr(v) : "–"}</td>)}
            <td className="r num"><b>{inr(r.total)}</b></td>
            <td><div className="row-actions">
              <button className="btn sm" disabled={busy} onClick={() => run(() => post(`/api/customers/${r.customerId}/remind`), `Reminder sent to ${r.customer}`).catch(() => {})}>Send reminder</button>
              <button className="btn pri sm" onClick={() => setPay(r)}>Record payment</button></div></td>
          </tr>))}
        </tbody>
      </table></div></div>
      {today.data && today.data.length > 0 && <div className="card"><div className="card-h"><h2>Receipts today</h2></div><div className="tbl-wrap"><table><tbody>
        {today.data.map(p => <tr key={p.id}><td className="num">{time(p.receivedAt)}</td><td>{p.receipt}</td><td>{p.customer}</td><td>{p.mode}</td><td className="r num">{inr(p.amount)}</td><td className="sub">{p.appliedTo ? "Against " + p.appliedTo : "Advance"}</td></tr>)}
      </tbody></table></div></div>}
      {pay && <PayModal row={pay} onClose={() => setPay(null)} onDone={() => { setPay(null); reload(); }} />}
    </>
  );
}

function PayModal({ row, onClose, onDone }: { row: Aging; onClose: () => void; onDone: () => void }) {
  const [amount, setAmount] = useState(String(Math.round(n(row.total))));
  const [mode, setMode] = useState("UPI");
  const [ref, setRef] = useState("");
  const key = useMemo(() => idempotencyKey(), []); // one key per modal: retries and double taps are recorded once
  const { busy, run } = useAction();
  const v = Number(amount);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!(v > 0)) return;
    await run(() => post<{ receipt: string; appliedTo: string[] }>("/api/payments", { customerId: row.customerId, amount: v, mode, reference: ref || undefined, idempotencyKey: key }),
      r => `${r.receipt} saved${r.appliedTo.length ? " against " + r.appliedTo.join(", ") : " as advance"}. Queued for Tally.`).then(onDone).catch(() => {});
  }
  return (
    <Modal title="Record payment" onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 12 }}>
        <p style={{ margin: 0, color: "var(--ink-2)" }}>{row.customer} · outstanding {inr(row.total)}</p>
        <label className="field" htmlFor="pamt">Amount (₹)<input id="pamt" type="number" min={1} step="0.01" required value={amount} onChange={e => setAmount(e.target.value)} autoFocus /></label>
        <label className="field" htmlFor="pmode">Mode<select id="pmode" value={mode} onChange={e => setMode(e.target.value)}><option>UPI</option><option>NEFT</option><option>CHEQUE</option><option>CASH</option></select></label>
        <label className="field" htmlFor="pref">Reference (UTR / cheque no.)<input id="pref" maxLength={100} value={ref} onChange={e => setRef(e.target.value)} /></label>
        {v > n(row.total) && <div className="sub">₹{Math.round(v - n(row.total))} more than outstanding will be kept as advance.</div>}
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy || !(v > 0)}>{busy ? "Saving…" : "Save receipt"}</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}
