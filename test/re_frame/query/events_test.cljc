(ns re-frame.query.events-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.walk :as walk]
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
  (rfq/set-default-effect-fn! h/noop-effect-fn)
  (testing "sets status to :loading when no data exists"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query)))
      (is (true? (:fetching? query)))
      (is (nil? (:data query)))))

  (testing "keeps :success status when stale data exists"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :stale-time-ms 1000})
    ;; Fetch successfully first
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Make it stale: fetched-at=0, now-ms returns 1001 (past stale-time of 1000)
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
      (with-redefs [util/now-ms (constantly 1001)]
        (h/process-event [:re-frame.query/ensure-query :books/list {}]))
      (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
        (is (= :success (:status query))
            "status stays :success so components keep showing stale data")
        (is (true? (:fetching? query))
            "fetching? is true to indicate a background refetch")
        (is (= [{:id 1}] (:data query))
            "stale data is preserved while refetching"))))

  (testing "sets :loading when retrying after an error (no prior data)"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
    (h/process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query))
          "status resets to :loading on retry after error")
      (is (true? (:fetching? query)))))

  (testing "sets :loading after error even when stale data exists"
    (rfq/reg-query :books/detail
      {:query-fn (fn [_] {})
       :stale-time-ms 1000})
    ;; First fetch succeeds
    (h/process-event [:re-frame.query/query-success :books/detail {} {:title "Dune"}])
    (let [qid (util/query-id :books/detail {})]
      ;; Then a refetch fails — data persists but status is :error
      (h/process-event [:re-frame.query/query-failure :books/detail {} {:status 500}])
      (is (= :error (get-in (h/app-db) [:re-frame.query/queries qid :status])))
      (is (some? (get-in (h/app-db) [:re-frame.query/queries qid :data])))
      ;; Retry
      (h/process-event [:re-frame.query/ensure-query :books/detail {}])
      (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
        (is (= :loading (:status query))
            "status resets to :loading even though stale data exists, because last status was :error")
        (is (true? (:fetching? query)))))))

(deftest refetch-query-test
  (rfq/set-default-effect-fn! h/noop-effect-fn)
  (testing "keeps :success status when data exists"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})})
    ;; Fetch successfully first
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Force refetch
    (h/process-event [:re-frame.query/refetch-query :books/list {}])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= :success (:status query))
          "status stays :success during background refetch")
      (is (true? (:fetching? query)))
      (is (= [{:id 1}] (:data query)))))

  (testing "sets :loading when no prior data exists"
    ;; Reset db to clear data from previous testing block
    (reset! rf-db/app-db {})
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/refetch-query :books/list {}])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query)))
      (is (true? (:fetching? query)))))

  (testing "sets :loading when current status is :error"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    ;; First succeeds, then fails
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/query-failure :books/list {} {:status 503}])
    ;; Refetch after error
    (h/process-event [:re-frame.query/refetch-query :books/list {}])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query))
          "status is :loading, not :success, because last status was :error")
      (is (true? (:fetching? query))))))

(deftest query-success-test
  (testing "query-success stores data and metadata"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :stale-time-ms 30000
       :cache-time-ms 300000
       :tags (fn [_] [[:books :all]])})
    ;; Simulate success
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1 :title "Dune"}]])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
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
    (h/process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (let [qid (util/query-id :books/detail {:id 1})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= gc/default-cache-time-ms (:cache-time-ms query))))))

(deftest query-failure-test
  (testing "query-failure stores error"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
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
    (h/process-event [:re-frame.query/mutation-success :books/create {} {} {:id 2}])
    (let [mid (util/query-id :books/create {})
          mutation (get-in (h/app-db) [:re-frame.query/mutations mid])]
      (is (= :success (:status mutation)))))

  (testing "mutation-failure stores error"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (h/process-event [:re-frame.query/mutation-failure :books/create {} {} {:status 422}])
    (let [mid (util/query-id :books/create {})
          mutation (get-in (h/app-db) [:re-frame.query/mutations mid])]
      (is (= :error (:status mutation)))
      (is (= {:status 422} (:error mutation))))))

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
                      :url (str "/api/books?page=" page)})})
      (h/process-event [:re-frame.query/refetch-query :books/list {:page 1}])
      (is (= {:method :get
              :url "/api/books?page=1"
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
                         :url "/api/books"
                         :body {:title title}})})
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= {:method :post
              :url "/api/books"
              :body {:title "Dune"}
              :on-success [:re-frame.query/mutation-success :books/create {:title "Dune"} {}]
              :on-failure [:re-frame.query/mutation-failure :books/create {:title "Dune"} {}]}
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
        {:query-fn (fn [_] {:method :get :url "/api/special"})
         :effect-fn (fn [request on-success on-failure]
                      {:custom-http (assoc request
                                           :on-success on-success
                                           :on-failure on-failure)})})
      (h/process-event [:re-frame.query/refetch-query :books/special {}])
      (is (= {:method :get
              :url "/api/special"
              :on-success [:re-frame.query/query-success :books/special {}]
              :on-failure [:re-frame.query/query-failure :books/special {}]}
             @captured)))))

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
                                   :url (str "/api/books?page=" page)})
                      :stale-time-ms 30000
                      :tags (fn [_] [[:books]])}}
        :mutations
        {:books/create {:mutation-fn (fn [{:keys [title]}]
                                       {:method :post
                                        :url "/api/books"
                                        :body {:title title}})
                        :invalidates (fn [_] [[:books]])}}})
      ;; Query works
      (h/process-event [:re-frame.query/refetch-query :books/list {:page 1}])
      (is (= {:method :get
              :url "/api/books?page=1"
              :on-success [:re-frame.query/query-success :books/list {:page 1}]
              :on-failure [:re-frame.query/query-failure :books/list {:page 1}]}
             @captured))
      ;; Mutation works
      (reset! captured nil)
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= {:method :post
              :url "/api/books"
              :body {:title "Dune"}
              :on-success [:re-frame.query/mutation-success :books/create {:title "Dune"} {}]
              :on-failure [:re-frame.query/mutation-failure :books/create {:title "Dune"} {}]}
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
      (h/process-event [:re-frame.query/ensure-query :books/list {}])
      (is (= 1 @call-count) "first ensure-query fires the effect")
      ;; Verify fetching? is now true
      (let [qid (util/query-id :books/list {})]
        (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))))
      ;; Second dispatch — should be a no-op because fetching? is true
      (h/process-event [:re-frame.query/ensure-query :books/list {}])
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
      (h/process-event [:re-frame.query/ensure-query :books/detail {:id 1}])
      (h/process-event [:re-frame.query/ensure-query :books/detail {:id 2}])
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
        {:query-fn (fn [_] {:method :get :url "/api/books"})
         :stale-time-ms 60000})
      ;; Populate cache with fresh data
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      ;; Reset counter after the setup
      (reset! call-count 0)
      ;; ensure-query on fresh data — should produce no effect
      (h/process-event [:re-frame.query/ensure-query :books/list {}])
      (is (zero? @call-count)
          "no effect produced when data is fresh"))))

