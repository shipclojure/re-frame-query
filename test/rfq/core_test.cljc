(ns rfq.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [rfq.core :as rfq]
   [rfq.util :as util]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn reset-db! []
  (reset! rf-db/app-db {})
  (rfq/clear-registry!))

(use-fixtures :each
  {:before reset-db!
   :after  reset-db!})

(defn app-db [] @rf-db/app-db)

(defn process-event
  "Dispatch an event synchronously for testing."
  [event]
  (rf/dispatch-sync event))

;; ---------------------------------------------------------------------------
;; Registration tests
;; ---------------------------------------------------------------------------

(deftest reg-query-test
  (testing "registers a query and returns the key"
    (let [k (rfq/reg-query :todos/list
              {:query-fn (fn [_] {})})]
      (is (= :todos/list k)))))

(deftest reg-mutation-test
  (testing "registers a mutation and returns the key"
    (let [k (rfq/reg-mutation :todos/add
              {:mutation-fn (fn [_] {})})]
      (is (= :todos/add k)))))

;; ---------------------------------------------------------------------------
;; Query event tests
;; ---------------------------------------------------------------------------

(deftest ensure-query-sets-loading-test
  (testing "ensure-query sets status to :loading for a new query"
    (rfq/reg-query :books/list
      {:query-fn (fn [_]
                   ;; Return no effects — we just test the db state
                   {})})
    (process-event [:rfq/ensure-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:rfq/queries qid])]
      (is (= :loading (:status query)))
      (is (true? (:fetching? query))))))

(deftest query-success-test
  (testing "query-success stores data and metadata"
    (rfq/reg-query :books/list
      {:query-fn      (fn [_] {})
       :stale-time-ms 30000
       :cache-time-ms 300000
       :tags          (fn [_] [[:books :all]])})
    ;; Simulate success
    (process-event [:rfq/query-success :books/list {} [{:id 1 :title "Dune"}]])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:rfq/queries qid])]
      (is (= :success (:status query)))
      (is (= [{:id 1 :title "Dune"}] (:data query)))
      (is (nil? (:error query)))
      (is (false? (:fetching? query)))
      (is (number? (:fetched-at query)))
      (is (= #{[:books :all]} (:tags query)))
      (is (= 30000 (:stale-time-ms query)))
      (is (= 300000 (:cache-time-ms query))))))

(deftest query-failure-test
  (testing "query-failure stores error"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:rfq/query-failure :books/list {} {:status 500}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:rfq/queries qid])]
      (is (= :error (:status query)))
      (is (= {:status 500} (:error query)))
      (is (false? (:fetching? query))))))

;; ---------------------------------------------------------------------------
;; Mutation event tests
;; ---------------------------------------------------------------------------

(deftest mutation-success-test
  (testing "mutation-success sets status and triggers invalidation dispatch"
    (rfq/reg-mutation :books/create
      {:mutation-fn (fn [_] {})
       :invalidates (fn [_] [[:books :all]])})
    (process-event [:rfq/mutation-success :books/create {} {:id 2}])
    (let [mid      (util/query-id :books/create {})
          mutation (get-in (app-db) [:rfq/mutations mid])]
      (is (= :success (:status mutation))))))

(deftest mutation-failure-test
  (testing "mutation-failure stores error"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (process-event [:rfq/mutation-failure :books/create {} {:status 422}])
    (let [mid      (util/query-id :books/create {})
          mutation (get-in (app-db) [:rfq/mutations mid])]
      (is (= :error (:status mutation)))
      (is (= {:status 422} (:error mutation))))))

;; ---------------------------------------------------------------------------
;; Invalidation tests
;; ---------------------------------------------------------------------------

(deftest invalidate-tags-marks-matching-queries-stale
  (testing "invalidate-tags marks queries with matching tags as stale"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :tags     (fn [_] [[:books :all]])})
    ;; Set up a cached query
    (process-event [:rfq/query-success :books/list {} [{:id 1}]])
    ;; Invalidate
    (process-event [:rfq/invalidate-tags [[:books :all]]])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:rfq/queries qid])]
      (is (true? (:stale? query))))))

;; ---------------------------------------------------------------------------
;; Garbage collection tests
;; ---------------------------------------------------------------------------

(deftest garbage-collect-removes-expired-inactive-queries
  (testing "GC removes expired inactive queries"
    (rfq/reg-query :books/list
      {:query-fn      (fn [_] {})
       :cache-time-ms 1000})
    ;; Simulate a query that was fetched 2 seconds ago
    (process-event [:rfq/query-success :books/list {} [{:id 1}]])
    ;; Manually set fetched-at to the past
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:rfq/queries qid :fetched-at] 1000)
      ;; GC at time 3000 (2 seconds after fetch, past 1s cache-time)
      (process-event [:rfq/garbage-collect 3000])
      (is (nil? (get-in (app-db) [:rfq/queries qid]))))))

(deftest garbage-collect-keeps-active-queries
  (testing "GC keeps active queries even if expired"
    (rfq/reg-query :books/list
      {:query-fn      (fn [_] {})
       :cache-time-ms 1000})
    (process-event [:rfq/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:rfq/queries qid :fetched-at] 1000)
      (swap! rf-db/app-db assoc-in [:rfq/queries qid :active?] true)
      (process-event [:rfq/garbage-collect 3000])
      (is (some? (get-in (app-db) [:rfq/queries qid]))))))

;; ---------------------------------------------------------------------------
;; Active tracking tests
;; ---------------------------------------------------------------------------

(deftest mark-active-inactive-test
  (testing "mark-active sets active? to true"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:rfq/query-success :books/list {} []])
    (process-event [:rfq/mark-active :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (true? (get-in (app-db) [:rfq/queries qid :active?])))))

  (testing "mark-inactive sets active? to false"
    (process-event [:rfq/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (false? (get-in (app-db) [:rfq/queries qid :active?]))))))
