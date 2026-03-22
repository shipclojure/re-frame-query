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
// Caching & invalidation (network-level assertions)
// ---------------------------------------------------------------------------

test.describe("Caching & Invalidation", () => {
  test("creating a book invalidates list queries exactly once each", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Basic CRUD");

    // Wait for initial data to fully load
    await expect(page.getByRole("heading", { name: "📚 Books" })).toBeVisible();
    await expect(page.locator(".book-card", { hasText: "Dune" }).first()).toBeVisible();
    await expect(page.getByText("Page 1 of 4")).toBeVisible();
    // Let all initial fetches settle
    await page.waitForLoadState("networkidle");

    // Start counting API requests
    const apiRequests = [];
    page.on("request", (req) => {
      const url = req.url();
      if (url.includes("/api/books") && req.method() === "GET") {
        apiRequests.push(url);
      }
    });

    // Fill form and create a book
    await page.getByPlaceholder("e.g. The Left Hand of Darkness").fill("Test Book");
    await page.getByPlaceholder("e.g. Ursula K. Le Guin").fill("Test Author");
    await page.getByRole("button", { name: "Add Book" }).click();

    // Wait for the mutation to complete and invalidation refetches to finish
    await expect(page.locator(".book-card", { hasText: "Test Book" }).first()).toBeVisible({ timeout: 5000 });
    await page.waitForLoadState("networkidle");

    // Count refetch requests by type
    const listRequests = apiRequests.filter((u) => u.match(/\/api\/books(\?|$)/) && !u.includes("page="));
    const pageRequests = apiRequests.filter((u) => u.includes("page="));

    // The books/list query should have been refetched exactly once
    expect(listRequests.length).toBe(1);
    // The books/page query (page 1 is active) should have been refetched exactly once
    expect(pageRequests.length).toBe(1);
  });

  test("switching tabs and returning does not re-fetch fresh data", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Basic CRUD");

    // Wait for initial data to fully load
    await expect(page.locator(".book-card", { hasText: "Dune" }).first()).toBeVisible();
    await page.waitForLoadState("networkidle");

    // Start counting API requests after initial load
    const apiRequests = [];
    page.on("request", (req) => {
      const url = req.url();
      if (url.includes("/api/books")) {
        apiRequests.push(url);
      }
    });

    // Switch to Dependent Queries tab and back to Basic CRUD
    await selectTab(page, "Dependent Queries");
    await expect(page.getByRole("heading", { name: "👤 Current User" })).toBeVisible({ timeout: 5000 });
    await selectTab(page, "Basic CRUD");

    // Data should appear immediately from cache (no loading state)
    await expect(page.locator(".book-card", { hasText: "Dune" }).first()).toBeVisible();
    await page.waitForLoadState("networkidle");

    // No book API requests should have fired — data is still fresh (stale-time: 30s)
    const bookRequests = apiRequests.filter((u) => u.includes("/api/books"));
    expect(bookRequests.length).toBe(0);
  });

  test("prefetch cache hit produces zero network requests on click", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Prefetching");

    await expect(page.locator(".book-card", { hasText: "Dune" }).first()).toBeVisible({ timeout: 5000 });

    // Hover to prefetch Dune's detail
    const duneCard = page.locator(".book-card", { hasText: "Dune" }).first();
    await duneCard.hover();
    // Wait for prefetch to complete
    await page.waitForTimeout(1500);
    await page.waitForLoadState("networkidle");

    // Now start counting — clicking should produce ZERO /api/books/:id requests
    const detailRequests = [];
    page.on("request", (req) => {
      if (req.url().match(/\/api\/books\/\d+/)) {
        detailRequests.push(req.url());
      }
    });

    await duneCard.click();
    await expect(page.getByRole("heading", { name: "Book Detail" })).toBeVisible();
    await expect(page.getByText("Frank Herbert")).toBeVisible();

    // No detail request — served from prefetch cache
    expect(detailRequests.length).toBe(0);
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

  test("fast subscriber increases polling frequency to ~1s", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Polling");

    // Wait for initial data to settle
    await expect(page.getByText("Uptime:")).toBeVisible();
    await page.waitForLoadState("networkidle");

    // Count requests at the default 2s interval over 3 seconds
    let slowRequests = [];
    const slowHandler = (req) => {
      if (req.url().includes("/api/server-stats")) slowRequests.push(Date.now());
    };
    page.on("request", slowHandler);
    await page.waitForTimeout(3000);
    page.off("request", slowHandler);
    const slowCount = slowRequests.length;

    // Enable the fast subscriber (1s interval — lowest wins)
    await page.getByRole("button", { name: /Show Fast Subscriber/ }).click();
    await expect(page.getByRole("heading", { name: /Fast Stats/ })).toBeVisible();
    await page.waitForTimeout(500); // let interval switch settle

    // Count requests at the fast 1s interval over 3 seconds
    let fastRequests = [];
    const fastHandler = (req) => {
      if (req.url().includes("/api/server-stats")) fastRequests.push(Date.now());
    };
    page.on("request", fastHandler);
    await page.waitForTimeout(3000);
    page.off("request", fastHandler);
    const fastCount = fastRequests.length;

    // Fast subscriber should produce more requests than slow
    // At 2s interval: ~1-2 requests in 3s. At 1s interval: ~2-3 requests in 3s.
    expect(fastCount).toBeGreaterThan(slowCount);
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
// Optimistic Updates tab
// ---------------------------------------------------------------------------

test.describe("Optimistic Updates", () => {
  test("toggling a todo updates UI instantly before server responds", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Optimistic Updates");

    // Wait for todos to load
    await expect(page.getByText("Ship it")).toBeVisible({ timeout: 5000 });

    // Get the last todo checkbox and its initial state
    const checkbox = page.locator("input[type=checkbox]").last();
    const wasChecked = await checkbox.isChecked();

    // Click it — should toggle instantly (optimistic, before 800ms server response)
    await checkbox.click();
    if (wasChecked) {
      await expect(checkbox).not.toBeChecked();
    } else {
      await expect(checkbox).toBeChecked();
    }
  });

  test("successful toggle persists after server confirms", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Optimistic Updates");

    await expect(page.getByText("Ship it")).toBeVisible({ timeout: 5000 });

    const checkbox = page.locator("input[type=checkbox]").last();
    const wasChecked = await checkbox.isChecked();

    await checkbox.click();
    const toggled = !wasChecked;

    // Wait for server response + invalidation refetch
    await page.waitForTimeout(2000);
    // Should still be in toggled state after server confirms
    if (toggled) {
      await expect(checkbox).toBeChecked();
    } else {
      await expect(checkbox).not.toBeChecked();
    }
  });

  test("failed toggle rolls back the checkbox", async ({ page, baseURL }) => {
    test.setTimeout(30000);
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Optimistic Updates");

    await expect(page.getByText("Write Playwright tests")).toBeVisible({ timeout: 5000 });

    // Enable fail mode
    await page.getByLabel("Fail Mode").click();

    // Keep trying toggles until one fails and rolls back
    // (50% fail rate per attempt, so with 10 attempts P(all succeed) < 0.1%)
    const checkbox = page.locator("input[type=checkbox]").nth(2); // 3rd todo
    const initialState = await checkbox.isChecked();

    let rolledBack = false;
    for (let i = 0; i < 10; i++) {
      await checkbox.click();
      // Wait for server response (800ms) + invalidation refetch + buffer
      await page.waitForTimeout(2000);
      const currentState = await checkbox.isChecked();
      if (currentState === initialState) {
        rolledBack = true;
        break;
      }
      // Succeeded — already toggled, try again (next toggle is the reverse)
    }
    expect(rolledBack).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// WebSocket Transport tab
// ---------------------------------------------------------------------------

test.describe("WebSocket Transport", () => {
  test("loads notifications via WebSocket", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "WebSocket");

    await expect(page.getByRole("heading", { name: /Notifications.*WebSocket/ })).toBeVisible();
    // Wait for WS data to arrive
    await expect(page.locator(".book-card", { hasText: "Server deployed" }).first()).toBeVisible({ timeout: 5000 });
    await expect(page.locator(".book-card", { hasText: "High memory usage" })).toBeVisible();
  });

  test("polls latest notification via WebSocket", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "WebSocket");

    await expect(page.getByRole("heading", { name: /Latest Notification/ })).toBeVisible();
    // Wait for first poll result
    await expect(page.getByText("Message:")).toBeVisible({ timeout: 5000 });

    // Capture current message and wait for poll to update it
    const initial = await page.locator(".detail-field", { hasText: "Message:" }).textContent();
    await page.waitForTimeout(4000);
    const updated = await page.locator(".detail-field", { hasText: "Message:" }).textContent();
    expect(updated).not.toBe(initial);
  });

  test("chat messages load and sending invalidates the list", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "WebSocket");

    // Wait for chat messages to load
    await expect(page.getByRole("heading", { name: /Chat/ })).toBeVisible();
    await expect(page.getByText("Hey, anyone here?")).toBeVisible({ timeout: 5000 });
    await expect(page.getByText("Working on the re-frame-query demo")).toBeVisible();

    // Send a new message
    await page.getByPlaceholder("Type a message…").fill("Hello from Playwright!");
    await page.getByRole("button", { name: "Send" }).click();

    // After mutation + invalidation, the new message should appear
    await expect(page.getByText("Hello from Playwright!")).toBeVisible({ timeout: 5000 });
  });

  test("WebSocket queries use :ws-send effect, not :http", async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);

    // Start counting HTTP requests to /api/ endpoints
    const httpRequests = [];
    page.on("request", (req) => {
      const url = req.url();
      if (url.includes("/api/") && !url.includes("server-stats") && !url.includes("books") && !url.includes("me") && !url.includes("favorites")) {
        httpRequests.push(url);
      }
    });

    await selectTab(page, "WebSocket");
    // Wait for WS data to load
    await expect(page.locator(".book-card", { hasText: "Server deployed" }).first()).toBeVisible({ timeout: 5000 });

    // No HTTP requests should have been made for WS queries
    // (notifications, latest-notification, chat are all via WebSocket)
    const wsEndpointHttp = httpRequests.filter(
      (u) => u.includes("notifications") || u.includes("chat")
    );
    expect(wsEndpointHttp.length).toBe(0);
  });
});

