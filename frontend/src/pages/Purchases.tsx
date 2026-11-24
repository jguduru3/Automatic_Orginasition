import { FormEvent, useState } from "react";
import { api, post } from "../api";
import { Kpi, Loading, Modal, PageTop, Pill, Tone, useAction, useApi } from "../components/ui";
import { dayLabel, inr, lakh, n } from "../format";

interface Po { id: number; number: number; status: string; total: number; expectedDate?: string; supplier: string; godown: string; late: boolean; summary?: string }
interface Suggestion { productId: number; sku: string; name: string; reorderLevel: number; free: number; onOrder: number; suggestedQty: number;
  lastSupplierId?: number; lastRate?: number; listRate: number }
interface Supplier { id: number; name: string; phone?: string; email?: string; gstin?: string; leadDays: number; openOrders: number }
interface Godown { id: number; code: string; name: string; isDefault: boolean }
interface PoLine { id: number; productId: number; sku: string; name: string; batchTracked: boolean; qty: number; receivedQty: number; rate: number }
interface PoDetail extends Po { subtotal: number; tax: number; lines: PoLine[]; receipts: { grn: string; supplierInvoiceNo?: string; amount: number }[] }

const TONE: Record<string, Tone> = { DRAFT: "mute", SENT: "info", PARTIAL: "warn", RECEIVED: "good", CANCELLED: "mute" };
const LABEL: Record<string, string> = { DRAFT: "Draft", SENT: "Sent", PARTIAL: "Part received", RECEIVED: "Received", CANCELLED: "Cancelled" };

export default function Purchases() {
  const [filter, setFilter] = useState("OPEN");
  const pos = useApi(s => api<Po[]>(`/api/purchases?status=${filter}`, { signal: s }), [filter]);
  const sugg = useApi(s => api<Suggestion[]>("/api/purchases/suggestions", { signal: s }));
  const suppliers = useApi(s => api<Supplier[]>("/api/suppliers", { signal: s }));
  const godowns = useApi(s => api<Godown[]>("/api/godowns", { signal: s }));
  const [creating, setCreating] = useState<Suggestion[] | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [addSupplier, setAddSupplier] = useState(false);
  const reload = () => { pos.reload(); sugg.reload(); suppliers.reload(); };
  const openValue = (pos.data ?? []).filter(p => p.status === "SENT" || p.status === "PARTIAL").reduce((s, p) => s + n(p.total), 0);

  return (
    <>
      <PageTop title="Purchases" sub="Reorder from suppliers, then receive goods into a godown. Receipts post to Tally as purchase vouchers.">
        <div className="row-actions">
          <button className="btn sm" onClick={() => setAddSupplier(true)}>Add supplier</button>
          <button className="btn pri sm" onClick={() => setCreating([])}>New purchase order</button>
        </div>
      </PageTop>
      <div className="grid g-kpi" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(180px,1fr))" }}>
        <Kpi label="Items to reorder" value={sugg.data?.length ?? "–"} detail={sugg.data?.length ? <Pill tone="warn">Below reorder level</Pill> : <Pill tone="good">Stock healthy</Pill>} />
        <Kpi label="Open purchase orders" value={lakh(openValue)} detail={`${(pos.data ?? []).filter(p => p.status === "SENT" || p.status === "PARTIAL").length} with suppliers`} />
        <Kpi label="Late deliveries" value={(pos.data ?? []).filter(p => p.late).length} detail="Past the expected date" />
      </div>
      {sugg.data && sugg.data.length > 0 && (
        <div className="card"><div className="card-h"><h2>Reorder suggestions</h2>
          <button className="btn sm" onClick={() => setCreating(sugg.data!)}>Order all suggested</button></div>
          <div className="tbl-wrap"><table>
            <thead><tr><th>Item</th><th className="r">Free</th><th className="r">On order</th><th className="r">Reorder at</th><th className="r">Suggested</th><th /></tr></thead>
            <tbody>{sugg.data.map(s => <tr key={s.productId}><td>{s.name}<span className="sub">{s.sku}</span></td><td className="r num">{n(s.free)}</td>
              <td className="r num">{n(s.onOrder) || "–"}</td><td className="r num">{n(s.reorderLevel)}</td><td className="r num"><b>{n(s.suggestedQty)}</b></td>
              <td><button className="btn ghost sm" onClick={() => setCreating([s])}>Order</button></td></tr>)}</tbody>
          </table></div></div>
      )}
      <div className="card">
        <div className="card-h"><div className="filters" role="tablist">
          {["OPEN", "ALL", "RECEIVED", "CANCELLED"].map(f => <button key={f} role="tab" aria-selected={filter === f} className={"chip" + (filter === f ? " on" : "")} onClick={() => setFilter(f)}>{f === "OPEN" ? "Open" : LABEL[f] ?? "All"}</button>)}
        </div></div>
        {!pos.data ? <Loading error={pos.error} onRetry={pos.reload} /> : (
          <div className="tbl-wrap"><table>
            <thead><tr><th>PO</th><th>Supplier</th><th>Items</th><th className="r">Value</th><th>Status</th><th /></tr></thead>
            <tbody>
              {pos.data.length === 0 && <tr><td colSpan={6} className="empty">No purchase orders here.</td></tr>}
              {pos.data.map(p => <tr key={p.id}>
                <td className="num">PO-{p.number}<span className="sub">{p.godown}</span></td>
                <td>{p.supplier}<span className="sub">{p.expectedDate ? `Expected ${dayLabel(p.expectedDate)}` : ""}</span></td>
                <td className="sub">{p.summary}</td>
                <td className="r num">{inr(p.total)}</td>
                <td><Pill tone={p.late ? "bad" : TONE[p.status]}>{p.late ? "Late" : LABEL[p.status]}</Pill></td>
                <td><button className="btn sm" onClick={() => setOpen(p.id)}>Open</button></td>
              </tr>)}
            </tbody>
          </table></div>
        )}
      </div>
      {suppliers.data && <div className="card"><div className="card-h"><h2>Suppliers</h2></div><div className="tbl-wrap"><table><tbody>
        {suppliers.data.map(s => <tr key={s.id}><td>{s.name}<span className="sub">{[s.phone && "+" + s.phone, s.email, s.gstin].filter(Boolean).join(" · ")}</span></td>
          <td className="sub">Lead time {s.leadDays} days</td><td className="sub">{n(s.openOrders)} open orders</td></tr>)}
      </tbody></table></div></div>}
      {creating && suppliers.data && godowns.data && <NewPo seed={creating} suppliers={suppliers.data} godowns={godowns.data}
        onClose={() => setCreating(null)} onDone={id => { setCreating(null); reload(); setOpen(id); }} />}
      {open !== null && <PoModal id={open} onClose={() => setOpen(null)} onChanged={reload} />}
      {addSupplier && <SupplierModal onClose={() => setAddSupplier(false)} onDone={() => { setAddSupplier(false); suppliers.reload(); }} />}
    </>
  );
}

