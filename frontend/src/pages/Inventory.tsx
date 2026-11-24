import { FormEvent, useState } from "react";
import { api, post, put } from "../api";
import { Kpi, Loading, Modal, PageTop, Pill, useAction, useApi } from "../components/ui";
import { dayLabel, inr, lakh, n } from "../format";
import { can, useUser } from "../App";

export interface Item { id: number; sku: string; name: string; unit: string; category: string; listRate: number; reorderLevel: number;
  onHand: number; reserved: number; free: number; gstRate: number; patterns?: string; batchTracked?: boolean; requiresLicence?: boolean;
  hsn?: string; expired?: number; nextExpiry?: string }
export interface Godown { id: number; code: string; name: string; isDefault: boolean; stockValue?: number }
interface Batch { id: number; batchNo: string; expiryDate: string; onHand: number; reserved: number; godown: string; godownId: number; expired: boolean; daysLeft: number }
interface Detail { id: number; sku: string; name: string; batchTracked: boolean; godowns: { godownId: number; godown: string; onHand: number; reserved: number }[]; batches: Batch[] }
interface Expiring { id: number; sku: string; name: string; batchNo: string; expiryDate: string; godown: string; onHand: number; daysLeft: number; value: number }

export default function Inventory() {
  const user = useUser();
  const godowns = useApi(s => api<Godown[]>("/api/godowns", { signal: s }));
  const [godownId, setGodownId] = useState<number | "">("");
  const inv = useApi(s => api<Item[]>(`/api/inventory${godownId ? `?godownId=${godownId}` : ""}`, { signal: s }), [godownId]);
  const expiring = useApi(s => api<Expiring[]>("/api/inventory/expiring?days=90", { signal: s }));
  const [adjust, setAdjust] = useState<Item | null>(null);
  const [editing, setEditing] = useState<Item | "new" | null>(null);
  const [detail, setDetail] = useState<Item | null>(null);
  const [transfer, setTransfer] = useState(false);
  const [addGodown, setAddGodown] = useState(false);
  if (!inv.data) return <Loading error={inv.error} onRetry={inv.reload} />;
  const rows = [...inv.data].sort((a, b) => n(a.free) / Math.max(1, n(a.reorderLevel)) - n(b.free) / Math.max(1, n(b.reorderLevel)));
  const value = rows.reduce((s, p) => s + n(p.onHand) * n(p.listRate), 0);
  const reservedValue = rows.reduce((s, p) => s + n(p.reserved) * n(p.listRate), 0);
  const low = rows.filter(p => n(p.free) < n(p.reorderLevel));
  const anyBatch = rows.some(r => r.batchTracked);
  const reload = () => { inv.reload(); expiring.reload(); godowns.reload(); };
  const expiredVal = (expiring.data ?? []).filter(e => e.daysLeft <= 0).reduce((s, e) => s + n(e.value), 0);
  const soonVal = (expiring.data ?? []).filter(e => e.daysLeft > 0).reduce((s, e) => s + n(e.value), 0);

  return (
    <>
      <PageTop title="Inventory" sub="Free stock = on hand minus what open orders have reserved, excluding expired batches.">
        <div className="row-actions">
          <label className="sr" htmlFor="inv-g">Godown</label>
          <select id="inv-g" value={godownId} onChange={e => setGodownId(e.target.value ? Number(e.target.value) : "")}
            style={{ padding: 7, border: "1px solid var(--line)", borderRadius: 8, background: "var(--surface)" }}>
            <option value="">All godowns</option>{godowns.data?.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select>
          {can(user, "OWNER", "ACCOUNTANT") && (godowns.data?.length ?? 0) > 1 && <button className="btn sm" onClick={() => setTransfer(true)}>Transfer stock</button>}
          {can(user, "OWNER") && <button className="btn sm" onClick={() => setAddGodown(true)}>Add godown</button>}
          {can(user, "OWNER") && <button className="btn pri sm" onClick={() => setEditing("new")}>Add item</button>}
        </div>
      </PageTop>
      <div className="grid g-kpi" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(180px,1fr))" }}>
        <Kpi label="Stock value at list price" value={lakh(value)} detail={`${rows.length} SKUs${godownId ? "" : ` · ${godowns.data?.length ?? 1} godowns`}`} />
        <Kpi label="Reserved for open orders" value={lakh(reservedValue)} detail="Held until dispatch" />
        <Kpi label="Below reorder level" value={low.length} detail={low.length ? <Pill tone="warn">Sorted to the top</Pill> : <Pill tone="good">All healthy</Pill>} />
        {anyBatch && <Kpi label="Expired / expiring in 90 days" value={`${lakh(expiredVal)} / ${lakh(soonVal)}`}
          detail={expiredVal > 0 ? <Pill tone="bad">Return or destroy expired stock</Pill> : <Pill tone="good">Nothing expired</Pill>} />}
      </div>
      <div className="card"><div className="tbl-wrap"><table>
        <thead><tr><th>Item</th><th>Category</th><th className="r">On hand</th><th className="r">Reserved</th><th className="r">Free</th>{anyBatch && <th>Next expiry</th>}<th>Cover vs reorder</th><th /></tr></thead>
        <tbody>{rows.map(p => {
          const r = n(p.free) / Math.max(1, n(p.reorderLevel));
          return (<tr key={p.id}>
            <td>{p.name}<span className="sub">{p.sku} · {p.unit} · list {inr(p.listRate)}{p.requiresLicence ? " · Rx" : ""}</span></td>
            <td>{p.category}</td>
            <td className="r num">{n(p.onHand)}</td><td className="r num">{n(p.reserved) || "–"}</td><td className="r num"><b>{n(p.free)}</b></td>
            {anyBatch && <td>{p.batchTracked ? <>{p.nextExpiry ? dayLabel(p.nextExpiry) + " " + new Date(p.nextExpiry).getFullYear() : "–"}
              {n(p.expired) > 0 && <span className="sub" style={{ color: "var(--bad)" }}>{n(p.expired)} expired</span>}</> : <span className="sub">Not batch-tracked</span>}</td>}
            <td><div className="meter"><i className={r < 1 ? "b" : r < 1.5 ? "w" : ""} style={{ width: Math.min(100, (r / 4) * 100) + "%" }} /></div>
              <span className="sub">{r < 1 ? "Below" : "Reorder at"} {n(p.reorderLevel)}</span></td>
            <td><div className="row-actions">
              <button className="btn ghost sm" onClick={() => setDetail(p)}>Stock detail</button>
              {can(user, "OWNER", "ACCOUNTANT") && <button className="btn sm" onClick={() => setAdjust(p)}>Adjust</button>}
              {can(user, "OWNER") && <button className="btn ghost sm" onClick={() => setEditing(p)}>Edit</button>}</div></td>
          </tr>);
        })}</tbody>
      </table></div></div>
      {expiring.data && expiring.data.length > 0 && <div className="card"><div className="card-h"><h2>Expired and expiring within 90 days</h2></div><div className="tbl-wrap"><table>
        <thead><tr><th>Item</th><th>Batch</th><th>Godown</th><th className="r">Qty</th><th>Expiry</th><th className="r">Value</th></tr></thead>
        <tbody>{expiring.data.map(e => <tr key={e.id}><td>{e.name}<span className="sub">{e.sku}</span></td><td className="num">{e.batchNo}</td><td>{e.godown}</td>
          <td className="r num">{n(e.onHand)}</td><td>{e.daysLeft <= 0 ? <Pill tone="bad">Expired</Pill> : <Pill tone={e.daysLeft <= 30 ? "warn" : "mute"}>{e.daysLeft} days</Pill>}</td>
          <td className="r num">{inr(e.value)}</td></tr>)}</tbody>
      </table></div></div>}
      {editing && <ItemModal item={editing === "new" ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); reload(); }} />}
      {adjust && godowns.data && <AdjustModal item={adjust} godowns={godowns.data} onClose={() => setAdjust(null)} onDone={() => { setAdjust(null); reload(); }} />}
      {detail && <DetailModal item={detail} onClose={() => setDetail(null)} />}
      {transfer && godowns.data && <TransferModal items={inv.data} godowns={godowns.data} onClose={() => setTransfer(false)} onDone={() => { setTransfer(false); reload(); }} />}
      {addGodown && <GodownModal onClose={() => setAddGodown(false)} onDone={() => { setAddGodown(false); godowns.reload(); }} />}
    </>
  );
}

