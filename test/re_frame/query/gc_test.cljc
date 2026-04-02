(ns re-frame.query.gc-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [day8.re-frame.test :as rf-test]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [re-frame.query :as rfq]
   [re-frame.query.gc :as gc]
   [re-frame.query.polling :as polling]
   [re-frame.query.registry :as registry]
   [re-frame.query.test-helpers :as h]
   [re-frame.query.util :as util]
   #?(:cljs [reagent.ratom :as ratom])))

(use-fixtures :each {:before h/reset-db! :after h/reset-db!})

;; ---------------------------------------------------------------------------
;; Garbage collection tests
;; ---------------------------------------------------------------------------

(deftest garbage-collection-test
  (testing "GC removes expired inactive queries"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :cache-time-ms 1000})
    ;; Simulate a query that was fetched 2 seconds ago
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Manually set fetched-at to the past
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 1000)
      ;; GC at time 3000 (2 seconds after fetch, past 1s cache-time)
      (h/process-event [:re-frame.query/garbage-collect 3000])
      (is (nil? (get-in (h/app-db) [:re-frame.query/queries qid])))))

  (testing "GC keeps active queries even if expired"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :cache-time-ms 1000})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 1000)
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :active?] true)
      (h/process-event [:re-frame.query/garbage-collect 3000])
      (is (some? (get-in (h/app-db) [:re-frame.query/queries qid]))))))

;; ---------------------------------------------------------------------------
;; Active tracking tests
;; ---------------------------------------------------------------------------

(deftest mark-active-inactive-test
  (testing "mark-active sets active? to true"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-success :books/list {} []])
    (h/process-event [:re-frame.query/mark-active :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :active?])))))

  (testing "mark-inactive sets active? to false"
    (h/process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (false? (get-in (h/app-db) [:re-frame.query/queries qid :active?]))))))

;; ---------------------------------------------------------------------------
;; Per-query GC timer tests
;; ---------------------------------------------------------------------------

(deftest mark-inactive-gc-timer-test
  (testing "mark-inactive schedules a GC timer when query has cache-time-ms"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :cache-time-ms 60000})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (contains? (gc/active-timers) qid))))

  (testing "mark-inactive schedules a GC timer using default cache time when query has no cache-time-ms"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (contains? (gc/active-timers) qid)))))

(deftest mark-active-cancels-gc-timer
  (testing "mark-active cancels any pending GC timer"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :cache-time-ms 60000})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Go inactive → timer starts
    (h/process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (contains? (gc/active-timers) qid))
      ;; Go active again → timer cancelled
      (h/process-event [:re-frame.query/mark-active :books/list {}])
      (is (not (contains? (gc/active-timers) qid))))))

(deftest remove-query-test
  (testing "remove-query removes an inactive query from cache"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Mark inactive, then remove
      (h/process-event [:re-frame.query/mark-inactive :books/list {}])
      (h/process-event [:re-frame.query/remove-query qid])
      (is (nil? (get-in (h/app-db) [:re-frame.query/queries qid])))))

  (testing "remove-query is a no-op if query became active again"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Mark active
      (h/process-event [:re-frame.query/mark-active :books/list {}])
      ;; Attempt removal — should be a no-op
      (h/process-event [:re-frame.query/remove-query qid])
      (is (some? (get-in (h/app-db) [:re-frame.query/queries qid]))))))

(deftest zero-cache-time-does-not-schedule-gc-timer
  (testing "With cache-time-ms 0, no GC timer is scheduled (pos? guard in schedule-gc!)"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :cache-time-ms 0})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (not (contains? (gc/active-timers) qid))
          "no GC timer is scheduled for cache-time-ms 0")
      (is (some? (get-in (h/app-db) [:re-frame.query/queries qid]))
          "query is still in cache (no timer means no auto-cleanup)"))))

(deftest gc-removes-query-only-when-expired-and-inactive
  (testing "GC skips queries that are expired but active, or inactive but not expired"
    (rfq/reg-query :books/active
      {:query-fn (fn [_] {})
       :cache-time-ms 1000})
    (rfq/reg-query :books/fresh
      {:query-fn (fn [_] {})
       :cache-time-ms 60000})
    (rfq/reg-query :books/expired
      {:query-fn (fn [_] {})
       :cache-time-ms 1000})
    ;; All fetched at time 1000
    (with-redefs [util/now-ms (constantly 1000)]
      (h/process-event [:re-frame.query/query-success :books/active {} [{:id 1}]])
      (h/process-event [:re-frame.query/query-success :books/fresh {} [{:id 2}]])
      (h/process-event [:re-frame.query/query-success :books/expired {} [{:id 3}]]))
    ;; active query is marked active
    (h/process-event [:re-frame.query/mark-active :books/active {}])
    (let [active-qid (util/query-id :books/active {})
          fresh-qid (util/query-id :books/fresh {})
          expired-qid (util/query-id :books/expired {})]
      ;; GC at time 3000: (3000 - 1000 = 2000 > cache-time 1000 for active & expired)
      ;; but (3000 - 1000 = 2000 < cache-time 60000 for fresh)
      (h/process-event [:re-frame.query/garbage-collect 3000])
      (is (some? (get-in (h/app-db) [:re-frame.query/queries active-qid]))
          "active query kept even though expired")
      (is (some? (get-in (h/app-db) [:re-frame.query/queries fresh-qid]))
          "inactive but fresh query kept")
      (is (nil? (get-in (h/app-db) [:re-frame.query/queries expired-qid]))
          "inactive and expired query removed"))))