function NewPo({ seed, suppliers, godowns, onClose, onDone }: { seed: Suggestion[]; suppliers: Supplier[]; godowns: Godown[]; onClose: () => void; onDone: (id: number) => void }) {
  const items = useApi(s => api<{ id: number; sku: string; name: string; listRate: number }[]>("/api/inventory", { signal: s }));
  const [supplierId, setSupplierId] = useState<number | "">(seed.find(s => s.lastSupplierId)?.lastSupplierId ?? suppliers[0]?.id ?? "");
  const [godownId, setGodownId] = useState<number>(godowns.find(g => g.isDefault)?.id ?? godowns[0]?.id);
  const [expected, setExpected] = useState(() => new Date(Date.now() + 3 * 864e5).toISOString().slice(0, 10));
  const [lines, setLines] = useState(seed.length ? seed.map(s => ({ productId: s.productId as number | "", qty: n(s.suggestedQty) || 1, rate: String(n(s.lastRate) || Math.round(n(s.listRate) * 0.8)) }))
    : [{ productId: "" as number | "", qty: 1, rate: "" }]);
  const { busy, run } = useAction();
  const total = lines.reduce((s, l) => s + l.qty * (Number(l.rate) || 0), 0);
  const valid = supplierId !== "" && lines.some(l => l.productId !== "" && l.qty > 0 && l.rate !== "");
  const set = (i: number, p: Partial<(typeof lines)[number]>) => setLines(ls => ls.map((l, j) => (j === i ? { ...l, ...p } : l)));
  async function submit(e: FormEvent) {
    e.preventDefault();
    const body = { supplierId, godownId, expectedDate: expected || undefined,
      lines: lines.filter(l => l.productId !== "" && l.qty > 0 && l.rate !== "").map(l => ({ productId: l.productId, qty: l.qty, rate: Number(l.rate) })) };
    await run(() => post<{ id: number; number: number }>("/api/purchases", body), r => `PO-${r.number} saved as draft`).then(r => r && onDone(r.id)).catch(() => {});
  }
  const sel = { padding: 8, border: "1px solid var(--line)", borderRadius: 8, background: "var(--surface-2)" };
  return (
    <Modal title="New purchase order" onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 10 }}>
        <label className="field" htmlFor="po-s">Supplier<select id="po-s" required value={supplierId} onChange={e => setSupplierId(e.target.value ? Number(e.target.value) : "")}>
          <option value="">Choose…</option>{suppliers.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
        <div className="grid" style={{ gridTemplateColumns: "1fr 1fr", gap: 10 }}>
          <label className="field" htmlFor="po-g">Deliver to<select id="po-g" value={godownId} onChange={e => setGodownId(Number(e.target.value))}>{godowns.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
          <label className="field" htmlFor="po-e">Expected by<input id="po-e" type="date" value={expected} onChange={e => setExpected(e.target.value)} /></label>
        </div>
        {lines.map((l, i) => (
          <div key={i} className="row-actions" style={{ flexWrap: "nowrap" }}>
            <label className="sr" htmlFor={`po-p${i}`}>Item {i + 1}</label>
            <select id={`po-p${i}`} value={l.productId} style={{ ...sel, flex: 1, minWidth: 0 }} onChange={e => set(i, { productId: e.target.value ? Number(e.target.value) : "" })}>
              <option value="">Item…</option>{items.data?.map(x => <option key={x.id} value={x.id}>{x.sku} · {x.name}</option>)}</select>
            <label className="sr" htmlFor={`po-q${i}`}>Quantity {i + 1}</label>
            <input id={`po-q${i}`} type="number" min={1} value={l.qty} style={{ ...sel, width: 70 }} onChange={e => set(i, { qty: Math.max(0, parseInt(e.target.value, 10) || 0) })} />
            <label className="sr" htmlFor={`po-r${i}`}>Rate {i + 1}</label>
            <input id={`po-r${i}`} type="number" min={0} step="0.01" placeholder="Rate" value={l.rate} style={{ ...sel, width: 90 }} onChange={e => set(i, { rate: e.target.value })} />
            {lines.length > 1 && <button type="button" className="btn ghost sm" aria-label={`Remove item ${i + 1}`} onClick={() => setLines(ls => ls.filter((_, j) => j !== i))}>✕</button>}
          </div>))}
        <button type="button" className="btn sm" style={{ justifySelf: "start" }} onClick={() => setLines(ls => [...ls, { productId: "", qty: 1, rate: "" }])}>Add item</button>
        <div className="sub">Total before GST {inr(total)}</div>
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy || !valid}>Save draft</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}

