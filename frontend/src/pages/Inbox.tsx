import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api, ApiError, post } from "../api";
import { computeDraft, DraftCredit, DraftLine } from "../draft";
import { FileButton, Loading, PageTop, Pill, StatusPill, useAction, useApi, useToast } from "../components/ui";
import { inr, n, time } from "../format";
import { can, useUser } from "../App";

interface Conv { id: number; phone: string; status: string; customer?: string; town?: string; preview?: string; kind?: string;
  lastMessageAt: string; orderNumber?: number }
interface Msg { id: number; direction: "IN" | "OUT"; body: string; kind: string; createdAt: string; mediaPath?: string; mediaStatus?: string }
interface Thread extends Conv { customerId?: number; tier?: string; orderId?: number; orderStatus?: string; orderTotal?: number; messages: Msg[] }
interface Draft { lines: DraftLine[]; credit?: DraftCredit; unknownCustomer?: boolean; godownId?: number; licence?: { number?: string; expiry?: string } }
interface Product { id: number; sku: string; name: string }

export default function Inbox() {
  const list = useApi(s => api<Conv[]>("/api/inbox", { signal: s }));
  const [sel, setSel] = useState<number | null>(null);
  useEffect(() => {
    if (sel === null && list.data?.length) setSel(list.data[0].id);
  }, [list.data, sel]);

  return (
    <>
      <PageTop title="WhatsApp orders" sub="Incoming messages are matched to your item master and turned into draft sales orders.">
        <button className="btn sm" onClick={list.reload}>Refresh</button>
      </PageTop>
      {!list.data ? <Loading error={list.error} onRetry={list.reload} /> : list.data.length === 0 ? (
        <div className="card empty">No WhatsApp messages yet.</div>
      ) : (
        <div className="inbox">
          <div className="card chats" role="list">
            {list.data.map(c => (
              <button key={c.id} role="listitem" className={"chat-i" + (c.id === sel ? " on" : "")} onClick={() => setSel(c.id)}>
                <div className="t">{c.customer ?? "+" + c.phone}<span>{time(c.lastMessageAt)}</span></div>
                <p>{c.kind === "VOICE" ? "Voice note: " : ""}{(c.preview ?? "").replace(/\n/g, " · ")}</p>
                {c.status === "NEW" ? <Pill tone="bad">New order</Pill>
                  : c.status === "IGNORED" ? <Pill tone="mute">Ignored</Pill>
                  : c.orderNumber ? <Pill tone="good">SO-{c.orderNumber}</Pill> : <Pill tone="mute">Replied</Pill>}
              </button>
            ))}
          </div>
          {sel !== null && <Conversation key={sel} id={sel} onChanged={list.reload} />}
        </div>
      )}
    </>
  );
}

