# Lifecycle Hooks

re-frame-query exposes lifecycle information differently for mutations and queries:

- **Mutations** support explicit per-call hooks (`:on-start`, `:on-success`, `:on-failure`) passed in the opts map to `execute-mutation`. They're scoped to a single action and commonly power optimistic updates.
- **Queries** are observed via **re-frame global interceptors** on the library's lifecycle events. This matches the fact that query fetches can originate from many places (subscriptions, navigation events, polling, tag invalidation, prefetches) — a per-call hook would silently miss most of them.

## Mutation Lifecycle Hooks

Pass an opts map as the third argument to `execute-mutation` to hook into the mutation lifecycle:

```clojure
(rf/dispatch [::rfq/execute-mutation :todos/toggle {:id 5 :done true}
              {:on-start   [[:my-app/on-start-event]]
               :on-success [[:my-app/on-success-event]]
               :on-failure [[:my-app/on-failure-event]]}])
```

| Hook | When | Args conj'd onto each event vector |
|---|---|---|
| `:on-start` | Before the effect fires | `params` |
| `:on-success` | After mutation succeeds | `params`, `response-data` |
| `:on-failure` | After mutation fails | `params`, `error` |

Each hook is a vector of event vectors — all events in the vector are dispatched. Hooks are optional; omitting the opts map works exactly as before.

### Hook Handler Signatures

rfq **conj's its own args onto every hook event you register**. This is different from how day8/http-fx and most other re-frame HTTP effects work — those dispatch `(conj on-success-event response)`, appending only the response. rfq appends `params` **and** (for `:on-success`/`:on-failure`) the response or error.

If you pre-bind data in the hook event vector, those values sit *before* rfq's appended args:

```clojure
;; Dispatch:
[::rfq/execute-mutation :todos/toggle {:id 5}
 {:on-success [[:my/hook extra-1 extra-2]]}]

;; Hook handler receives:
(fn [cofx [_ extra-1 extra-2 mutation-params response]] ...)
;;               ^^^^^^^ ^^^^^^^  ^^^^^^^^^^^^^^^^  ^^^^^^^^
;;               your pre-bound args  rfq args
```

### Signature cheat sheet

```clojure
;; No pre-bound args
(rf/reg-event-fx :my/on-success
  (fn [_ [_ params response]] ...))

(rf/reg-event-fx :my/on-failure
  (fn [_ [_ params error]] ...))

(rf/reg-event-fx :my/on-start
  (fn [_ [_ params]] ...))

;; With pre-bound args (e.g. a user-supplied callback fn)
(rf/dispatch [::rfq/execute-mutation :todos/add {:title "x"}
              {:on-success [[:my/on-success some-data]]}])

(rf/reg-event-fx :my/on-success
  (fn [_ [_ some-data params response]] ...))
```

### Why this design?

Hooks receive `params` so they can operate on the same input the mutation ran with — essential for optimistic-update snapshots keyed by the mutation input, rollback logic, and generic analytics/toast interceptors. Making `params` implicit (always passed) keeps hook events reusable across call sites without threading mutation inputs through the event vector manually.

## Optimistic Updates Recipe

Use lifecycle hooks + `set-query-data` to build optimistic updates in pure re-frame:

```clojure
;; 1. Register hook events — these are YOUR event handlers, not library code
(rf/reg-event-fx :todos/optimistic-toggle
  (fn [{:keys [db]} [_ {:keys [id done]}]]
    (let [qid  [:todos/list {}]
          old  (get-in db [:re-frame.query/queries qid :data])
          new  (mapv #(if (= (:id %) id) (assoc % :done done) %) old)]
      {:db       (assoc-in db [:snapshots qid] old)           ;; save snapshot
       :dispatch [::rfq/set-query-data :todos/list {} new]}))) ;; patch cache

(rf/reg-event-fx :todos/rollback
  (fn [{:keys [db]} [_ _params _error]]
    (let [qid [:todos/list {}]
          old (get-in db [:snapshots qid])]
      {:db       (update db :snapshots dissoc qid)
       :dispatch [::rfq/set-query-data :todos/list {} old]}))) ;; restore snapshot

;; 2. Dispatch mutation with hooks
(rf/dispatch [::rfq/execute-mutation :todos/toggle {:id 5 :done true}
              {:on-start   [[:todos/optimistic-toggle]]
               :on-failure [[:todos/rollback]]}])
```

