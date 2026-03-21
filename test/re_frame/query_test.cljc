(ns re-frame.query-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [day8.re-frame.test :as rf-test]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [re-frame.query :as rfq]
   [re-frame.query.gc :as gc]
   [re-frame.query.polling :as polling]
   [re-frame.query.registry :as registry]
   [re-frame.query.util :as util]
   #?(:cljs [reagent.ratom :as ratom])))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn reset-db! []
  (reset! rf-db/app-db {})
  (rfq/clear-registry!)
  (gc/cancel-all!)
  (polling/cancel-all!))

(use-fixtures :each
  {:before reset-db!
   :after  reset-db!})

(defn app-db [] @rf-db/app-db)

(defn process-event
  "Dispatch an event synchronously for testing."
  [event]
  (rf/dispatch-sync event))

(def noop-effect-fn
  "No-op effect adapter for tests that need an effect-fn
   but don't care about the actual HTTP effects."
  (fn [_ _ _] {}))

;; ---------------------------------------------------------------------------
;; Registration tests
;; ---------------------------------------------------------------------------

(deftest registration-test
  (testing "registers a query and returns the key"
    (let [k (rfq/reg-query :todos/list
                           {:query-fn (fn [_] {})})]
      (is (= :todos/list k))))

  (testing "registers a mutation and returns the key"
    (let [k (rfq/reg-mutation :todos/add
                              {:mutation-fn (fn [_] {})})]
      (is (= :todos/add k)))))

;; ---------------------------------------------------------------------------
;; Query event tests
;; ---------------------------------------------------------------------------

(deftest ensure-query-test
  (testing "sets status to :loading when no data exists"
    (rfq/reg-query :books/list
                   {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query)))
      (is (true? (:fetching? query)))
      (is (nil? (:data query)))))

  (testing "keeps :success status when stale data exists"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :stale-time-ms 1000})
    ;; Fetch successfully first
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Make it stale: fetched-at=0, now-ms returns 1001 (past stale-time of 1000)
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
      (with-redefs [util/now-ms (constantly 1001)]
        (process-event [:re-frame.query/ensure-query :books/list {}]))
      (let [query (get-in (app-db) [:re-frame.query/queries qid])]
        (is (= :success (:status query))
            "status stays :success so components keep showing stale data")
        (is (true? (:fetching? query))
            "fetching? is true to indicate a background refetch")
        (is (= [{:id 1}] (:data query))
            "stale data is preserved while refetching"))))

  (testing "sets :loading when retrying after an error (no prior data)"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
    (process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query))
          "status resets to :loading on retry after error")
      (is (true? (:fetching? query)))))

  (testing "sets :loading after error even when stale data exists"
    (rfq/reg-query :books/detail
                   {:query-fn      (fn [_] {})
                    :stale-time-ms 1000})
    ;; First fetch succeeds
    (process-event [:re-frame.query/query-success :books/detail {} {:title "Dune"}])
    (let [qid (util/query-id :books/detail {})]
      ;; Then a refetch fails — data persists but status is :error
      (process-event [:re-frame.query/query-failure :books/detail {} {:status 500}])
      (is (= :error (get-in (app-db) [:re-frame.query/queries qid :status])))
      (is (some? (get-in (app-db) [:re-frame.query/queries qid :data])))
      ;; Retry
      (process-event [:re-frame.query/ensure-query :books/detail {}])
      (let [query (get-in (app-db) [:re-frame.query/queries qid])]
        (is (= :loading (:status query))
            "status resets to :loading even though stale data exists, because last status was :error")
        (is (true? (:fetching? query)))))))

(deftest refetch-query-test
  (testing "keeps :success status when data exists"
    (rfq/reg-query :books/list
                   {:query-fn (fn [_] {})})
    ;; Fetch successfully first
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Force refetch
    (process-event [:re-frame.query/refetch-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :success (:status query))
          "status stays :success during background refetch")
      (is (true? (:fetching? query)))
      (is (= [{:id 1}] (:data query)))))

  (testing "sets :loading when no prior data exists"
    ;; Reset db to clear data from previous testing block
    (reset! rf-db/app-db {})
    (rfq/reg-query :books/list
                   {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/refetch-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query)))
      (is (true? (:fetching? query)))))

  (testing "sets :loading when current status is :error"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    ;; First succeeds, then fails
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/query-failure :books/list {} {:status 503}])
    ;; Refetch after error
    (process-event [:re-frame.query/refetch-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query))
          "status is :loading, not :success, because last status was :error")
      (is (true? (:fetching? query))))))

(deftest query-success-test
  (testing "query-success stores data and metadata"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :stale-time-ms 30000
                    :cache-time-ms 300000
                    :tags          (fn [_] [[:books :all]])})
    ;; Simulate success
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1 :title "Dune"}]])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :success (:status query)))
      (is (= [{:id 1 :title "Dune"}] (:data query)))
      (is (nil? (:error query)))
      (is (false? (:fetching? query)))
      (is (number? (:fetched-at query)))
      (is (= #{[:books :all]} (:tags query)))
      (is (= 30000 (:stale-time-ms query)))
      (is (= 300000 (:cache-time-ms query)))))

  (testing "query-success stores default cache-time-ms when not specified in config"
    (rfq/reg-query :books/detail
                   {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (let [qid   (util/query-id :books/detail {:id 1})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= gc/default-cache-time-ms (:cache-time-ms query))))))

(deftest query-failure-test
  (testing "query-failure stores error"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :error (:status query)))
      (is (= {:status 500} (:error query)))
      (is (false? (:fetching? query))))))

;; ---------------------------------------------------------------------------
;; Mutation event tests
;; ---------------------------------------------------------------------------

(deftest mutation-events-test
  (testing "mutation-success sets status and triggers invalidation dispatch"
    (rfq/reg-mutation :books/create
                      {:mutation-fn (fn [_] {})
                       :invalidates (fn [_] [[:books :all]])})
    (process-event [:re-frame.query/mutation-success :books/create {} {:id 2}])
    (let [mid      (util/query-id :books/create {})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= :success (:status mutation)))))

  (testing "mutation-failure stores error"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/mutation-failure :books/create {} {:status 422}])
    (let [mid      (util/query-id :books/create {})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
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
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Invalidate
    (process-event [:re-frame.query/invalidate-tags [[:books :all]]])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (true? (:stale? query))))))

;; ---------------------------------------------------------------------------
;; Garbage collection tests
;; ---------------------------------------------------------------------------

(deftest garbage-collection-test
  (testing "GC removes expired inactive queries"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 1000})
    ;; Simulate a query that was fetched 2 seconds ago
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Manually set fetched-at to the past
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 1000)
      ;; GC at time 3000 (2 seconds after fetch, past 1s cache-time)
      (process-event [:re-frame.query/garbage-collect 3000])
      (is (nil? (get-in (app-db) [:re-frame.query/queries qid])))))

  (testing "GC keeps active queries even if expired"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 1000})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 1000)
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :active?] true)
      (process-event [:re-frame.query/garbage-collect 3000])
      (is (some? (get-in (app-db) [:re-frame.query/queries qid]))))))

;; ---------------------------------------------------------------------------
;; Active tracking tests
;; ---------------------------------------------------------------------------

(deftest mark-active-inactive-test
  (testing "mark-active sets active? to true"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/list {} []])
    (process-event [:re-frame.query/mark-active :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (true? (get-in (app-db) [:re-frame.query/queries qid :active?])))))

  (testing "mark-inactive sets active? to false"
    (process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (false? (get-in (app-db) [:re-frame.query/queries qid :active?]))))))

;; ---------------------------------------------------------------------------
;; Per-query GC timer tests
;; ---------------------------------------------------------------------------

(deftest mark-inactive-gc-timer-test
  (testing "mark-inactive schedules a GC timer when query has cache-time-ms"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 60000})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (contains? (gc/active-timers) qid))))

  (testing "mark-inactive schedules a GC timer using default cache time when query has no cache-time-ms"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (contains? (gc/active-timers) qid)))))

(deftest mark-active-cancels-gc-timer
  (testing "mark-active cancels any pending GC timer"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 60000})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Go inactive → timer starts
    (process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (contains? (gc/active-timers) qid))
      ;; Go active again → timer cancelled
      (process-event [:re-frame.query/mark-active :books/list {}])
      (is (not (contains? (gc/active-timers) qid))))))

(deftest remove-query-test
  (testing "remove-query removes an inactive query from cache"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Mark inactive, then remove
      (process-event [:re-frame.query/mark-inactive :books/list {}])
      (process-event [:re-frame.query/remove-query qid])
      (is (nil? (get-in (app-db) [:re-frame.query/queries qid])))))

  (testing "remove-query is a no-op if query became active again"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Mark active
      (process-event [:re-frame.query/mark-active :books/list {}])
      ;; Attempt removal — should be a no-op
      (process-event [:re-frame.query/remove-query qid])
      (is (some? (get-in (app-db) [:re-frame.query/queries qid]))))))

;; ---------------------------------------------------------------------------
;; Effect-fn (auto-injected callbacks) tests
;; ---------------------------------------------------------------------------

