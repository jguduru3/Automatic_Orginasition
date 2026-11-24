import { FormEvent, useEffect, useRef, useState } from "react";
import { api, post } from "../api";
import { FileButton, Loading, PageTop, Pill, useAction, useApi } from "../components/ui";
import { inr, lakh, n, pct, time } from "../format";
import { DeliverButton } from "./Orders";
import { can, useUser } from "../App";

interface Sp { id: number; name: string; beat?: string; target: number; visitsToday: number; salesMtd: number; collectedMtd: number; lastCheckIn?: string }
interface Visit { id: number; salesperson: string; customer?: string; note?: string; lat?: number; lng?: number; checkedInAt: string }
interface Delivery { id: number; number: number; status: string; total: number; customer: string; town?: string; vehicle?: string; vehicleType?: string;
  driver?: string; invoice?: string; deliveredAt?: string; podPath?: string }

export default function Field() {
  const user = useUser();
  const d = useApi(s => api<{ salespeople: Sp[]; deliveries: Delivery[] }>("/api/field", { signal: s }));
  const visits = useApi(s => api<Visit[]>("/api/field/visits/today", { signal: s }));
  if (!d.data) return <Loading error={d.error} onRetry={d.reload} />;
  return (
    <>
      <PageTop title="Field & delivery" sub="Salesperson check-ins and today's delivery runs"><button className="btn sm" onClick={d.reload}>Refresh</button></PageTop>
      {(user.role === "SALES" || user.role === "DELIVERY") && <ShareLocation />}
      {can(user, "OWNER", "ACCOUNTANT") && <LiveMap />}
      {user.role === "SALES" && <CheckIn onDone={() => { d.reload(); visits.reload(); }} />}
      {user.role !== "DELIVERY" && <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(240px,1fr))" }}>
        {d.data.salespeople.map(s => (
          <div className="card" key={s.id}><div className="card-h"><h2>{s.name}</h2><Pill tone={n(s.visitsToday) > 0 ? "good" : "mute"}>{n(s.visitsToday) > 0 ? "On beat" : "No check-in yet"}</Pill></div>
            <dl className="kv"><dt>Beat</dt><dd>{s.beat ?? "–"}</dd><dt>Visits today</dt><dd className="num">{n(s.visitsToday)}</dd>
              <dt>Last check-in</dt><dd>{s.lastCheckIn ?? "–"}</dd><dt>Sales MTD</dt><dd className="num">{lakh(s.salesMtd)} of {lakh(s.target)} ({pct(s.salesMtd, s.target)}%)</dd>
              <dt>Collected MTD</dt><dd className="num">{lakh(s.collectedMtd)}</dd></dl>
          </div>))}
      </div>}
      {user.role !== "DELIVERY" && <div className="card"><div className="card-h"><h2>Visits today</h2><span className="hint">{visits.data?.length ?? 0} check-ins</span></div>
        {!visits.data?.length ? <div className="empty">No check-ins yet today.</div> : <div className="tbl-wrap"><table><tbody>
          {visits.data.map(v => <tr key={v.id}><td className="num">{time(v.checkedInAt)}</td><td>{v.salesperson}</td><td>{v.customer ?? "No customer"}{v.note && <span className="sub">{v.note}</span>}</td>
            <td>{v.lat != null && v.lng != null ? <a href={`https://www.google.com/maps?q=${v.lat},${v.lng}`} target="_blank" rel="noreferrer">Map</a> : <span className="sub">No location</span>}</td></tr>)}
        </tbody></table></div>}</div>}
      <div className="card"><div className="card-h"><h2>Deliveries today</h2></div><div className="tbl-wrap"><table>
        <thead><tr><th>Vehicle</th><th>Order</th><th>Drop</th><th className="r">Invoice value</th><th>Status</th><th /></tr></thead>
        <tbody>
          {d.data.deliveries.length === 0 && <tr><td colSpan={6} className="empty">No vehicles out right now.</td></tr>}
          {d.data.deliveries.map(o => (<tr key={o.id}>
            <td className="num">{o.vehicle}<span className="sub">{o.vehicleType} · {o.driver}</span></td>
            <td className="num">SO-{o.number}<span className="sub">{o.invoice}</span></td>
            <td>{o.customer}<span className="sub">{o.town}</span></td>
            <td className="r num">{inr(o.total)}</td>
            <td>{o.status === "DISPATCHED" ? <Pill tone="warn">Out for delivery</Pill> : <Pill tone="good">Delivered {time(o.deliveredAt)}</Pill>}
              {o.podPath && <FileButton path={o.podPath} label="Proof photo" />}</td>
            <td>{o.status === "DISPATCHED" && can(user, "OWNER", "ACCOUNTANT", "DELIVERY") && <div className="row-actions"><DeliverButton id={o.id} number={o.number} onDone={d.reload} /></div>}</td>
          </tr>))}
        </tbody>
      </table></div></div>
    </>
  );
}