;; ---------------------------------------------------------------------------
;; Registration error handling tests
;; ---------------------------------------------------------------------------

(deftest unregistered-key-throws-test
  (testing "Dispatching ensure-query for an unregistered key throws an error"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"No query registered for key"
         (h/process-event [:re-frame.query/ensure-query :nonexistent/query {}]))))

  (testing "Dispatching refetch-query for an unregistered key throws an error"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"No query registered for key"
         (h/process-event [:re-frame.query/refetch-query :nonexistent/query {}]))))

  (testing "Dispatching execute-mutation for an unregistered key throws an error"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"No mutation registered for key"
         (h/process-event [:re-frame.query/execute-mutation :nonexistent/mutation {}]))))

  (testing "Subscribing to an unregistered query key throws because ensure-query is dispatched"
    (rf-test/run-test-sync
     (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
          #"No query registered for key"
          (rf/subscribe [:re-frame.query/query :nonexistent/query {}]))))))

(deftest ensure-query-rejects-infinite-query-test
  (testing "ensure-query throws when called with an infinite query key"
    (rfq/set-default-effect-fn! h/noop-effect-fn)
    (rfq/reg-query :feed/items
      {:query-fn (fn [_] {:method :get :url "/api/feed"})
       :infinite {:initial-cursor nil
                  :get-next-cursor (fn [resp] (:next_cursor resp))}})
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"infinite query.*ensure-infinite-query"
         (h/process-event [:re-frame.query/ensure-query :feed/items {}])))))

;; The fixture calls h/reset-db! → rfq/clear-registry!, which also clears the
;; global default effect-fn — so "no adapter anywhere" is the starting state.
(deftest no-effect-adapter-throws-test
  (testing "ensure-query throws when neither :effect-fn nor a global default exists"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {:method :get :url "/api/books"})})
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"no effect adapter configured for :books/list"
         (h/process-event [:re-frame.query/ensure-query :books/list {}]))))

  (testing "refetch-query throws too, and ex-data carries the key"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {:method :get :url "/api/books"})})
    (let [error (try (h/process-event [:re-frame.query/refetch-query :books/list {}])
                     nil
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))]
      (is (some? error) "refetching without an adapter throws")
      (is (= {:key :books/list} (ex-data error)))))

  (testing "execute-mutation throws when no adapter is resolvable"
    (rfq/reg-mutation :books/create
      {:mutation-fn (fn [_] {:method :post :url "/api/books"})})
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"no effect adapter configured for :books/create"
         (h/process-event [:re-frame.query/execute-mutation :books/create {}]))))

  (testing "ensure-infinite-query throws when no adapter is resolvable"
    (rfq/reg-query :feed/items
      {:query-fn (fn [_] {:method :get :url "/api/feed"})
       :infinite {:initial-cursor nil
                  :get-next-cursor (fn [resp] (:next_cursor resp))}})
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"no effect adapter configured for :feed/items"
         (h/process-event [:re-frame.query/ensure-infinite-query :feed/items {}]))))

  (testing "a per-query :effect-fn resolves without a global default"
    (rfq/reg-query :books/detail
      {:query-fn (fn [_] {:method :get :url "/api/books/1"})
       :effect-fn h/noop-effect-fn})
    (h/process-event [:re-frame.query/ensure-query :books/detail {}])
    (let [qid (util/query-id :books/detail {})]
      (is (true? (:fetching? (get-in (h/app-db) [:re-frame.query/queries qid])))
          "the query starts normally through its own adapter"))))

;; ---------------------------------------------------------------------------
;; Query state shape completeness tests
;; ---------------------------------------------------------------------------

(deftest state-nil-before-operations-test
  (testing "Before any fetch, query state is nil in app-db"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      (is (nil? (get-in (h/app-db) [:re-frame.query/queries qid]))
          "query state does not exist before any event is dispatched")))

  (testing "Before any execution, mutation state is nil in app-db"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (let [mid (util/query-id :books/create {})]
      (is (nil? (get-in (h/app-db) [:re-frame.query/mutations mid]))
          "mutation state does not exist before any event is dispatched"))))

;; NOTE: the :books/list blocks below share one qid ([:books/list {}]) and the db
;; is not reset between them. :request-id is an opaque per-attempt value supplied
;; by the :re-frame.query/request-id coeffect (a UUID), so the shape assertions
;; dissoc it and pin it separately — the value itself carries no meaning, only
;; whether an attempt was stamped and whether the stamp changed.
(deftest query-state-shape-test
  (rfq/set-default-effect-fn! h/noop-effect-fn)
  (testing "Full state shape after ensure-query (initial load, no prior data)"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= {:status :loading
              :data nil
              :error nil
              :fetching? true
              :stale? false
              :active? false
              :tags #{}}
             (dissoc query :request-id)))
      (is (uuid? (:request-id query))
          "issuing a request stamps the entry with the injected request id")))

  (testing "Full state shape after query-success"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :stale-time-ms 30000
       :cache-time-ms 300000
       :tags (fn [_] [[:books :all]])})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1 :title "Dune"}]])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= {:status :success
              :data [{:id 1 :title "Dune"}]
              :error nil
              :fetching? false
              :stale? false
              :active? false
              :tags #{[:books :all]}
              :stale-time-ms 30000
              :cache-time-ms 300000}
             (dissoc query :fetched-at :request-id)))
      (is (uuid? (:request-id query))
          "committing a result issues nothing, so the previous block's stamp stays")
      (is (number? (:fetched-at query))
          "fetched-at is a numeric timestamp")))

  (testing "Full state shape after query-failure"
    (rfq/reg-query :books/error-shape {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-failure :books/error-shape {} {:status 500 :body "error"}])
    (let [qid (util/query-id :books/error-shape {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= {:status :error
              :data nil
              :error {:status 500 :body "error"}
              :fetching? false
              :stale? true
              :active? false
              :tags #{}}
             query))))

  (testing "Full state shape during a background refetch (stale data visible)"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :stale-time-ms 1000
       :cache-time-ms 300000
       :tags (fn [_] [[:books :all]])})
    ;; Initial success
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Make stale: fetched-at=0, now-ms=1001 (past stale-time of 1000)
    (let [qid (util/query-id :books/list {})
          id-before (get-in (h/app-db) [:re-frame.query/queries qid :request-id])]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
      (with-redefs [util/now-ms (constantly 1001)]
        (h/process-event [:re-frame.query/ensure-query :books/list {}]))
      (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
        (is (= {:status :success
                :data [{:id 1}]
                :error nil
                :fetching? true
                :stale? false
                :active? false
                :tags #{[:books :all]}
                :stale-time-ms 1000
                :cache-time-ms 300000}
               (dissoc query :fetched-at :request-id))
            "status stays :success, fetching? true, stale data preserved")
        (is (not= id-before (:request-id query))
            "this ensure-query issued a new attempt, so the entry is restamped")))))

