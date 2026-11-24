import { FormEvent, useState } from "react";
import { api, post } from "../api";
import { Kpi, Loading, Modal, PageTop, Pill, useAction, useApi } from "../components/ui";
import { inr, lakh, n } from "../format";
import { can, useUser } from "../App";

interface Item { id: number; sku: string; name: string; unit: string; category: string; listRate: number; reorderLevel: number;
  onHand: number; reserved: number; free: number }

export default function Inventory() {
  const user = useUser();
  const inv = useApi(s => api<Item[]>("/api/inventory", { signal: s }));
  const [adjust, setAdjust] = useState<Item | null>(null);
  if (!inv.data) return <Loading error={inv.error} onRetry={inv.reload} />;
  const rows = inv.data;
  const value = rows.reduce((s, p) => s + n(p.onHand) * n(p.listRate), 0);
  const reservedValue = rows.reduce((s, p) => s + n(p.reserved) * n(p.listRate), 0);
  const low = rows.filter(p => n(p.free) < n(p.reorderLevel));
  return (
    <>
      <PageTop title="Inventory" sub="Free stock = on hand minus what open orders have reserved." />
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
            <td>{can(user, "OWNER", "ACCOUNTANT") && <button className="btn sm" onClick={() => setAdjust(p)}>Adjust stock</button>}</td>
          </tr>);
        })}</tbody>
      </table></div></div>
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