(deftest effect-fn-auto-injects-callbacks-test
  (testing "execute-query-effect uses effect-fn to inject success/failure callbacks"
    (let [captured (atom nil)]
      ;; Register a test effect that captures its arguments
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      ;; Set global effect-fn
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      ;; Register query — query-fn returns only the request description
      (rfq/reg-query :books/list
                     {:query-fn (fn [{:keys [page]}]
                                  {:method :get
                                   :url    (str "/api/books?page=" page)})})
      (process-event [:re-frame.query/refetch-query :books/list {:page 1}])
      (is (= {:method     :get
              :url        "/api/books?page=1"
              :on-success [:re-frame.query/query-success :books/list {:page 1}]
              :on-failure [:re-frame.query/query-failure :books/list {:page 1}]}
             @captured))))

  (testing "execute-mutation uses effect-fn to inject success/failure callbacks"
    (let [captured (atom nil)]
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-mutation :books/create
                        {:mutation-fn (fn [{:keys [title]}]
                                        {:method :post
                                         :url    "/api/books"
                                         :body   {:title title}})})
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= {:method     :post
              :url        "/api/books"
              :body       {:title "Dune"}
              :on-success [:re-frame.query/mutation-success :books/create {:title "Dune"}]
              :on-failure [:re-frame.query/mutation-failure :books/create {:title "Dune"}]}
             @captured)))))

(deftest per-query-effect-fn-overrides-global
  (testing "per-query :effect-fn takes precedence over the global one"
    (let [captured (atom nil)]
      (rf/reg-fx :custom-http (fn [v] (reset! captured v)))
      ;; Global effect-fn (should NOT be used)
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:global-http (assoc request
                               :on-success on-success
                               :on-failure on-failure)}))
      ;; Per-query effect-fn override
      (rfq/reg-query :books/special
                     {:query-fn  (fn [_] {:method :get :url "/api/special"})
                      :effect-fn (fn [request on-success on-failure]
                                   {:custom-http (assoc request
                                                        :on-success on-success
                                                        :on-failure on-failure)})})
      (process-event [:re-frame.query/refetch-query :books/special {}])
      (is (= {:method     :get
              :url        "/api/special"
              :on-success [:re-frame.query/query-success :books/special {}]
              :on-failure [:re-frame.query/query-failure :books/special {}]}
             @captured)))))

(deftest effect-fn-fallback-test
  (testing "without effect-fn, query-fn returning a full effects map still works"
    (let [captured (atom nil)]
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      ;; No set-default-effect-fn! call — legacy mode
      (rfq/reg-query :books/legacy
                     {:query-fn (fn [{:keys [page]}]
                                  {:test-http {:method     :get
                                               :url        "/api/books"
                                               :on-success [:re-frame.query/query-success :books/legacy {:page page}]
                                               :on-failure [:re-frame.query/query-failure :books/legacy {:page page}]}})})
      (process-event [:re-frame.query/refetch-query :books/legacy {:page 1}])
      (is (= {:method     :get
              :url        "/api/books"
              :on-success [:re-frame.query/query-success :books/legacy {:page 1}]
              :on-failure [:re-frame.query/query-failure :books/legacy {:page 1}]}
             @captured))))

  (testing "queries without custom effect-fn work normally"
    (rfq/reg-query :books/plain {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/plain {} [{:id 1}]])
    (let [qid (util/query-id :books/plain {})]
      (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status])))
      (is (= [{:id 1}] (get-in (app-db) [:re-frame.query/queries qid :data]))))))

;; ---------------------------------------------------------------------------
;; init! (declarative registry) tests
;; ---------------------------------------------------------------------------

(deftest init-registers-queries-and-mutations
  (testing "init! sets up queries, mutations, and default-effect-fn in one shot"
    (let [captured (atom nil)]
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      (rfq/init!
        {:default-effect-fn (fn [request on-success on-failure]
                              {:test-http (assoc request
                                                 :on-success on-success
                                                 :on-failure on-failure)})
         :queries
         {:books/list {:query-fn (fn [{:keys [page]}]
                                   {:method :get
                                    :url    (str "/api/books?page=" page)})
                       :stale-time-ms 30000
                       :tags (fn [_] [[:books]])}}
         :mutations
         {:books/create {:mutation-fn (fn [{:keys [title]}]
                                        {:method :post
                                         :url    "/api/books"
                                         :body   {:title title}})
                         :invalidates (fn [_] [[:books]])}}})
      ;; Query works
      (process-event [:re-frame.query/refetch-query :books/list {:page 1}])
      (is (= {:method     :get
              :url        "/api/books?page=1"
              :on-success [:re-frame.query/query-success :books/list {:page 1}]
              :on-failure [:re-frame.query/query-failure :books/list {:page 1}]}
             @captured))
      ;; Mutation works
      (reset! captured nil)
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= {:method     :post
              :url        "/api/books"
              :body       {:title "Dune"}
              :on-success [:re-frame.query/mutation-success :books/create {:title "Dune"}]
              :on-failure [:re-frame.query/mutation-failure :books/create {:title "Dune"}]}
             @captured)))))

(deftest init-edge-cases-test
  (testing "init! replaces any previously registered queries/mutations"
    ;; Register something first via the incremental API
    (rfq/reg-query :old/query {:query-fn (fn [_] {})})
    (rfq/reg-mutation :old/mutation {:mutation-fn (fn [_] {})})
    ;; Now init! with a fresh config
    (rfq/init!
      {:queries {:new/query {:query-fn (fn [_] {:url "/new"})}}})
    ;; Old registrations are gone
    (is (nil? (registry/get-query :old/query)))
    (is (nil? (registry/get-mutation :old/mutation)))
    ;; New registration exists
    (is (some? (registry/get-query :new/query))))

  (testing "init! with empty map clears everything"
    (rfq/reg-query :some/query {:query-fn (fn [_] {})})
    (rfq/init! {})
    (is (nil? (registry/get-query :some/query)))
    (is (nil? (registry/get-default-effect-fn))))

  (testing "reg-query and reg-mutation still work after init!"
    (rfq/init!
      {:queries {:books/list {:query-fn (fn [_] {:url "/books"})}}})
    ;; Add another query incrementally
    (rfq/reg-query :books/detail {:query-fn (fn [{:keys [id]}] {:url (str "/books/" id)})})
    ;; Both exist
    (is (some? (registry/get-query :books/list)))
    (is (some? (registry/get-query :books/detail)))))

;; ---------------------------------------------------------------------------
;; Query deduplication tests
;; ---------------------------------------------------------------------------

(deftest query-deduplication-test
  (testing "ensure-query is a no-op when the query is already fetching"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn (fn [_] {:method :get :url "/api/books"})})
      ;; First dispatch — should fire the effect
      (process-event [:re-frame.query/ensure-query :books/list {}])
      (is (= 1 @call-count) "first ensure-query fires the effect")
      ;; Verify fetching? is now true
      (let [qid (util/query-id :books/list {})]
        (is (true? (get-in (app-db) [:re-frame.query/queries qid :fetching?]))))
      ;; Second dispatch — should be a no-op because fetching? is true
      (process-event [:re-frame.query/ensure-query :books/list {}])
      (is (= 1 @call-count)
          "second ensure-query is a no-op while already fetching")))

  (testing "ensure-query with different params produces separate effects"
    (let [calls (atom [])]
      (rf/reg-fx :test-http (fn [v] (swap! calls conj v)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-query :books/detail
                     {:query-fn (fn [{:keys [id]}]
                                  {:method :get :url (str "/api/books/" id)})})
      ;; Two dispatches with different params
      (process-event [:re-frame.query/ensure-query :books/detail {:id 1}])
      (process-event [:re-frame.query/ensure-query :books/detail {:id 2}])
      (is (= 2 (count @calls))
          "different params produce separate effects")
      (is (= "/api/books/1" (:url (first @calls))))
      (is (= "/api/books/2" (:url (second @calls))))))

  (testing "ensure-query does nothing when cached data is within stale-time"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {:method :get :url "/api/books"})
                      :stale-time-ms 60000})
      ;; Populate cache with fresh data
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      ;; Reset counter after the setup
      (reset! call-count 0)
      ;; ensure-query on fresh data — should produce no effect
      (process-event [:re-frame.query/ensure-query :books/list {}])
      (is (zero? @call-count)
          "no effect produced when data is fresh"))))

;; ---------------------------------------------------------------------------
;; Invalidation refetch behavior tests
;; ---------------------------------------------------------------------------