(deftest mutation-state-shape-test
  (rfq/set-default-effect-fn! h/noop-effect-fn)
  (testing "Full state shape after execute-mutation"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
    (let [mid (util/query-id :books/create {:title "Dune"})
          mutation (get-in (h/app-db) [:re-frame.query/mutations mid])]
      (is (= {:status :loading
              :error nil}
             mutation))))

  (testing "Full state shape after mutation-success"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (h/process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {} {:id 1 :title "Dune"}])
    (let [mid (util/query-id :books/create {:title "Dune"})
          mutation (get-in (h/app-db) [:re-frame.query/mutations mid])]
      (is (= {:status :success
              :data {:id 1 :title "Dune"}
              :error nil}
             mutation))))

  (testing "Full state shape after mutation-failure"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (h/process-event [:re-frame.query/mutation-failure :books/create {:title "Dune"} {} {:status 422 :body "Unprocessable"}])
    (let [mid (util/query-id :books/create {:title "Dune"})
          mutation (get-in (h/app-db) [:re-frame.query/mutations mid])]
      (is (= {:status :error
              :error {:status 422 :body "Unprocessable"}}
             mutation)))))

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
        {:query-fn (fn [_] {:method :get :url "/api/books"})
         :stale-time-ms 60000})
      ;; Populate with fresh data
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (reset! call-count 0)
      ;; ensure-query — data is fresh, should be a no-op
      (h/process-event [:re-frame.query/ensure-query :books/list {}])
      (let [qid (util/query-id :books/list {})]
        (is (zero? @call-count)
            "no effect produced for fresh data")
        (is (false? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))
            "fetching? remains false")
        (is (= :success (get-in (h/app-db) [:re-frame.query/queries qid :status]))
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
        {:query-fn (fn [_] {:method :get :url "/api/books"})
         :stale-time-ms 60000})
      ;; Populate with fresh data
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (reset! call-count 0)
      ;; refetch-query — should fire regardless of freshness
      (h/process-event [:re-frame.query/refetch-query :books/list {}])
      (let [qid (util/query-id :books/list {})]
        (is (= 1 @call-count)
            "effect fires even though data is fresh")
        (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))
            "fetching? is true")
        (is (= :success (get-in (h/app-db) [:re-frame.query/queries qid :status]))
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
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (let [qid (util/query-id :books/list {})]
        (swap! rf-db/app-db assoc-in
               [:re-frame.query/queries qid :fetched-at] 1000)
        (reset! call-count 0)
        ;; ensure-query at time 2001: (2001 - 1000) = 1001 > stale-time-ms 1000
        (with-redefs [util/now-ms (constantly 2001)]
          (h/process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (= 1 @call-count)
            "effect fires because stale-time has elapsed")
        (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))))))

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
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (let [qid (util/query-id :books/list {})]
        (swap! rf-db/app-db assoc-in
               [:re-frame.query/queries qid :fetched-at] 0)
        (reset! call-count 0)
        ;; ensure-query at time 999999 — no stale-time-ms, so NOT stale by time
        (with-redefs [util/now-ms (constantly 999999)]
          (h/process-event [:re-frame.query/ensure-query :books/list {}]))
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
        {:query-fn (fn [_] {:method :get :url "/api/books"})
         :stale-time-ms 30000})
      ;; First fetch succeeds at time 1000
      (with-redefs [util/now-ms (constantly 1000)]
        (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]]))
      (let [qid (util/query-id :books/list {})
            fetched-at (get-in (h/app-db) [:re-frame.query/queries qid :fetched-at])]
        (is (= 1000 fetched-at) "fetched-at is recorded at controlled time")
        (reset! call-count 0)
        ;; ensure-query at time 31001 (31001 - 1000 = 30001 > stale-time 30000)
        (with-redefs [util/now-ms (constantly 31001)]
          (h/process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (= 1 @call-count) "stale data triggers a refetch")
        ;; Simulate success at time 31001 — fetched-at should update
        (with-redefs [util/now-ms (constantly 31001)]
          (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1} {:id 2}]]))
        (let [new-fetched-at (get-in (h/app-db) [:re-frame.query/queries qid :fetched-at])]
          (is (= 31001 new-fetched-at) "fetched-at is set to the controlled timestamp")
          (is (> new-fetched-at fetched-at) "new fetched-at is more recent than the original")
          ;; Now the data is fresh — ensure-query at 31002 should be a no-op
          ;; (31002 - 31001 = 1ms, well within stale-time of 30000ms)
          (reset! call-count 0)
          (with-redefs [util/now-ms (constantly 31002)]
            (h/process-event [:re-frame.query/ensure-query :books/list {}]))
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
      (h/process-event [:re-frame.query/query-failure :books/list {} {:status 500 :body "Internal Server Error"}])
      (is (= :error (get-in (h/app-db) [:re-frame.query/queries qid :status])))
      (is (= {:status 500 :body "Internal Server Error"}
             (get-in (h/app-db) [:re-frame.query/queries qid :error])))
      ;; Retry succeeds
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
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
      (h/process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
      (is (= {:status 500} (get-in (h/app-db) [:re-frame.query/queries qid :error])))
      ;; Second failure with different error
      (h/process-event [:re-frame.query/query-failure :books/list {} {:status 503}])
      (is (= {:status 503} (get-in (h/app-db) [:re-frame.query/queries qid :error]))
          "error is replaced, not accumulated")
      (is (= :error (get-in (h/app-db) [:re-frame.query/queries qid :status])))))

  (testing "After success then failure, data persists but status is :error"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      ;; Success first
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (is (= :success (get-in (h/app-db) [:re-frame.query/queries qid :status])))
      ;; Then a refetch fails
      (h/process-event [:re-frame.query/query-failure :books/list {} {:status 502}])
      (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
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
        {:query-fn (fn [_] {:method :get :url "/api/books"})
         :stale-time-ms 1000})
      (let [qid (util/query-id :books/list {})]
        ;; 1. Initial success
        (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
        (is (= :success (get-in (h/app-db) [:re-frame.query/queries qid :status])))
        ;; 2. Make stale: fetched-at=0, now-ms=1001 (past stale-time of 1000)
        (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
        (reset! call-count 0)
        ;; 3. ensure-query detects staleness → refetch
        (with-redefs [util/now-ms (constantly 1001)]
          (h/process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (= 1 @call-count))
        ;; 4. Refetch fails
        (h/process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
        (is (= :error (get-in (h/app-db) [:re-frame.query/queries qid :status])))
        (is (= [{:id 1}] (get-in (h/app-db) [:re-frame.query/queries qid :data]))
            "stale data preserved after failure")
        ;; 5. Retry — error status is stale, so ensure-query triggers
        (reset! call-count 0)
        (h/process-event [:re-frame.query/ensure-query :books/list {}])
        (is (= 1 @call-count) "retry fires because error status is stale")
        (is (= :loading (get-in (h/app-db) [:re-frame.query/queries qid :status]))
            "status resets to :loading on retry after error")
        ;; 6. Success with new data
        (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1} {:id 2}]])
        (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
          (is (= :success (:status query)))
          (is (= [{:id 1} {:id 2}] (:data query)))
          (is (nil? (:error query))
              "error is cleared after successful recovery"))))))

