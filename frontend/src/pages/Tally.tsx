import { api, post } from "../api";
import { Kpi, Loading, PageTop, Pill, useAction, useApi } from "../components/ui";
import { inr, time } from "../format";

interface V { id: number; type: string; ref: string; party: string; amount: number; attempts?: number; lastError?: string; voucherNo?: string; postedAt?: string }
interface Status { mode: string; company?: string; autoSync: boolean; mapping: Record<string, string>; queue: V[]; failed: V[]; log: V[]; postedToday: number }

export default function Tally() {
  const s = useApi(sig => api<Status>("/api/tally", { signal: sig }));
  const { busy, run } = useAction();
  if (!s.data) return <Loading error={s.error} onRetry={s.reload} />;
  const d = s.data;
  const sync = () => run(() => post<{ posted: number; failed: number }>("/api/tally/sync"),
    r => `${r.posted} voucher${r.posted === 1 ? "" : "s"} posted to Tally${r.failed ? `, ${r.failed} failed` : ""}`).then(s.reload).catch(() => {});
  return (
    <>
      <PageTop title="Tally sync" sub={`${d.mode === "http" ? "Connected to TallyPrime" : "Mock mode (no Tally connected)"}${d.company ? ` · ${d.company}` : ""}${d.autoSync ? " · auto-sync every minute" : ""}`}>
        <button className="btn pri" disabled={busy || d.queue.length === 0} onClick={sync}>{busy ? "Posting…" : `Push ${d.queue.length} voucher${d.queue.length === 1 ? "" : "s"} to Tally`}</button>
      </PageTop>
      <div className="grid g-kpi" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(180px,1fr))" }}>
        <Kpi label="Waiting to post" value={d.queue.length} detail={d.queue.length ? <Pill tone="warn">Queued</Pill> : <Pill tone="good">Up to date</Pill>} />
        <Kpi label="Posted today" value={d.postedToday} detail="Sales and receipt vouchers" />
        <Kpi label="Failed" value={d.failed.length} detail={d.failed.length ? <Pill tone="bad">Needs attention</Pill> : <Pill tone="good">None</Pill>} />
      </div>
      {d.failed.length > 0 && <div className="card"><div className="card-h"><h2>Failed vouchers</h2></div><div className="tbl-wrap"><table>
        <thead><tr><th>Ref</th><th>Party</th><th className="r">Amount</th><th>Error</th><th /></tr></thead>
        <tbody>{d.failed.map(v => <tr key={v.id}><td className="num">{v.ref}</td><td>{v.party}</td><td className="r num">{inr(v.amount)}</td><td className="sub">{v.lastError}</td>
          <td><button className="btn sm" disabled={busy} onClick={() => run(() => post(`/api/tally/failed/${v.id}/retry`), "Queued again").then(s.reload).catch(() => {})}>Retry</button></td></tr>)}</tbody>
      </table></div></div>}
      <div className="grid g-2e">
        <div className="card"><div className="card-h"><h2>Queue</h2></div>{d.queue.length === 0 ? <div className="empty">Nothing waiting.</div> : <div className="tbl-wrap"><table>
          <thead><tr><th>Type</th><th>Ref</th><th>Party ledger</th><th className="r">Amount</th></tr></thead>
          <tbody>{d.queue.map(v => <tr key={v.id}><td>{v.type === "SALES" ? "Sales" : "Receipt"}</td><td className="num">{v.ref}{v.lastError && <span className="sub" style={{ color: "var(--warn)" }}>Retrying: {v.lastError}</span>}</td><td>{v.party}</td><td className="r num">{inr(v.amount)}</td></tr>)}</tbody>
        </table></div>}</div>
        <div className="card"><div className="card-h"><h2>Recently posted</h2></div>{d.log.length === 0 ? <div className="empty">Nothing posted yet.</div> : <div className="tbl-wrap"><table>
          <thead><tr><th>Time</th><th>Ref</th><th>Tally voucher</th></tr></thead>
          <tbody>{d.log.map(v => <tr key={v.id}><td className="num">{time(v.postedAt)}</td><td className="num">{v.ref}<span className="sub">{v.party}</span></td><td className="num">{v.voucherNo} <Pill tone="good">Posted</Pill></td></tr>)}</tbody>
        </table></div>}</div>
      </div>
      <div className="card"><div className="card-h"><h2>Ledger mapping</h2><span className="hint">Set through environment variables</span></div>
        <dl className="kv"><dt>Sales ledger</dt><dd>{d.mapping.salesLedger}</dd><dt>Tax ledgers</dt><dd>{d.mapping.cgstLedger}, {d.mapping.sgstLedger}</dd><dt>Receipts to</dt><dd>{d.mapping.bankLedger}</dd></dl></div>
    </>
  );
}
