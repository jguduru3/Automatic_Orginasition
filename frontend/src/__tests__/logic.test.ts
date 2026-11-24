import { describe, expect, it } from "vitest";
import { computeDraft } from "../draft";
import { inr, lakh, n, pct } from "../format";
import { niceMax } from "../components/charts";

describe("format", () => {
  it("formats Indian rupees and lakhs", () => {
    expect(inr(123456.6)).toBe("₹1,23,457");
    expect(lakh(1385000)).toBe("₹13.85 L");
    expect(lakh(23500000)).toBe("₹2.35 Cr");
    expect(lakh(999)).toBe("₹999");
  });
  it("is safe with junk", () => {
    expect(n(undefined)).toBe(0);
    expect(n("abc")).toBe(0);
    expect(n("12.5")).toBe(12.5);
    expect(inr(null)).toBe("₹0");
    expect(pct(5, 0)).toBe(0);
  });
  it("picks readable axis maxima", () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(4.6e5)).toBe(5e5);
    expect(niceMax(5.3e5)).toBe(6e5);
  });
});

describe("computeDraft", () => {
  const credit = { limit: 100000, outstanding: 80000, openOrders: 0, oldestDays: 10 };
  it("prices only in-stock quantity and adds GST", () => {
    const t = computeDraft([{ raw: "", productId: 1, qty: 10, rate: 100, gstRate: 18, free: 4 }], credit);
    expect(t.subtotal).toBe(400);
    expect(t.tax).toBe(72);
    expect(t.short).toBe(1);
    expect(t.status).toBe("PASS"); // 80,472 is under 85% of the limit
    expect(computeDraft([{ raw: "", productId: 1, qty: 10, rate: 100, gstRate: 18, free: 4 }], { ...credit, outstanding: 85000 }).status).toBe("WATCH");
  });
  it("holds when over limit", () => {
    const t = computeDraft([{ raw: "", productId: 1, qty: 200, rate: 100, gstRate: 18, free: 500 }], credit);
    expect(t.status).toBe("HOLD");
  });
  it("counts unmatched lines and skips manual picks", () => {
    const t = computeDraft([{ raw: "x", qty: 1 }, { raw: "y", productId: 2, qty: 3 }], null);
    expect(t.unmatched).toBe(1);
    expect(t.total).toBe(0);
    expect(t.status).toBe("PASS");
  });
  it("watches old bills even with headroom", () => {
    expect(computeDraft([], { ...credit, outstanding: 0, oldestDays: 75 }).status).toBe("WATCH");
  });
});
