import { expect, test } from "@playwright/test";
import { login, nav } from "./helpers";

// Runs on desktop and phone: every module renders without errors or horizontal overflow.
const MODULES = ["Dashboard", "WhatsApp orders", "Sales orders", "Inventory", "Customers & credit", "Collections", "Purchases", "Field & delivery", "Tally sync", "Team"];

test("every module renders cleanly", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", e => errors.push(e.message));
  page.on("response", r => { if (r.url().includes("/api/") && r.status() >= 500) errors.push(`${r.status()} ${r.url()}`); });
  await login(page);
  for (const m of MODULES) {
    await nav(page, m);
    await expect(page.locator(".skeleton")).toHaveCount(0, { timeout: 15_000 });
    await expect(page.getByRole("alert")).toHaveCount(0);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `${m} overflows sideways`).toBeLessThanOrEqual(1);
  }
  expect(errors).toEqual([]);
});

test("session expiry returns to sign-in", async ({ page }) => {
  await login(page);
  await page.evaluate(() => sessionStorage.setItem("dos.token", "expired.token.value"));
  await page.reload();
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
});