function DetailModal({ item, onClose }: { item: Item; onClose: () => void }) {
  const d = useApi(s => api<Detail>(`/api/inventory/${item.id}`, { signal: s }), [item.id]);
  return (
    <Modal title={`${item.sku}: stock by godown`} onClose={onClose}>
      {!d.data ? <Loading error={d.error} /> : <>
        <div className="tbl-wrap"><table><thead><tr><th>Godown</th><th className="r">On hand</th><th className="r">Reserved</th></tr></thead>
          <tbody>{d.data.godowns.map(g => <tr key={g.godownId}><td>{g.godown}</td><td className="r num">{n(g.onHand)}</td><td className="r num">{n(g.reserved)}</td></tr>)}</tbody></table></div>
        {d.data.batchTracked && <div className="tbl-wrap"><table><thead><tr><th>Batch</th><th>Expiry</th><th>Godown</th><th className="r">On hand</th><th className="r">Reserved</th></tr></thead>
          <tbody>{d.data.batches.length === 0 && <tr><td colSpan={5} className="empty">No batches in stock.</td></tr>}
            {d.data.batches.map(b => <tr key={b.id}><td className="num">{b.batchNo}</td>
              <td>{b.expired ? <Pill tone="bad">Expired</Pill> : <>{new Date(b.expiryDate).toLocaleDateString("en-IN", { month: "short", year: "numeric" })}{b.daysLeft <= 90 && <span className="sub">{b.daysLeft} days left</span>}</>}</td>
              <td>{b.godown}</td><td className="r num">{n(b.onHand)}</td><td className="r num">{n(b.reserved) || "–"}</td></tr>)}</tbody></table></div>}
      </>}
      <button className="btn" onClick={onClose}>Close</button>
    </Modal>
  );
}