// ---------------------------------------------------------------------------
// Infinite Scroll tab
// ---------------------------------------------------------------------------

test.describe("Infinite Scroll", () => {
  test.beforeEach(async ({ page, baseURL }) => {
    await page.goto(baseURL);
    await waitForApp(page);
    await selectTab(page, "Infinite Scroll");
  });

  test("loads first page of Alex's feed by default", async ({ page }) => {
    // Alex is selected by default
    await expect(page.getByRole("button", { name: /Alex/ })).toHaveClass(/primary/);
    // First page (10 items) should be visible — newest first, so Post #35 is first
    await expect(page.getByText("Alex's Post #35", { exact: true })).toBeVisible({ timeout: 5000 });
    await expect(page.getByText("Alex's Post #26", { exact: true })).toBeVisible();
    // Load More should be visible (35 items total, 10 per page)
    await expect(page.getByRole("button", { name: "Load More" })).toBeVisible();
  });

  test("loads 3 pages via Load More", async ({ page }) => {
    // Wait for first page
    await expect(page.getByText("Alex's Post #35", { exact: true })).toBeVisible({ timeout: 5000 });

    // Load page 2
    await page.getByRole("button", { name: "Load More" }).click();
    await expect(page.getByText("Alex's Post #16", { exact: true })).toBeVisible({ timeout: 5000 });

    // Load page 3
    await page.getByRole("button", { name: "Load More" }).click();
    await expect(page.getByText("Alex's Post #6", { exact: true })).toBeVisible({ timeout: 5000 });

    // All 30 items from 3 pages should be visible (posts #35 down to #6)
    await expect(page.getByText("Alex's Post #35", { exact: true })).toBeVisible();
    await expect(page.getByText("Alex's Post #26", { exact: true })).toBeVisible();
    await expect(page.getByText("Alex's Post #16", { exact: true })).toBeVisible();
  });

  test("switching users shows independent cache entries", async ({ page }) => {
    // Wait for Alex's feed
    await expect(page.getByText("Alex's Post #35", { exact: true })).toBeVisible({ timeout: 5000 });

    // Switch to Maria
    await page.getByRole("button", { name: /Maria/ }).click();
    await expect(page.getByText("Maria's Post #25", { exact: true })).toBeVisible({ timeout: 5000 });
    // Alex's posts should not be visible
    await expect(page.getByText("Alex's Post #35", { exact: true })).not.toBeVisible();

    // Switch back to Alex — should show cached data instantly
    await page.getByRole("button", { name: /Alex/ }).click();
    await expect(page.getByText("Alex's Post #35", { exact: true })).toBeVisible();
  });

  test("adding posts triggers re-fetch and previously seen items remain visible", async ({ page }) => {
    // Load 3 pages (30 items: posts #35 down to #6)
    await expect(page.getByText("Alex's Post #35", { exact: true })).toBeVisible({ timeout: 5000 });
    await page.getByRole("button", { name: "Load More" }).click();
    await expect(page.getByText("Alex's Post #16", { exact: true })).toBeVisible({ timeout: 5000 });
    await page.getByRole("button", { name: "Load More" }).click();
    await expect(page.getByText("Alex's Post #6", { exact: true })).toBeVisible({ timeout: 5000 });

    // Add first post — triggers mutation + tag invalidation + sequential re-fetch of 3 pages
    const titleInput = page.getByPlaceholder("Post title…");
    await titleInput.fill("Brand New Post Alpha");
    await page.getByRole("button", { name: "Add Post" }).click();

    // Wait for sequential re-fetch to complete — the new post should be at the top
    await expect(page.getByText("Brand New Post Alpha")).toBeVisible({ timeout: 20000 });

    // Key assertion: previously seen items are still visible after re-fetch with fresh cursors
    await expect(page.getByText("Alex's Post #35", { exact: true })).toBeVisible({ timeout: 5000 });
    await expect(page.getByText("Alex's Post #26", { exact: true })).toBeVisible();
    await expect(page.getByText("Alex's Post #16", { exact: true })).toBeVisible();

    // Add second post
    await titleInput.fill("Brand New Post Beta");
    await page.getByRole("button", { name: "Add Post" }).click();
    await expect(page.getByText("Brand New Post Beta")).toBeVisible({ timeout: 20000 });

    // Items from earlier pages still visible after second re-fetch
    await expect(page.getByText("Alex's Post #35", { exact: true })).toBeVisible();
    await expect(page.getByText("Alex's Post #26", { exact: true })).toBeVisible();
  });

  test("end of feed is shown when all pages loaded", async ({ page }) => {
    // Alex has 35 posts, 10 per page = 4 pages (10+10+10+5)
    await expect(page.getByText("Alex's Post #35", { exact: true })).toBeVisible({ timeout: 5000 });
    await page.getByRole("button", { name: "Load More" }).click();
    await expect(page.getByText("Alex's Post #16", { exact: true })).toBeVisible({ timeout: 5000 });
    await page.getByRole("button", { name: "Load More" }).click();
    await expect(page.getByText("Alex's Post #6", { exact: true })).toBeVisible({ timeout: 5000 });
    await page.getByRole("button", { name: "Load More" }).click();
    // Last page has posts #5 down to #1 — use exact match to avoid #10-#19
    await expect(page.getByText("Alex's Post #1", { exact: true })).toBeVisible({ timeout: 5000 });

    // No more pages — "End of feed" message should appear
    await expect(page.getByText("— End of feed —")).toBeVisible();
    // Load More button should be gone
    await expect(page.getByRole("button", { name: "Load More" })).not.toBeVisible();
  });

  test("debug stats show page count and cursors", async ({ page }) => {
    await expect(page.getByText("Alex's Post #35", { exact: true })).toBeVisible({ timeout: 5000 });

    // Enable debug stats
    await page.getByText("Show pagination debug info").click();
    await expect(page.getByText("1 page(s) loaded")).toBeVisible();
    await expect(page.getByText("Has next: true")).toBeVisible();

    // Load another page
    await page.getByRole("button", { name: "Load More" }).click();
    await expect(page.getByText("2 page(s) loaded")).toBeVisible({ timeout: 5000 });
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
