import { FormEvent, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api, post, put } from "../api";
import { Loading, Meter, Modal, PageTop, Pill, useAction, useApi } from "../components/ui";
import { inr, lakh, n } from "../format";
import { can, useUser } from "../App";

interface Customer { id: number; name: string; town?: string; phone: string; gstin?: string; salespersonId?: number; creditLimit: number; tierCode: string; tierName: string;
  tierDiscount: number; salesperson?: string; outstanding: number; oldestDays: number; openOrders: number; exposure: number; specialSkus?: string }
interface Tier { code: string; name: string; discount: number }

export default function Customers() {
  const user = useUser();
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState("");
  const list = useApi(s => api<Customer[]>(`/api/customers${q ? `?q=${encodeURIComponent(q)}` : ""}`, { signal: s }), [q]);
  const tiers = useApi(s => api<Tier[]>("/api/customers/tiers", { signal: s }));
  const [edit, setEdit] = useState<Customer | null>(null);
  const [details, setDetails] = useState<Customer | null>(null);
  const [prices, setPrices] = useState<Customer | null>(null);
  const addPhone = params.get("add");
  const editor = can(user, "OWNER", "ACCOUNTANT");

  return (
    <>
      <PageTop title="Customers & credit" sub="Price tier, special rates and credit limits apply automatically to every order.">
        <div className="row-actions">
          <label className="sr" htmlFor="cq">Search customers</label>
          <input id="cq" placeholder="Search name, town, phone" value={q} onChange={e => setQ(e.target.value)}
            style={{ padding: 7, border: "1px solid var(--line)", borderRadius: 8, background: "var(--surface)" }} />
          {editor && <button className="btn pri sm" onClick={() => setParams({ add: "" })}>Add customer</button>}
        </div>
      </PageTop>
      {!list.data ? <Loading error={list.error} onRetry={list.reload} /> : (
        <div className="card"><div className="tbl-wrap"><table>
          <thead><tr><th>Customer</th><th>Pricing</th><th className="r">Limit</th><th className="r">Exposure</th><th>Used</th><th className="r">Oldest bill</th><th>Status</th><th /></tr></thead>
          <tbody>
            {list.data.length === 0 && <tr><td colSpan={8} className="empty">No customers match.</td></tr>}
            {list.data.map(c => {
              const r = n(c.exposure) / Math.max(1, n(c.creditLimit));
              const bad = r > 1 || c.oldestDays > 90, warn = r > 0.85 || c.oldestDays > 60;
              return (<tr key={c.id}>
                <td>{c.name}<span className="sub">{[c.town, "+" + c.phone, c.salesperson].filter(Boolean).join(" · ")}</span></td>
                <td>{c.tierName}{n(c.tierDiscount) > 0 ? ` (−${n(c.tierDiscount)}%)` : ""}{c.specialSkus && <span className="sub">Special rates: {c.specialSkus}</span>}</td>
                <td className="r num">{lakh(c.creditLimit)}</td><td className="r num">{lakh(c.exposure)}</td>
                <td><Meter ratio={r} /><span className="sub">{Math.round(r * 100)}%</span></td>
                <td className="r num">{c.oldestDays} d</td>
                <td><Pill tone={bad ? "bad" : warn ? "warn" : "good"}>{bad ? "Hold" : warn ? "Watch" : "Good"}</Pill></td>
                <td><div className="row-actions">{editor && <button className="btn sm" onClick={() => setEdit(c)}>Edit credit</button>}
                  {editor && <button className="btn ghost sm" onClick={() => setDetails(c)}>Details</button>}
                  {editor && <button className="btn ghost sm" onClick={() => setPrices(c)}>Special rates</button>}</div></td>
              </tr>);
            })}
          </tbody>
        </table></div></div>
      )}
      {edit && tiers.data && <CreditModal c={edit} tiers={tiers.data} onClose={() => setEdit(null)} onDone={() => { setEdit(null); list.reload(); }} />}
      {details && <DetailsModal c={details} onClose={() => setDetails(null)} onDone={() => { setDetails(null); list.reload(); }} />}
      {prices && <PricesModal c={prices} onClose={() => setPrices(null)} onDone={() => { setPrices(null); list.reload(); }} />}
      {addPhone !== null && editor && tiers.data && <AddModal phone={addPhone} tiers={tiers.data} onClose={() => setParams({})}
        onDone={() => { setParams({}); list.reload(); }} />}
    </>
  );
}