function PoModal({ id, onClose, onChanged }: { id: number; onClose: () => void; onChanged: () => void }) {
  const po = useApi(s => api<PoDetail>(`/api/purchases/${id}`, { signal: s }), [id]);
  const { busy, run } = useAction();
  const [rx, setRx] = useState<Record<number, { qty: string; batchNo: string; expiryDate: string }>>({});
  const [inv, setInv] = useState("");
  if (!po.data) return <Modal title="Purchase order" onClose={onClose}><Loading error={po.error} /></Modal>;
  const d = po.data;
  const after = () => { po.reload(); onChanged(); };
  const canReceive = d.status === "SENT" || d.status === "PARTIAL";
  const lines = d.lines.map(l => ({ l, r: rx[l.id] ?? { qty: "", batchNo: "", expiryDate: "" } }));
  const missingBatch = lines.some(({ l, r }) => Number(r.qty) > 0 && l.batchTracked && (!r.batchNo || !r.expiryDate));
  const anyQty = lines.some(({ r }) => Number(r.qty) > 0);
  async function receive(e: FormEvent) {
    e.preventDefault();
    const body = { supplierInvoiceNo: inv || undefined, lines: lines.filter(({ r }) => Number(r.qty) > 0)
      .map(({ l, r }) => ({ poLineId: l.id, qty: Number(r.qty), batchNo: r.batchNo || undefined, expiryDate: r.expiryDate || undefined })) };
    await run(() => post<{ grn: string; status: string }>(`/api/purchases/${id}/receive`, body), r => `${r.grn} saved, stock added`).then(() => { setRx({}); setInv(""); after(); }).catch(() => {});
  }
  const cell = { padding: 6, border: "1px solid var(--line)", borderRadius: 6, background: "var(--surface-2)" };
  return (
    <Modal title={`PO-${d.number} · ${d.supplier}`} onClose={onClose}>
      <div className="row-actions"><Pill tone={TONE[d.status]}>{LABEL[d.status]}</Pill><span className="sub">{d.godown} · total {inr(d.total)} incl. GST</span></div>
      <form onSubmit={receive} style={{ display: "grid", gap: 8 }}>
        <div className="tbl-wrap"><table><thead><tr><th>Item</th><th className="r">Ordered</th><th className="r">Received</th>{canReceive && <th>Receive now</th>}</tr></thead>
          <tbody>{lines.map(({ l, r }) => {
            const open = l.qty - l.receivedQty;
            const set = (p: Partial<typeof r>) => setRx(x => ({ ...x, [l.id]: { ...r, ...p } }));
            return <tr key={l.id}><td>{l.name}<span className="sub">{l.sku} @ {inr(l.rate)}</span></td><td className="r num">{l.qty}</td><td className="r num">{l.receivedQty}</td>
              {canReceive && <td>{open > 0 ? <div style={{ display: "grid", gap: 4 }}>
                <label className="sr" htmlFor={`rx-q${l.id}`}>Quantity received for {l.sku}</label>
                <input id={`rx-q${l.id}`} type="number" min={0} max={open} placeholder={`up to ${open}`} value={r.qty} style={{ ...cell, width: 90 }} onChange={e => set({ qty: e.target.value })} />
                {l.batchTracked && <>
                  <label className="sr" htmlFor={`rx-b${l.id}`}>Batch number for {l.sku}</label>
                  <input id={`rx-b${l.id}`} placeholder="Batch no." value={r.batchNo} style={{ ...cell, width: 110 }} onChange={e => set({ batchNo: e.target.value })} />
                  <label className="sr" htmlFor={`rx-e${l.id}`}>Expiry for {l.sku}</label>
                  <input id={`rx-e${l.id}`} type="date" value={r.expiryDate} style={cell} onChange={e => set({ expiryDate: e.target.value })} /></>}
              </div> : <span className="sub">Complete</span>}</td>}
            </tr>;
          })}</tbody></table></div>
        {canReceive && <>
          <label className="field" htmlFor="rx-inv">Supplier invoice no.<input id="rx-inv" maxLength={60} value={inv} onChange={e => setInv(e.target.value)} /></label>
          {missingBatch && <div className="err">Batch number and expiry are needed for batch-tracked items.</div>}
          <button className="btn pri" disabled={busy || !anyQty || missingBatch}>Save goods receipt</button>
        </>}
      </form>
      {d.receipts.length > 0 && <div className="sub">Receipts: {d.receipts.map(r => `${r.grn}${r.supplierInvoiceNo ? ` (${r.supplierInvoiceNo})` : ""}`).join(", ")}</div>}
      <div className="actions" style={{ margin: 0 }}>
        {d.status === "DRAFT" && <button className="btn pri" disabled={busy} onClick={() => run(() => post<{ messaged: boolean }>(`/api/purchases/${id}/send`),
          r => r.messaged ? "Sent to supplier on WhatsApp" : "Marked as sent (supplier has no WhatsApp number)").then(after).catch(() => {})}>Send to supplier</button>}
        {(d.status === "DRAFT" || d.status === "SENT") && <button className="btn ghost" disabled={busy} onClick={() => run(() => post(`/api/purchases/${id}/cancel`), "Cancelled").then(after).catch(() => {})}>Cancel PO</button>}
        <button className="btn" onClick={onClose}>Close</button>
      </div>
    </Modal>
  );
}

function SupplierModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ name: "", phone: "", email: "", gstin: "", leadDays: "3" });
  const { busy, run } = useAction();
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  const phone = f.phone.replace(/\D/g, "");
  async function submit(e: FormEvent) {
    e.preventDefault();
    await run(() => post("/api/suppliers", { name: f.name, phone: phone ? (phone.length === 10 ? "91" + phone : phone) : undefined,
      email: f.email || undefined, gstin: f.gstin || undefined, leadDays: Number(f.leadDays) || 0 }), "Supplier added").then(onDone).catch(() => {});
  }
  return (
    <Modal title="Add supplier" onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 10 }}>
        <label className="field" htmlFor="sp-n">Name<input id="sp-n" required maxLength={200} value={f.name} onChange={set("name")} /></label>
        <label className="field" htmlFor="sp-p">WhatsApp number<input id="sp-p" inputMode="tel" value={f.phone} onChange={set("phone")} /></label>
        <label className="field" htmlFor="sp-e">Email<input id="sp-e" type="email" value={f.email} onChange={set("email")} /></label>
        <label className="field" htmlFor="sp-g">GSTIN<input id="sp-g" maxLength={15} value={f.gstin} onChange={set("gstin")} /></label>
        <label className="field" htmlFor="sp-l">Lead time (days)<input id="sp-l" type="number" min={0} max={365} value={f.leadDays} onChange={set("leadDays")} /></label>
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy}>Add supplier</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}