function AdjustModal({ item, godowns, onClose, onDone }: { item: Item; godowns: Godown[]; onClose: () => void; onDone: () => void }) {
  const [godownId, setGodownId] = useState<number>(godowns.find(g => g.isDefault)?.id ?? godowns[0].id);
  const d = useApi(s => api<Detail>(`/api/inventory/${item.id}`, { signal: s }), [item.id]);
  const [delta, setDelta] = useState("");
  const [reason, setReason] = useState("Goods received");
  const [batchId, setBatchId] = useState<number | "new" | "">("");
  const [batchNo, setBatchNo] = useState("");
  const [expiry, setExpiry] = useState("");
  const { busy, run } = useAction();
  const dn = parseInt(delta, 10);
  const g = d.data?.godowns.find(x => x.godownId === godownId);
  const batches = (d.data?.batches ?? []).filter(b => b.godownId === godownId);
  const chosen = batches.find(b => b.id === batchId);
  const batchOk = !item.batchTracked || (dn > 0 ? (batchId === "new" ? !!batchNo && !!expiry : batchId !== "") : batchId !== "" && batchId !== "new");
  const stockOk = !Number.isFinite(dn) || dn > 0 || (item.batchTracked && chosen ? n(chosen.onHand) - n(chosen.reserved) + dn >= 0 : g ? n(g.onHand) - n(g.reserved) + dn >= 0 : true);
  const valid = Number.isFinite(dn) && dn !== 0 && stockOk && batchOk;
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!valid) return;
    await run(() => post(`/api/products/${item.id}/stock`, { delta: dn, reason, godownId,
      batchId: typeof batchId === "number" ? batchId : undefined, batchNo: batchId === "new" ? batchNo : undefined, expiryDate: batchId === "new" ? expiry : undefined }),
    "Stock updated").then(onDone).catch(() => {});
  }
  return (
    <Modal title={`Adjust stock: ${item.sku}`} onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 12 }}>
        <label className="field" htmlFor="adj-g">Godown<select id="adj-g" value={godownId} onChange={e => { setGodownId(Number(e.target.value)); setBatchId(""); }}>
          {godowns.map(x => <option key={x.id} value={x.id}>{x.name}</option>)}</select></label>
        <p style={{ margin: 0, color: "var(--ink-2)" }}>On hand {n(g?.onHand)}, reserved {n(g?.reserved)}. Use a minus sign to reduce.</p>
        <label className="field" htmlFor="delta">Change in quantity<input id="delta" type="number" required value={delta} onChange={e => setDelta(e.target.value)} autoFocus /></label>
        {item.batchTracked && <label className="field" htmlFor="adj-b">Batch<select id="adj-b" value={batchId} onChange={e => setBatchId(e.target.value === "new" ? "new" : e.target.value ? Number(e.target.value) : "")}>
          <option value="">Choose batch…</option>
          {batches.map(b => <option key={b.id} value={b.id}>{b.batchNo} · exp {new Date(b.expiryDate).toLocaleDateString("en-IN", { month: "short", year: "numeric" })} · {n(b.onHand)} on hand</option>)}
          {dn > 0 && <option value="new">New batch…</option>}</select></label>}
        {batchId === "new" && <div className="grid" style={{ gridTemplateColumns: "1fr 1fr", gap: 10 }}>
          <label className="field" htmlFor="adj-bn">Batch no.<input id="adj-bn" maxLength={40} value={batchNo} onChange={e => setBatchNo(e.target.value)} /></label>
          <label className="field" htmlFor="adj-ex">Expiry<input id="adj-ex" type="date" value={expiry} onChange={e => setExpiry(e.target.value)} /></label></div>}
        <label className="field" htmlFor="reason">Reason<select id="reason" value={reason} onChange={e => setReason(e.target.value)}>
          <option>Goods received</option><option>Stock count correction</option><option>Damaged</option><option>Expired - destroyed</option><option>Returned by customer</option></select></label>
        {delta && !stockOk && <div className="err">Stock can't go below what is already reserved.</div>}
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy || !valid}>Save</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}

