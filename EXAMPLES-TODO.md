# re-frame-query: Feature & Example Parity Plan

Tracks feature parity with [TanStack Query](https://tanstack.com/query) and
[RTK Query](https://redux-toolkit.js.org/rtk-query/overview), noting what we
cover and what remains.

---

## Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Implemented + tested |
| 🔨 | Needs new tests (feature exists) |
| 🏗️ | Needs new feature + tests |
| 📄 | Needs new example tab |
| ⏭️ | Skipped (not needed / handled elsewhere) |

---

## Feature Status

### Core Features — Complete

| Feature | Unit Tests | E2E Tests | Example Tab | Notes |
|---|---|---|---|---|
| Basic query lifecycle | ✅ | ✅ | ✅ Basic CRUD | loading → success, error, refetch |
| Mutations (full lifecycle) | ✅ | ✅ | ✅ Mutation Lifecycle | independent state, no dedup, reset |
| Tag-based cache invalidation | ✅ | ✅ | ✅ Basic CRUD | mutation → invalidate → refetch active |
| Query deduplication | ✅ | ✅ | — | in-flight guard, fresh cache skip |
| Polling | ✅ | ✅ | ✅ Polling | query + subscription level, lowest wins |
| Conditional fetching (skip?) | ✅ | ✅ | ✅ Dependent Queries | dependent query pattern |
| Prefetching | ✅ | ✅ | ✅ Prefetching | hover prefetch, zero requests on click |
| Transform response/error | ✅ | — | — | `(fn [data params] -> data')` |
| Pagination | ✅ | ✅ | ✅ Basic CRUD | parameterized, cached pages |
| Per-query effect-fn override | ✅ | — | ✅ WebSocket | custom transport per query |
| Reset API state | ✅ | — | — | clear all queries + mutations + timers |
| Reset mutation | ✅ | ✅ | ✅ Mutation Lifecycle | clear back to :idle |
| GC (per-query timers) | ✅ | — | — | mark-active/inactive, cache-time-ms |
| Error recovery | ✅ | — | — | error → retry → success clears error |
| Stale-time behavior | ✅ | ✅ | — | fresh data not refetched |
| Registration error handling | ✅ | — | — | throws for unregistered keys |
| `init!` declarative config | ✅ | — | — | batch registration |
| Debug logging | ✅ | — | — | `enable-debug-logging!` |
| App-db inspector | — | ✅ | ✅ (all tabs) | collapsible live state viewer |

### Features To Build

| # | Feature | Complexity | TanStack | RTK | Recommendation |
|---|---|---|---|---|---|
| 1 | **Refetch on focus/reconnect** | Medium | ✅ default on | ✅ `setupListeners` | **Do it** — `::rfq/refetch-stale` event + `setup-listeners!` |
| 2 | **Optimistic updates** | High | ✅ `setQueryData` | ✅ `onQueryStarted` | **Do it** — `::rfq/update-query-data` + rollback |
| 3 | **Pessimistic upserts** | Medium | ✅ `setQueryData` | ✅ `upsertQueryData` | **Do it** — `::rfq/upsert-query-data` |
| 4 | **Infinite queries** | High | ✅ `useInfiniteQuery` | ✅ `infiniteQueryOptions` | **Design needed** — userland pattern or library feature |

### Intentionally Skipped

| Feature | Reason |
|---|---|
| Retry logic | Better handled at the transport layer via user's `effect-fn` |
| Custom select on subscriptions | User builds own `reg-sub` — trivial in re-frame |
| Structural sharing | Clojure's value equality + Reagent's `=` comparison handles this |
| Server components / SSR | Not applicable to re-frame/ClojureScript SPAs |
| Offline mutation queue | Complex, niche — can be built in userland |

---

## Example Tabs Status

Both Reagent (port 8710) and UIx (port 8720) apps have identical tabs.

| Tab | Status | Features Demonstrated |
|---|---|---|
| Basic CRUD | ✅ | Queries, mutations, pagination, invalidation |
| Polling | ✅ | Query-level + subscription-level intervals, lowest wins |
| Dependent Queries | ✅ | `:skip?` pattern, sequential loading |
| Prefetching | ✅ | Hover prefetch, instant cache hit |
| Mutation Lifecycle | ✅ | idle → loading → success/error, reset-mutation |
| WebSocket Transport | ✅ | Per-query `:effect-fn`, mock WebSocket, custom transport |
| Authentication | 📄 TODO | Login mutation → JWT → protected queries → logout |
| Optimistic Updates | 📄 TODO | Instant UI update, rollback on failure |
| Infinite Scroll | 📄 TODO | Load more pattern (needs design) |
| Auto-Refetching | 📄 TODO | Focus/reconnect refetch (needs feature first) |

---

## E2E Test Coverage

34 Playwright tests run against both apps (17 per app):

| Test Group | Tests | What's Asserted |
|---|---|---|
| Basic CRUD | 5 | Book list, pagination, create, detail, invalidation |
| Caching & Invalidation | 3 | Exactly-once refetch, cache across tabs, prefetch cache hit |
| Polling | 3 | Stats update, toggle subscriber, frequency increase |
| Dependent Queries | 1 | User loads → favorites load |
| Prefetching | 2 | Book list with hint, hover → click instant detail |
| Mutation Lifecycle | 2 | Idle status, delete error + reset |
| Inspector | 1 | Toggle open/close, shows entries |
| WebSocket Transport | TODO | Mock WS queries, per-query effect-fn |

---

## Implementation Priority

### Next Up

1. **Refetch on focus/reconnect** (feature + example tab + tests)
2. **Optimistic updates** (feature + example tab + tests)
3. **Pessimistic upserts** (feature + tests)
4. **Authentication example tab** (example only, no new features)
5. **Infinite scroll** (design discussion → feature → example)

### CI Pipeline

- ✅ Unit tests: `pnpm test` (shadow-cljs, 63 tests)
- ✅ E2E tests: `pnpm test:e2e` (Playwright, 34 tests × 2 apps)
- ✅ Parallel jobs in GitHub Actions