(deftest mutation-error-then-retry-success
  (testing "A failed mutation followed by re-execution transitions to :success"
    (rfq/set-default-effect-fn! h/noop-effect-fn)
    (rfq/reg-mutation :books/create
      {:mutation-fn (fn [{:keys [title]}]
                      {:method :post :url "/api/books" :body {:title title}})})
    (let [mid (util/query-id :books/create {:title "Dune"})]
      ;; First attempt fails
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (h/process-event [:re-frame.query/mutation-failure :books/create {:title "Dune"} {} {:status 500}])
      (is (= :error (get-in (h/app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:status 500} (get-in (h/app-db) [:re-frame.query/mutations mid :error])))
      ;; Retry succeeds
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= :loading (get-in (h/app-db) [:re-frame.query/mutations mid :status]))
          "status resets to :loading")
      (is (nil? (get-in (h/app-db) [:re-frame.query/mutations mid :error]))
          "error cleared on retry")
      (h/process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {} {:id 1}])
      (is (= :success (get-in (h/app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:id 1} (get-in (h/app-db) [:re-frame.query/mutations mid :data])))
      (is (nil? (get-in (h/app-db) [:re-frame.query/mutations mid :error]))))))

;; ---------------------------------------------------------------------------
;; Transform response tests
;; ---------------------------------------------------------------------------

(deftest transform-response-query-test
  (testing "transform-response unwraps nested data before caching"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :transform-response (fn [response _params]
                             (:data response))})
    (h/process-event [:re-frame.query/query-success
                      :books/list {} {:data [{:id 1} {:id 2}] :meta {:total 2}}])
    (let [qid (util/query-id :books/list {})]
      (is (= [{:id 1} {:id 2}]
             (get-in (h/app-db) [:re-frame.query/queries qid :data]))
          "only the :data key is stored, :meta is stripped")))

  (testing "transform-response receives params for context-dependent transforms"
    (rfq/reg-query :book/detail
      {:query-fn (fn [_] {})
       :transform-response (fn [response params]
                             (assoc response :requested-id (:id params)))})
    (h/process-event [:re-frame.query/query-success
                      :book/detail {:id 42} {:title "Dune"}])
    (let [qid (util/query-id :book/detail {:id 42})]
      (is (= {:title "Dune" :requested-id 42}
             (get-in (h/app-db) [:re-frame.query/queries qid :data]))
          "params are passed to transform-response")))

  (testing "transform-response can normalize into a lookup map"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :transform-response (fn [items _params]
                             (into {} (map (juxt :id identity)) items))})
    (h/process-event [:re-frame.query/query-success
                      :books/list {} [{:id 1 :title "Dune"} {:id 2 :title "Foundation"}]])
    (let [qid (util/query-id :books/list {})]
      (is (= {1 {:id 1 :title "Dune"}
              2 {:id 2 :title "Foundation"}}
             (get-in (h/app-db) [:re-frame.query/queries qid :data])))))

  (testing "without transform-response, data passes through unchanged"
    (rfq/reg-query :books/plain
      {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-success
                      :books/plain {} {:raw "data" :nested {:deep true}}])
    (let [qid (util/query-id :books/plain {})]
      (is (= {:raw "data" :nested {:deep true}}
             (get-in (h/app-db) [:re-frame.query/queries qid :data]))
          "data stored as-is when no transform-response"))))

(deftest transform-error-query-test
  (testing "transform-error normalizes error before storing"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :transform-error (fn [error _params]
                          {:message (or (:body error) "Unknown error")
                           :code (:status error)})})
    (h/process-event [:re-frame.query/query-failure
                      :books/list {} {:status 500 :body "Internal Server Error"}])
    (let [qid (util/query-id :books/list {})]
      (is (= {:message "Internal Server Error" :code 500}
             (get-in (h/app-db) [:re-frame.query/queries qid :error])))))

  (testing "transform-error receives params"
    (rfq/reg-query :book/detail
      {:query-fn (fn [_] {})
       :transform-error (fn [error params]
                          {:message (str "Failed to load book " (:id params))
                           :original error})})
    (h/process-event [:re-frame.query/query-failure
                      :book/detail {:id 42} {:status 404}])
    (let [qid (util/query-id :book/detail {:id 42})]
      (is (= {:message "Failed to load book 42"
              :original {:status 404}}
             (get-in (h/app-db) [:re-frame.query/queries qid :error])))))

  (testing "without transform-error, error passes through unchanged"
    (rfq/reg-query :books/plain
      {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-failure
                      :books/plain {} {:status 503 :body "Service Unavailable"}])
    (let [qid (util/query-id :books/plain {})]
      (is (= {:status 503 :body "Service Unavailable"}
             (get-in (h/app-db) [:re-frame.query/queries qid :error]))))))

