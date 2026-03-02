(ns example.uix-app.queries
  "Query and mutation registrations for the demo book app.
   Uses :http effect (js/fetch) — requests are intercepted by MSW."
  (:require
   [rfq.core :as rfq]))

;; ---------------------------------------------------------------------------
;; Effect adapter — configure once, used by all queries and mutations
;; ---------------------------------------------------------------------------

(rfq/set-default-effect-fn!
  (fn [request on-success on-failure]
    {:http (assoc request :on-success on-success :on-failure on-failure)}))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(rfq/reg-query :books/list
  {:query-fn      (fn [_params]
                    {:method :get
                     :url    "/api/books"})
   :stale-time-ms 30000        ;; 30 seconds
   :cache-time-ms 300000       ;; 5 minutes
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
;; Mutations
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
