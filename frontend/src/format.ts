const nf0 = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 });

export const n = (v: unknown): number => {
  const x = typeof v === "number" ? v : parseFloat(String(v ?? 0));
  return Number.isFinite(x) ? x : 0;
};
export const inr = (v: unknown) => "₹" + nf0.format(Math.round(n(v)));
export const lakh = (v: unknown) => {
  const x = n(v);
  if (Math.abs(x) >= 1e7) return "₹" + (x / 1e7).toFixed(2) + " Cr";
  if (Math.abs(x) >= 1e5) return "₹" + (x / 1e5).toFixed(2) + " L";
  return inr(x);
};
export const time = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false }) : "";
export const dayLabel = (iso?: string | null) => {
  if (!iso) return "";
  const d = new Date(iso);
  const today = new Date();
  if (d.toDateString() === today.toDateString()) return "Today";
  return d.toLocaleDateString("en-IN", { day: "numeric", month: "short" });
};
export const pct = (a: unknown, b: unknown) => (n(b) > 0 ? Math.round((n(a) / n(b)) * 100) : 0);
export const titleCase = (s: string) => s.charAt(0) + s.slice(1).toLowerCase();