(deftest transform-response-mutation-test
  (testing "transform-response unwraps mutation success data"
    (rfq/reg-mutation :books/create
      {:mutation-fn (fn [_] {})
       :transform-response (fn [response _params]
                             (:book response))})
    (h/process-event [:re-frame.query/mutation-success
                      :books/create {:title "Dune"} {} {:book {:id 1 :title "Dune"} :status "created"}])
    (let [mid (util/query-id :books/create {:title "Dune"})]
      (is (= {:id 1 :title "Dune"}
             (get-in (h/app-db) [:re-frame.query/mutations mid :data]))
          "only the :book key is stored")))

  (testing "mutation transform-response receives params"
    (rfq/reg-mutation :books/update
      {:mutation-fn (fn [_] {})
       :transform-response (fn [response params]
                             (merge response
                                    (select-keys params [:id])))})
    (h/process-event [:re-frame.query/mutation-success
                      :books/update {:id 42 :title "Dune Revised"} {} {:title "Dune Revised"}])
    (let [mid (util/query-id :books/update {:id 42 :title "Dune Revised"})]
      (is (= {:id 42 :title "Dune Revised"}
             (get-in (h/app-db) [:re-frame.query/mutations mid :data])))))

  (testing "without mutation transform-response, data passes through unchanged"
    (rfq/reg-mutation :books/plain
      {:mutation-fn (fn [_] {})})
    (h/process-event [:re-frame.query/mutation-success
                      :books/plain {} {} {:raw "response"}])
    (let [mid (util/query-id :books/plain {})]
      (is (= {:raw "response"}
             (get-in (h/app-db) [:re-frame.query/mutations mid :data]))))))

(deftest transform-error-mutation-test
  (testing "transform-error normalizes mutation error"
    (rfq/reg-mutation :books/create
      {:mutation-fn (fn [_] {})
       :transform-error (fn [error _params]
                          (:body error))})
    (h/process-event [:re-frame.query/mutation-failure
                      :books/create {:title "Dune"} {} {:status 422 :body "Validation failed"}])
    (let [mid (util/query-id :books/create {:title "Dune"})]
      (is (= "Validation failed"
             (get-in (h/app-db) [:re-frame.query/mutations mid :error])))))

  (testing "mutation transform-error receives params"
    (rfq/reg-mutation :books/update
      {:mutation-fn (fn [_] {})
       :transform-error (fn [error params]
                          {:message (str "Failed to update book " (:id params))
                           :code (:status error)})})
    (h/process-event [:re-frame.query/mutation-failure
                      :books/update {:id 42} {} {:status 500}])
    (let [mid (util/query-id :books/update {:id 42})]
      (is (= {:message "Failed to update book 42" :code 500}
             (get-in (h/app-db) [:re-frame.query/mutations mid :error])))))

  (testing "without mutation transform-error, error passes through unchanged"
    (rfq/reg-mutation :books/plain
      {:mutation-fn (fn [_] {})})
    (h/process-event [:re-frame.query/mutation-failure
                      :books/plain {} {} {:status 500 :body "error"}])
    (let [mid (util/query-id :books/plain {})]
      (is (= {:status 500 :body "error"}
             (get-in (h/app-db) [:re-frame.query/mutations mid :error]))))))

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
         {:query-fn (fn [_] {:method :get :url "/api/books"})
          :transform-response (fn [response _params] (:items response))
          :tags (fn [_] [[:books :all]])})
       (rfq/reg-mutation :books/create
         {:mutation-fn (fn [{:keys [title]}]
                         {:method :post :url "/api/books" :body {:title title}})
          :transform-response (fn [response _params] (:book response))
          :invalidates (fn [_] [[:books :all]])})
        ;; Populate list with transformed data and mark active
       (rf/dispatch [:re-frame.query/query-success
                     :books/list {} {:items [{:id 1}] :total 1}])
       (rf/dispatch [:re-frame.query/mark-active :books/list {}])
       (let [qid (util/query-id :books/list {})]
         (is (= [{:id 1}] (get-in (h/app-db) [:re-frame.query/queries qid :data]))
             "query data is transformed"))
       (reset! calls [])
        ;; Mutation success → invalidation → refetch
       (rf/dispatch [:re-frame.query/mutation-success
                     :books/create {:title "Dune"} {} {:book {:id 2 :title "Dune"} :meta "extra"}])
       (let [mid (util/query-id :books/create {:title "Dune"})]
         (is (= {:id 2 :title "Dune"}
                (get-in (h/app-db) [:re-frame.query/mutations mid :data]))
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
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/mutation-success :books/create {} {} {:id 2}])
    (let [qid (util/query-id :books/list {})
          mid (util/query-id :books/create {})]
      (is (some? (get-in (h/app-db) [:re-frame.query/queries qid])))
      (is (some? (get-in (h/app-db) [:re-frame.query/mutations mid])))
      ;; Reset
      (h/process-event [:re-frame.query/reset-api-state])
      (is (nil? (:re-frame.query/queries (h/app-db)))
          "all queries removed")
      (is (nil? (:re-frame.query/mutations (h/app-db)))
          "all mutations removed"))))

(deftest reset-api-state-preserves-other-db-test
  (testing "reset-api-state does not affect non-rfq keys in app-db"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    ;; Set some app state alongside query state
    (swap! rf-db/app-db assoc :my-app/user {:id 42 :name "Alice"})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (is (some? (:re-frame.query/queries (h/app-db))))
    (is (= {:id 42 :name "Alice"} (:my-app/user (h/app-db))))
    ;; Reset
    (h/process-event [:re-frame.query/reset-api-state])
    (is (nil? (:re-frame.query/queries (h/app-db)))
        "query state cleared")
    (is (= {:id 42 :name "Alice"} (:my-app/user (h/app-db)))
        "app state preserved")))

