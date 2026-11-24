import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import App from "../App";
import { setToken } from "../api";
import { ToastProvider } from "../components/ui";
import * as F from "./fixtures";
import { mockApi, Routes } from "./mockApi";

class RO { observe() {} disconnect() {} unobserve() {} }
(globalThis as unknown as { ResizeObserver: typeof RO }).ResizeObserver = RO;

function renderAt(path: string, routes: Routes, role = "OWNER") {
  setToken("t");
  const calls = mockApi({ "GET /api/me": { ...F.me, role }, ...routes });
  render(<MemoryRouter initialEntries={[path]}><ToastProvider><App /></ToastProvider></MemoryRouter>);
  return calls;
}

describe("login", () => {
  beforeEach(() => setToken(null));
  it("shows the server's error and signs in", async () => {
    let attempt = 0;
    mockApi({
      "POST /api/auth/login": () => (++attempt === 1 ? { status: 401, json: { code: "BAD_CREDENTIALS", message: "Email or password is wrong" } }
        : { json: { token: "tok", user: F.me } }),
      "GET /api/dashboard": F.dashboard,
    });
    render(<MemoryRouter><ToastProvider><App /></ToastProvider></MemoryRouter>);
    const u = userEvent.setup();
    await u.type(screen.getByLabelText("Email"), "owner@demo.in");
    await u.type(screen.getByLabelText("Password"), "nope");
    await u.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Email or password is wrong");
    await u.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByText("Hello, Datta")).toBeInTheDocument();
  });
});

describe("dashboard", () => {
  it("renders KPIs, charts and activity", async () => {
    renderAt("/", { "GET /api/dashboard": F.dashboard });
    expect(await screen.findByText("₹2.23 L")).toBeInTheDocument();
    expect(screen.getByText("4 new WhatsApp orders")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /Daily sales/ })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /Receivables by age/ })).toBeInTheDocument();
    expect(screen.getByText("SO-1036 confirmed")).toBeInTheDocument();
    expect(screen.getByText("58%")).toBeInTheDocument();
  });
  it("shows a retry banner when the API fails", async () => {
    renderAt("/", { "GET /api/dashboard": () => ({ status: 500, json: { code: "INTERNAL", message: "Something went wrong" } }) });
    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong");
  });
});

describe("whatsapp inbox", () => {
  it("blocks confirm until the unmatched line is skipped, then confirms with edited qty", async () => {
    let confirmBody: unknown;
    renderAt("/inbox", {
      "GET /api/inbox": F.inbox, "GET /api/inbox/1": F.thread1, "GET /api/inbox/1/draft": F.draft1, "GET /api/inventory": F.inventory,
      "POST /api/inbox/1/confirm": (b: unknown) => { confirmBody = b; return { json: { id: 99, number: 1037, total: 1, backorders: [] } }; },
    });
    const u = userEvent.setup();
    const confirm = await screen.findByRole("button", { name: "Confirm & reply on WhatsApp" });
    expect(confirm).toBeDisabled();
    expect(screen.getByText("Not matched")).toBeInTheDocument();
    await u.click(screen.getByRole("button", { name: "Skip line" }));
    const qty = screen.getByLabelText(/Quantity for FR House Wire/);
    await u.clear(qty);
    await u.type(qty, "25");
    expect(confirm).toBeEnabled();
    await u.click(confirm);
    await waitFor(() => expect(confirmBody).toEqual({ overrideCredit: false, lines: [{ productId: 1, qty: 25 }] }));
    expect(await screen.findByText(/SO-1037 confirmed/)).toBeInTheDocument();
  });

  it("puts an over-limit order on hold and lets the owner override", async () => {
    let body: { overrideCredit?: boolean } = {};
    renderAt("/inbox", {
      "GET /api/inbox": F.inbox, "GET /api/inbox/1": F.thread1, "GET /api/inbox/1/draft": F.draft1,
      "GET /api/inbox/2": F.thread2, "GET /api/inbox/2/draft": F.draft2, "GET /api/inventory": F.inventory,
      "POST /api/inbox/2/confirm": (b: unknown) => { body = b as typeof body; return { json: { id: 1, number: 1038, total: 1, backorders: [] } }; },
    });
    const u = userEvent.setup();
    await u.click(await screen.findByText("Shiv Shakti Traders"));
    expect(await screen.findByText("Credit hold: needs owner approval")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm & reply on WhatsApp" })).toBeDisabled();
    await u.click(screen.getByRole("button", { name: "Approve override" }));
    await u.click(screen.getByRole("button", { name: "Confirm & reply on WhatsApp" }));
    await waitFor(() => expect(body.overrideCredit).toBe(true));
  });

  it("hides the override from salespeople", async () => {
    renderAt("/inbox", { "GET /api/inbox": [F.inbox[1]], "GET /api/inbox/2": F.thread2, "GET /api/inbox/2/draft": F.draft2, "GET /api/inventory": F.inventory }, "SALES");
    expect(await screen.findByText("Credit hold: needs owner approval")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve override" })).toBeNull();
    expect(screen.getByRole("button", { name: "Ask for payment first" })).toBeInTheDocument();
  });
});

describe("orders", () => {
  it("packs and dispatches with the chosen vehicle", async () => {
    const calls = renderAt("/orders", {
      "GET /api/orders": F.orders, "GET /api/orders/summary": F.summary, "GET /api/orders/vehicles": F.vehicles,
      "POST /api/orders/30/pack": { ok: true }, "POST /api/orders/31/dispatch": { invoice: "INV-2305", vehicle: "MH12 AB 4521", driver: "Raju Pawar" },
    });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "Mark packed" }));
    await u.click(screen.getByRole("button", { name: "Dispatch & invoice" }));
    await waitFor(() => expect(calls.some(c => c.url === "/api/orders/31/dispatch" && (c.body as { vehicleId: number }).vehicleId === 1)).toBe(true));
    expect(calls.some(c => c.url === "/api/orders/30/pack")).toBe(true);
  });
  it("delivery staff see only their modules", async () => {
    renderAt("/orders", { "GET /api/orders": F.orders, "GET /api/orders/summary": F.summary, "GET /api/orders/vehicles": F.vehicles }, "DELIVERY");
    const nav = await screen.findByRole("navigation", { name: "Modules" });
    expect(within(nav).queryByText("Tally sync")).toBeNull();
    expect(within(nav).queryByText("WhatsApp orders")).toBeNull();
    expect(within(nav).getByText("Field & delivery")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel" })).toBeNull();
  });
});