function TransferModal({ items, godowns, onClose, onDone }: { items: Item[]; godowns: Godown[]; onClose: () => void; onDone: () => void }) {
  const [from, setFrom] = useState(godowns[0].id);
  const [to, setTo] = useState(godowns[1]?.id ?? godowns[0].id);
  const [productId, setProductId] = useState<number | "">("");
  const [qty, setQty] = useState("1");
  const [batchId, setBatchId] = useState<number | "">("");
  const item = items.find(i => i.id === productId);
  const d = useApi(s => productId ? api<Detail>(`/api/inventory/${productId}`, { signal: s }) : Promise.resolve(null), [productId]);
  const { busy, run } = useAction();
  const src = d.data?.godowns.find(g => g.godownId === from);
  const batches = (d.data?.batches ?? []).filter(b => b.godownId === from && !b.expired);
  const q = Number(qty);
  const valid = productId !== "" && from !== to && q > 0 && (!item?.batchTracked || batchId !== "");
  async function submit(e: FormEvent) {
    e.preventDefault();
    await run(() => post<{ number: string }>("/api/transfers", { fromGodownId: from, toGodownId: to,
      lines: [{ productId, qty: q, batchId: batchId === "" ? undefined : batchId }] }), r => `${r.number} done`).then(onDone).catch(() => {});
  }
  return (
    <Modal title="Transfer stock" onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 10 }}>
        <div className="grid" style={{ gridTemplateColumns: "1fr 1fr", gap: 10 }}>
          <label className="field" htmlFor="tr-f">From<select id="tr-f" value={from} onChange={e => setFrom(Number(e.target.value))}>{godowns.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
          <label className="field" htmlFor="tr-t">To<select id="tr-t" value={to} onChange={e => setTo(Number(e.target.value))}>{godowns.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
        </div>
        <label className="field" htmlFor="tr-p">Item<select id="tr-p" value={productId} onChange={e => { setProductId(e.target.value ? Number(e.target.value) : ""); setBatchId(""); }}>
          <option value="">Choose…</option>{items.map(i => <option key={i.id} value={i.id}>{i.sku} · {i.name}</option>)}</select></label>
        {item?.batchTracked && <label className="field" htmlFor="tr-b">Batch<select id="tr-b" value={batchId} onChange={e => setBatchId(e.target.value ? Number(e.target.value) : "")}>
          <option value="">Choose batch…</option>{batches.map(b => <option key={b.id} value={b.id}>{b.batchNo} · {n(b.onHand) - n(b.reserved)} free</option>)}</select></label>}
        <label className="field" htmlFor="tr-q">Quantity<input id="tr-q" type="number" min={1} value={qty} onChange={e => setQty(e.target.value)} /></label>
        {src && <div className="sub">{n(src.onHand) - n(src.reserved)} free in the source godown</div>}
        {from === to && <div className="err">Choose two different godowns.</div>}
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy || !valid}>Transfer</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}

function GodownModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ code: "", name: "", address: "" });
  const { busy, run } = useAction();
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  return (
    <Modal title="Add godown" onClose={onClose}>
      <form onSubmit={e => { e.preventDefault(); run(() => post("/api/godowns", f), "Godown added").then(onDone).catch(() => {}); }} style={{ display: "grid", gap: 10 }}>
        <label className="field" htmlFor="gd-c">Short code<input id="gd-c" required maxLength={12} value={f.code} onChange={set("code")} placeholder="e.g. CHK" /></label>
        <label className="field" htmlFor="gd-n">Name<input id="gd-n" required maxLength={100} value={f.name} onChange={set("name")} /></label>
        <label className="field" htmlFor="gd-a">Address<input id="gd-a" maxLength={300} value={f.address} onChange={set("address")} /></label>
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy}>Add godown</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}