(deftest reset-api-state-cancels-gc-timers-test
  (testing "reset-api-state cancels all pending GC timers"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :cache-time-ms 60000})
    (rfq/reg-query :books/detail
      {:query-fn (fn [_] {})
       :cache-time-ms 60000})
    ;; Populate and mark inactive to start GC timers
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (h/process-event [:re-frame.query/mark-inactive :books/list {}])
    (h/process-event [:re-frame.query/mark-inactive :books/detail {:id 1}])
    (is (= 2 (count (gc/active-timers)))
        "two GC timers are ticking")
    ;; Reset
    (h/process-event [:re-frame.query/reset-api-state])
    (is (empty? (gc/active-timers))
        "all GC timers cancelled")))

(deftest reset-api-state-cancels-polling-timers-test
  (testing "reset-api-state cancels all active polling timers"
    (let [qid (util/query-id :books/list {})]
      (polling/add-subscriber! qid :sub-1 :books/list {} 5000)
      (is (contains? (polling/active-polls) qid)
          "polling is active")
      ;; Reset
      (h/process-event [:re-frame.query/reset-api-state])
      (is (empty? (polling/active-polls))
          "all polling timers cancelled"))))

(deftest reset-api-state-then-queries-work-test
  (testing "After reset, new queries work normally"
    (rfq/set-default-effect-fn! h/noop-effect-fn)
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    ;; Populate, then reset
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/reset-api-state])
    (is (nil? (:re-frame.query/queries (h/app-db))))
    ;; New query works
    (h/process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (= :loading (get-in (h/app-db) [:re-frame.query/queries qid :status]))
          "query starts fresh after reset")
      ;; Complete it
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 2}]])
      (is (= [{:id 2}] (get-in (h/app-db) [:re-frame.query/queries qid :data]))
          "new data cached normally"))))

;; ---------------------------------------------------------------------------
;; Reset mutation tests
;; ---------------------------------------------------------------------------

(deftest reset-mutation-test
  (testing "reset-mutation clears mutation state back to idle"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (h/process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {} {:id 1}])
    (let [mid (util/query-id :books/create {:title "Dune"})]
      (is (= :success (get-in (h/app-db) [:re-frame.query/mutations mid :status])))
      ;; Reset
      (h/process-event [:re-frame.query/reset-mutation :books/create {:title "Dune"}])
      (is (nil? (get-in (h/app-db) [:re-frame.query/mutations mid]))
          "mutation entry removed from app-db")))

  (testing "reset-mutation after failure clears error state"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (h/process-event [:re-frame.query/mutation-failure :books/create {:title "Dune"} {} {:status 422}])
    (let [mid (util/query-id :books/create {:title "Dune"})]
      (is (= :error (get-in (h/app-db) [:re-frame.query/mutations mid :status])))
      (h/process-event [:re-frame.query/reset-mutation :books/create {:title "Dune"}])
      (is (nil? (get-in (h/app-db) [:re-frame.query/mutations mid])))))

  (testing "reset-mutation is a no-op for non-existent mutation"
    (h/process-event [:re-frame.query/reset-mutation :books/create {:title "nope"}])
    (is (= {} (or (:re-frame.query/mutations (h/app-db)) {}))
        "app-db is unaffected"))

  (testing "mutation subscription returns idle after reset"
    (rf-test/run-test-sync
     (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
     (rf/dispatch [:re-frame.query/mutation-success :books/create {:title "Dune"} {} {:id 1}])
     (let [sub (rf/subscribe [:re-frame.query/mutation :books/create {:title "Dune"}])]
       (is (= :success (:status @sub)))
       (rf/dispatch [:re-frame.query/reset-mutation :books/create {:title "Dune"}])
       (is (= :idle (:status @sub))
           "subscription falls back to idle default"))))

  (testing "reset does not affect other mutations"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (rfq/reg-mutation :books/update {:mutation-fn (fn [_] {})})
    (h/process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {} {:id 1}])
    (h/process-event [:re-frame.query/mutation-success :books/update {:id 1} {} {:id 1 :title "Dune Revised"}])
    (let [create-mid (util/query-id :books/create {:title "Dune"})
          update-mid (util/query-id :books/update {:id 1})]
      (h/process-event [:re-frame.query/reset-mutation :books/create {:title "Dune"}])
      (is (nil? (get-in (h/app-db) [:re-frame.query/mutations create-mid]))
          "reset mutation is cleared")
      (is (= :success (get-in (h/app-db) [:re-frame.query/mutations update-mid :status]))
          "other mutation is untouched"))))

;; ---------------------------------------------------------------------------
;; set-query-data tests
;; ---------------------------------------------------------------------------

(deftest set-query-data-test
  (testing "replaces cached query data"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/set-query-data :books/list {} [{:id 1} {:id 2}]])
    (let [qid (util/query-id :books/list {})]
      (is (= [{:id 1} {:id 2}]
             (get-in (h/app-db) [:re-frame.query/queries qid :data])))
      (is (= :success (get-in (h/app-db) [:re-frame.query/queries qid :status])))))

  (testing "creates cache entry for non-existent query"
    (rfq/reg-query :books/new {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/set-query-data :books/new {} [{:id 99}]])
    (let [qid (util/query-id :books/new {})]
      (is (= [{:id 99}] (get-in (h/app-db) [:re-frame.query/queries qid :data])))
      (is (= :success (get-in (h/app-db) [:re-frame.query/queries qid :status])))
      (is (number? (get-in (h/app-db) [:re-frame.query/queries qid :fetched-at])))))

  (testing "preserves existing query fields like tags and stale-time"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :stale-time-ms 30000
       :tags (fn [_] [[:books :all]])})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Verify tags are set from the original success
      (is (= #{[:books :all]} (get-in (h/app-db) [:re-frame.query/queries qid :tags])))
      ;; Now set-query-data — should preserve tags
      (h/process-event [:re-frame.query/set-query-data :books/list {} [{:id 1} {:id 2}]])
      (is (= [{:id 1} {:id 2}] (get-in (h/app-db) [:re-frame.query/queries qid :data])))
      (is (= #{[:books :all]} (get-in (h/app-db) [:re-frame.query/queries qid :tags]))
          "tags preserved after set-query-data")))

  (testing "can be used for rollback (set back to snapshot)"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})
          snapshot (get-in (h/app-db) [:re-frame.query/queries qid :data])]
      ;; Optimistic patch
      (h/process-event [:re-frame.query/set-query-data :books/list {} [{:id 1} {:id 2 :optimistic true}]])
      (is (= 2 (count (get-in (h/app-db) [:re-frame.query/queries qid :data]))))
      ;; Rollback
      (h/process-event [:re-frame.query/set-query-data :books/list {} snapshot])
      (is (= [{:id 1}] (get-in (h/app-db) [:re-frame.query/queries qid :data]))
          "data rolled back to snapshot")))

  (testing "marks the entry stale so the next ensure-query background-refetches"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/set-query-data :books/list {} [{:id 99}]])
    (let [qid (util/query-id :books/list {})]
      (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :stale?]))
          "data written via set-query-data is unverified — must be marked stale")))

  (testing "preserves :fetching? when a request is already in flight"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (rfq/set-default-effect-fn! h/noop-effect-fn)
    ;; Kick off a fetch — :fetching? is now true, no response yet
    (h/process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))
          "precondition: fetch is in flight")
      ;; Seed placeholder data while the request is still in flight
      (h/process-event [:re-frame.query/set-query-data :books/list {} [{:id 99}]])
      (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))
          "set-query-data must not lie about an in-flight request"))))

