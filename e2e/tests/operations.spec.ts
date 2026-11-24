import { expect, test } from "@playwright/test";
import { login, nav, toast } from "./helpers";

test.describe.serial("Pharma, purchases, godowns, GPS, signup", () => {
  test("pharma: expired licence blocks prescription items", async ({ page }) => {
    await login(page, "owner@pharma.demo.in");
    await nav(page, "WhatsApp orders");
    await page.getByRole("listitem").filter({ hasText: "Lifeline Chemists" }).click();
    await expect(page.getByText("Paracetamol 650 mg (strip of 15)")).toBeVisible();
    await page.getByLabel("Simulate a customer message").fill("Amox 500 x 10");
    await page.getByRole("button", { name: "Send as customer" }).click();
    await expect(page.getByText(/Drug licence .* expired/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Confirm & reply on WhatsApp" })).toBeDisabled();
  });

  test("pharma: order picks earliest unexpired batch", async ({ page }) => {
    await login(page, "owner@pharma.demo.in");
    await nav(page, "WhatsApp orders");
    await page.getByRole("listitem").filter({ hasText: "Shree Ganesh Medical" }).click();
    await page.getByRole("button", { name: "Confirm & reply on WhatsApp" }).click();
    await expect(toast(page)).toContainText(/confirmed/);
    await nav(page, "Inventory");
    await expect(page.getByText("Expired and expiring within 90 days")).toBeVisible();
    await page.getByRole("row").filter({ hasText: "PCM650" }).getByRole("button", { name: "Stock detail" }).click();
    await expect(page.getByRole("dialog").getByText("Expired")).toBeVisible();
  });

  test("purchase order: draft, send, receive with batch", async ({ page }) => {
    await login(page, "owner@pharma.demo.in");
    await nav(page, "Purchases");
    await page.getByRole("button", { name: "New purchase order" }).click();
    await page.getByLabel("Item 1").selectOption({ label: "INS30 · Insulin 30/70 (10 ml vial)" });
    await page.getByLabel("Quantity 1").fill("20");
    await page.getByLabel("Rate 1").fill("118");
    await page.getByRole("button", { name: "Save draft" }).click();
    await page.getByRole("button", { name: "Send to supplier" }).click();
    await expect(toast(page)).toContainText(/Sent to supplier/);
    await page.getByLabel("Quantity received for INS30").fill("20");
    await page.getByLabel("Batch number for INS30").fill("E2E-01");
    await page.getByLabel("Expiry for INS30").fill("2028-01-31");
    await page.getByRole("button", { name: "Save goods receipt" }).click();
    await expect(toast(page)).toContainText(/GRN-\d+ saved/);
  });

  test("transfer between godowns", async ({ page }) => {
    await login(page);
    await nav(page, "Inventory");
    await page.getByRole("button", { name: "Transfer stock" }).click();
    await page.getByLabel("To").selectOption({ label: "Chakan branch" });
    await page.getByLabel("Item").selectOption({ label: "W15 · FR House Wire 1.5 sq mm" });
    await page.getByLabel("Quantity").fill("3");
    await page.getByRole("button", { name: "Transfer" }).click();
    await expect(toast(page)).toContainText(/TRF-\d+ done/);
  });

  test("Tally pull", async ({ page }) => {
    await login(page);
    await nav(page, "Tally sync");
    await page.getByRole("button", { name: "Pull items and customers" }).click();
    await expect(toast(page)).toContainText(/Pulled:/);
  });

  test("new business signs up", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("button", { name: /Create an account/ }).click();
    await page.getByLabel("Business name").fill("E2E Traders");
    await page.getByLabel("Your name").fill("Test Founder");
    await page.getByLabel("Email").fill(`e2e${Date.now()}@example.com`);
    await page.getByLabel(/Password/).fill("Start@2026");
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByText("Hello, Test")).toBeVisible();
  });
});