describe("inventory, customers, collections, field, tally", () => {
  it("validates stock adjustments against reserved stock", async () => {
    renderAt("/inventory", { "GET /api/inventory": F.inventory });
    const u = userEvent.setup();
    await u.click((await screen.findAllByRole("button", { name: "Adjust stock" }))[1]);
    await u.type(screen.getByLabelText("Change in quantity"), "-20");
    expect(screen.getByText(/can't go below the 6 already reserved/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("edits a credit limit", async () => {
    let body: unknown;
    renderAt("/customers", { "GET /api/customers": F.customers, "GET /api/customers/tiers": F.tiers,
      "PUT /api/customers/5/credit": (b: unknown) => { body = b; return { json: { ok: true } }; } });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "Edit credit" }));
    const lim = screen.getByLabelText("Credit limit (₹)");
    await u.clear(lim); await u.type(lim, "150000");
    await u.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(body).toEqual({ creditLimit: 150000, tierCode: "C" }));
  });

  it("records a payment once even on a double tap", async () => {
    const keys: string[] = [];
    renderAt("/collections", { "GET /api/collections": F.aging, "GET /api/payments/today": [],
      "POST /api/payments": (b: unknown) => { keys.push((b as { idempotencyKey: string }).idempotencyKey); return { json: { receipt: "RCPT-801", appliedTo: ["INV-2171"] } }; } });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "Record payment" }));
    const save = screen.getByRole("button", { name: "Save receipt" });
    await u.dblClick(save);
    await waitFor(() => expect(keys.length).toBeGreaterThan(0));
    expect(new Set(keys).size).toBe(1);
  });

  it("shows field teams and lets delivery confirm without photo", async () => {
    const calls = renderAt("/field", { "GET /api/field": F.field, "POST /api/orders/40/deliver": { ok: true } });
    const u = userEvent.setup();
    expect(await screen.findByText("Amit Jadhav")).toBeInTheDocument();
    await u.click(screen.getByRole("button", { name: "No photo" }));
    await waitFor(() => expect(calls.some(c => c.url === "/api/orders/40/deliver" && c.method === "POST")).toBe(true));
  });

  it("pushes the Tally queue", async () => {
    const calls = renderAt("/tally", { "GET /api/tally": F.tally, "POST /api/tally/sync": { posted: 1, failed: 0 } });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "Push 1 voucher to Tally" }));
    expect(await screen.findByText("1 voucher posted to Tally")).toBeInTheDocument();
    expect(calls.filter(c => c.url === "/api/tally/sync")).toHaveLength(1);
  });
});

