import { n } from "./format";

export interface DraftLine {
  raw: string; qty: number; productId?: number; sku?: string; name?: string;
  rate?: number; gstRate?: number; priceSource?: string; free?: number;
}
export interface DraftCredit { limit: number; outstanding: number; openOrders: number; oldestDays: number }

export interface DraftTotals {
  subtotal: number; tax: number; total: number; short: number; unmatched: number;
  after: number; status: "PASS" | "WATCH" | "HOLD";
}

/** Mirrors the server's pricing/credit rules so the preview matches what confirm will do. */
export function computeDraft(lines: DraftLine[], credit: DraftCredit | null): DraftTotals {
  let subtotal = 0, tax = 0, short = 0, unmatched = 0;
  for (const l of lines) {
    if (!l.productId) { unmatched++; continue; }
    if (l.rate === undefined) continue; // manually picked: priced by the server at confirm
    const take = Math.max(0, Math.min(l.qty, n(l.free)));
    if (take < l.qty) short++;
    const amt = n(l.rate) * take;
    subtotal += amt;
    tax += (amt * n(l.gstRate)) / 100;
  }
  tax = Math.round(tax * 100) / 100;
  const total = subtotal + tax;
  const exposure = credit ? n(credit.outstanding) + n(credit.openOrders) : 0;
  const after = exposure + total;
  let status: DraftTotals["status"] = "PASS";
  if (credit) {
    if (after > n(credit.limit)) status = "HOLD";
    else if (credit.oldestDays > 60 || after > n(credit.limit) * 0.85) status = "WATCH";
  }
  return { subtotal, tax, total, short, unmatched, after, status };
}