(deftest invalidation-refetch-behavior-test
  (testing "Active queries matching invalidated tags are refetched;
            inactive queries are only marked stale."
    (rf-test/run-test-sync
      (let [calls (atom [])]
        (rf/reg-fx :test-http (fn [v] (swap! calls conj v)))
        (rfq/set-default-effect-fn!
          (fn [request on-success on-failure]
            {:test-http (assoc request
                               :on-success on-success
                               :on-failure on-failure)}))
        ;; Register two queries sharing the same tag
        (rfq/reg-query :books/list
                       {:query-fn (fn [_] {:method :get :url "/api/books"})
                        :tags     (fn [_] [[:books :all]])})
        (rfq/reg-query :books/detail
                       {:query-fn (fn [{:keys [id]}] {:method :get :url (str "/api/books/" id)})
                        :tags     (fn [{:keys [id]}] [[:books :all] [:books :id id]])})
        ;; Populate both with data
        (rf/dispatch [:re-frame.query/query-success :books/list {} [{:id 1}]])
        (rf/dispatch [:re-frame.query/query-success :books/detail {:id 1} {:id 1 :title "Dune"}])
        ;; Mark only list as active, detail stays inactive
        (rf/dispatch [:re-frame.query/mark-active :books/list {}])
        ;; Reset call log after setup
        (reset! calls [])
        ;; Invalidate — run-test-sync resolves the full chain:
        ;; invalidate-tags → (marks stale) → refetch-query (for active only)
        (rf/dispatch [:re-frame.query/invalidate-tags [[:books :all]]])
        (let [list-qid   (util/query-id :books/list {})
              detail-qid (util/query-id :books/detail {:id 1})]
          ;; The active query was refetched automatically (stale? reset to false)
          (is (true? (get-in (app-db) [:re-frame.query/queries list-qid :fetching?]))
              "active query is now refetching")
          (is (false? (get-in (app-db) [:re-frame.query/queries list-qid :stale?]))
              "stale? reset to false by the refetch")
          ;; The inactive query is just marked stale — no refetch
          (is (true? (get-in (app-db) [:re-frame.query/queries detail-qid :stale?]))
              "inactive query is marked stale")
          (is (false? (get-in (app-db) [:re-frame.query/queries detail-qid :fetching?]))
              "inactive query is NOT refetching")
          ;; Only the active query triggered a refetch effect
          (is (= 1 (count @calls))
              "only one refetch effect fired (for the active query)")
          (is (= "/api/books" (:url (first @calls)))
              "the refetch effect is for the active query")))))

  (testing "Inactive queries get marked stale but no refetch dispatch happens"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn (fn [_] {:method :get :url "/api/books"})
                      :tags     (fn [_] [[:books :all]])})
      ;; Populate with data but do NOT mark active
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (reset! call-count 0)
      ;; Invalidate
      (process-event [:re-frame.query/invalidate-tags [[:books :all]]])
      (let [qid (util/query-id :books/list {})]
        (is (true? (get-in (app-db) [:re-frame.query/queries qid :stale?]))
            "query is marked stale")
        (is (zero? @call-count)
            "no refetch effect for inactive query")))))

(deftest invalidation-tag-matching-test
  (testing "Exact tag match, partial tag match, and non-matching tags"
    (rfq/reg-query :books/list
                   {:query-fn (fn [_] {})
                    :tags     (fn [_] [[:books :all]])})
    (rfq/reg-query :books/detail
                   {:query-fn (fn [_] {})
                    :tags     (fn [{:keys [id]}] [[:books :id id]])})
    (rfq/reg-query :authors/list
                   {:query-fn (fn [_] {})
                    :tags     (fn [_] [[:authors :all]])})
    ;; Populate all three
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (process-event [:re-frame.query/query-success :authors/list {} [{:name "Herbert"}]])
    (let [books-qid   (util/query-id :books/list {})
          detail-qid  (util/query-id :books/detail {:id 1})
          authors-qid (util/query-id :authors/list {})]
      ;; Invalidate only [:books :all]
      (process-event [:re-frame.query/invalidate-tags [[:books :all]]])
      (is (true? (get-in (app-db) [:re-frame.query/queries books-qid :stale?]))
          "books/list matches [:books :all]")
      (is (false? (get-in (app-db) [:re-frame.query/queries detail-qid :stale?]))
          "books/detail does NOT match [:books :all] — its tag is [:books :id 1]")
      (is (false? (get-in (app-db) [:re-frame.query/queries authors-qid :stale?]))
          "authors/list does NOT match [:books :all]")))

  (testing "Invalidating a specific id tag only affects the matching detail query"
    (rfq/reg-query :books/detail
                   {:query-fn (fn [_] {})
                    :tags     (fn [{:keys [id]}] [[:books :id id]])})
    ;; Populate two detail queries
    (process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (process-event [:re-frame.query/query-success :books/detail {:id 2} {:title "Foundation"}])
    (let [qid-1 (util/query-id :books/detail {:id 1})
          qid-2 (util/query-id :books/detail {:id 2})]
      ;; Invalidate only book id 1
      (process-event [:re-frame.query/invalidate-tags [[:books :id 1]]])
      (is (true? (get-in (app-db) [:re-frame.query/queries qid-1 :stale?]))
          "book 1 is invalidated")
      (is (false? (get-in (app-db) [:re-frame.query/queries qid-2 :stale?]))
          "book 2 is not affected"))))

(deftest full-mutation-invalidation-cycle
  (testing "mutation-success → invalidate-tags → refetch active query → new data"
    (rf-test/run-test-sync
      (let [calls (atom [])]
        (rf/reg-fx :test-http (fn [v] (swap! calls conj v)))
        (rfq/set-default-effect-fn!
          (fn [request on-success on-failure]
            {:test-http (assoc request
                               :on-success on-success
                               :on-failure on-failure)}))
        (rfq/reg-query :books/list
                       {:query-fn (fn [_] {:method :get :url "/api/books"})
                        :tags     (fn [_] [[:books :all]])})
        (rfq/reg-mutation :books/create
                          {:mutation-fn (fn [{:keys [title]}]
                                          {:method :post :url "/api/books" :body {:title title}})
                           :invalidates (fn [_] [[:books :all]])})
        ;; Populate list and mark active
        (rf/dispatch [:re-frame.query/query-success :books/list {} [{:id 1}]])
        (rf/dispatch [:re-frame.query/mark-active :books/list {}])
        (reset! calls [])
        ;; Mutation succeeds — run-test-sync resolves the full chain:
        ;; mutation-success → invalidate-tags → refetch-query
        (rf/dispatch [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 2}])
        ;; Verify mutation state
        (let [mid (util/query-id :books/create {:title "Dune"})]
          (is (= :success (get-in (app-db) [:re-frame.query/mutations mid :status]))
              "mutation is :success"))
        ;; The full chain resolved: query should be refetching
        (let [qid (util/query-id :books/list {})]
          (is (true? (get-in (app-db) [:re-frame.query/queries qid :fetching?]))
              "active query is refetching after mutation-triggered invalidation")
          (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status]))
              "status stays :success during background refetch (stale data visible)")
          (is (= 1 (count @calls))
              "exactly one refetch effect fired")
          (is (= "/api/books" (:url (first @calls))))
          ;; Simulate the refetch completing with updated data
          (rf/dispatch [:re-frame.query/query-success :books/list {} [{:id 1} {:id 2}]])
          (is (= [{:id 1} {:id 2}]
                 (get-in (app-db) [:re-frame.query/queries qid :data]))
              "cache now has the updated data"))))))

;; ---------------------------------------------------------------------------
;; Full mutation lifecycle tests
;; ---------------------------------------------------------------------------

(deftest mutation-lifecycle-test
  (testing "execute-mutation immediately sets :loading before the effect fires"
    (let [captured-status (atom nil)]
      (rf/reg-fx :test-http
                 (fn [_]
                   ;; Capture the mutation status at the moment the effect fires
                   (let [mid (util/query-id :books/create {:title "Dune"})]
                     (reset! captured-status
                             (get-in (app-db) [:re-frame.query/mutations mid :status])))))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-mutation :books/create
                        {:mutation-fn (fn [{:keys [title]}]
                                        {:method :post :url "/api/books" :body {:title title}})})
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status]))
            "status is :loading after execute-mutation")
        (is (nil? (get-in (app-db) [:re-frame.query/mutations mid :error]))
            "error is nil during :loading")
        (is (= :loading @captured-status)
            "status was :loading when the effect fired"))))

  (testing "mutation-success stores the response data in the mutation state"
    (rfq/reg-mutation :books/create
                      {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 2 :title "Dune"}])
    (let [mid      (util/query-id :books/create {:title "Dune"})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= :success (:status mutation)))
      (is (= {:id 2 :title "Dune"} (:data mutation))
          "response data is stored")
      (is (nil? (:error mutation))
          "error is cleared on success"))))

(deftest independent-mutations-have-separate-state
  (testing "Two mutations with different params track independent status"
    (rfq/set-default-effect-fn! noop-effect-fn)
    (rfq/reg-mutation :books/update
                      {:mutation-fn (fn [{:keys [id title]}]
                                      {:method :put :url (str "/api/books/" id) :body {:title title}})})
    ;; Start two mutations
    (process-event [:re-frame.query/execute-mutation :books/update {:id 1 :title "Dune Revised"}])
    (process-event [:re-frame.query/execute-mutation :books/update {:id 2 :title "Foundation Revised"}])
    (let [mid-1 (util/query-id :books/update {:id 1 :title "Dune Revised"})
          mid-2 (util/query-id :books/update {:id 2 :title "Foundation Revised"})]
      ;; Both should be :loading independently
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid-1 :status])))
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid-2 :status])))
      ;; First one succeeds
      (process-event [:re-frame.query/mutation-success :books/update {:id 1 :title "Dune Revised"} {:id 1}])
      (is (= :success (get-in (app-db) [:re-frame.query/mutations mid-1 :status]))
          "mutation 1 is :success")
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid-2 :status]))
          "mutation 2 is still :loading")
      ;; Second one fails
      (process-event [:re-frame.query/mutation-failure :books/update {:id 2 :title "Foundation Revised"} {:status 500}])
      (is (= :success (get-in (app-db) [:re-frame.query/mutations mid-1 :status]))
          "mutation 1 still :success")
      (is (= :error (get-in (app-db) [:re-frame.query/mutations mid-2 :status]))
          "mutation 2 is :error"))))