function CreditModal({ c, tiers, onClose, onDone }: { c: Customer; tiers: Tier[]; onClose: () => void; onDone: () => void }) {
  const [limit, setLimit] = useState(String(n(c.creditLimit)));
  const [tier, setTier] = useState(c.tierCode);
  const { busy, run } = useAction();
  const v = Number(limit);
  async function submit(e: FormEvent) {
    e.preventDefault();
    await run(() => put(`/api/customers/${c.id}/credit`, { creditLimit: v, tierCode: tier }), "Saved").then(onDone).catch(() => {});
  }
  return (
    <Modal title="Credit limit and pricing" onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 12 }}>
        <p style={{ margin: 0, color: "var(--ink-2)" }}>{c.name} · current exposure {inr(c.exposure)}</p>
        <label className="field" htmlFor="lim">Credit limit (₹)<input id="lim" type="number" min={0} step={1000} required value={limit} onChange={e => setLimit(e.target.value)} /></label>
        <label className="field" htmlFor="tier">Price tier<select id="tier" value={tier} onChange={e => setTier(e.target.value)}>
          {tiers.map(t => <option key={t.code} value={t.code}>{t.name}{n(t.discount) ? ` (−${n(t.discount)}%)` : ""}</option>)}</select></label>
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy || !(v >= 0)}>Save</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}

function AddModal({ phone, tiers, onClose, onDone }: { phone: string; tiers: Tier[]; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ name: "", town: "", phone, tierCode: tiers[tiers.length - 1]?.code ?? "", creditLimit: "50000" });
  const { busy, run } = useAction();
  const phoneOk = /^\d{10,15}$/.test(f.phone.replace(/\D/g, ""));
  async function submit(e: FormEvent) {
    e.preventDefault();
    let p = f.phone.replace(/\D/g, "");
    if (p.length === 10) p = "91" + p;
    await run(() => post("/api/customers", { ...f, phone: p, creditLimit: Number(f.creditLimit) }), "Customer added").then(onDone).catch(() => {});
  }
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  return (
    <Modal title="Add customer" onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 12 }}>
        <label className="field" htmlFor="cn">Shop name<input id="cn" required maxLength={200} value={f.name} onChange={set("name")} autoFocus /></label>
        <label className="field" htmlFor="ct">Town / area<input id="ct" maxLength={100} value={f.town} onChange={set("town")} /></label>
        <label className="field" htmlFor="cp">WhatsApp number<input id="cp" required inputMode="tel" value={f.phone} onChange={set("phone")} placeholder="98220 41000" /></label>
        <label className="field" htmlFor="ctr">Price tier<select id="ctr" value={f.tierCode} onChange={set("tierCode")}>{tiers.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}</select></label>
        <label className="field" htmlFor="cl">Credit limit (₹)<input id="cl" type="number" min={0} step={1000} required value={f.creditLimit} onChange={set("creditLimit")} /></label>
        {f.phone && !phoneOk && <div className="err">Enter a 10-digit mobile number or a full number with country code.</div>}
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy || !phoneOk || !f.name.trim()}>Add customer</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}

