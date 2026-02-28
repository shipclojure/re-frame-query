(ns rfq.core
  "re-frame-query: Declarative data fetching and caching for re-frame.

  Public API namespace — require this to get started:

    (ns my-app.core
      (:require [rfq.core :as rfq]))

    ;; Register a query
    (rfq/reg-query :todos/list
      {:query-fn (fn [{:keys [user-id]}]
                   {:http-xhrio {:method :get
                                 :uri (str \"/api/users/\" user-id \"/todos\")
                                 :on-success [:rfq/query-success :todos/list {:user-id user-id}]
                                 :on-failure [:rfq/query-failure :todos/list {:user-id user-id}]}})
       :stale-time-ms 30000
       :tags (fn [{:keys [user-id]}] [[:todos :user user-id]])})

    ;; Fetch and subscribe
    (rf/dispatch [:rfq/ensure-query :todos/list {:user-id 42}])
    @(rf/subscribe [:rfq/query :todos/list {:user-id 42}])"
  (:require
   ;; Side-effecting requires — registers events and subs on load
   [rfq.events]
   [rfq.subs]
   ;; Functional requires
   [rfq.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Public Registration API
;; ---------------------------------------------------------------------------

(def reg-query
  "Register a query definition.

  Arguments:
    k      - Keyword identifying the query (e.g. :todos/list)
    config - Map with:
      :query-fn      (fn [params] -> re-frame effects map)  REQUIRED
      :cache-time-ms  Milliseconds before inactive query is GC'd
      :stale-time-ms  Milliseconds before query auto-becomes stale
      :tags           (fn [params] -> [[tag ...] ...]) for invalidation

  Returns the query key."
  registry/reg-query)

(def reg-mutation
  "Register a mutation definition.

  Arguments:
    k      - Keyword identifying the mutation (e.g. :todos/add)
    config - Map with:
      :mutation-fn  (fn [params] -> re-frame effects map)  REQUIRED
      :invalidates  (fn [params] -> [[tag ...] ...]) tags to invalidate on success

  Returns the mutation key."
  registry/reg-mutation)

(def clear-registry!
  "Reset all query and mutation registrations. For testing only."
  registry/clear-registry!)
