import { Link } from "react-router-dom";
import { api } from "../api";
import { AgingBars, HBars, LineChart } from "../components/charts";
import { Kpi, Loading, Meter, PageTop, Pill, useApi } from "../components/ui";
import { lakh, n, pct, time } from "../format";
import { useUser } from "../App";

type Row = Record<string, unknown>;
interface Dash {
  kpis: Row; lowStock: Row[]; dailySales: { day: string; value: number }[]; sources: Row[];
  aging: { label: string; value: unknown; bills: unknown }[]; creditRisk: Row[]; salespeople: Row[]; activity: Row[];
}
const SOURCE: Record<string, string> = { WHATSAPP: "WhatsApp", SALESPERSON: "Salesperson", PHONE: "Phone" };

export default function Dashboard() {
  const user = useUser();
  const { data, error, reload } = useApi(s => api<Dash>("/api/dashboard", { signal: s }));
  if (!data) return <Loading error={error} onRetry={reload} />;
  const k = data.kpis;
  const series = data.dailySales.map((d, i) => ({
    label: i === data.dailySales.length - 1 ? "Today" : new Date(d.day).toLocaleDateString("en-IN", { day: "numeric", month: "short" }),
    value: n(d.value), live: i === data.dailySales.length - 1,
  }));
  const srcTotal = data.sources.reduce((s, r) => s + n(r.value), 0);
  const maxSales = Math.max(1, ...data.salespeople.map(s => Math.max(n(s.achieved), n(s.target)))) * 1.05;
  const overdueShare = n(k.receivables) ? n(k.overdue30) / n(k.receivables) : 0;

  return (
    <>
      <PageTop title={`Hello, ${user.name.split(" ")[0]}`} sub={new Date().toLocaleDateString("en-IN", { weekday: "long", day: "numeric", month: "short", year: "numeric" })}>
        <div className="row-actions">
          {n(k.newMessages) > 0 && <Link className="btn sm" to="/inbox">{n(k.newMessages)} new WhatsApp orders</Link>}
          <button className="btn sm" onClick={reload}>Refresh</button>
        </div>
      </PageTop>
      <div className="grid g-kpi">
        <Kpi label="Sales today" value={lakh(k.salesToday)} detail={`${n(k.ordersToday)} orders · same day last week ${lakh(k.salesSameDayLastWeek)}`} />
        <Kpi label="Open orders" value={n(k.openOrders)} detail={`${lakh(k.openOrderValue)} reserved, not dispatched`} />
        <Kpi label="Receivables" value={lakh(k.receivables)} detail={<Pill tone={overdueShare > 0.3 ? "bad" : "warn"}>{lakh(k.overdue30)} over 30 days</Pill>} />
        <Kpi label="Collections this month" value={lakh(k.collectedMtd)} detail={`${pct(k.collectedMtd, k.salesMtd)}% of sales ${lakh(k.salesMtd)}`} />
        <Kpi label="Low stock" value={`${data.lowStock.length} SKU${data.lowStock.length === 1 ? "" : "s"}`} detail={data.lowStock.length
          ? <Pill tone="warn">{data.lowStock.slice(0, 4).map(r => String(r.sku)).join(", ")}</Pill>
          : <Pill tone="good">All above reorder</Pill>} />
      </div>
      <div className="grid g-2">
        <div className="card"><div className="card-h"><h2>Daily sales, incl. GST</h2><span className="hint">Working days, last 30 days + today</span></div>
          <LineChart data={series} label="Daily sales for the last 30 working days" /></div>
        <div className="card"><div className="card-h"><h2>Where orders come from</h2><span className="hint">Share of value, 30 days</span></div>
          <HBars rows={data.sources.map(r => ({ label: SOURCE[String(r.source)] ?? String(r.source), value: n(r.value), max: srcTotal || 1, display: pct(r.value, srcTotal) + "%" }))} />
        </div>
      </div>
      <div className="grid g-2e">
        <div className="card"><div className="card-h"><h2>Receivables by age</h2><span className="hint">{lakh(k.receivables)} open</span></div><AgingBars data={data.aging} /></div>
        <div className="card"><div className="card-h"><h2>Credit used vs limit</h2><span className="hint">Outstanding + open orders</span></div>
          <div className="hbars">{data.creditRisk.map(c => {
            const r = n(c.exposure) / Math.max(1, n(c.limit));
            return (<div className="hbar" key={String(c.id)}>
              <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }} title={String(c.name)}>{String(c.name)}</span>
              <Meter ratio={r} />
              <span className="v">{Math.round(r * 100)}% <Pill tone={r > 1 ? "bad" : r > 0.85 ? "warn" : "good"}>{r > 1 ? "Over" : r > 0.85 ? "Near" : "OK"}</Pill></span>
            </div>);
          })}</div>
        </div>
      </div>
      <div className="grid g-2e">
        <div className="card"><div className="card-h"><h2>Salespeople, month to date</h2><span className="hint">Bar = achieved, tick = target</span></div>
          <HBars rows={data.salespeople.map(s => ({ label: String(s.name), value: n(s.achieved), target: n(s.target), max: maxSales, display: `${pct(s.achieved, s.target)}% of ${lakh(s.target)}` }))} />
          <div className="legend"><span><i></i>Achieved</span><span><i className="t"></i>Target</span></div>
        </div>
        <div className="card"><div className="card-h"><h2>Recent activity</h2></div>
          <div className="timeline">{data.activity.map((a, i) => <div key={i}><time>{time(String(a.at))}</time><span className="dot" /><span>{String(a.message)}</span></div>)}</div>
        </div>
      </div>
    </>
  );
}