function DetailsModal({ c, onClose, onDone }: { c: Customer; onClose: () => void; onDone: () => void }) {
  const sps = useApi(s => api<{ id: number; name: string }[]>("/api/field/salespeople", { signal: s }));
  const [f, setF] = useState({ name: c.name, town: c.town ?? "", gstin: c.gstin ?? "", salespersonId: c.salespersonId ? String(c.salespersonId) : "" });
  const { busy, run } = useAction();
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  async function submit(e: FormEvent) {
    e.preventDefault();
    await run(() => put(`/api/customers/${c.id}`, { name: f.name, town: f.town || undefined, gstin: f.gstin || undefined,
      salespersonId: f.salespersonId ? Number(f.salespersonId) : undefined }), "Saved").then(onDone).catch(() => {});
  }
  return (
    <Modal title="Customer details" onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 12 }}>
        <label className="field" htmlFor="d-n">Shop name<input id="d-n" required maxLength={200} value={f.name} onChange={set("name")} /></label>
        <label className="field" htmlFor="d-t">Town / area<input id="d-t" maxLength={100} value={f.town} onChange={set("town")} /></label>
        <label className="field" htmlFor="d-g">GSTIN<input id="d-g" maxLength={15} value={f.gstin} onChange={set("gstin")} /></label>
        <label className="field" htmlFor="d-s">Salesperson<select id="d-s" value={f.salespersonId} onChange={set("salespersonId")}>
          <option value="">None</option>{sps.data?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy}>Save</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}

function PricesModal({ c, onClose, onDone }: { c: Customer; onClose: () => void; onDone: () => void }) {
  const items = useApi(s => api<{ id: number; sku: string; name: string; listRate: number }[]>("/api/inventory", { signal: s }));
  const current = useApi(s => api<{ productId: number; rate: number }[]>(`/api/customers/${c.id}/prices`, { signal: s }));
  const [rows, setRows] = useState<{ productId: number | ""; rate: string }[] | null>(null);
  const { busy, run } = useAction();
  const data = rows ?? current.data?.map(r => ({ productId: r.productId, rate: String(r.rate) })) ?? null;
  if (!data) return <Modal title="Special rates" onClose={onClose}><Loading error={current.error} /></Modal>;
  const update = (i: number, patch: Partial<{ productId: number | ""; rate: string }>) => setRows(data.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  async function submit(e: FormEvent) {
    e.preventDefault();
    const body = data!.filter(r => r.productId !== "" && r.rate !== "").map(r => ({ productId: r.productId, rate: Number(r.rate) }));
    await run(() => put(`/api/customers/${c.id}/prices`, body), `${body.length} special rates saved`).then(onDone).catch(() => {});
  }
  return (
    <Modal title={`Special rates: ${c.name}`} onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 8 }}>
        <span className="sub">These override the tier discount for this customer only.</span>
        {data.map((r, i) => (
          <div key={i} className="row-actions" style={{ flexWrap: "nowrap" }}>
            <label className="sr" htmlFor={`sp-p${i}`}>Item</label>
            <select id={`sp-p${i}`} value={r.productId} onChange={e => update(i, { productId: e.target.value ? Number(e.target.value) : "" })}
              style={{ flex: 1, minWidth: 0, padding: 8, border: "1px solid var(--line)", borderRadius: 8, background: "var(--surface-2)" }}>
              <option value="">Item…</option>{items.data?.map(x => <option key={x.id} value={x.id}>{x.sku} · list {inr(x.listRate)}</option>)}</select>
            <label className="sr" htmlFor={`sp-r${i}`}>Rate</label>
            <input id={`sp-r${i}`} type="number" min={0} step="0.01" value={r.rate} onChange={e => update(i, { rate: e.target.value })}
              style={{ width: 100, padding: 8, border: "1px solid var(--line)", borderRadius: 8 }} />
            <button type="button" className="btn ghost sm" aria-label="Remove rate" onClick={() => setRows(data.filter((_, j) => j !== i))}>✕</button>
          </div>))}
        <button type="button" className="btn sm" style={{ justifySelf: "start" }} onClick={() => setRows([...data, { productId: "", rate: "" }])}>Add rate</button>
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy}>Save rates</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}
