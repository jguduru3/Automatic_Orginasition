import { useState } from "react";
import { api, post } from "../api";
import { FileButton, Loading, PageTop, StatusPill, useAction, useApi, useToast } from "../components/ui";
import NewOrder from "./NewOrder";
import { dayLabel, inr, lakh, n } from "../format";
import { can, useUser } from "../App";

export interface Order { id: number; number: number; status: string; source: string; total: number; createdAt: string;
  customer: string; town?: string; vehicle?: string; driver?: string; invoice?: string; summary?: string; backorder: number; podPath?: string }
export interface Vehicle { id: number; regNo: string; type: string; driver: string }

const FILTERS = ["OPEN", "ALL", "CONFIRMED", "PACKED", "DISPATCHED", "DELIVERED", "CANCELLED"];
const LABEL: Record<string, string> = { OPEN: "Open", ALL: "All", CONFIRMED: "Confirmed", PACKED: "Packed", DISPATCHED: "Out for delivery", DELIVERED: "Delivered", CANCELLED: "Cancelled" };
const SRC: Record<string, string> = { WHATSAPP: "WhatsApp", SALESPERSON: "Salesperson", PHONE: "Phone" };

export default function Orders() {
  const user = useUser();
  const [filter, setFilter] = useState("OPEN");
  const orders = useApi(s => api<Order[]>(`/api/orders?status=${filter}`, { signal: s }), [filter]);
  const summary = useApi(s => api<{ status: string; count: number; value: number }[]>("/api/orders/summary", { signal: s }));
  const vehicles = useApi(s => api<Vehicle[]>("/api/orders/vehicles", { signal: s }));
  const reload = () => { orders.reload(); summary.reload(); };
  const maxC = Math.max(1, ...(summary.data ?? []).map(s => n(s.count)));
  const [creating, setCreating] = useState(false);

  return (
    <>
      <PageTop title="Sales orders" sub="Confirmed orders hold stock. Dispatch raises the invoice and queues it for Tally.">
        {can(user, "OWNER", "ACCOUNTANT", "SALES") && <button className="btn pri sm" onClick={() => setCreating(true)}>New order</button>}
      </PageTop>
      {creating && <NewOrder onClose={() => setCreating(false)} onDone={() => { setCreating(false); reload(); }} />}
      {summary.data && <div className="pipe">{summary.data.map(s => (
        <div className="stage" key={s.status}><small>{LABEL[s.status]}{s.status === "DELIVERED" ? " today" : ""}</small>
          <b>{n(s.count)}</b> <span className="num" style={{ color: "var(--muted)", fontSize: 12 }}>{lakh(s.value)}</span>
          <div className="bar" style={{ width: (n(s.count) / maxC) * 100 + "%" }} /></div>))}</div>}
      <div className="card">
        <div className="card-h">
          <div className="filters" role="tablist">{FILTERS.map(f => <button key={f} role="tab" aria-selected={filter === f} className={"chip" + (filter === f ? " on" : "")} onClick={() => setFilter(f)}>{LABEL[f]}</button>)}</div>
          <span className="hint">{orders.data?.length ?? 0} orders</span>
        </div>
        {!orders.data ? <Loading error={orders.error} onRetry={orders.reload} /> : (
          <div className="tbl-wrap"><table>
            <thead><tr><th>Order</th><th>Customer</th><th>Source</th><th className="r">Value</th><th>Status</th><th>Next step</th></tr></thead>
            <tbody>
              {orders.data.length === 0 && <tr><td colSpan={6} className="empty">No orders here.</td></tr>}
              {orders.data.map(o => <OrderRow key={o.id} o={o} vehicles={vehicles.data ?? []} onChanged={reload} canCancel={can(user, "OWNER", "ACCOUNTANT")}
                canMove={can(user, "OWNER", "ACCOUNTANT", "DELIVERY")} />)}
            </tbody>
          </table></div>
        )}
      </div>
    </>
  );
}

