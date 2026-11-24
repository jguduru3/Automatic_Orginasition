import { api } from "../api";
import { Loading, PageTop, Pill, useApi } from "../components/ui";
import { inr, lakh, n, pct, time } from "../format";
import { DeliverButton } from "./Orders";
import { can, useUser } from "../App";

interface Sp { id: number; name: string; beat?: string; target: number; visitsToday: number; salesMtd: number; collectedMtd: number; lastCheckIn?: string }
interface Delivery { id: number; number: number; status: string; total: number; customer: string; town?: string; vehicle?: string; vehicleType?: string;
  driver?: string; invoice?: string; deliveredAt?: string; podPath?: string }

export default function Field() {
  const user = useUser();
  const d = useApi(s => api<{ salespeople: Sp[]; deliveries: Delivery[] }>("/api/field", { signal: s }));
  if (!d.data) return <Loading error={d.error} onRetry={d.reload} />;
  return (
    <>
      <PageTop title="Field & delivery" sub="Salesperson check-ins and today's delivery runs"><button className="btn sm" onClick={d.reload}>Refresh</button></PageTop>
      {user.role !== "DELIVERY" && <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(240px,1fr))" }}>
        {d.data.salespeople.map(s => (
          <div className="card" key={s.id}><div className="card-h"><h2>{s.name}</h2><Pill tone={n(s.visitsToday) > 0 ? "good" : "mute"}>{n(s.visitsToday) > 0 ? "On beat" : "No check-in yet"}</Pill></div>
            <dl className="kv"><dt>Beat</dt><dd>{s.beat ?? "–"}</dd><dt>Visits today</dt><dd className="num">{n(s.visitsToday)}</dd>
              <dt>Last check-in</dt><dd>{s.lastCheckIn ?? "–"}</dd><dt>Sales MTD</dt><dd className="num">{lakh(s.salesMtd)} of {lakh(s.target)} ({pct(s.salesMtd, s.target)}%)</dd>
              <dt>Collected MTD</dt><dd className="num">{lakh(s.collectedMtd)}</dd></dl>
          </div>))}
      </div>}
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
              {o.podPath && <span className="sub">Photo proof saved</span>}</td>
            <td>{o.status === "DISPATCHED" && can(user, "OWNER", "ACCOUNTANT", "DELIVERY") && <div className="row-actions"><DeliverButton id={o.id} number={o.number} onDone={d.reload} /></div>}</td>
          </tr>))}
        </tbody>
      </table></div></div>
    </>
  );
}
