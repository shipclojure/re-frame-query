(ns example.uix-app.queries
  "Query and mutation registrations for the demo book app.
   Uses :http effect (js/fetch) — requests are intercepted by MSW."
  (:require
   [rfq.core :as rfq]))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(rfq/reg-query :books/list
               {:query-fn     (fn [params]
                                {:http {:method     :get
                                        :url        "/api/books"
                                        :on-success [:rfq/query-success :books/list params]
                                        :on-failure [:rfq/query-failure :books/list params]}})
                :stale-time-ms 30000        ;; 30 seconds
                :cache-time-ms 300000       ;; 5 minutes
                :tags          (constantly [[:books :all]])})

(rfq/reg-query :books/page
               {:query-fn     (fn [{:keys [page per-page] :as params}]
                                {:http {:method     :get
                                        :url        (str "/api/books?page=" page "&per_page=" (or per-page 3))
                                        :on-success [:rfq/query-success :books/page params]
                                        :on-failure [:rfq/query-failure :books/page params]}})
                :stale-time-ms 30000
                :cache-time-ms 300000
                :tags          (fn [{:keys [page]}]
                                 [[:books :all]
                                  [:books :page page]])})

(rfq/reg-query :book/detail
               {:query-fn     (fn [{:keys [id] :as params}]
                                {:http {:method     :get
                                        :url        (str "/api/books/" id)
                                        :on-success [:rfq/query-success :book/detail params]
                                        :on-failure [:rfq/query-failure :book/detail params]}})
                :stale-time-ms 30000
                :cache-time-ms 300000
                :tags          (fn [{:keys [id]}]
                                 [[:books :id id]
                                  [:books :all]])})

;; ---------------------------------------------------------------------------
;; Mutations
;; ---------------------------------------------------------------------------

(rfq/reg-mutation :books/create
                  {:mutation-fn  (fn [{:keys [title author] :as params}]
                                   {:http {:method     :post
                                           :url        "/api/books"
                                           :body       {:title title :author author}
                                           :on-success [:rfq/mutation-success :books/create params]
                                           :on-failure [:rfq/mutation-failure :books/create params]}})
                   :invalidates  (constantly [[:books :all]])})

(rfq/reg-mutation :books/update
                  {:mutation-fn  (fn [{:keys [id title author] :as params}]
                                   {:http {:method     :put
                                           :url        (str "/api/books/" id)
                                           :body       (cond-> {}
                                                         title  (assoc :title title)
                                                         author (assoc :author author))
                                           :on-success [:rfq/mutation-success :books/update params]
                                           :on-failure [:rfq/mutation-failure :books/update params]}})
                   :invalidates  (fn [{:keys [id]}]
                                   [[:books :id id]
                                    [:books :all]])})

(rfq/reg-mutation :books/delete
                  {:mutation-fn  (fn [{:keys [id] :as params}]
                                   {:http {:method     :delete
                                           :url        (str "/api/books/" id)
                                           :on-success [:rfq/mutation-success :books/delete params]
                                           :on-failure [:rfq/mutation-failure :books/delete params]}})
                   :invalidates  (fn [{:keys [id]}]
                                   [[:books :id id]
                                    [:books :all]])})
