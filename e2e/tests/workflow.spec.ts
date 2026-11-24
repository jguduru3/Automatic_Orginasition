import { expect, test } from "@playwright/test";
import { login, nav, toast } from "./helpers";

// The full demo story, in order, against the real stack (docker compose with demo data).
test.describe.serial("WhatsApp to Tally", () => {
  test("dashboard loads with live numbers", async ({ page }) => {
    await login(page);
    await expect(page.getByText(/^Hello, /)).toBeVisible();
    await expect(page.getByRole("img", { name: /Daily sales/ })).toBeVisible();
    await expect(page.getByText("Sales today")).toBeVisible();
  });

  test("convert Mahalaxmi's WhatsApp message into a sales order", async ({ page }) => {
    await login(page);
    await nav(page, "WhatsApp orders");
    await page.getByRole("listitem").filter({ hasText: "Mahalaxmi Electricals" }).click();
    await expect(page.getByText("FR House Wire 1.5 sq mm")).toBeVisible();
    await expect(page.getByText("Credit OK")).toBeVisible();
    await page.getByRole("button", { name: "Confirm & reply on WhatsApp" }).click();
    await expect(toast(page)).toContainText(/SO-\d+ confirmed/);
    await expect(page.locator(".msg.out").last()).toContainText("confirmed");
  });

  test("Shiv Shakti's order is held on credit, owner overrides", async ({ page }) => {
    await login(page);
    await nav(page, "WhatsApp orders");
    await page.getByRole("listitem").filter({ hasText: "Shiv Shakti Traders" }).click();
    await expect(page.getByText("Credit hold: needs owner approval")).toBeVisible();
    await expect(page.getByRole("button", { name: "Confirm & reply on WhatsApp" })).toBeDisabled();
    await page.getByRole("button", { name: "Approve override" }).click();
    await page.getByRole("button", { name: "Confirm & reply on WhatsApp" }).click();
    await expect(toast(page)).toContainText(/confirmed/);
  });

  test("Kumar Contractors: short stock becomes back-order", async ({ page }) => {
    await login(page);
    await nav(page, "WhatsApp orders");
    await page.getByRole("listitem").filter({ hasText: "Kumar Contractors" }).click();
    await expect(page.getByText(/Short \d+/)).toBeVisible();
    await page.getByRole("button", { name: "Confirm & reply on WhatsApp" }).click();
    await expect(toast(page)).toContainText(/Back-order/);
  });

  test("voice note with an unmatched item needs a decision", async ({ page }) => {
    await login(page);
    await nav(page, "WhatsApp orders");
    await page.getByRole("listitem").filter({ hasText: "Om Sai" }).click();
    await expect(page.getByText("Not matched")).toBeVisible();
    const confirm = page.getByRole("button", { name: "Confirm & reply on WhatsApp" });
    await expect(confirm).toBeDisabled();
    await page.getByRole("button", { name: "Skip line" }).click();
    await confirm.click();
    await expect(toast(page)).toContainText(/confirmed/);
  });

  test("simulated customer message is parsed live", async ({ page }) => {
    await login(page);
    await nav(page, "WhatsApp orders");
    await page.getByRole("listitem").filter({ hasText: "Mahalaxmi Electricals" }).click();
    await page.getByLabel("Simulate a customer message").fill("16A MCB 50 pcs");
    await page.getByRole("button", { name: "Send as customer" }).click();
    await expect(page.getByText("MCB 16A SP C-curve")).toBeVisible();
  });

  test("pack, dispatch and deliver", async ({ page }) => {
    await login(page);
    await nav(page, "Sales orders");
    await page.getByRole("button", { name: "Mark packed" }).first().click();
    await expect(toast(page)).toContainText("packed");
    await page.getByRole("button", { name: "Dispatch & invoice" }).first().click();
    await expect(toast(page)).toContainText(/INV-\d+ raised/);
    await nav(page, "Field & delivery");
    await page.getByRole("button", { name: "No photo" }).first().click();
    await expect(toast(page)).toContainText("delivered");
  });

  test("record a payment and remind a customer", async ({ page }) => {
    await login(page, "accounts@demo.in");
    await nav(page, "Collections");
    await page.getByRole("row").filter({ hasText: "Shiv Shakti Traders" }).getByRole("button", { name: "Send reminder" }).click();
    await expect(toast(page)).toContainText("Reminder sent");
    await page.getByRole("row").filter({ hasText: "Shiv Shakti Traders" }).getByRole("button", { name: "Record payment" }).click();
    await page.getByLabel("Amount (₹)").fill("24600");
    await page.getByRole("button", { name: "Save receipt" }).click();
    await expect(toast(page)).toContainText(/RCPT-\d+ saved/);
  });

  test("push vouchers to Tally", async ({ page }) => {
    await login(page, "accounts@demo.in");
    await nav(page, "Tally sync");
    await page.getByRole("button", { name: /Push \d+ vouchers? to Tally/ }).click();
    await expect(toast(page)).toContainText("posted to Tally");
    await expect(page.getByText("Nothing waiting.")).toBeVisible();
  });

  test("inventory and customers edits", async ({ page }) => {
    await login(page);
    await nav(page, "Inventory");
    await page.getByRole("row").filter({ hasText: "RC40" }).getByRole("button", { name: "Adjust stock" }).click();
    await page.getByLabel("Change in quantity").fill("50");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(toast(page)).toContainText("Stock updated");
    await nav(page, "Customers & credit");
    await page.getByRole("row").filter({ hasText: "Ganesh Hardware" }).getByRole("button", { name: "Edit credit" }).click();
    await page.getByLabel("Credit limit (₹)").fill("120000");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(toast(page)).toContainText("Saved");
  });

  test("roles: delivery staff see only orders and field", async ({ page }) => {
    await login(page, "raju@demo.in");
    const navEl = page.getByRole("navigation", { name: "Modules" });
    await expect(navEl.getByRole("link")).toHaveCount(2);
    await page.goto("/tally");
    await expect(page).not.toHaveURL(/tally/);
  });
});