function Conversation({ id, onChanged }: { id: number; onChanged: () => void }) {
  const t = useApi(s => api<Thread>(`/api/inbox/${id}`, { signal: s }), [id]);
  const [text, setText] = useState("");
  const user = useUser();
  const { busy, run } = useAction();
  const msgsRef = useRef<HTMLDivElement>(null);
  useEffect(() => { const el = msgsRef.current; if (el) el.scrollTop = el.scrollHeight; }, [t.data]);

  if (!t.data) return <><div className="thread"><Loading error={t.error} onRetry={t.reload} /></div><div /></>;
  const th = t.data;
  const refresh = () => { t.reload(); onChanged(); };

  async function send(e: FormEvent, asCustomer: boolean) {
    e.preventDefault();
    if (!text.trim()) return;
    const ok = await run(() => asCustomer ? post("/api/inbox/simulate", { phone: th.phone, text: text.trim() })
      : post(`/api/inbox/${th.id}/reply`, { body: text.trim() }), asCustomer ? undefined : "Reply sent on WhatsApp").then(() => true).catch(() => false);
    if (ok) setText("");
    refresh();
  }

  return (
    <>
      <div className="thread">
        <div className="thread-h"><div><b>{th.customer ?? "Unknown number"}</b>
          <div className="sub">{[th.town, "+" + th.phone, th.tier].filter(Boolean).join(" · ")}</div></div></div>
        <div className="msgs" ref={msgsRef}>
          {th.messages.map(m => (
            <div key={m.id} className={`msg ${m.direction === "IN" ? "in" : "out"} ${m.kind === "VOICE" ? "voice" : ""}`}>
              {m.body}
              {m.mediaPath && <div className="row-actions"><FileButton path={m.mediaPath} label={m.kind === "VOICE" ? "Play voice note" : "View photo"} kind={m.kind === "VOICE" ? "audio" : "image"} />
                {m.direction === "IN" && m.mediaStatus !== "DONE" && <button type="button" className="btn ghost sm" disabled={busy}
                  onClick={() => run(() => post<{ ok: boolean }>(`/api/inbox/messages/${m.id}/read`), r => r.ok ? "Read into text. Check the draft." : "Nothing readable found").then(refresh).catch(() => {})}>
                  {m.kind === "VOICE" ? "Transcribe" : "Read text"}</button>}</div>}
              <time>{time(m.createdAt)}{m.direction === "OUT" ? " ✓✓" : ""}</time>
            </div>
          ))}
        </div>
        <form className="composer" onSubmit={e => send(e, false)}>
          <label htmlFor="simtxt" className="sr">Message</label>
          <textarea id="simtxt" value={text} maxLength={4000} onChange={e => setText(e.target.value)}
            placeholder="Reply to the customer, or test: send as customer, e.g. 16A MCB 50 pcs" />
          <div style={{ display: "grid", gap: 6 }}>
            <button className="btn pri sm" type="submit" disabled={busy || !text.trim()}>Send reply</button>
            {user.simulateEnabled && <button className="btn sm" type="button" disabled={busy || !text.trim()} onClick={e => send(e, true)}>Send as customer</button>}
          </div>
        </form>
      </div>
      <div className="conv">
        {th.status === "NEW"
          ? (th.customerId ? <DraftPanel thread={th} onDone={refresh} /> : <UnknownCustomer thread={th} onDone={refresh} />)
          : th.orderId ? (
            <div className="card"><div className="card-h"><h2>SO-{th.orderNumber}</h2>{th.orderStatus && <StatusPill status={th.orderStatus} />}</div>
              <p style={{ margin: "0 0 10px", color: "var(--ink-2)" }}>Created from this chat · {inr(th.orderTotal)} incl. GST.</p>
              <Link className="btn" to="/orders">Open sales orders</Link></div>
          ) : <div className="card empty">{th.status === "IGNORED" ? "Marked as not an order." : "No open order in this chat."}</div>}
      </div>
    </>
  );
}

function UnknownCustomer({ thread, onDone }: { thread: Thread; onDone: () => void }) {
  const { run, busy } = useAction();
  return (
    <div className="card"><div className="card-h"><h2>Unknown number</h2></div>
      <p style={{ marginTop: 0, color: "var(--ink-2)" }}>+{thread.phone} is not a customer yet. Add them in Customers & credit with this phone number, then come back to convert the order.</p>
      <div className="actions">
        <Link className="btn pri" to={`/customers?add=${thread.phone}`}>Add as customer</Link>
        <button className="btn" disabled={busy} onClick={() => run(() => post(`/api/inbox/${thread.id}/ignore`), "Marked as not an order").then(onDone).catch(() => {})}>Not an order</button>
      </div>
    </div>
  );
}