(deftest mark-active-after-inactive-preserves-query
  (testing "Re-subscribing before GC fires keeps the query alive"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :cache-time-ms 60000})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Subscribe → unsubscribe (timer starts)
      (h/process-event [:re-frame.query/mark-active :books/list {}])
      (h/process-event [:re-frame.query/mark-inactive :books/list {}])
      (is (contains? (gc/active-timers) qid)
          "GC timer is ticking")
      ;; Re-subscribe before timer fires
      (h/process-event [:re-frame.query/mark-active :books/list {}])
      (is (not (contains? (gc/active-timers) qid))
          "GC timer cancelled on re-subscribe")
      (is (some? (get-in (h/app-db) [:re-frame.query/queries qid]))
          "query is still in cache")
      (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :active?]))
          "query is active again"))))

(deftest per-query-cleanup-test
  (testing "Full per-query cleanup: mark-inactive → timer → remove-query → evicted"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :cache-time-ms 60000})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/mark-active :books/list {}])
    (let [qid (util/query-id :books/list {})]
      ;; Step 1: Query is active, no timer
      (is (not (contains? (gc/active-timers) qid))
          "no timer while active")
      ;; Step 2: All subscribers gone → mark-inactive → timer starts
      (h/process-event [:re-frame.query/mark-inactive :books/list {}])
      (is (false? (get-in (h/app-db) [:re-frame.query/queries qid :active?]))
          "query is now inactive")
      (is (contains? (gc/active-timers) qid)
          "GC timer is scheduled after mark-inactive")
      ;; Step 3: Timer fires → dispatches remove-query → query evicted
      (h/process-event [:re-frame.query/remove-query qid])
      (is (nil? (get-in (h/app-db) [:re-frame.query/queries qid]))
          "query evicted from cache after remove-query")))

  (testing "remove-query is a no-op if the query became active again before timer fires"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :cache-time-ms 60000})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Unsubscribe → timer starts
      (h/process-event [:re-frame.query/mark-inactive :books/list {}])
      (is (contains? (gc/active-timers) qid))
      ;; Re-subscribe → timer cancelled
      (h/process-event [:re-frame.query/mark-active :books/list {}])
      (is (not (contains? (gc/active-timers) qid)))
      ;; Stale timer fires anyway (edge case) → remove-query should be no-op
      (h/process-event [:re-frame.query/remove-query qid])
      (is (some? (get-in (h/app-db) [:re-frame.query/queries qid]))
          "query is NOT removed because it's active again")
      (is (= [{:id 1}] (get-in (h/app-db) [:re-frame.query/queries qid :data]))
          "data is preserved"))))

(deftest gc-does-not-remove-queries-without-cache-time
  (testing "Queries without cache-time-ms are never expired by bulk GC"
    (rfq/reg-query :books/permanent {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-success :books/permanent {} [{:id 1}]])
    (let [qid (util/query-id :books/permanent {})]
      ;; Bulk GC at a very large time
      (h/process-event [:re-frame.query/garbage-collect 999999999])
      (is (some? (get-in (h/app-db) [:re-frame.query/queries qid]))
          "query without cache-time-ms survives bulk GC"))))

(deftest per-query-timer-fires-and-evicts-async
  (testing "After cache-time-ms elapses, the real timer fires remove-query and evicts the query"
    (rf-test/run-test-async
     (rfq/reg-query :books/list
       {:query-fn (fn [_] {})
        :cache-time-ms 100})
     (rf/dispatch-sync [:re-frame.query/query-success :books/list {} [{:id 1}]])
     (let [qid (util/query-id :books/list {})]
       (is (some? (get-in (h/app-db) [:re-frame.query/queries qid]))
           "query exists before mark-inactive")
        ;; Mark inactive — starts a real 100ms timer
       (rf/dispatch-sync [:re-frame.query/mark-inactive :books/list {}])
       (is (contains? (gc/active-timers) qid)
           "GC timer is scheduled")
        ;; Wait for the timer to fire remove-query
       (rf-test/wait-for [:re-frame.query/remove-query]
                         (is (nil? (get-in (h/app-db) [:re-frame.query/queries qid]))
                             "query evicted after timer fires"))))))
