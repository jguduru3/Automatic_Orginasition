import { expect, Page } from "@playwright/test";

export async function login(page: Page, email = "owner@demo.in") {
  await page.goto("/");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("Demo@1234");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("navigation", { name: "Modules" })).toBeVisible();
}

export async function nav(page: Page, name: string) {
  await page.getByRole("navigation", { name: "Modules" }).getByRole("link", { name }).click();
}

export const toast = (page: Page) => page.getByRole("status");
