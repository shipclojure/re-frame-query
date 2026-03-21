(ns example.reagent-app.queries
  "Query and mutation registrations for the demo book app.
   Uses :http effect (js/fetch) — requests are intercepted by MSW."
  (:require
   [re-frame.query :as rfq]))

;; ---------------------------------------------------------------------------
;; Effect adapter — configure once, used by all queries and mutations
;; ---------------------------------------------------------------------------

(rfq/set-default-effect-fn!
  (fn [request on-success on-failure]
    {:http (assoc request :on-success on-success :on-failure on-failure)}))

;; ---------------------------------------------------------------------------
;; Queries — Basic CRUD
;; ---------------------------------------------------------------------------

(rfq/reg-query :books/list
  {:query-fn      (fn [_params]
                    {:method :get
                     :url    "/api/books"})
   :stale-time-ms 30000
   :cache-time-ms 300000
   :tags          (constantly [[:books :all]])})

(rfq/reg-query :books/page
  {:query-fn      (fn [{:keys [page per-page]}]
                    {:method :get
                     :url    (str "/api/books?page=" page "&per_page=" (or per-page 3))})
   :stale-time-ms 30000
   :cache-time-ms 300000
   :tags          (fn [{:keys [page]}]
                    [[:books :all]
                     [:books :page page]])})

(rfq/reg-query :book/detail
  {:query-fn      (fn [{:keys [id]}]
                    {:method :get
                     :url    (str "/api/books/" id)})
   :stale-time-ms 30000
   :cache-time-ms 300000
   :tags          (fn [{:keys [id]}]
                    [[:books :id id]
                     [:books :all]])})

;; ---------------------------------------------------------------------------
;; Queries — Polling
;; ---------------------------------------------------------------------------

(rfq/reg-query :server/stats
  {:query-fn            (fn [_] {:method :get :url "/api/server-stats"})
   :stale-time-ms       1000
   :polling-interval-ms 2000})

;; ---------------------------------------------------------------------------
;; Queries — Dependent queries
;; ---------------------------------------------------------------------------

(rfq/reg-query :user/current
  {:query-fn      (fn [_] {:method :get :url "/api/me"})
   :stale-time-ms 60000})

(rfq/reg-query :user/favorites
  {:query-fn      (fn [{:keys [user-id]}]
                    {:method :get
                     :url    (str "/api/users/" user-id "/favorites")})
   :stale-time-ms 30000})

;; ---------------------------------------------------------------------------
;; Mutations — Basic CRUD
;; ---------------------------------------------------------------------------

(rfq/reg-mutation :books/create
  {:mutation-fn  (fn [{:keys [title author]}]
                   {:method :post
                    :url    "/api/books"
                    :body   {:title title :author author}})
   :invalidates  (constantly [[:books :all]])})

(rfq/reg-mutation :books/update
  {:mutation-fn  (fn [{:keys [id title author]}]
                   {:method :put
                    :url    (str "/api/books/" id)
                    :body   (cond-> {}
                              title  (assoc :title title)
                              author (assoc :author author))})
   :invalidates  (fn [{:keys [id]}]
                   [[:books :id id]
                    [:books :all]])})

(rfq/reg-mutation :books/delete
  {:mutation-fn  (fn [{:keys [id]}]
                   {:method :delete
                    :url    (str "/api/books/" id)})
   :invalidates  (fn [{:keys [id]}]
                   [[:books :id id]
                    [:books :all]])})

;; ---------------------------------------------------------------------------
;; Mutations — Mutation lifecycle demo
;; ---------------------------------------------------------------------------

(rfq/reg-mutation :books/create-demo
  {:mutation-fn  (fn [{:keys [title author]}]
                   {:method :post
                    :url    "/api/books"
                    :body   {:title title :author author}})
   :invalidates  (constantly [[:books :all]])})