function CheckIn({ onDone }: { onDone: () => void }) {
  const customers = useApi(s => api<{ id: number; name: string; town?: string }[]>("/api/customers", { signal: s }));
  const [customerId, setCustomerId] = useState("");
  const [note, setNote] = useState("");
  const { busy, run } = useAction();
  const position = () => new Promise<{ lat?: number; lng?: number }>(resolve => {
    if (!navigator.geolocation) return resolve({});
    navigator.geolocation.getCurrentPosition(p => resolve({ lat: p.coords.latitude, lng: p.coords.longitude }), () => resolve({}), { timeout: 8000, maximumAge: 60000 });
  });
  async function submit(e: FormEvent) {
    e.preventDefault();
    const loc = await position();
    await run(() => post("/api/field/visits", { customerId: customerId ? Number(customerId) : undefined, note: note || undefined, ...loc }),
      loc.lat != null ? "Checked in with location" : "Checked in (location not shared)").then(() => { setNote(""); onDone(); }).catch(() => {});
  }
  return (
    <form className="card" onSubmit={submit} style={{ display: "grid", gap: 10 }}>
      <div className="card-h" style={{ margin: 0 }}><h2>Check in at a shop</h2><span className="hint">Shares your location if you allow it</span></div>
      <div className="row-actions">
        <label className="sr" htmlFor="ci-c">Customer</label>
        <select id="ci-c" value={customerId} onChange={e => setCustomerId(e.target.value)} style={{ padding: 8, border: "1px solid var(--line)", borderRadius: 8, background: "var(--surface-2)", flex: 1, minWidth: 0 }}>
          <option value="">Choose shop…</option>{customers.data?.map(c => <option key={c.id} value={c.id}>{c.name}{c.town ? ` · ${c.town}` : ""}</option>)}</select>
        <label className="sr" htmlFor="ci-n">Note</label>
        <input id="ci-n" maxLength={500} placeholder="Note (optional)" value={note} onChange={e => setNote(e.target.value)} style={{ padding: 8, border: "1px solid var(--line)", borderRadius: 8, flex: 1, minWidth: 0 }} />
        <button className="btn pri" disabled={busy || !customerId}>{busy ? "Checking in…" : "Check in"}</button>
      </div>
    </form>
  );
}

/** Sends the phone's position while this screen is open (at most once a minute). */
function ShareLocation() {
  const [on, setOn] = useState(false);
  const [status, setStatus] = useState("");
  const last = useRef(0);
  useEffect(() => {
    if (!on) return;
    if (!navigator.geolocation) { setStatus("This device can't share location"); setOn(false); return; }
    const id = navigator.geolocation.watchPosition(p => {
      const now = Date.now();
      if (now - last.current < 60_000) return;
      last.current = now;
      post<{ stored: boolean }>("/api/field/location", { lat: p.coords.latitude, lng: p.coords.longitude, accuracy: Math.round(p.coords.accuracy) })
        .then(() => setStatus(`Location sent at ${time(new Date().toISOString())}`)).catch(() => setStatus("Could not send location, will retry"));
    }, () => { setStatus("Location permission denied"); setOn(false); }, { enableHighAccuracy: true, maximumAge: 30_000, timeout: 20_000 });
    return () => navigator.geolocation.clearWatch(id);
  }, [on]);
  return (
    <div className="card"><div className="card-h" style={{ margin: 0 }}><h2>Live location</h2>
      <div className="row-actions"><span className="sub">{on ? status || "Waiting for GPS…" : "Off"}</span>
        <button className="btn sm" aria-pressed={on} onClick={() => { setStatus(""); setOn(o => !o); }}>{on ? "Stop sharing" : "Share while on duty"}</button></div></div>
      <span className="sub">Shared only while this screen is open. Your manager sees your last position and today's route.</span>
    </div>
  );
}

interface Live { userId: number; name: string; role: string; lat: number; lng: number; accuracy?: number; recordedAt: string; ageSeconds: number; pointsToday: number }

function LiveMap() {
  const live = useApi(s => api<Live[]>("/api/field/live", { signal: s }));
  const [route, setRoute] = useState<string | null>(null);
  useEffect(() => { const t = window.setInterval(live.reload, 60_000); return () => window.clearInterval(t); }, [live.reload]);
  async function openRoute(p: Live) {
    const pts = await api<{ lat: number; lng: number }[]>(`/api/field/trail/${p.userId}`);
    if (pts.length < 2) { setRoute(`https://www.google.com/maps?q=${p.lat},${p.lng}`); return; }
    const step = Math.max(1, Math.ceil(pts.length / 10)); // Google Maps allows ~10 waypoints in a link
    const sample = pts.filter((_, i) => i % step === 0);
    const last = pts[pts.length - 1];
    setRoute(`https://www.google.com/maps/dir/${[...sample, last].map(x => `${x.lat},${x.lng}`).join("/")}`);
  }
  if (!live.data) return null;
  return (
    <div className="card"><div className="card-h"><h2>Team on the move</h2><span className="hint">Refreshes every minute</span></div>
      {live.data.length === 0 ? <div className="empty">No one is sharing location right now.</div> : <div className="tbl-wrap"><table><tbody>
        {live.data.map(p => <tr key={p.userId}><td>{p.name}<span className="sub">{p.role === "DELIVERY" ? "Delivery" : "Sales"}</span></td>
          <td>{p.ageSeconds < 300 ? <Pill tone="good">Live</Pill> : <Pill tone="mute">{Math.round(p.ageSeconds / 60)} min ago</Pill>}</td>
          <td className="sub">{n(p.pointsToday)} points today{p.accuracy ? ` · ±${Math.round(p.accuracy)} m` : ""}</td>
          <td><div className="row-actions"><a className="btn ghost sm" href={`https://www.google.com/maps?q=${p.lat},${p.lng}`} target="_blank" rel="noreferrer">Where now</a>
            <button className="btn ghost sm" onClick={() => openRoute(p)}>Today's route</button></div></td></tr>)}
      </tbody></table></div>}
      {route && <p style={{ margin: "10px 0 0" }}><a href={route} target="_blank" rel="noreferrer">Open today's route in Google Maps</a></p>}
    </div>
  );
}