(deftest mutation-full-lifecycle-test
  (testing "Full lifecycle: execute → :loading → success → :success with data"
    (let [captured (atom nil)]
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-mutation :books/create
                        {:mutation-fn (fn [{:keys [title]}]
                                        {:method :post
                                         :url "/api/books"
                                         :body {:title title}})})
      ;; Execute
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status])))
        ;; Verify the effect was captured with correct callbacks
        (is (= [:re-frame.query/mutation-success :books/create {:title "Dune"}]
               (:on-success @captured)))
        ;; Simulate success
        (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 1 :title "Dune"}])
        (let [mutation (get-in (app-db) [:re-frame.query/mutations mid])]
          (is (= :success (:status mutation)))
          (is (= {:id 1 :title "Dune"} (:data mutation)))
          (is (nil? (:error mutation)))))))

  (testing "Full lifecycle: execute → :error → re-execute → :loading → :success"
    (rfq/reg-mutation :books/create
                      {:mutation-fn (fn [{:keys [title]}]
                                      {:method :post :url "/api/books" :body {:title title}})})
    (let [mid (util/query-id :books/create {:title "Dune"})]
      ;; First attempt fails
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status])))
      (process-event [:re-frame.query/mutation-failure :books/create {:title "Dune"} {:status 500}])
      (is (= :error (get-in (app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:status 500} (get-in (app-db) [:re-frame.query/mutations mid :error])))
      ;; Retry — should go back to :loading
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status]))
          "status resets to :loading on retry")
      (is (nil? (get-in (app-db) [:re-frame.query/mutations mid :error]))
          "error is cleared on retry")
      ;; This time it succeeds
      (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 1 :title "Dune"}])
      (is (= :success (get-in (app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:id 1 :title "Dune"} (get-in (app-db) [:re-frame.query/mutations mid :data]))))))

(deftest mutations-do-not-deduplicate
  (testing "Unlike queries, mutations always fire — even with the same params after success"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-mutation :books/create
                        {:mutation-fn (fn [{:keys [title]}]
                                        {:method :post :url "/api/books" :body {:title title}})})
      ;; First execution
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= 1 @call-count) "first mutation fires the effect")
      ;; Complete it
      (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 1}])
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :success (get-in (app-db) [:re-frame.query/mutations mid :status]))))
      ;; Execute the exact same mutation again — should fire again, no dedup
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= 2 @call-count)
          "second mutation fires the effect again (no deduplication)")
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status]))
            "status resets to :loading for the new execution")))))

;; ---------------------------------------------------------------------------
;; Stale-time integration tests
;; ---------------------------------------------------------------------------

(deftest stale-time-behavior-test
  (testing "ensure-query produces no effect when data is within stale-time"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {:method :get :url "/api/books"})
                      :stale-time-ms 60000})
      ;; Populate with fresh data
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (reset! call-count 0)
      ;; ensure-query — data is fresh, should be a no-op
      (process-event [:re-frame.query/ensure-query :books/list {}])
      (let [qid (util/query-id :books/list {})]
        (is (zero? @call-count)
            "no effect produced for fresh data")
        (is (false? (get-in (app-db) [:re-frame.query/queries qid :fetching?]))
            "fetching? remains false")
        (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status]))
            "status remains :success"))))

  (testing "refetch-query always fires, even when data is within stale-time"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {:method :get :url "/api/books"})
                      :stale-time-ms 60000})
      ;; Populate with fresh data
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (reset! call-count 0)
      ;; refetch-query — should fire regardless of freshness
      (process-event [:re-frame.query/refetch-query :books/list {}])
      (let [qid (util/query-id :books/list {})]
        (is (= 1 @call-count)
            "effect fires even though data is fresh")
        (is (true? (get-in (app-db) [:re-frame.query/queries qid :fetching?]))
            "fetching? is true")
        (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status]))
            "status stays :success during background refetch"))))

  (testing "ensure-query refetches when stale-time has elapsed since last fetch"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn (fn [_] {:method :get
                                         :url "/api/books"})
                      :stale-time-ms 1000})
      ;; Populate and push fetched-at far into the past
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (let [qid (util/query-id :books/list {})]
        (swap! rf-db/app-db assoc-in
               [:re-frame.query/queries qid :fetched-at] 1000)
        (reset! call-count 0)
        ;; ensure-query at time 2001: (2001 - 1000) = 1001 > stale-time-ms 1000
        (with-redefs [util/now-ms (constantly 2001)]
          (process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (= 1 @call-count)
            "effect fires because stale-time has elapsed")
        (is (true? (get-in (app-db) [:re-frame.query/queries qid :fetching?]))))))

  (testing "Without stale-time-ms, a successful query is never considered stale by time"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn (fn [_] {:method :get
                                         :url "/api/books"})})
      ;; Populate and push fetched-at far into the past
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (let [qid (util/query-id :books/list {})]
        (swap! rf-db/app-db assoc-in
               [:re-frame.query/queries qid :fetched-at] 0)
        (reset! call-count 0)
        ;; ensure-query at time 999999 — no stale-time-ms, so NOT stale by time
        (with-redefs [util/now-ms (constantly 999999)]
          (process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (zero? @call-count)
            "no effect — query without stale-time-ms never auto-stales")))))

(deftest stale-time-resets-on-success
  (testing "After a successful fetch, the stale-time window restarts from the new fetched-at"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {:method :get :url "/api/books"})
                      :stale-time-ms 30000})
      ;; First fetch succeeds at time 1000
      (with-redefs [util/now-ms (constantly 1000)]
        (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]]))
      (let [qid        (util/query-id :books/list {})
            fetched-at (get-in (app-db) [:re-frame.query/queries qid :fetched-at])]
        (is (= 1000 fetched-at) "fetched-at is recorded at controlled time")
        (reset! call-count 0)
        ;; ensure-query at time 31001 (31001 - 1000 = 30001 > stale-time 30000)
        (with-redefs [util/now-ms (constantly 31001)]
          (process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (= 1 @call-count) "stale data triggers a refetch")
        ;; Simulate success at time 31001 — fetched-at should update
        (with-redefs [util/now-ms (constantly 31001)]
          (process-event [:re-frame.query/query-success :books/list {} [{:id 1} {:id 2}]]))
        (let [new-fetched-at (get-in (app-db) [:re-frame.query/queries qid :fetched-at])]
          (is (= 31001 new-fetched-at) "fetched-at is set to the controlled timestamp")
          (is (> new-fetched-at fetched-at) "new fetched-at is more recent than the original")
          ;; Now the data is fresh — ensure-query at 31002 should be a no-op
          ;; (31002 - 31001 = 1ms, well within stale-time of 30000ms)
          (reset! call-count 0)
          (with-redefs [util/now-ms (constantly 31002)]
            (process-event [:re-frame.query/ensure-query :books/list {}]))
          (is (zero? @call-count)
              "data is fresh after refetch — no effect produced"))))))

;; ---------------------------------------------------------------------------
;; Error recovery cycle tests
;; ---------------------------------------------------------------------------

(deftest query-error-recovery-test
  (testing "After a failed query, a successful retry clears the error and restores :success"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      ;; Initial failure
      (process-event [:re-frame.query/query-failure :books/list {} {:status 500 :body "Internal Server Error"}])
      (is (= :error (get-in (app-db) [:re-frame.query/queries qid :status])))
      (is (= {:status 500 :body "Internal Server Error"}
             (get-in (app-db) [:re-frame.query/queries qid :error])))
      ;; Retry succeeds
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (let [query (get-in (app-db) [:re-frame.query/queries qid])]
        (is (= :success (:status query))
            "status transitions to :success")
        (is (= [{:id 1}] (:data query))
            "data is stored")
        (is (nil? (:error query))
            "error is cleared on success")
        (is (false? (:fetching? query))))))

  (testing "Each failure updates the error value, not accumulates"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      ;; First failure
      (process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
      (is (= {:status 500} (get-in (app-db) [:re-frame.query/queries qid :error])))
      ;; Second failure with different error
      (process-event [:re-frame.query/query-failure :books/list {} {:status 503}])
      (is (= {:status 503} (get-in (app-db) [:re-frame.query/queries qid :error]))
          "error is replaced, not accumulated")
      (is (= :error (get-in (app-db) [:re-frame.query/queries qid :status])))))

  (testing "After success then failure, data persists but status is :error"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      ;; Success first
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status])))
      ;; Then a refetch fails
      (process-event [:re-frame.query/query-failure :books/list {} {:status 502}])
      (let [query (get-in (app-db) [:re-frame.query/queries qid])]
        (is (= :error (:status query))
            "status is :error")
        (is (= {:status 502} (:error query))
            "error is stored")
        (is (= [{:id 1}] (:data query))
            "previous data is preserved across failure")))))

