# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.3.0] - 2026-04-02

### Fixed

- **`invalidate-tags` refetched all active queries** — previously, any tag invalidation triggered a refetch of every active query in the cache, regardless of whether its tags matched. Now only queries whose tags match the invalidation set are refetched, as intended. This eliminates N×M unnecessary network requests in apps with multiple active queries.

### Added

- **`ensure-query` rejects infinite queries** — dispatching `::rfq/ensure-query` on a query registered with `:infinite` config now throws with a clear error directing users to `::rfq/ensure-infinite-query`. Previously this would silently treat the query as a regular single-result query, producing incorrect cache state.
- **Bidirectional infinite queries (`fetch-previous-page`)** — infinite queries now support backward pagination via `:get-previous-cursor` in the `:infinite` config. Call `rfq/fetch-previous-page` or dispatch `::rfq/fetch-previous-page` to prepend pages. When `:max-pages` is set, prepending trims from the end (opposite of `fetch-next-page` which trims from the start), enabling a true sliding window in both directions. Queries without `:get-previous-cursor` are unchanged — no new keys appear in their data.

### Changed

- **Early validation in event handlers** — all event handlers (`ensure-query`, `refetch-query`, `execute-mutation`, `ensure-infinite-query`, `refetch-infinite-query`) now validate config at the top of the `let` binding using `(or (get-query k) (throw ...))`. Previously, some handlers destructured the config before validating it, allowing silent `nil` propagation.
- **`dispatch-hooks` always returns `[]`** — the internal `dispatch-hooks` helper now returns an empty vector instead of `nil` when no hooks are configured. This simplifies all mutation handler callsites by removing `fnil`/`seq` guard ceremony.
- **`idle-state` derived from `default-query`** — the subscription idle states in `subs.cljc` are now derived from `util/default-query` via `select-keys`, eliminating a duplicated source of truth that could silently diverge.
- **Test suite split into domain-specific namespaces** — the monolithic `query_test.cljc` (2600 lines) is now 6 focused files (`events_test`, `mutations_test`, `invalidation_test`, `gc_test`, `polling_test`, `infinite_test`) plus a shared `test_helpers` namespace.

## [0.2.0] - 2026-03-22

### Added

- **Passive subscriptions** — pure reads from `app-db` with no side effects
  - `::rfq/query-state` — same shape as `::rfq/query`, no fetching/lifecycle
  - `::rfq/infinite-query-state` — same shape as `::rfq/infinite-query`, no fetching/lifecycle
  - Use with manual lifecycle management via `::rfq/ensure-query`, `::rfq/mark-active`, `::rfq/mark-inactive` in navigation hooks

### Changed

- Extracted `resolve-query` and `resolve-infinite-query` pure functions in `subs.cljc` — shared by both effectful and passive subscriptions, eliminating duplicated logic

## [0.1.0] - 2026-03-22

Initial public release.

### Added

- **Core query system** — `reg-query`, `ensure-query`, `refetch-query`, `query-success`, `query-failure`
- **Mutation system** — `reg-mutation`, `execute-mutation`, `mutation-success`, `mutation-failure`, `reset-mutation`
- **`::rfq/query` subscription** — `reg-sub-raw` with automatic fetch, mark-active/inactive, polling, and GC lifecycle
- **Tag-based cache invalidation** — `invalidate-tags` marks matching queries stale and refetches active ones
- **Per-query garbage collection** — timer-based eviction of inactive queries after `cache-time-ms`
- **Polling** — per-query and per-subscriber intervals, lowest non-zero wins, auto-start/stop
- **Conditional fetching** — `:skip?` option on subscriptions for dependent queries
- **Prefetching** — `rfq/prefetch` to pre-populate cache before subscribing
- **Infinite queries** — cursor-based pagination with `:infinite` config, `::rfq/infinite-query` subscription, `fetch-next-page`, sequential re-fetch on invalidation, `:max-pages` sliding window
- **`transform-response` / `transform-error`** — per-query and per-mutation response transformation
- **`set-query-data`** — directly set cached query data for optimistic updates
- **`reset-api-state!`** — clear all query/mutation state and cancel all timers
- **Mutation lifecycle hooks** — `:on-start`, `:on-success`, `:on-failure` on `execute-mutation` opts map
- **`init!`** — declarative one-shot registry initialization (queries, mutations, default-effect-fn)
- **Per-query effect override** — `:effect-fn` on individual queries/mutations for WebSocket, GraphQL, etc.
- **Abort controller support** — `:abort-key` and `:abort-request` for cancelling in-flight requests
- Derived subscriptions: `query-data`, `query-status`, `query-fetching?`, `query-error`, `mutation`, `mutation-status`
- Debug logging via `rfq/enable-debug-logging!`
- Reagent and UIx example apps with 8 tabs each
- 80 unit tests (371 assertions), 60 Playwright e2e tests
- GitHub Actions CI with parallel unit + e2e jobs
