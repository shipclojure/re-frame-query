# re-frame-query · Reagent Example

A minimal Reagent app demonstrating **re-frame-query** features:

- **Queries** — declarative data fetching with automatic caching and stale detection
- **Mutations** — create, update, and delete operations with cache invalidation
- **Invalidation** — tag-based cache invalidation that triggers automatic refetching
- **Loading & error states** — built-in status tracking for every query and mutation

## Running the example

```bash
cd examples/reagent-app
pnpm install        # only needed once — installs shadow-cljs + react
pnpm exec shadow-cljs watch demo
```

Then open [http://localhost:8710](http://localhost:8710) in your browser.

## What to try

1. **Browse books** — the list loads via a `:books/list` query with 30 s stale time.
2. **Click a book** — opens a detail view powered by a separate `:book/detail` query.
3. **Edit a title** — dispatches a `:books/update` mutation that invalidates the cache.
4. **Delete a book** — dispatches `:books/delete` and returns to the list.
5. **Add a book** — uses `:books/create`; the list auto-refetches via tag invalidation.
6. **Manual invalidate** — click "🔄 Invalidate All Books" to force all book queries stale.

## Project structure

```
src/example/reagent_app/
├── core.cljs       Entry point — mounts the Reagent root
├── mock_api.cljs   In-memory mock API + :mock-http effect handler
├── queries.cljs    Query & mutation registrations (rfq/reg-query, rfq/reg-mutation)
└── views.cljs      Reagent components (book list, detail, form, invalidate button)
```
