import { FormEvent, useMemo, useState } from "react";
import { api, ApiError, post } from "../api";
import { Modal, useApi, useToast } from "../components/ui";
import { inr, n } from "../format";
import { can, useUser } from "../App";

interface Cust { id: number; name: string; town?: string; tierDiscount: number; creditLimit: number; exposure: number }
interface Item { id: number; sku: string; name: string; listRate: number; free: number; gstRate: number }
type Line = { productId: number | ""; qty: number };

/** Order booked by a salesperson on a visit or taken over the phone. Server prices it and checks stock and credit. */
export default function NewOrder({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const user = useUser();
  const toast = useToast();
  const customers = useApi(s => api<Cust[]>("/api/customers", { signal: s }));
  const items = useApi(s => api<Item[]>("/api/inventory", { signal: s }));
  const [customerId, setCustomerId] = useState<number | "">("");
  const [source, setSource] = useState(user.role === "SALES" ? "SALESPERSON" : "PHONE");
  const [lines, setLines] = useState<Line[]>([{ productId: "", qty: 1 }]);
  const [override, setOverride] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const cust = customers.data?.find(c => c.id === customerId);
  const estimate = useMemo(() => lines.reduce((s, l) => {
    const it = items.data?.find(i => i.id === l.productId);
    if (!it) return s;
    const rate = n(it.listRate) * (1 - n(cust?.tierDiscount) / 100);
    return s + rate * Math.min(l.qty, Math.max(0, n(it.free))) * (1 + n(it.gstRate) / 100);
  }, 0), [lines, items.data, cust]);
  const valid = customerId !== "" && lines.some(l => l.productId !== "" && l.qty > 0);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!valid || busy) return;
    setBusy(true); setErr("");
    try {
      const r = await post<{ number: number; total: number; backorders: string[] }>("/api/orders", {
        customerId, source, overrideCredit: override,
        lines: lines.filter(l => l.productId !== "" && l.qty > 0).map(l => ({ productId: l.productId, qty: l.qty })),
      });
      toast(`SO-${r.number} confirmed for ${inr(r.total)}` + (r.backorders.length ? `. Back-order: ${r.backorders.join(", ")}` : ""));
      onDone();
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : "Could not create the order");
    } finally {
      setBusy(false);
    }
  }

  const setLine = (i: number, patch: Partial<Line>) => setLines(ls => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  return (
    <Modal title="New sales order" onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 12 }}>
        <label className="field" htmlFor="no-c">Customer
          <select id="no-c" required value={customerId} onChange={e => setCustomerId(e.target.value ? Number(e.target.value) : "")}>
            <option value="">Choose a customer…</option>
            {customers.data?.map(c => <option key={c.id} value={c.id}>{c.name}{c.town ? ` · ${c.town}` : ""}</option>)}
          </select></label>
        <label className="field" htmlFor="no-s">Taken by
          <select id="no-s" value={source} onChange={e => setSource(e.target.value)}>
            <option value="SALESPERSON">Salesperson visit</option><option value="PHONE">Phone call</option></select></label>
        <div style={{ display: "grid", gap: 8 }}>
          {lines.map((l, i) => {
            const it = items.data?.find(x => x.id === l.productId);
            return (
              <div key={i} className="row-actions" style={{ flexWrap: "nowrap" }}>
                <label className="sr" htmlFor={`no-p${i}`}>Item {i + 1}</label>
                <select id={`no-p${i}`} value={l.productId} style={{ flex: 1, minWidth: 0, padding: 8, border: "1px solid var(--line)", borderRadius: 8, background: "var(--surface-2)" }}
                  onChange={e => setLine(i, { productId: e.target.value ? Number(e.target.value) : "" })}>
                  <option value="">Item…</option>
                  {items.data?.map(x => <option key={x.id} value={x.id}>{x.sku} · {x.name} ({n(x.free)} free)</option>)}
                </select>
                <label className="sr" htmlFor={`no-q${i}`}>Quantity {i + 1}</label>
                <input id={`no-q${i}`} type="number" min={1} max={100000} value={l.qty} style={{ width: 72, padding: 8, border: "1px solid var(--line)", borderRadius: 8 }}
                  onChange={e => setLine(i, { qty: Math.max(0, Math.min(100000, parseInt(e.target.value, 10) || 0)) })} />
                {lines.length > 1 && <button type="button" className="btn ghost sm" aria-label={`Remove item ${i + 1}`} onClick={() => setLines(ls => ls.filter((_, j) => j !== i))}>✕</button>}
                {it && l.qty > n(it.free) && <span className="sub" style={{ color: "var(--warn)" }}>short</span>}
              </div>);
          })}
          <button type="button" className="btn sm" style={{ justifySelf: "start" }} onClick={() => setLines(ls => [...ls, { productId: "", qty: 1 }])}>Add item</button>
        </div>
        {cust && <div className="sub">Estimate {inr(estimate)} incl. GST at tier price (special rates apply on save). Credit used {inr(cust.exposure)} of {inr(cust.creditLimit)}.</div>}
        {can(user, "OWNER") && <label style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 13 }}>
          <input type="checkbox" checked={override} onChange={e => setOverride(e.target.checked)} /> Approve if over credit limit</label>}
        {err && <div className="err" role="alert">{err}</div>}
        <div className="actions" style={{ margin: 0 }}>
          <button className="btn pri" disabled={!valid || busy}>{busy ? "Saving…" : "Confirm order"}</button>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
        </div>
      </form>
    </Modal>
  );
}