function OrderRow({ o, vehicles, onChanged, canCancel, canMove }: { o: Order; vehicles: Vehicle[]; onChanged: () => void; canCancel: boolean; canMove: boolean }) {
  const { busy, run } = useAction();
  const [veh, setVeh] = useState<number | "">(vehicles[0]?.id ?? "");
  const act = (path: string, body: unknown, msg: string | ((r: Record<string, string>) => string)) =>
    run(() => post<Record<string, string>>(`/api/orders/${o.id}/${path}`, body), msg).then(onChanged).catch(() => {});
  return (
    <tr>
      <td className="num">SO-{o.number}<span className="sub">{dayLabel(o.createdAt)}</span></td>
      <td>{o.customer}<span className="sub">{o.summary}{n(o.backorder) > 0 ? ` · ${o.backorder} on back-order` : ""}</span></td>
      <td>{SRC[o.source] ?? o.source}</td>
      <td className="r num">{inr(o.total)}</td>
      <td><StatusPill status={o.status} /></td>
      <td><div className="row-actions">
        {canMove && o.status === "CONFIRMED" && <button className="btn sm" disabled={busy} onClick={() => act("pack", {}, `SO-${o.number} packed`)}>Mark packed</button>}
        {canMove && o.status === "PACKED" && <>
          <label className="sr" htmlFor={`veh-${o.id}`}>Vehicle for SO-{o.number}</label>
          <select id={`veh-${o.id}`} value={veh} onChange={e => setVeh(Number(e.target.value))} style={{ padding: 4, border: "1px solid var(--line)", borderRadius: 6, background: "var(--surface-2)", fontSize: 12 }}>
            {vehicles.map(v => <option key={v.id} value={v.id}>{v.regNo} · {v.driver.split(" ")[0]}</option>)}
          </select>
          <button className="btn pri sm" disabled={busy || veh === ""} onClick={() => act("dispatch", { vehicleId: veh }, r => `${r.invoice} raised and queued for Tally. Loaded on ${r.vehicle} (${r.driver}).`)}>Dispatch & invoice</button>
        </>}
        {canMove && o.status === "DISPATCHED" && <DeliverButton id={o.id} number={o.number} onDone={onChanged} />}
        {o.status === "DISPATCHED" && <span className="sub">{o.vehicle}</span>}
        {o.status === "DELIVERED" && <span className="sub">{o.invoice} · delivered</span>}
        {o.podPath && <FileButton path={o.podPath} label="Proof photo" />}
        {canCancel && (o.status === "CONFIRMED" || o.status === "PACKED") && <button className="btn ghost sm" disabled={busy} onClick={() => act("cancel", {}, `SO-${o.number} cancelled, stock released`)}>Cancel</button>}
      </div></td>
    </tr>
  );
}

export function DeliverButton({ id, number, onDone }: { id: number; number: number; onDone: () => void }) {
  const { busy, run } = useAction();
  const toast = useToast();
  async function deliver(file?: File) {
    const fd = new FormData();
    if (file) fd.append("pod", file);
    await run(() => api(`/api/orders/${id}/deliver`, { method: "POST", form: fd }), file ? `SO-${number} delivered, photo saved` : `SO-${number} delivered`)
      .then(onDone).catch(() => {});
  }
  return (
    <>
      <label className="btn sm" style={{ cursor: busy ? "wait" : "pointer" }}>
        Delivered + photo
        <input type="file" accept="image/jpeg,image/png" capture="environment" hidden disabled={busy}
          onChange={e => { const f = e.target.files?.[0]; e.target.value = ""; if (f) { if (f.size > 5 * 1024 * 1024) toast("Photo is larger than 5 MB"); else deliver(f); } }} />
      </label>
      <button className="btn ghost sm" disabled={busy} onClick={() => deliver()}>No photo</button>
    </>
  );
}