(deftest query-full-error-recovery-cycle
  (testing "Full cycle: success → stale → refetch fails → retry → success with new data"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {:method :get :url "/api/books"})
                      :stale-time-ms 1000})
      (let [qid (util/query-id :books/list {})]
        ;; 1. Initial success
        (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
        (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status])))
        ;; 2. Make stale: fetched-at=0, now-ms=1001 (past stale-time of 1000)
        (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
        (reset! call-count 0)
        ;; 3. ensure-query detects staleness → refetch
        (with-redefs [util/now-ms (constantly 1001)]
          (process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (= 1 @call-count))
        ;; 4. Refetch fails
        (process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
        (is (= :error (get-in (app-db) [:re-frame.query/queries qid :status])))
        (is (= [{:id 1}] (get-in (app-db) [:re-frame.query/queries qid :data]))
            "stale data preserved after failure")
        ;; 5. Retry — error status is stale, so ensure-query triggers
        (reset! call-count 0)
        (process-event [:re-frame.query/ensure-query :books/list {}])
        (is (= 1 @call-count) "retry fires because error status is stale")
        (is (= :loading (get-in (app-db) [:re-frame.query/queries qid :status]))
            "status resets to :loading on retry after error")
        ;; 6. Success with new data
        (process-event [:re-frame.query/query-success :books/list {} [{:id 1} {:id 2}]])
        (let [query (get-in (app-db) [:re-frame.query/queries qid])]
          (is (= :success (:status query)))
          (is (= [{:id 1} {:id 2}] (:data query)))
          (is (nil? (:error query))
              "error is cleared after successful recovery"))))))

(deftest mutation-error-then-retry-success
  (testing "A failed mutation followed by re-execution transitions to :success"
    (rfq/set-default-effect-fn! noop-effect-fn)
    (rfq/reg-mutation :books/create
                      {:mutation-fn (fn [{:keys [title]}]
                                      {:method :post :url "/api/books" :body {:title title}})})
    (let [mid (util/query-id :books/create {:title "Dune"})]
      ;; First attempt fails
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (process-event [:re-frame.query/mutation-failure :books/create {:title "Dune"} {:status 500}])
      (is (= :error (get-in (app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:status 500} (get-in (app-db) [:re-frame.query/mutations mid :error])))
      ;; Retry succeeds
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status]))
          "status resets to :loading")
      (is (nil? (get-in (app-db) [:re-frame.query/mutations mid :error]))
          "error cleared on retry")
      (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 1}])
      (is (= :success (get-in (app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:id 1} (get-in (app-db) [:re-frame.query/mutations mid :data])))
      (is (nil? (get-in (app-db) [:re-frame.query/mutations mid :error]))))))

;; ---------------------------------------------------------------------------
;; Registration error handling tests
;; ---------------------------------------------------------------------------

(deftest unregistered-key-throws-test
  (testing "Dispatching ensure-query for an unregistered key throws an error"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
          #"No query registered for key"
          (process-event [:re-frame.query/ensure-query :nonexistent/query {}]))))

  (testing "Dispatching refetch-query for an unregistered key throws an error"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
          #"No query registered for key"
          (process-event [:re-frame.query/refetch-query :nonexistent/query {}]))))

  (testing "Dispatching execute-mutation for an unregistered key throws an error"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
          #"No mutation registered for key"
          (process-event [:re-frame.query/execute-mutation :nonexistent/mutation {}]))))

  (testing "Subscribing to an unregistered query key throws because ensure-query is dispatched"
    (rf-test/run-test-sync
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
            #"No query registered for key"
            (rf/subscribe [:re-frame.query/query :nonexistent/query {}]))))))

;; ---------------------------------------------------------------------------
;; Query state shape completeness tests
;; ---------------------------------------------------------------------------

(deftest state-nil-before-operations-test
  (testing "Before any fetch, query state is nil in app-db"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      (is (nil? (get-in (app-db) [:re-frame.query/queries qid]))
          "query state does not exist before any event is dispatched")))

  (testing "Before any execution, mutation state is nil in app-db"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (let [mid (util/query-id :books/create {})]
      (is (nil? (get-in (app-db) [:re-frame.query/mutations mid]))
          "mutation state does not exist before any event is dispatched"))))

(deftest query-state-shape-test
  (testing "Full state shape after ensure-query (initial load, no prior data)"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= {:status    :loading
              :data      nil
              :error     nil
              :fetching? true
              :stale?    false
              :active?   false
              :tags      #{}}
             query))))

  (testing "Full state shape after query-success"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :stale-time-ms 30000
                    :cache-time-ms 300000
                    :tags          (fn [_] [[:books :all]])})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1 :title "Dune"}]])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= {:status        :success
              :data          [{:id 1 :title "Dune"}]
              :error         nil
              :fetching?     false
              :stale?        false
              :active?       false
              :tags          #{[:books :all]}
              :stale-time-ms 30000
              :cache-time-ms 300000}
             (dissoc query :fetched-at)))
      (is (number? (:fetched-at query))
          "fetched-at is a numeric timestamp")))

  (testing "Full state shape after query-failure"
    (rfq/reg-query :books/error-shape {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-failure :books/error-shape {} {:status 500 :body "error"}])
    (let [qid   (util/query-id :books/error-shape {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= {:status    :error
              :data      nil
              :error     {:status 500 :body "error"}
              :fetching? false
              :stale?    true
              :active?   false
              :tags      #{}}
             query))))

  (testing "Full state shape during a background refetch (stale data visible)"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :stale-time-ms 1000
                    :cache-time-ms 300000
                    :tags          (fn [_] [[:books :all]])})
    ;; Initial success
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Make stale: fetched-at=0, now-ms=1001 (past stale-time of 1000)
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
      (with-redefs [util/now-ms (constantly 1001)]
        (process-event [:re-frame.query/ensure-query :books/list {}]))
      (let [query (get-in (app-db) [:re-frame.query/queries qid])]
        (is (= {:status        :success
                :data          [{:id 1}]
                :error         nil
                :fetching?     true
                :stale?        false
                :active?       false
                :tags          #{[:books :all]}
                :stale-time-ms 1000
                :cache-time-ms 300000}
               (dissoc query :fetched-at))
            "status stays :success, fetching? true, stale data preserved")))))

(deftest mutation-state-shape-test
  (testing "Full state shape after execute-mutation"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
    (let [mid      (util/query-id :books/create {:title "Dune"})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= {:status :loading
              :error  nil}
             mutation))))

  (testing "Full state shape after mutation-success"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 1 :title "Dune"}])
    (let [mid      (util/query-id :books/create {:title "Dune"})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= {:status :success
              :data   {:id 1 :title "Dune"}
              :error  nil}
             mutation))))

  (testing "Full state shape after mutation-failure"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/mutation-failure :books/create {:title "Dune"} {:status 422 :body "Unprocessable"}])
    (let [mid      (util/query-id :books/create {:title "Dune"})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= {:status :error
              :error  {:status 422 :body "Unprocessable"}}
             mutation)))))

;; ---------------------------------------------------------------------------
;; Cache lifetime edge-case tests
;; ---------------------------------------------------------------------------

(deftest zero-cache-time-does-not-schedule-gc-timer
  (testing "With cache-time-ms 0, no GC timer is scheduled (pos? guard in schedule-gc!)"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 0})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (not (contains? (gc/active-timers) qid))
          "no GC timer is scheduled for cache-time-ms 0")
      (is (some? (get-in (app-db) [:re-frame.query/queries qid]))
          "query is still in cache (no timer means no auto-cleanup)"))))

(deftest gc-removes-query-only-when-expired-and-inactive
  (testing "GC skips queries that are expired but active, or inactive but not expired"
    (rfq/reg-query :books/active
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 1000})
    (rfq/reg-query :books/fresh
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 60000})
    (rfq/reg-query :books/expired
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 1000})
    ;; All fetched at time 1000
    (with-redefs [util/now-ms (constantly 1000)]
      (process-event [:re-frame.query/query-success :books/active {} [{:id 1}]])
      (process-event [:re-frame.query/query-success :books/fresh {} [{:id 2}]])
      (process-event [:re-frame.query/query-success :books/expired {} [{:id 3}]]))
    ;; active query is marked active
    (process-event [:re-frame.query/mark-active :books/active {}])
    (let [active-qid  (util/query-id :books/active {})
          fresh-qid   (util/query-id :books/fresh {})
          expired-qid (util/query-id :books/expired {})]
      ;; GC at time 3000: (3000 - 1000 = 2000 > cache-time 1000 for active & expired)
      ;; but (3000 - 1000 = 2000 < cache-time 60000 for fresh)
      (process-event [:re-frame.query/garbage-collect 3000])
      (is (some? (get-in (app-db) [:re-frame.query/queries active-qid]))
          "active query kept even though expired")
      (is (some? (get-in (app-db) [:re-frame.query/queries fresh-qid]))
          "inactive but fresh query kept")
      (is (nil? (get-in (app-db) [:re-frame.query/queries expired-qid]))
          "inactive and expired query removed"))))

(deftest mark-active-after-inactive-preserves-query
  (testing "Re-subscribing before GC fires keeps the query alive"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 60000})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Subscribe → unsubscribe (timer starts)
      (process-event [:re-frame.query/mark-active :books/list {}])
      (process-event [:re-frame.query/mark-inactive :books/list {}])
      (is (contains? (gc/active-timers) qid)
          "GC timer is ticking")
      ;; Re-subscribe before timer fires
      (process-event [:re-frame.query/mark-active :books/list {}])
      (is (not (contains? (gc/active-timers) qid))
          "GC timer cancelled on re-subscribe")
      (is (some? (get-in (app-db) [:re-frame.query/queries qid]))
          "query is still in cache")
      (is (true? (get-in (app-db) [:re-frame.query/queries qid :active?]))
          "query is active again"))))

