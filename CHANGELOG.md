# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
