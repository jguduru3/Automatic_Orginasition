import { useEffect, useRef, useState } from "react";
import { inr, n } from "../format";

function useWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(600);
  useEffect(() => {
    if (!ref.current) return;
    const el = ref.current;
    setW(el.clientWidth || 600);
    const ro = new ResizeObserver(() => setW(el.clientWidth || 600));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w] as const;
}

export function niceMax(v: number) {
  if (v <= 0) return 1;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  const x = v / p;
  return (x <= 1 ? 1 : x <= 2 ? 2 : x <= 2.5 ? 2.5 : x <= 5 ? 5 : x <= 6 ? 6 : x <= 8 ? 8 : 10) * p;
}
const lk = (v: number) => (v === 0 ? "0" : (v / 1e5).toFixed(v % 1e5 ? 1 : 0) + "L");

type Tip = { x: number; y: number; title: string; sub: string } | null;
const Tooltip = ({ tip }: { tip: Tip }) => tip ? (
  <div className="tip" style={{ display: "block", left: Math.min(tip.x + 14, window.innerWidth - 200), top: Math.max(8, tip.y - 54) }}>
    <strong>{tip.title}</strong><span>{tip.sub}</span>
  </div>
) : null;

export function LineChart({ data, label }: { data: { label: string; value: number; live?: boolean }[]; label: string }) {
  const [ref, W] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const [tip, setTip] = useState<Tip>(null);
  const H = 220, m = { l: 48, r: 16, t: 12, b: 26 };
  const iw = Math.max(10, W - m.l - m.r), ih = H - m.t - m.b;
  if (data.length < 2) return <div ref={ref} className="empty">Not enough history yet</div>;
  const max = niceMax(Math.max(...data.map(d => d.value)) * 1.05);
  const x = (i: number) => m.l + i * (iw / (data.length - 1));
  const y = (v: number) => m.t + ih - (v / max) * ih;
  const path = data.map((d, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(d.value).toFixed(1)}`).join("");
  const area = `${path}L${x(data.length - 1)},${m.t + ih}L${m.l},${m.t + ih}Z`;
  const ticks = [0, max / 4, max / 2, (max * 3) / 4, max];
  const step = Math.max(1, Math.round(data.length / 6));
  const last = data.length - 1;
  return (
    <div ref={ref} className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} height={H} role="img" aria-label={label}
        onPointerLeave={() => { setHover(null); setTip(null); }}
        onPointerMove={e => {
          const r = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          const sx = ((e.clientX - r.left) * W) / r.width;
          const i = Math.max(0, Math.min(last, Math.round((sx - m.l) / (iw / last))));
          setHover(i);
          setTip({ x: e.clientX, y: e.clientY, title: inr(data[i].value), sub: data[i].label + (data[i].live ? " · so far" : "") });
        }}>
        <defs><linearGradient id="ga" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor="var(--s1)" stopOpacity=".22" /><stop offset="1" stopColor="var(--s1)" stopOpacity="0" /></linearGradient></defs>
        <g className="axis">
          {ticks.map(t => <g key={t}><line className="gridl" x1={m.l} x2={W - m.r} y1={y(t)} y2={y(t)} /><text x={m.l - 8} y={y(t) + 4} textAnchor="end">{lk(t)}</text></g>)}
          {data.map((d, i) => (i % step === 0 || i === last) && (i === last || last - i >= step / 2)
            ? <text key={i} x={x(i)} y={H - 6} textAnchor={i === last ? "end" : "middle"}>{d.label}</text> : null)}
        </g>
        <path d={area} fill="url(#ga)" />
        <path d={path} fill="none" stroke="var(--s1)" strokeWidth={2} strokeLinejoin="round" />
        <circle cx={x(last)} cy={y(data[last].value)} r={5} fill="var(--s1)" stroke="var(--surface)" strokeWidth={2} />
        {hover !== null && <g>
          <line x1={x(hover)} x2={x(hover)} y1={m.t} y2={m.t + ih} stroke="var(--muted)" strokeDasharray="3 3" />
          <circle cx={x(hover)} cy={y(data[hover].value)} r={5} fill="var(--s1)" stroke="var(--surface)" strokeWidth={2} />
        </g>}
      </svg>
      <Tooltip tip={tip} />
    </div>
  );
}

const RAMP = ["var(--r250)", "var(--r350)", "var(--r450)", "var(--r600)"];
export function AgingBars({ data }: { data: { label: string; value: unknown; bills: unknown }[] }) {
  const [ref, W] = useWidth();
  const [tip, setTip] = useState<Tip>(null);
  const H = 190, m = { l: 44, r: 8, t: 18, b: 30 }, iw = Math.max(10, W - m.l - m.r), ih = H - m.t - m.b;
  const vals = data.map(d => n(d.value));
  const max = niceMax(Math.max(...vals, 1) * 1.1);
  const bw = Math.min(56, (iw / Math.max(1, data.length)) * 0.6);
  const y = (v: number) => m.t + ih - (v / max) * ih;
  return (
    <div ref={ref} className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} height={H} role="img" aria-label="Receivables by age of bill">
        <g className="axis">{[0, max / 2, max].map(t => <g key={t}><line className="gridl" x1={m.l} x2={W - m.r} y1={y(t)} y2={y(t)} /><text x={m.l - 8} y={y(t) + 4} textAnchor="end">{lk(t)}</text></g>)}</g>
        {data.map((d, i) => {
          const slot = iw / data.length, cx = m.l + slot * (i + 0.5), h = Math.max(0, (vals[i] / max) * ih), x0 = cx - bw / 2, y0 = m.t + ih - h, r = Math.min(4, h);
          return (
            <g key={d.label} onPointerMove={e => setTip({ x: e.clientX, y: e.clientY, title: inr(vals[i]), sub: `${d.label} · ${n(d.bills)} bills` })}
               onPointerLeave={() => setTip(null)}>
              <path d={`M${x0},${m.t + ih}V${y0 + r}Q${x0},${y0} ${x0 + r},${y0}H${x0 + bw - r}Q${x0 + bw},${y0} ${x0 + bw},${y0 + r}V${m.t + ih}Z`} fill={RAMP[i % 4]} />
              <text x={cx} y={y0 - 5} textAnchor="middle" style={{ fill: "var(--ink-2)", font: "500 11px var(--f-num)" }}>{lk(vals[i])}</text>
              <text x={cx} y={H - 10} textAnchor="middle" style={{ fill: "var(--muted)", font: "11px var(--f-body)" }}>{d.label}</text>
              <rect x={cx - slot / 2} y={m.t} width={slot} height={ih} fill="transparent" />
            </g>
          );
        })}
      </svg>
      <Tooltip tip={tip} />
    </div>
  );
}

export function HBars({ rows }: { rows: { label: string; value: number; display: string; target?: number; max: number }[] }) {
  return (
    <div className="hbars">
      {rows.map(r => (
        <div className="hbar" key={r.label}>
          <span title={r.label} style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{r.label}</span>
          <div className="track">
            <div className="fill" style={{ width: Math.min(100, (r.value / r.max) * 100) + "%" }} />
            {r.target !== undefined && <div className="tgt" style={{ left: Math.min(100, (r.target / r.max) * 100) + "%" }} />}
          </div>
          <span className="v">{r.display}</span>
        </div>
      ))}
    </div>
  );
}