;; ---------------------------------------------------------------------------
;; Map-form payload tests (canonical single-map event form)
;; ---------------------------------------------------------------------------

(deftest map-form-ensure-query-test
  (rfq/set-default-effect-fn! h/noop-effect-fn)
  (testing "map form produces the same query state as the positional form"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid (util/query-id :books/list {})
          positional (get-in (h/app-db) [:re-frame.query/queries qid])]
      (reset! rf-db/app-db {})
      (h/process-event [:re-frame.query/ensure-query {:query :books/list :params {}}])
      (let [map-form (get-in (h/app-db) [:re-frame.query/queries qid])]
        (is (= (dissoc positional :request-id)
               (dissoc map-form :request-id))
            "both forms yield identical query state (modulo the attempt stamp)")
        (is (uuid? (:request-id map-form))
            "the map form stamps the attempt like the positional form"))))

  (testing "map form without :params still keys the cache under [k {}]"
    (reset! rf-db/app-db {})
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/ensure-query {:query :books/list}])
    (let [qid (util/query-id :books/list {})]
      (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))
          "the entry lives under [k {}], same as the positional form"))))

(deftest map-form-refetch-query-test
  (rfq/set-default-effect-fn! h/noop-effect-fn)
  (testing "map form refetches exactly like the positional form"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/refetch-query {:query :books/list :params {}}])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= :success (:status query))
          "status stays :success during background refetch")
      (is (true? (:fetching? query)))
      (is (= [{:id 1}] (:data query))))))

(deftest map-form-cancel-query-test
  (rfq/set-default-effect-fn! h/noop-effect-fn)
  (testing "map form cancels an in-flight attempt"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid (util/query-id :books/list {})
          id-before (get-in (h/app-db) [:re-frame.query/queries qid :request-id])]
      (h/process-event [:re-frame.query/cancel-query {:query :books/list :params {}}])
      (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
        (is (false? (:fetching? query))
            "fetching? cleared by cancel")
        (is (not= id-before (:request-id query))
            "cancel claims a fresh request id, superseding the in-flight attempt")))))

(deftest map-form-set-query-data-test
  (testing "map form writes the same cache entry as the positional form"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/set-query-data :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})
          positional (get-in (h/app-db) [:re-frame.query/queries qid])]
      (reset! rf-db/app-db {})
      (h/process-event [:re-frame.query/set-query-data
                        {:query :books/list :params {} :data [{:id 1}]}])
      (let [map-form (get-in (h/app-db) [:re-frame.query/queries qid])]
        (is (= (dissoc positional :fetched-at)
               (dissoc map-form :fetched-at))
            "both forms produce identical cache entries (modulo timestamp)")))))

(deftest map-form-mark-active-test
  (rfq/set-default-effect-fn! h/noop-effect-fn)
  (testing "map form marks the query active"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (h/process-event [:re-frame.query/mark-active {:query :books/list :params {}}])
    (let [qid (util/query-id :books/list {})]
      (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :active?])))
      (h/process-event [:re-frame.query/mark-inactive {:query :books/list :params {}}])
      (is (false? (get-in (h/app-db) [:re-frame.query/queries qid :active?]))
          "map-form mark-inactive flips it back")))

  (testing "flattened :polling-interval-ms and :sub-id behave like positional opts"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      (h/process-event [:re-frame.query/mark-active
                        {:query :books/list
                         :params {}
                         :sub-id :dashboard
                         :polling-interval-ms 5000}])
      (is (contains? (polling/active-polls) qid)
          "polling started from the flattened interval")
      (is (= 5000 (polling/current-interval qid)))
      (h/process-event [:re-frame.query/mark-active
                        {:query :books/list
                         :params {}
                         :sub-id :sidebar
                         :polling-interval-ms 1000}])
      (is (= 1000 (polling/current-interval qid))
          "lowest interval wins, as with positional opts")
      (is (= 2 (polling/subscriber-count qid))
          "flattened :sub-id registers independent subscribers")
      (h/process-event [:re-frame.query/mark-inactive
                        {:query :books/list :params {} :sub-id :sidebar}])
      (is (= 5000 (polling/current-interval qid))
          "removing one sub-id reverts to the other's interval")
      (h/process-event [:re-frame.query/mark-inactive
                        {:query :books/list :params {} :sub-id :dashboard}])
      (is (not (contains? (polling/active-polls) qid))
          "polling stops after all subscribers are removed"))))

(deftest map-form-rejects-query-hooks-test
  (rfq/set-default-effect-fn! h/noop-effect-fn)
  (testing "the map form of query events rejects mutation-only lifecycle hooks"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"does not accept"
         (h/process-event [:re-frame.query/ensure-query
                           {:query :books/list :params {} :on-success [:noop]}])))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"does not accept"
         (h/process-event [:re-frame.query/refetch-query
                           {:query :books/list :params {} :on-failure [:noop]}]))))

  (testing "a params map without the :query wrapper is rejected"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"expects a keyword"
         (h/process-event [:re-frame.query/ensure-query {:page 1}])))))