function ItemModal({ item, onClose, onDone }: { item: Item | null; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({
    sku: item?.sku ?? "", name: item?.name ?? "", unit: item?.unit ?? "pc", category: item?.category ?? "",
    listRate: String(item?.listRate ?? ""), gstRate: String(n(item?.gstRate ?? 18)), reorderLevel: String(item?.reorderLevel ?? 0),
    patterns: item?.patterns ?? "", hsn: item?.hsn ?? "",
  });
  const [batchTracked, setBatchTracked] = useState(!!item?.batchTracked);
  const [requiresLicence, setRequiresLicence] = useState(!!item?.requiresLicence);
  const { busy, run } = useAction();
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  const badPattern = f.patterns.split("\n").map(s => s.trim()).filter(Boolean).find(s => { try { new RegExp(s); return false; } catch { return true; } });
  async function submit(e: FormEvent) {
    e.preventDefault();
    const body = { ...f, listRate: Number(f.listRate), gstRate: Number(f.gstRate), reorderLevel: Number(f.reorderLevel), hsn: f.hsn || undefined,
      batchTracked, requiresLicence, patterns: f.patterns.split("\n").map(s => s.trim()).filter(Boolean) };
    await run(() => (item ? put(`/api/products/${item.id}`, body) : post("/api/products", body)), item ? "Item saved" : "Item added").then(onDone).catch(() => {});
  }
  return (
    <Modal title={item ? `Edit ${item.sku}` : "Add item"} onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 10 }}>
        <label className="field" htmlFor="i-sku">SKU<input id="i-sku" required maxLength={40} value={f.sku} onChange={set("sku")} /></label>
        <label className="field" htmlFor="i-name">Name<input id="i-name" required maxLength={200} value={f.name} onChange={set("name")} /></label>
        <div className="grid" style={{ gridTemplateColumns: "1fr 1fr", gap: 10 }}>
          <label className="field" htmlFor="i-unit">Unit<input id="i-unit" required maxLength={40} value={f.unit} onChange={set("unit")} /></label>
          <label className="field" htmlFor="i-cat">Category<input id="i-cat" required maxLength={60} value={f.category} onChange={set("category")} /></label>
          <label className="field" htmlFor="i-rate">List rate (₹)<input id="i-rate" type="number" min={0} step="0.01" required value={f.listRate} onChange={set("listRate")} /></label>
          <label className="field" htmlFor="i-gst">GST %<select id="i-gst" value={f.gstRate} onChange={set("gstRate")}>{["0", "5", "12", "18", "28"].map(g => <option key={g}>{g}</option>)}</select></label>
          <label className="field" htmlFor="i-ro">Reorder level<input id="i-ro" type="number" min={0} required value={f.reorderLevel} onChange={set("reorderLevel")} /></label>
          <label className="field" htmlFor="i-hsn">HSN code<input id="i-hsn" maxLength={12} value={f.hsn} onChange={set("hsn")} /></label>
        </div>
        <label style={{ display: "flex", gap: 8, fontSize: 13 }}><input type="checkbox" checked={batchTracked} onChange={e => setBatchTracked(e.target.checked)} /> Track batches and expiry (medicines, food)</label>
        <label style={{ display: "flex", gap: 8, fontSize: 13 }}><input type="checkbox" checked={requiresLicence} onChange={e => setRequiresLicence(e.target.checked)} /> Buyer needs a valid drug licence (Schedule H/H1)</label>
        <label className="field" htmlFor="i-pat">WhatsApp match patterns (one per line)
          <textarea id="i-pat" rows={3} value={f.patterns} onChange={set("patterns")} placeholder={"32\\s*a\\s*mcb\nmcb\\s*32\\s*a"} /></label>
        <span className="sub">How retailers write this item, as patterns. Example: <code>{"32\\s*a\\s*mcb"}</code> matches "32A MCB" and "32 a mcb".</span>
        {badPattern && <div className="err">Invalid pattern: {badPattern}</div>}
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy || !!badPattern}>Save</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}