The checkbox toggles instantly. If the server rejects, the snapshot is restored. No library magic — just re-frame events and data.

> **Race condition note:** If a query has active polling or an in-flight refetch, the refetch response could briefly overwrite your optimistic data before the mutation completes. In practice this race is rare and self-correcting — the mutation's `:invalidates` triggers a fresh refetch with correct server data immediately after success. If you need to guard against it, see the cancellation recipe below.

## Advanced: Cancelling In-Flight Requests

TanStack Query solves the optimistic update race with `cancelQueries`, which aborts in-flight HTTP requests via `AbortController`. Since re-frame-query is transport-agnostic, cancellation lives in your transport layer — not in the library. Here's the pattern:

```clojure
;; 1. Store AbortControllers per query in your transport layer
(defonce abort-controllers (atom {}))

(rf/reg-fx :http-xhrio
  (fn [{:keys [method url body on-success on-failure abort-key]}]
    (let [controller (js/AbortController.)
          signal     (.-signal controller)]
      (when abort-key
        (swap! abort-controllers assoc abort-key controller))
      (-> (js/fetch url (clj->js {:method  (name method)
                                   :headers {"Content-Type" "application/json"}
                                   :signal  signal
                                   :body    (some-> body clj->js js/JSON.stringify)}))
          (.then  #(when (.-ok %) ...dispatch on-success...))
          (.catch #(when-not (.-aborted signal)  ;; silently drop aborted requests
                    ...dispatch on-failure...))))))

;; 2. Register an effect that aborts a request by key
(rf/reg-fx :abort-request
  (fn [abort-key]
    (when-let [controller (get @abort-controllers abort-key)]
      (.abort controller)
      (swap! abort-controllers dissoc abort-key))))

;; 3. Tag queries with an abort-key so they can be cancelled
(rfq/reg-query :todos/list
  {:query-fn (fn [_] {:method :get :url "/api/todos"
                       :abort-key [:todos/list {}]})
   :tags     (constantly [[:todos]])})

;; 4. In your on-start hook, abort the in-flight refetch before patching
(rf/reg-event-fx :todos/optimistic-toggle
  (fn [{:keys [db]} [_ {:keys [id done]}]]
    (let [qid [:todos/list {}]
          old (get-in db [:re-frame.query/queries qid :data])
          new (mapv #(if (= (:id %) id) (assoc % :done done) %) old)]
      {:db            (assoc-in db [:snapshots qid] old)
       :abort-request qid                                  ;; cancel in-flight refetch
       :dispatch      [::rfq/set-query-data :todos/list {} new]})))
```

The aborted fetch silently drops (no `on-failure` dispatch), the optimistic data stays intact, and the mutation's `:invalidates` triggers a correct refetch when the server responds.

## Observing Query Lifecycle

Queries don't have per-call `:on-start`/`:on-success`/`:on-failure` hooks. The reason is that a single query key can be fetched from many entry points in the same session — `ensure-query`, `refetch-query`, polling ticks, tag invalidations, prefetches, or the `::rfq/query` subscription — and most of those paths have no natural place to carry caller-supplied opts. Baking hooks into only some of them would be a footgun.

Instead, observe the library's **lifecycle events** with a re-frame global interceptor. The events are stable and part of the public surface:

| Event | Carries | When |
|---|---|---|
| `[:re-frame.query/ensure-query k params]` | `k`, `params` | A fetch is about to start (not fired on cache hits) |
| `[:re-frame.query/refetch-query k params]` | `k`, `params` | A forced refetch is starting |
| `[:re-frame.query/query-success k params data]` | `k`, `params`, post-`:transform-response` data | Success, after `:db` commit |
| `[:re-frame.query/query-failure k params error]` | `k`, `params`, post-`:transform-error` error | Failure, after `:db` commit |