(deftest per-query-cleanup-test
  (testing "Full per-query cleanup: mark-inactive → timer → remove-query → evicted"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 60000})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/mark-active :books/list {}])
    (let [qid (util/query-id :books/list {})]
      ;; Step 1: Query is active, no timer
      (is (not (contains? (gc/active-timers) qid))
          "no timer while active")
      ;; Step 2: All subscribers gone → mark-inactive → timer starts
      (process-event [:re-frame.query/mark-inactive :books/list {}])
      (is (false? (get-in (app-db) [:re-frame.query/queries qid :active?]))
          "query is now inactive")
      (is (contains? (gc/active-timers) qid)
          "GC timer is scheduled after mark-inactive")
      ;; Step 3: Timer fires → dispatches remove-query → query evicted
      (process-event [:re-frame.query/remove-query qid])
      (is (nil? (get-in (app-db) [:re-frame.query/queries qid]))
          "query evicted from cache after remove-query")))

  (testing "remove-query is a no-op if the query became active again before timer fires"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 60000})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Unsubscribe → timer starts
      (process-event [:re-frame.query/mark-inactive :books/list {}])
      (is (contains? (gc/active-timers) qid))
      ;; Re-subscribe → timer cancelled
      (process-event [:re-frame.query/mark-active :books/list {}])
      (is (not (contains? (gc/active-timers) qid)))
      ;; Stale timer fires anyway (edge case) → remove-query should be no-op
      (process-event [:re-frame.query/remove-query qid])
      (is (some? (get-in (app-db) [:re-frame.query/queries qid]))
          "query is NOT removed because it's active again")
      (is (= [{:id 1}] (get-in (app-db) [:re-frame.query/queries qid :data]))
          "data is preserved"))))

(deftest gc-does-not-remove-queries-without-cache-time
  (testing "Queries without cache-time-ms are never expired by bulk GC"
    (rfq/reg-query :books/permanent {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/permanent {} [{:id 1}]])
    (let [qid (util/query-id :books/permanent {})]
      ;; Bulk GC at a very large time
      (process-event [:re-frame.query/garbage-collect 999999999])
      (is (some? (get-in (app-db) [:re-frame.query/queries qid]))
          "query without cache-time-ms survives bulk GC"))))

(deftest per-query-timer-fires-and-evicts-async
  (testing "After cache-time-ms elapses, the real timer fires remove-query and evicts the query"
    (rf-test/run-test-async
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {})
                      :cache-time-ms 100})
      (rf/dispatch-sync [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (let [qid (util/query-id :books/list {})]
        (is (some? (get-in (app-db) [:re-frame.query/queries qid]))
            "query exists before mark-inactive")
        ;; Mark inactive — starts a real 100ms timer
        (rf/dispatch-sync [:re-frame.query/mark-inactive :books/list {}])
        (is (contains? (gc/active-timers) qid)
            "GC timer is scheduled")
        ;; Wait for the timer to fire remove-query
        (rf-test/wait-for [:re-frame.query/remove-query]
                          (is (nil? (get-in (app-db) [:re-frame.query/queries qid]))
                              "query evicted after timer fires"))))))

;; ---------------------------------------------------------------------------
;; Polling tests
;; ---------------------------------------------------------------------------

(deftest polling-subscriber-basics-test
  (testing "add-subscriber! with a positive interval starts polling"
    (let [qid (util/query-id :books/list {})]
      (polling/add-subscriber! qid :sub-1 :books/list {} 5000)
      (is (contains? (polling/active-polls) qid)
          "polling timer is active")
      (is (= 5000 (polling/current-interval qid))
          "effective interval matches the subscriber")
      (polling/remove-subscriber! qid :sub-1)
      (is (not (contains? (polling/active-polls) qid))
          "polling stopped after last subscriber removed")))

  (testing "add-subscriber! is a no-op for zero, nil, or negative intervals"
    (let [qid (util/query-id :books/list {})]
      (polling/add-subscriber! qid :sub-1 :books/list {} 0)
      (is (empty? (polling/active-polls))
          "zero interval does not start polling")
      (polling/add-subscriber! qid :sub-2 :books/list {} nil)
      (is (empty? (polling/active-polls))
          "nil interval does not start polling")
      (polling/add-subscriber! qid :sub-3 :books/list {} -1000)
      (is (empty? (polling/active-polls))
          "negative interval does not start polling")))

  (testing "remove-subscriber! is a no-op for a subscriber that doesn't exist"
    (let [qid (util/query-id :books/list {})]
      (polling/remove-subscriber! qid :nonexistent)
      (is (empty? (polling/active-polls))
          "no error when removing non-existent subscriber"))))

(deftest polling-multiple-subscribers-test
  (testing "Effective interval is the minimum of all active positive subscriber intervals"
    (polling/cancel-all!)
    (let [qid (util/query-id :books/list {})]
      ;; First subscriber at 5000ms
      (polling/add-subscriber! qid :sub-1 :books/list {} 5000)
      (is (= 5000 (polling/current-interval qid)))
      ;; Second subscriber at 1000ms → effective becomes 1000
      (polling/add-subscriber! qid :sub-2 :books/list {} 1000)
      (is (= 1000 (polling/current-interval qid))
          "effective interval is the lowest non-zero")
      (is (= 2 (polling/subscriber-count qid)))
      ;; Third subscriber at 3000ms → still 1000
      (polling/add-subscriber! qid :sub-3 :books/list {} 3000)
      (is (= 1000 (polling/current-interval qid))
          "adding a slower subscriber doesn't change the effective interval")))

  (testing "Removing the fastest subscriber recalculates to the next lowest"
    (polling/cancel-all!)
    (let [qid (util/query-id :books/list {})]
      (polling/add-subscriber! qid :sub-1 :books/list {} 5000)
      (polling/add-subscriber! qid :sub-2 :books/list {} 1000)
      (is (= 1000 (polling/current-interval qid)))
      ;; Remove the faster subscriber
      (polling/remove-subscriber! qid :sub-2)
      (is (= 5000 (polling/current-interval qid))
          "effective interval recalculated to remaining subscriber")
      (is (contains? (polling/active-polls) qid)
          "polling still active with remaining subscriber")))

  (testing "Removing all subscribers stops polling entirely"
    (polling/cancel-all!)
    (let [qid (util/query-id :books/list {})]
      (polling/add-subscriber! qid :sub-1 :books/list {} 5000)
      (polling/add-subscriber! qid :sub-2 :books/list {} 1000)
      (is (contains? (polling/active-polls) qid))
      (polling/remove-subscriber! qid :sub-1)
      (polling/remove-subscriber! qid :sub-2)
      (is (not (contains? (polling/active-polls) qid))
          "polling stopped after all subscribers removed")
      (is (nil? (polling/current-interval qid))))))

(deftest polling-interval-precedence-test
  (testing "Query registered with :polling-interval-ms starts polling when subscribed"
    (let [qid (util/query-id :books/list {})]
      ;; Simulate what the subscription does: read query config, add subscriber
      (rfq/reg-query :books/list
                     {:query-fn              (fn [_] {})
                      :polling-interval-ms   5000})
      (let [config (registry/get-query :books/list)]
        (polling/add-subscriber! qid :sub-1 :books/list {}
                                 (:polling-interval-ms config)))
      (is (contains? (polling/active-polls) qid)
          "polling started from query-level config")
      (is (= 5000 (polling/current-interval qid)))))

  (testing "Per-subscription interval overrides query-level default"
    (let [qid (util/query-id :books/list {})]
      (rfq/reg-query :books/list
                     {:query-fn              (fn [_] {})
                      :polling-interval-ms   5000})
      ;; Subscriber 1 uses query-level default
      (let [config (registry/get-query :books/list)]
        (polling/add-subscriber! qid :sub-1 :books/list {}
                                 (:polling-interval-ms config)))
      (is (= 5000 (polling/current-interval qid)))
      ;; Subscriber 2 overrides with faster interval
      (polling/add-subscriber! qid :sub-2 :books/list {} 1000)
      (is (= 1000 (polling/current-interval qid))
          "subscription-level override wins (lowest interval)"))))

