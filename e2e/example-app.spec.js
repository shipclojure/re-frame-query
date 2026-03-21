// @ts-check
const { test, expect } = require("@playwright/test");

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Click a tab button by its label text. */
async function selectTab(page, label) {
  await page.getByRole("button", { name: label, exact: true }).click();
}

/** Wait for MSW + initial data to load. */
async function waitForApp(page) {
  await page.waitForLoadState("networkidle");
  await page.getByRole("heading", { level: 1 }).waitFor();
}

// ---------------------------------------------------------------------------
// Basic CRUD tab
// ---------------------------------------------------------------------------

test.describe("Basic CRUD", () => {
  test.beforeEach(async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Basic CRUD");
  });

  test("displays the book list", async ({ page }) => {
    // Use the heading to scope to the book list panel
    await expect(page.getByRole("heading", { name: "📚 Books" })).toBeVisible();
    await expect(page.locator(".book-card", { hasText: "Dune" }).first()).toBeVisible();
    await expect(page.locator(".book-card", { hasText: "Neuromancer" }).first()).toBeVisible();
  });

  test("shows paginated books", async ({ page }) => {
    await expect(page.getByText("Page 1 of 4")).toBeVisible();
    // Use exact match for the pagination button
    await page.getByRole("button", { name: "2", exact: true }).click();
    await expect(page.getByText("Page 2 of 4")).toBeVisible();
  });

  test("creates a new book via mutation", async ({ page }) => {
    await page.getByPlaceholder("e.g. The Left Hand of Darkness").fill("Ringworld");
    await page.getByPlaceholder("e.g. Ursula K. Le Guin").fill("Larry Niven");
    await page.getByRole("button", { name: "Add Book" }).click();
    // After mutation + invalidation, the new book should appear
    await expect(page.locator(".book-card", { hasText: "Ringworld" }).first()).toBeVisible({ timeout: 5000 });
  });

  test("opens book detail and navigates back", async ({ page }) => {
    await page.locator(".book-card", { hasText: "Dune" }).first().click();
    await expect(page.getByRole("heading", { name: "Book Detail" })).toBeVisible();
    await page.getByRole("button", { name: "← Back" }).click();
    await expect(page.getByRole("heading", { name: "📚 Books" })).toBeVisible();
  });

  test("invalidate button triggers refetch", async ({ page }) => {
    await expect(page.getByRole("heading", { name: "📚 Books" })).toBeVisible();
    await page.getByRole("button", { name: "🔄 Invalidate All Books" }).click();
    // Data should still be visible after refetch
    await expect(page.locator(".book-card", { hasText: "Dune" }).first()).toBeVisible({ timeout: 5000 });
  });
});

// ---------------------------------------------------------------------------
// Polling tab
// ---------------------------------------------------------------------------

test.describe("Polling", () => {
  test("shows server stats that update", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Polling");

    await expect(page.getByRole("heading", { name: /Server Stats/ })).toBeVisible();
    await expect(page.getByText("Uptime:")).toBeVisible();

    // Capture initial request count
    const initial = await page.getByText(/Requests:\s*\d+/).textContent();
    // Wait for at least one poll cycle
    await page.waitForTimeout(3000);
    const updated = await page.getByText(/Requests:\s*\d+/).textContent();
    expect(updated).not.toBe(initial);
  });

  test("toggling fast subscriber shows second panel", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Polling");

    await expect(page.getByRole("heading", { name: /Fast Stats/ })).not.toBeVisible();
    await page.getByRole("button", { name: /Show Fast Subscriber/ }).click();
    await expect(page.getByRole("heading", { name: /Fast Stats/ })).toBeVisible();
    await page.getByRole("button", { name: /Hide Fast Subscriber/ }).click();
    await expect(page.getByRole("heading", { name: /Fast Stats/ })).not.toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// Dependent Queries tab
// ---------------------------------------------------------------------------

test.describe("Dependent Queries", () => {
  test("loads user then loads favorites", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Dependent Queries");

    // User loads first
    await expect(page.getByText("Alice Hacker")).toBeVisible({ timeout: 5000 });
    await expect(page.getByText("alice@example.com")).toBeVisible();

    // Then favorites load
    await expect(page.getByRole("heading", { name: "⭐ Favorites" })).toBeVisible();
    // Wait for favorites data to appear (depends on user query completing first)
    await expect(page.locator(".book-card", { hasText: "Dune" })).toBeVisible({ timeout: 5000 });
    await expect(page.locator(".book-card", { hasText: "Foundation" })).toBeVisible();
    await expect(page.locator(".book-card", { hasText: "1984" })).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// Prefetching tab
// ---------------------------------------------------------------------------

test.describe("Prefetching", () => {
  test("shows book list with prefetch hint", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Prefetching");

    await expect(page.getByRole("heading", { name: /hover to prefetch/ })).toBeVisible({ timeout: 5000 });
    await expect(page.locator(".book-card", { hasText: "Dune" }).first()).toBeVisible();
    await expect(page.locator(".book-card").first()).toContainText("Hover to prefetch");
  });

  test("hover prefetches then click shows instant detail", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Prefetching");

    await expect(page.locator(".book-card", { hasText: "Dune" }).first()).toBeVisible({ timeout: 5000 });

    // Hover to trigger prefetch
    const duneCard = page.locator(".book-card", { hasText: "Dune" }).first();
    await duneCard.hover();
    await page.waitForTimeout(1000);

    // Click — should show detail instantly from cache
    await duneCard.click();
    await expect(page.getByRole("heading", { name: "Book Detail" })).toBeVisible();
    await expect(page.getByText("Frank Herbert")).toBeVisible();

    // Go back
    await page.getByRole("button", { name: "← Back to list" }).click();
    await expect(page.getByRole("heading", { name: /hover to prefetch/ })).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// Mutation Lifecycle tab
// ---------------------------------------------------------------------------

test.describe("Mutation Lifecycle", () => {
  test("shows idle status initially", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Mutation Lifecycle");

    await expect(page.getByRole("heading", { name: /Mutation Lifecycle/ })).toBeVisible();
    // Both status badges show "idle"
    await expect(page.locator("span", { hasText: "idle" }).first()).toBeVisible();
  });

  test("delete error flow with reset", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Mutation Lifecycle");

    // Click "Try Delete #9999" — should fail
    await page.getByRole("button", { name: "Try Delete #9999" }).click();
    // Wait for error status badge
    await expect(page.locator("span", { hasText: "error" }).last()).toBeVisible({ timeout: 5000 });

    // Reset
    await page.getByRole("button", { name: "Reset Status" }).last().click();
    // Should return to idle
    await expect(page.locator("span", { hasText: "idle" }).last()).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// Inspector
// ---------------------------------------------------------------------------

test.describe("Inspector", () => {
  test("toggles open and shows query entries", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);

    const toggle = page.getByRole("button", { name: /app-db/ });
    await expect(toggle).toBeVisible();

    // Open it
    await toggle.click();
    // The inspector section heading "Queries" (not the tab "Dependent Queries")
    await expect(page.locator("div", { hasText: ":books/list" }).first()).toBeVisible();

    // Close it
    await toggle.click();
    await expect(page.locator("code", { hasText: ":books/list" })).not.toBeVisible();
  });
});
