import { FormEvent, useState } from "react";
import { api, post, put } from "../api";
import { Kpi, Loading, Modal, PageTop, Pill, useAction, useApi } from "../components/ui";
import { inr, lakh, n } from "../format";
import { can, useUser } from "../App";

interface Item { id: number; sku: string; name: string; unit: string; category: string; listRate: number; reorderLevel: number;
  onHand: number; reserved: number; free: number; gstRate: number; patterns?: string }

export default function Inventory() {
  const user = useUser();
  const inv = useApi(s => api<Item[]>("/api/inventory", { signal: s }));
  const [adjust, setAdjust] = useState<Item | null>(null);
  const [editing, setEditing] = useState<Item | "new" | null>(null);
  if (!inv.data) return <Loading error={inv.error} onRetry={inv.reload} />;
  const rows = inv.data;
  const value = rows.reduce((s, p) => s + n(p.onHand) * n(p.listRate), 0);
  const reservedValue = rows.reduce((s, p) => s + n(p.reserved) * n(p.listRate), 0);
  const low = rows.filter(p => n(p.free) < n(p.reorderLevel));
  return (
    <>
      <PageTop title="Inventory" sub="Free stock = on hand minus what open orders have reserved.">
        {can(user, "OWNER") && <button className="btn pri sm" onClick={() => setEditing("new")}>Add item</button>}
      </PageTop>
      <div className="grid g-kpi" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(180px,1fr))" }}>
        <Kpi label="Stock value at list price" value={lakh(value)} detail={`${rows.length} SKUs`} />
        <Kpi label="Reserved for open orders" value={lakh(reservedValue)} detail="Held until dispatch" />
        <Kpi label="Below reorder level" value={low.length} detail={low.length ? <Pill tone="warn">Sorted to the top</Pill> : <Pill tone="good">All healthy</Pill>} />
      </div>
      <div className="card"><div className="tbl-wrap"><table>
        <thead><tr><th>Item</th><th>Category</th><th className="r">On hand</th><th className="r">Reserved</th><th className="r">Free</th><th>Cover vs reorder</th><th /></tr></thead>
        <tbody>{rows.map(p => {
          const r = n(p.free) / Math.max(1, n(p.reorderLevel));
          return (<tr key={p.id}>
            <td>{p.name}<span className="sub">{p.sku} · {p.unit} · list {inr(p.listRate)}</span></td>
            <td>{p.category}</td>
            <td className="r num">{n(p.onHand)}</td><td className="r num">{n(p.reserved) || "–"}</td><td className="r num"><b>{n(p.free)}</b></td>
            <td><div className="meter"><i className={r < 1 ? "b" : r < 1.5 ? "w" : ""} style={{ width: Math.min(100, (r / 4) * 100) + "%" }} /></div>
              <span className="sub">{r < 1 ? "Below" : "Reorder at"} {n(p.reorderLevel)}</span></td>
            <td><div className="row-actions">{can(user, "OWNER", "ACCOUNTANT") && <button className="btn sm" onClick={() => setAdjust(p)}>Adjust stock</button>}
              {can(user, "OWNER") && <button className="btn ghost sm" onClick={() => setEditing(p)}>Edit</button>}</div></td>
          </tr>);
        })}</tbody>
      </table></div></div>
      {editing && <ItemModal item={editing === "new" ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); inv.reload(); }} />}
      {adjust && <AdjustModal item={adjust} onClose={() => setAdjust(null)} onDone={() => { setAdjust(null); inv.reload(); }} />}
    </>
  );
}

function AdjustModal({ item, onClose, onDone }: { item: Item; onClose: () => void; onDone: () => void }) {
  const [delta, setDelta] = useState("");
  const [reason, setReason] = useState("Goods received");
  const { busy, run } = useAction();
  const d = parseInt(delta, 10);
  const valid = Number.isFinite(d) && d !== 0 && n(item.onHand) + d >= n(item.reserved);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!valid) return;
    await run(() => post(`/api/products/${item.id}/stock`, { delta: d, reason }), "Stock updated").then(onDone).catch(() => {});
  }
  return (
    <Modal title={`Adjust stock: ${item.sku}`} onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 12 }}>
        <p style={{ margin: 0, color: "var(--ink-2)" }}>On hand {n(item.onHand)}, reserved {n(item.reserved)}. Use a minus sign to reduce.</p>
        <label className="field" htmlFor="delta">Change in quantity<input id="delta" type="number" required value={delta} onChange={e => setDelta(e.target.value)} autoFocus /></label>
        <label className="field" htmlFor="reason">Reason<select id="reason" value={reason} onChange={e => setReason(e.target.value)}>
          <option>Goods received</option><option>Stock count correction</option><option>Damaged</option><option>Returned by customer</option></select></label>
        {delta && !valid && <div className="err">Stock can't go below the {n(item.reserved)} already reserved.</div>}
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy || !valid}>Save</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}

function ItemModal({ item, onClose, onDone }: { item: Item | null; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({
    sku: item?.sku ?? "", name: item?.name ?? "", unit: item?.unit ?? "pc", category: item?.category ?? "",
    listRate: String(item?.listRate ?? ""), gstRate: String(item?.gstRate ?? 18), reorderLevel: String(item?.reorderLevel ?? 0),
    patterns: item?.patterns ?? "",
  });
  const { busy, run } = useAction();
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  const badPattern = f.patterns.split("\n").map(s => s.trim()).filter(Boolean).find(s => { try { new RegExp(s); return false; } catch { return true; } });
  async function submit(e: FormEvent) {
    e.preventDefault();
    const body = { ...f, listRate: Number(f.listRate), gstRate: Number(f.gstRate), reorderLevel: Number(f.reorderLevel),
      patterns: f.patterns.split("\n").map(s => s.trim()).filter(Boolean) };
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
        </div>
        <label className="field" htmlFor="i-pat">WhatsApp match patterns (one per line)
          <textarea id="i-pat" rows={3} value={f.patterns} onChange={set("patterns")} placeholder={"32\\s*a\\s*mcb\nmcb\\s*32\\s*a"} /></label>
        <span className="sub">How retailers write this item, as patterns. Example: <code>32\s*a\s*mcb</code> matches "32A MCB" and "32 a mcb".</span>
        {badPattern && <div className="err">Invalid pattern: {badPattern}</div>}
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy || !!badPattern}>Save</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}