(deftest polling-dispatches-refetch-async
  (testing "Polling timer dispatches refetch-query on each tick"
    (rf-test/run-test-async
      (let [call-count (atom 0)]
        (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
        (rfq/set-default-effect-fn!
          (fn [request on-success on-failure]
            {:test-http (assoc request
                               :on-success on-success
                               :on-failure on-failure)}))
        (rfq/reg-query :books/list
                       {:query-fn (fn [_] {:method :get :url "/api/books"})})
        ;; Populate with data first so refetches produce effects
        (rf/dispatch-sync [:re-frame.query/query-success :books/list {} [{:id 1}]])
        ;; Start polling at 100ms via subscriber API
        (let [qid (util/query-id :books/list {})]
          (polling/add-subscriber! qid :sub-1 :books/list {} 100)
          ;; Wait for the first refetch-query to fire
          (rf-test/wait-for [:re-frame.query/refetch-query]
                            (is (>= @call-count 1)
                                "at least one refetch effect fired from polling")
                            ;; Clean up
                            (polling/remove-subscriber! qid :sub-1)))))))

;; ---------------------------------------------------------------------------
;; Polling e2e tests (through the subscription)
;; ---------------------------------------------------------------------------

(deftest subscription-polling-e2e-test
  (testing "Subscribing with :polling-interval-ms starts polling automatically"
    (rf-test/run-test-sync
      (rfq/reg-query :books/list {:query-fn (fn [_] {})})
      (let [qid (util/query-id :books/list {})
            sub (rf/subscribe [:re-frame.query/query :books/list {} {:polling-interval-ms 5000}])]
        ;; Deref to activate the subscription
        @sub
        (is (contains? (polling/active-polls) qid)
            "polling started via subscription")
        (is (= 5000 (polling/current-interval qid))
            "effective interval matches subscription option"))))

  (testing "Query registered with :polling-interval-ms starts polling on subscribe"
    (rf-test/run-test-sync
      (rfq/reg-query :books/list
                     {:query-fn            (fn [_] {})
                      :polling-interval-ms 3000})
      (let [qid (util/query-id :books/list {})
            sub (rf/subscribe [:re-frame.query/query :books/list {}])]
        @sub
        (is (contains? (polling/active-polls) qid)
            "polling started from query-level config")
        (is (= 3000 (polling/current-interval qid))))))

  (testing "Per-subscription interval overrides query-level default"
    (rf-test/run-test-sync
      (rfq/reg-query :books/list
                     {:query-fn            (fn [_] {})
                      :polling-interval-ms 5000})
      (let [qid (util/query-id :books/list {})
            sub (rf/subscribe [:re-frame.query/query :books/list {} {:polling-interval-ms 1000}])]
        @sub
        (is (= 1000 (polling/current-interval qid))
            "subscription-level override is used instead of query-level"))))

  (testing "Subscribing without :polling-interval-ms does not start polling"
    (rf-test/run-test-sync
      (rfq/reg-query :books/list {:query-fn (fn [_] {})})
      (let [qid (util/query-id :books/list {})
            sub (rf/subscribe [:re-frame.query/query :books/list {}])]
        @sub
        (is (not (contains? (polling/active-polls) qid))
            "no polling without interval")))))

#?(:cljs
   (deftest subscription-dispose-stops-polling
     (testing "Disposing the subscription stops polling"
       (rf-test/run-test-sync
         (rfq/reg-query :books/list {:query-fn (fn [_] {})})
         (let [qid (util/query-id :books/list {})
               sub (rf/subscribe [:re-frame.query/query :books/list {} {:polling-interval-ms 5000}])]
           @sub
           (is (contains? (polling/active-polls) qid)
               "polling is active before dispose")
           ;; Dispose the reaction — triggers on-dispose callback
           (ratom/dispose! sub)
           (is (not (contains? (polling/active-polls) qid))
               "polling stopped after subscription disposed"))))))

#?(:cljs
   (deftest subscription-dispose-with-query-level-polling-stops
     (testing "Disposing a subscription with query-level polling stops it"
       (rf-test/run-test-sync
         (rfq/reg-query :books/list
                        {:query-fn            (fn [_] {})
                         :polling-interval-ms 3000})
         (let [qid (util/query-id :books/list {})
               sub (rf/subscribe [:re-frame.query/query :books/list {}])]
           @sub
           (is (contains? (polling/active-polls) qid))
           (ratom/dispose! sub)
           (is (not (contains? (polling/active-polls) qid))
               "polling stopped after dispose"))))))
          ;; ---------------------------------------------------------------------------
          ;; Conditional fetching (skip) tests
          ;; ---------------------------------------------------------------------------
(deftest skip-query-behavior
  (rf-test/run-test-sync
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                        :on-success on-success
                        :on-failure on-failure)}))
      (rfq/reg-query :books/detail
        {:query-fn (fn [{:keys [id]}]
                     {:method :get :url (str "/api/books/" id)})})

      (testing "returns idle state and does not fetch"
        (let [sub (rf/subscribe [:re-frame.query/query :books/detail {:id 1} {:skip? true}])]
          (is (= {:status    :idle
                  :data      nil
                  :error     nil
                  :fetching? false
                  :stale?    true}
                 @sub))
          (is (zero? @call-count)
              "no effect fired when skipped")))

      (testing "does not mark the query active"
        (let [qid (util/query-id :books/detail {:id 1})]
          (is (nil? (get-in (app-db) [:re-frame.query/queries qid :active?])))))

      (testing "does not start polling even with :polling-interval-ms"
        (let [qid (util/query-id :books/detail {:id 1})
              sub (rf/subscribe [:re-frame.query/query :books/detail {:id 1}
                                 {:skip? true :polling-interval-ms 5000}])]
          @sub
          (is (not (contains? (polling/active-polls) qid)))))

      (testing ":skip? false fetches normally"
        (reset! call-count 0)
        (let [sub (rf/subscribe [:re-frame.query/query :books/detail {:id 1} {:skip? false}])]
          @sub
          (is (= 1 @call-count) "effect fires when :skip? is false")))

      (testing "toggling skip off triggers the fetch"
        (reset! call-count 0)
        ;; Subscribe with skip — no new fetch
        (let [sub (rf/subscribe [:re-frame.query/query :books/detail {:id 2} {:skip? true}])]
          @sub
          (is (zero? @call-count) "no fetch when skipped"))
        ;; Subscribe without skip — fetch fires
        (let [sub (rf/subscribe [:re-frame.query/query :books/detail {:id 2}])]
          @sub
          (is (= 1 @call-count) "fetch fires when skip is removed"))))))

(deftest dependent-query-pattern
  (testing "Query B skips until query A has data, then fetches with A's result"
    (rf-test/run-test-sync
      (let [calls (atom [])]
        (rf/reg-fx :test-http (fn [v] (swap! calls conj v)))
        (rfq/set-default-effect-fn!
          (fn [request on-success on-failure]
            {:test-http (assoc request
                          :on-success on-success
                          :on-failure on-failure)}))
        (rfq/reg-query :user/current
          {:query-fn (fn [_] {:method :get :url "/api/me"})})
        (rfq/reg-query :user/todos
          {:query-fn (fn [{:keys [user-id]}]
                       {:method :get :url (str "/api/users/" user-id "/todos")})})
        ;; Query A: fetch current user
        (let [user-sub (rf/subscribe [:re-frame.query/query :user/current {}])]
          @user-sub
          (is (= 1 (count @calls)) "user query fires"))
        ;; Query B: skip until we have user data
        (let [user-id  nil ;; no data yet
              todos-sub (rf/subscribe [:re-frame.query/query :user/todos {:user-id user-id}
                                       {:skip? (nil? user-id)}])]
          @todos-sub
          (is (= 1 (count @calls)) "todos query skipped — still only 1 call"))
        ;; Simulate user query success
        (rf/dispatch [:re-frame.query/query-success :user/current {} {:id 42}])
        ;; Now query B subscribes without skip
        (let [user-id  42
              todos-sub (rf/subscribe [:re-frame.query/query :user/todos {:user-id user-id}])]
          @todos-sub
          (is (= 2 (count @calls)) "todos query fires with user-id")
          (is (= "/api/users/42/todos" (:url (second @calls)))
              "todos query uses the correct user-id from query A"))))))

;; ---------------------------------------------------------------------------
;; Transform response tests
;; ---------------------------------------------------------------------------

(deftest transform-response-query-test
  (testing "transform-response unwraps nested data before caching"
    (rfq/reg-query :books/list
                   {:query-fn           (fn [_] {})
                    :transform-response (fn [response _params]
                                          (:data response))})
    (process-event [:re-frame.query/query-success
                    :books/list {} {:data [{:id 1} {:id 2}] :meta {:total 2}}])
    (let [qid (util/query-id :books/list {})]
      (is (= [{:id 1} {:id 2}]
             (get-in (app-db) [:re-frame.query/queries qid :data]))
          "only the :data key is stored, :meta is stripped")))

  (testing "transform-response receives params for context-dependent transforms"
    (rfq/reg-query :book/detail
                   {:query-fn           (fn [_] {})
                    :transform-response (fn [response params]
                                          (assoc response :requested-id (:id params)))})
    (process-event [:re-frame.query/query-success
                    :book/detail {:id 42} {:title "Dune"}])
    (let [qid (util/query-id :book/detail {:id 42})]
      (is (= {:title "Dune" :requested-id 42}
             (get-in (app-db) [:re-frame.query/queries qid :data]))
          "params are passed to transform-response")))

  (testing "transform-response can normalize into a lookup map"
    (rfq/reg-query :books/list
                   {:query-fn           (fn [_] {})
                    :transform-response (fn [items _params]
                                          (into {} (map (juxt :id identity)) items))})
    (process-event [:re-frame.query/query-success
                    :books/list {} [{:id 1 :title "Dune"} {:id 2 :title "Foundation"}]])
    (let [qid (util/query-id :books/list {})]
      (is (= {1 {:id 1 :title "Dune"}
              2 {:id 2 :title "Foundation"}}
             (get-in (app-db) [:re-frame.query/queries qid :data])))))

  (testing "without transform-response, data passes through unchanged"
    (rfq/reg-query :books/plain
                   {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success
                    :books/plain {} {:raw "data" :nested {:deep true}}])
    (let [qid (util/query-id :books/plain {})]
      (is (= {:raw "data" :nested {:deep true}}
             (get-in (app-db) [:re-frame.query/queries qid :data]))
          "data stored as-is when no transform-response"))))