Because these fire regardless of which entry point triggered the fetch, a single interceptor will reliably observe *every* lifecycle transition for the queries you care about.

### Global interceptor (all queries)

Use `rfq/parse-result-event` to extract the event into a map, and
`re-frame.interceptor/update-effect` to enqueue dispatches via `:fx` —
keeping the interceptor a pure `context -> context` function:

```clojure
(require '[re-frame.interceptor :as rfi])

(rf/reg-global-interceptor
  (rf/->interceptor
    :id :my-app/query-telemetry
    :after
    (fn [context]
      (let [{:keys [event-id k params data error]}
            (rfq/parse-result-event (get-in context [:coeffects :event]))]
        (case event-id
          :re-frame.query/query-success
          (rfi/update-effect context :fx (fnil conj [])
                             [:dispatch [:analytics/query-succeeded k params]])

          :re-frame.query/query-failure
          (rfi/update-effect context :fx (fnil conj [])
                             [:dispatch [:analytics/query-failed k params error]])

          context)))))
```

The `:after` hook runs after the handler commits, so for `query-success` / `query-failure` the fresh data is already in `app-db` — any event you enqueue via `:fx` will see the updated state.

### Route-scoped interceptors

Interceptors don't have to live forever. Register on route enter, clear on route leave — the interceptor only sees events dispatched while it's installed, so there's no global pollution.

Do the registration and clearing in the route-enter/leave **functions** themselves (e.g. reitit's `:controllers` `:start`/`:stop`, or whatever your router calls before dispatching its enter/leave events). re-frame events should remain pure data; `reg-global-interceptor` and `clear-global-interceptor` are side effects, so they don't belong inside an event handler.

```clojure
(require '[re-frame.interceptor :as rfi])

(defn books-route-enter []
  (rf/reg-global-interceptor
    (rf/->interceptor
      :id :books/page-telemetry       ;; unique id used to uninstall later
      :after
      (fn [context]
        (let [{:keys [event-id k]}
              (rfq/parse-result-event (get-in context [:coeffects :event]))]
          (if (and (= k :books/list)
                   (#{:re-frame.query/query-success
                      :re-frame.query/query-failure} event-id))
            (rfi/update-effect context :fx (fnil conj [])
                               [:dispatch [:analytics/books-event event-id]])
            context)))))
  (rf/dispatch [::rfq/ensure-query :books/list {:page 1}]))

(defn books-route-leave []
  (rf/clear-global-interceptor :books/page-telemetry)
  (rf/dispatch [::rfq/mark-inactive :books/list {:page 1}]))

;; Wire into your router. With reitit:
;; {:name :books
;;  :controllers [{:start books-route-enter
;;                 :stop  books-route-leave}]}
```

Pair this with [polling's route enter/leave pattern](polling.md) — the two use the same lifecycle, so a single pair of route hooks can wire both fetching and observability.

### When you'd use this

- **Analytics / tracing** for query completion times across the app.
- **Toast-on-failure** policies that apply to a whole route or section.
- **Post-success cache syncing** — e.g. when `:books/list` succeeds, patch a derived `:books/count` query.
- **Debug logging** in development (this is exactly what [`rfq/enable-debug-logging!`](../src/re_frame/query.cljc) does — a global interceptor on all `:re-frame.query/*` events).

### What about before the fetch ("on-start")?

The `ensure-query` / `refetch-query` events themselves fire before the HTTP effect does. An `:after` interceptor on those sees the event *after* the `:db` update that marked the query `:loading`/`:fetching? true`, which is the natural "start" signal. If you need strict before-effect timing (rare), a `:before` interceptor sees the event even earlier — but in practice, `:loading` being in `app-db` is the observable contract most consumers want.