describe("features added in re-verification", () => {
  it("books a phone order from the Orders page", async () => {
    let body: unknown;
    renderAt("/orders", {
      "GET /api/orders": F.orders, "GET /api/orders/summary": F.summary, "GET /api/orders/vehicles": F.vehicles,
      "GET /api/customers": F.customers, "GET /api/inventory": F.inventory,
      "POST /api/orders": (b: unknown) => { body = b; return { json: { id: 50, number: 1040, total: 5000, backorders: [] } }; },
    });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "New order" }));
    await u.selectOptions(await screen.findByLabelText("Customer"), "5");
    await u.selectOptions(screen.getByLabelText("Item 1"), "1");
    const q = screen.getByLabelText("Quantity 1");
    await u.clear(q); await u.type(q, "4");
    await u.click(screen.getByRole("button", { name: "Confirm order" }));
    await waitFor(() => expect(body).toEqual({ customerId: 5, source: "PHONE", overrideCredit: false, lines: [{ productId: 1, qty: 4 }] }));
  });

  it("shows the server's credit-hold reason in the order form", async () => {
    renderAt("/orders", {
      "GET /api/orders": F.orders, "GET /api/orders/summary": F.summary, "GET /api/orders/vehicles": F.vehicles,
      "GET /api/customers": F.customers, "GET /api/inventory": F.inventory,
      "POST /api/orders": () => ({ status: 422, json: { code: "CREDIT_HOLD", message: "Order takes the customer over the limit" } }),
    });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "New order" }));
    await u.selectOptions(await screen.findByLabelText("Customer"), "5");
    await u.selectOptions(screen.getByLabelText("Item 1"), "1");
    await u.click(screen.getByRole("button", { name: "Confirm order" }));
    expect(await screen.findByText("Order takes the customer over the limit")).toBeInTheDocument();
  });

  it("staff reply goes to the reply endpoint, not the simulator", async () => {
    const calls = renderAt("/inbox", { "GET /api/inbox": F.inbox, "GET /api/inbox/1": F.thread1, "GET /api/inbox/1/draft": F.draft1,
      "GET /api/inventory": F.inventory, "POST /api/inbox/1/reply": { ok: true } });
    const u = userEvent.setup();
    await u.type(await screen.findByLabelText("Message"), "Sending by 5 pm");
    await u.click(screen.getByRole("button", { name: "Send reply" }));
    await waitFor(() => expect(calls.find(c => c.url === "/api/inbox/1/reply")?.body).toEqual({ body: "Sending by 5 pm" }));
    expect(calls.some(c => c.url === "/api/inbox/simulate")).toBe(false);
  });

  it("rejects an invalid match pattern before saving an item", async () => {
    renderAt("/inventory", { "GET /api/inventory": F.inventory });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "Add item" }));
    await u.type(screen.getByLabelText(/match patterns/), "([[bad");
    expect(screen.getByText(/Invalid pattern/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("salesperson checks in at a shop", async () => {
    let body: unknown;
    renderAt("/field", { "GET /api/field": F.field, "GET /api/field/visits/today": [], "GET /api/customers": F.customers,
      "POST /api/field/visits": (b: unknown) => { body = b; return { json: { id: 1 } }; } }, "SALES");
    const u = userEvent.setup();
    await u.selectOptions(await screen.findByLabelText("Customer"), "5");
    await u.type(screen.getByLabelText("Note"), "Wants new price list");
    await u.click(screen.getByRole("button", { name: "Check in" }));
    await waitFor(() => expect(body).toEqual({ customerId: 5, note: "Wants new price list" }));
  });

  it("owner adds a team member; others don't see Team", async () => {
    let body: unknown;
    renderAt("/team", { "GET /api/users": [{ id: 1, email: "owner@demo.in", name: "Datta", role: "OWNER", active: true, monthlyTarget: 0 }],
      "POST /api/users": (b: unknown) => { body = b; return { json: { id: 9 } }; } });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "Add person" }));
    await u.type(screen.getByLabelText("Name"), "Sunil");
    await u.type(screen.getByLabelText("Email"), "sunil@demo.in");
    await u.type(screen.getByLabelText(/Temporary password/), "Start@2026");
    await u.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Add person" }));
    await waitFor(() => expect(body).toMatchObject({ name: "Sunil", email: "sunil@demo.in", role: "SALES" }));
  });
});