(deftest transform-error-query-test
  (testing "transform-error normalizes error before storing"
    (rfq/reg-query :books/list
                   {:query-fn        (fn [_] {})
                    :transform-error (fn [error _params]
                                       {:message (or (:body error) "Unknown error")
                                        :code    (:status error)})})
    (process-event [:re-frame.query/query-failure
                    :books/list {} {:status 500 :body "Internal Server Error"}])
    (let [qid (util/query-id :books/list {})]
      (is (= {:message "Internal Server Error" :code 500}
             (get-in (app-db) [:re-frame.query/queries qid :error])))))

  (testing "transform-error receives params"
    (rfq/reg-query :book/detail
                   {:query-fn        (fn [_] {})
                    :transform-error (fn [error params]
                                       {:message (str "Failed to load book " (:id params))
                                        :original error})})
    (process-event [:re-frame.query/query-failure
                    :book/detail {:id 42} {:status 404}])
    (let [qid (util/query-id :book/detail {:id 42})]
      (is (= {:message "Failed to load book 42"
              :original {:status 404}}
             (get-in (app-db) [:re-frame.query/queries qid :error])))))

  (testing "without transform-error, error passes through unchanged"
    (rfq/reg-query :books/plain
                   {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-failure
                    :books/plain {} {:status 503 :body "Service Unavailable"}])
    (let [qid (util/query-id :books/plain {})]
      (is (= {:status 503 :body "Service Unavailable"}
             (get-in (app-db) [:re-frame.query/queries qid :error]))))))

(deftest transform-response-mutation-test
  (testing "transform-response unwraps mutation success data"
    (rfq/reg-mutation :books/create
                      {:mutation-fn        (fn [_] {})
                       :transform-response (fn [response _params]
                                             (:book response))})
    (process-event [:re-frame.query/mutation-success
                    :books/create {:title "Dune"} {:book {:id 1 :title "Dune"} :status "created"}])
    (let [mid (util/query-id :books/create {:title "Dune"})]
      (is (= {:id 1 :title "Dune"}
             (get-in (app-db) [:re-frame.query/mutations mid :data]))
          "only the :book key is stored")))

  (testing "mutation transform-response receives params"
    (rfq/reg-mutation :books/update
                      {:mutation-fn        (fn [_] {})
                       :transform-response (fn [response params]
                                             (merge response
                                                    (select-keys params [:id])))})
    (process-event [:re-frame.query/mutation-success
                    :books/update {:id 42 :title "Dune Revised"} {:title "Dune Revised"}])
    (let [mid (util/query-id :books/update {:id 42 :title "Dune Revised"})]
      (is (= {:id 42 :title "Dune Revised"}
             (get-in (app-db) [:re-frame.query/mutations mid :data])))))

  (testing "without mutation transform-response, data passes through unchanged"
    (rfq/reg-mutation :books/plain
                      {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/mutation-success
                    :books/plain {} {:raw "response"}])
    (let [mid (util/query-id :books/plain {})]
      (is (= {:raw "response"}
             (get-in (app-db) [:re-frame.query/mutations mid :data]))))))

(deftest transform-error-mutation-test
  (testing "transform-error normalizes mutation error"
    (rfq/reg-mutation :books/create
                      {:mutation-fn     (fn [_] {})
                       :transform-error (fn [error _params]
                                          (:body error))})
    (process-event [:re-frame.query/mutation-failure
                    :books/create {:title "Dune"} {:status 422 :body "Validation failed"}])
    (let [mid (util/query-id :books/create {:title "Dune"})]
      (is (= "Validation failed"
             (get-in (app-db) [:re-frame.query/mutations mid :error])))))

  (testing "mutation transform-error receives params"
    (rfq/reg-mutation :books/update
                      {:mutation-fn     (fn [_] {})
                       :transform-error (fn [error params]
                                          {:message (str "Failed to update book " (:id params))
                                           :code    (:status error)})})
    (process-event [:re-frame.query/mutation-failure
                    :books/update {:id 42} {:status 500}])
    (let [mid (util/query-id :books/update {:id 42})]
      (is (= {:message "Failed to update book 42" :code 500}
             (get-in (app-db) [:re-frame.query/mutations mid :error])))))

  (testing "without mutation transform-error, error passes through unchanged"
    (rfq/reg-mutation :books/plain
                      {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/mutation-failure
                    :books/plain {} {:status 500 :body "error"}])
    (let [mid (util/query-id :books/plain {})]
      (is (= {:status 500 :body "error"}
             (get-in (app-db) [:re-frame.query/mutations mid :error]))))))

(deftest transform-response-with-invalidation-test
  (testing "transform-response works together with tag invalidation on mutations"
    (rf-test/run-test-sync
      (let [calls (atom [])]
        (rf/reg-fx :test-http (fn [v] (swap! calls conj v)))
        (rfq/set-default-effect-fn!
          (fn [request on-success on-failure]
            {:test-http (assoc request
                               :on-success on-success
                               :on-failure on-failure)}))
        (rfq/reg-query :books/list
                       {:query-fn           (fn [_] {:method :get :url "/api/books"})
                        :transform-response (fn [response _params] (:items response))
                        :tags               (fn [_] [[:books :all]])})
        (rfq/reg-mutation :books/create
                          {:mutation-fn        (fn [{:keys [title]}]
                                                 {:method :post :url "/api/books" :body {:title title}})
                           :transform-response (fn [response _params] (:book response))
                           :invalidates        (fn [_] [[:books :all]])})
        ;; Populate list with transformed data and mark active
        (rf/dispatch [:re-frame.query/query-success
                      :books/list {} {:items [{:id 1}] :total 1}])
        (rf/dispatch [:re-frame.query/mark-active :books/list {}])
        (let [qid (util/query-id :books/list {})]
          (is (= [{:id 1}] (get-in (app-db) [:re-frame.query/queries qid :data]))
              "query data is transformed"))
        (reset! calls [])
        ;; Mutation success → invalidation → refetch
        (rf/dispatch [:re-frame.query/mutation-success
                      :books/create {:title "Dune"} {:book {:id 2 :title "Dune"} :meta "extra"}])
        (let [mid (util/query-id :books/create {:title "Dune"})]
          (is (= {:id 2 :title "Dune"}
                 (get-in (app-db) [:re-frame.query/mutations mid :data]))
              "mutation data is transformed"))
        (is (= 1 (count @calls))
            "active query was refetched after invalidation")))))

;; ---------------------------------------------------------------------------
;; Reset API state tests
;; ---------------------------------------------------------------------------

(deftest reset-api-state-clears-all-test
  (testing "reset-api-state removes all queries and mutations from app-db"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    ;; Populate queries and mutations
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/mutation-success :books/create {} {:id 2}])
    (let [qid (util/query-id :books/list {})
          mid (util/query-id :books/create {})]
      (is (some? (get-in (app-db) [:re-frame.query/queries qid])))
      (is (some? (get-in (app-db) [:re-frame.query/mutations mid])))
      ;; Reset
      (process-event [:re-frame.query/reset-api-state])
      (is (nil? (:re-frame.query/queries (app-db)))
          "all queries removed")
      (is (nil? (:re-frame.query/mutations (app-db)))
          "all mutations removed"))))

(deftest reset-api-state-preserves-other-db-test
  (testing "reset-api-state does not affect non-rfq keys in app-db"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    ;; Set some app state alongside query state
    (swap! rf-db/app-db assoc :my-app/user {:id 42 :name "Alice"})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (is (some? (:re-frame.query/queries (app-db))))
    (is (= {:id 42 :name "Alice"} (:my-app/user (app-db))))
    ;; Reset
    (process-event [:re-frame.query/reset-api-state])
    (is (nil? (:re-frame.query/queries (app-db)))
        "query state cleared")
    (is (= {:id 42 :name "Alice"} (:my-app/user (app-db)))
        "app state preserved")))

(deftest reset-api-state-cancels-gc-timers-test
  (testing "reset-api-state cancels all pending GC timers"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 60000})
    (rfq/reg-query :books/detail
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 60000})
    ;; Populate and mark inactive to start GC timers
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (process-event [:re-frame.query/mark-inactive :books/list {}])
    (process-event [:re-frame.query/mark-inactive :books/detail {:id 1}])
    (is (= 2 (count (gc/active-timers)))
        "two GC timers are ticking")
    ;; Reset
    (process-event [:re-frame.query/reset-api-state])
    (is (empty? (gc/active-timers))
        "all GC timers cancelled")))

(deftest reset-api-state-cancels-polling-timers-test
  (testing "reset-api-state cancels all active polling timers"
    (let [qid (util/query-id :books/list {})]
      (polling/add-subscriber! qid :sub-1 :books/list {} 5000)
      (is (contains? (polling/active-polls) qid)
          "polling is active")
      ;; Reset
      (process-event [:re-frame.query/reset-api-state])
      (is (empty? (polling/active-polls))
          "all polling timers cancelled"))))

(deftest reset-api-state-then-queries-work-test
  (testing "After reset, new queries work normally"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    ;; Populate, then reset
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/reset-api-state])
    (is (nil? (:re-frame.query/queries (app-db))))
    ;; New query works
    (process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (= :loading (get-in (app-db) [:re-frame.query/queries qid :status]))
          "query starts fresh after reset")
      ;; Complete it
      (process-event [:re-frame.query/query-success :books/list {} [{:id 2}]])
      (is (= [{:id 2}] (get-in (app-db) [:re-frame.query/queries qid :data]))
          "new data cached normally"))))