function DraftPanel({ thread, onDone }: { thread: Thread; onDone: () => void }) {
  const user = useUser();
  const toast = useToast();
  const godowns = useApi(s => api<{ id: number; name: string; isDefault: boolean }[]>("/api/godowns", { signal: s }));
  const [godownId, setGodownId] = useState<number | "">("");
  const d = useApi(s => api<Draft>(`/api/inbox/${thread.id}/draft${godownId ? `?godownId=${godownId}` : ""}`, { signal: s }), [thread.id, thread.messages.length, godownId]);
  const products = useApi(s => api<Product[]>("/api/inventory", { signal: s }));
  const [lines, setLines] = useState<DraftLine[] | null>(null);
  const [override, setOverride] = useState(false);
  const { busy, run } = useAction();
  useEffect(() => { if (d.data) { setLines(d.data.lines); setOverride(false); } }, [d.data]);

  const totals = useMemo(() => computeDraft(lines ?? [], d.data?.credit ?? null), [lines, d.data]);
  if (!d.data || !lines) return <Loading error={d.error} onRetry={d.reload} />;
  const credit = d.data.credit!;
  const hold = totals.status === "HOLD" && !override;

  const setQty = (i: number, q: number) => setLines(ls => ls!.map((l, j) => (j === i ? { ...l, qty: Math.max(0, Math.min(100000, q || 0)) } : l)));
  const remove = (i: number) => setLines(ls => ls!.filter((_, j) => j !== i));
  const pick = (i: number, productId: number) => {
    const p = products.data?.find(x => x.id === productId);
    if (!p) return;
    setLines(ls => ls!.map((l, j) => (j === i ? { ...l, productId, sku: p.sku, name: p.name, rate: undefined, free: undefined } : l)));
    toast(`${p.sku} picked. Price and stock are checked when you confirm.`);
  };

  async function confirm() {
    const body = { overrideCredit: override, godownId: godownId || undefined, lines: lines!.filter(l => l.productId && l.qty > 0).map(l => ({ productId: l.productId, qty: l.qty })) };
    try {
      await run(() => post<{ number: number; backorders: string[] }>(`/api/inbox/${thread.id}/confirm`, body),
        r => `SO-${r.number} confirmed. Stock reserved, reply sent on WhatsApp.` + (r.backorders.length ? ` Back-order: ${r.backorders.length} line(s).` : ""));
      onDone();
    } catch (e) {
      if (e instanceof ApiError && (e.code === "ALREADY_HANDLED" || e.code === "CREDIT_HOLD" || e.code === "LICENCE_INVALID")) { d.reload(); onDone(); }
    }
  }

  const manualPick = lines.some(l => l.productId && l.rate === undefined);
  const needsLicence = lines.some(l => (l as DraftLine & { requiresLicence?: boolean }).requiresLicence);
  const lic = d.data.licence;
  const licenceProblem = !needsLicence ? null
    : !lic?.number ? "Prescription items need the customer's drug licence on file. Add it under Customers → Details."
    : lic.expiry && new Date(lic.expiry) < new Date(new Date().toDateString()) ? `Drug licence ${lic.number} expired on ${new Date(lic.expiry).toLocaleDateString("en-IN")}. Prescription items can't be sold until it is renewed.`
    : null;
  return (
    <div className="card">
      <div className="card-h"><h2>Draft sales order</h2>
        {(godowns.data?.length ?? 0) > 1 ? <><label className="sr" htmlFor="dr-g">Ship from</label>
          <select id="dr-g" value={godownId || d.data.godownId || ""} onChange={e => setGodownId(Number(e.target.value))} style={{ padding: 4, border: "1px solid var(--line)", borderRadius: 6, background: "var(--surface-2)", fontSize: 12 }}>
            {godowns.data!.map(g => <option key={g.id} value={g.id}>Ship from {g.name}</option>)}</select></>
          : <span className="hint">{lines.length} lines read from the chat</span>}</div>
      {licenceProblem && <div className="banner" role="alert" style={{ marginBottom: 10 }}>{licenceProblem}</div>}
      <div className="tbl-wrap"><table className="draft">
        <thead><tr><th>Item</th><th className="r">Qty</th><th className="r">Rate</th><th>Stock</th><th className="r">Amount</th><th /></tr></thead>
        <tbody>
          {lines.length === 0 && <tr><td colSpan={6} className="empty">No items found in this message.</td></tr>}
          {lines.map((l, i) => !l.productId ? (
            <tr key={i}><td colSpan={6}>
              <Pill tone="warn">Not matched</Pill> <span className="raw">“{l.raw}”</span>
              <div className="row-actions" style={{ marginTop: 6 }}>
                <label className="sr" htmlFor={`pick-${i}`}>Pick an item for {l.raw}</label>
                <select id={`pick-${i}`} defaultValue="" onChange={e => pick(i, Number(e.target.value))}>
                  <option value="">Pick an item…</option>
                  {products.data?.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
                <button className="btn ghost sm" onClick={() => remove(i)}>Skip line</button>
              </div>
            </td></tr>
          ) : (
            <tr key={i}>
              <td>{l.name}<span className="raw">“{l.raw}”</span></td>
              <td className="r"><label className="sr" htmlFor={`qty-${i}`}>Quantity for {l.name}</label>
                <input id={`qty-${i}`} type="number" min={0} max={100000} value={l.qty} onChange={e => setQty(i, parseInt(e.target.value, 10))} /></td>
              <td className="r num">{l.rate !== undefined ? <>{inr(l.rate)}<span className="sub">{l.priceSource}</span></> : <span className="sub">at confirm</span>}</td>
              <td>{l.free === undefined ? <span className="sub">at confirm</span> : n(l.free) >= l.qty ? <Pill tone="good">{n(l.free)} free</Pill>
                : <><Pill tone="bad">Short {l.qty - Math.max(0, n(l.free))}</Pill><span className="sub">{Math.max(0, n(l.free))} free</span></>}</td>
              <td className="r num">{l.rate !== undefined ? inr(n(l.rate) * Math.min(l.qty, Math.max(0, n(l.free)))) : "–"}</td>
              <td><button className="btn ghost sm" aria-label={`Remove ${l.name}`} onClick={() => remove(i)}>✕</button></td>
            </tr>
          ))}
        </tbody>
      </table></div>
      <div className="sum">
        <span>Subtotal (in-stock qty)</span><span className="num">{inr(totals.subtotal)}</span>
        <span>GST</span><span className="num">{inr(totals.tax)}</span>
        <span className="tot">Order total</span><span className="num tot">{inr(totals.total)}</span>
      </div>
      <div className={"credit " + (hold ? "hold" : totals.status === "PASS" || override ? (override ? "warn" : "ok") : "warn")}>
        <div className="row"><b>{hold ? "Credit hold: needs owner approval" : override ? "Over limit, owner override" : totals.status === "WATCH" ? "Credit OK, watch this account" : "Credit OK"}</b>
          <Pill tone={hold ? "bad" : totals.status === "PASS" ? "good" : "warn"}>{hold ? "Hold" : totals.status === "PASS" ? "Pass" : "Watch"}</Pill></div>
        <div className="row"><span>Limit</span><span className="num">{inr(credit.limit)}</span></div>
        <div className="row"><span>Outstanding + open orders</span><span className="num">{inr(n(credit.outstanding) + n(credit.openOrders))}</span></div>
        <div className="row"><span>After this order</span><span className="num">{inr(totals.after)} ({Math.round((totals.after / Math.max(1, n(credit.limit))) * 100)}%)</span></div>
        <div className="row"><span>Oldest unpaid bill</span><span className="num">{credit.oldestDays} days</span></div>
      </div>
      {totals.short > 0 && <p style={{ fontSize: 12, color: "var(--ink-2)", margin: "10px 0 0" }}>{totals.short} line(s) short on stock. Confirming reserves what is free; the rest goes on back-order.</p>}
      {manualPick && <p style={{ fontSize: 12, color: "var(--ink-2)", margin: "10px 0 0" }}>Manually picked items are priced and stock-checked when you confirm.</p>}
      <div className="actions">
        <button className="btn pri" onClick={confirm} disabled={busy || hold || !!licenceProblem || totals.unmatched > 0 || !lines.some(l => l.productId && l.qty > 0)}>
          {busy ? "Confirming…" : "Confirm & reply on WhatsApp"}</button>
        {totals.status === "HOLD" && !override && can(user, "OWNER") && <button className="btn" onClick={() => setOverride(true)}>Approve override</button>}
        {totals.status === "HOLD" && thread.customerId && <button className="btn" disabled={busy}
          onClick={() => run(() => post(`/api/customers/${thread.customerId}/remind`), "Payment reminder sent on WhatsApp").then(onDone).catch(() => {})}>Ask for payment first</button>}
        <button className="btn ghost" disabled={busy} onClick={() => run(() => post(`/api/inbox/${thread.id}/ignore`), "Marked as not an order").then(onDone).catch(() => {})}>Not an order</button>
      </div>
      {totals.unmatched > 0 && <p style={{ fontSize: 12, color: "var(--warn)", margin: "8px 0 0" }}>Pick or skip the unmatched line to continue.</p>}
    </div>
  );
}