(deftest nil-params-pass-through-test
  (testing "omitted/nil params reach query-fn as nil in every form, keyed under [k {}]"
    (rfq/set-default-effect-fn! h/noop-effect-fn)
    (let [seen (atom [])
          qid (util/query-id :books/list nil)]
      (rfq/reg-query :books/list {:query-fn (fn [p] (swap! seen conj p) {})})
      (doseq [event [[:re-frame.query/ensure-query :books/list]
                     [:re-frame.query/ensure-query :books/list nil]
                     [:re-frame.query/ensure-query {:query :books/list}]]]
        (reset! rf-db/app-db {})
        (h/process-event event)
        (is (= [qid] (keys (get (h/app-db) :re-frame.query/queries)))
            (str event " keys the cache under [k {}]")))
      (is (= [nil nil nil] @seen)
          "normalization never substitutes {} for the caller's nil"))))

;; ---------------------------------------------------------------------------
;; Positional ⇔ map form equivalence across every public event
;; ---------------------------------------------------------------------------

(defn- scrub
  "Strip the per-attempt stamp and wall-clock timestamp so two runs compare equal."
  [db]
  (walk/postwalk (fn [x] (if (map? x) (dissoc x :request-id :fetched-at) x)) db))

(defn- register-equivalence-fixtures! []
  (rfq/set-default-effect-fn! h/noop-effect-fn)
  (rfq/reg-query :books/list {:query-fn (fn [_] {}) :tags (fn [_] [[:books]])})
  (rfq/reg-query :feed/items
    {:query-fn (fn [_] {})
     :infinite {:initial-cursor 0
                :get-next-cursor :next
                :get-previous-cursor :prev}})
  (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})}))

(defn- seed-books! []
  (rf/dispatch [:re-frame.query/query-success :books/list {} [{:id 1}]]))

(defn- seed-feed-page! []
  (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} nil
                {:items [{:id 1}] :next 10 :prev -10}]))

(def ^:private equivalence-cases
  "[event-id positional-args map-payload seed-fn] — one row per public event.
   `seed-fn` (may be nil) establishes whatever precondition the event needs."
  [[:re-frame.query/ensure-query
    [:books/list {}] {:query :books/list :params {}} nil]
   [:re-frame.query/refetch-query
    [:books/list {}] {:query :books/list :params {}} seed-books!]
   [:re-frame.query/cancel-query
    [:books/list {}] {:query :books/list :params {}}
    #(rf/dispatch [:re-frame.query/ensure-query :books/list {}])]
   [:re-frame.query/set-query-data
    [:books/list {} [{:id 2}]] {:query :books/list :params {} :data [{:id 2}]} nil]
   [:re-frame.query/invalidate-tags
    [[[:books]]] {:tags [[:books]]}
    #(do (seed-books!) (rf/dispatch [:re-frame.query/mark-active :books/list {}]))]
   [:re-frame.query/mark-active
    [:books/list {} {:sub-id :a}] {:query :books/list :params {} :sub-id :a} seed-books!]
   [:re-frame.query/mark-inactive
    [:books/list {} {:sub-id :a}] {:query :books/list :params {} :sub-id :a}
    #(do (seed-books!) (rf/dispatch [:re-frame.query/mark-active :books/list {} {:sub-id :a}]))]
   [:re-frame.query/ensure-infinite-query
    [:feed/items {}] {:query :feed/items :params {}} nil]
   [:re-frame.query/fetch-next-page
    [:feed/items {}] {:query :feed/items :params {}} seed-feed-page!]
   [:re-frame.query/fetch-previous-page
    [:feed/items {}] {:query :feed/items :params {}} seed-feed-page!]
   [:re-frame.query/refetch-infinite-query
    [:feed/items {}] {:query :feed/items :params {}} seed-feed-page!]
   [:re-frame.query/execute-mutation
    [:books/create {:title "Dune"} {:on-success [[:test/hook]]}]
    {:mutation :books/create :params {:title "Dune"} :on-success [[:test/hook]]} nil]
   [:re-frame.query/reset-mutation
    [:books/create {:title "Dune"}] {:mutation :books/create :params {:title "Dune"}}
    #(rf/dispatch [:re-frame.query/execute-mutation :books/create {:title "Dune"}])]])

(defn- run-and-snapshot
  "Fresh db + registry, seed, dispatch `event`, return the scrubbed app-db."
  [seed-fn event]
  (h/reset-db!)
  (register-equivalence-fixtures!)
  (when seed-fn (seed-fn))
  (rf/dispatch event)
  (scrub (h/app-db)))

(deftest positional-and-map-forms-are-equivalent-test
  (rf-test/run-test-sync
   (doseq [[event-id positional payload seed-fn] equivalence-cases]
     (testing (str event-id)
       (is (= (run-and-snapshot seed-fn (into [event-id] positional))
              (run-and-snapshot seed-fn [event-id payload]))
           "both forms leave app-db in the same state (modulo :request-id/:fetched-at)")))))

;; ---------------------------------------------------------------------------
;; Public helper fns — map arities
;; ---------------------------------------------------------------------------

(deftest helper-fns-map-arity-test
  (testing "prefetch map arity ensures the query"
    (rf-test/run-test-sync
     (rfq/set-default-effect-fn! h/noop-effect-fn)
     (rfq/reg-query :books/list {:query-fn (fn [_] {})})
     (rfq/prefetch {:query :books/list :params {:page 1}})
     (let [qid (util/query-id :books/list {:page 1})]
       (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))
           "prefetch map arity started the fetch"))))

  (testing "set-query-data and cancel-query map arities match their event dispatches"
    (rf-test/run-test-sync
     (rfq/set-default-effect-fn! h/noop-effect-fn)
     (rfq/reg-query :books/list {:query-fn (fn [_] {})})
     (rfq/set-query-data {:query :books/list :params {} :data [{:id 1}]})
     (let [qid (util/query-id :books/list {})]
       (is (= [{:id 1}] (get-in (h/app-db) [:re-frame.query/queries qid :data]))
           "set-query-data map arity wrote the cache entry")
       (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :stale?]))
           "seeded data is marked stale, same as the event")
       (rf/dispatch [:re-frame.query/ensure-query {:query :books/list :params {}}])
       (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))
           "stale entry background-refetches on ensure")
       (rfq/cancel-query {:query :books/list :params {}})
       (is (false? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))
           "cancel-query map arity superseded the in-flight attempt"))))

  (testing "positional helper arities still delegate unchanged"
    (rf-test/run-test-sync
     (rfq/set-default-effect-fn! h/noop-effect-fn)
     (rfq/reg-query :books/list {:query-fn (fn [_] {})})
     (rfq/prefetch :books/list {:page 2})
     (let [qid (util/query-id :books/list {:page 2})]
       (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))
           "positional prefetch behaves as before")))))
