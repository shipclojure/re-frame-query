(ns re-frame.query.subs-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [re-frame.core :as rf]
   [re-frame.query :as rfq]
   [re-frame.query.subs :as subs]
   [re-frame.query.test-helpers :as h]
   [re-frame.query.util :as util]
   [day8.re-frame.test :as rf-test]))

(use-fixtures :each {:before h/reset-db! :after h/reset-db!})

;; ---------------------------------------------------------------------------
;; resolve-query tests
;; ---------------------------------------------------------------------------

(deftest resolve-query-test
  (testing "returns idle-state when query does not exist"
    (let [queries {}
          qid (util/query-id :books/list {})]
      (is (= {:status :idle :data nil :error nil :fetching? false :stale? true}
             (#'subs/resolve-query queries qid)))))

  (testing "returns idle-state for a qid not in the queries map"
    (let [qid (util/query-id :nonexistent/query {:id 99})
          queries {(util/query-id :other/query {}) {:status :success :data [1]}}]
      (is (= :idle (:status (#'subs/resolve-query queries qid))))))

  (testing "returns query with computed stale? false when fresh"
    (let [now (util/now-ms)
          qid (util/query-id :books/list {})
          queries {qid {:status :success
                        :data [{:id 1}]
                        :error nil
                        :fetching? false
                        :fetched-at now
                        :stale-time-ms 30000
                        :stale? false}}
          result (#'subs/resolve-query queries qid)]
      (is (= :success (:status result)))
      (is (= [{:id 1}] (:data result)))
      (is (false? (:stale? result)))))

  (testing "returns query with stale? true when stale-time-ms has elapsed"
    (let [qid (util/query-id :books/list {})
          queries {qid {:status :success
                        :data [{:id 1}]
                        :error nil
                        :fetching? false
                        :fetched-at 0
                        :stale-time-ms 1000
                        :stale? false}}
          result (#'subs/resolve-query queries qid)]
      (is (= :success (:status result)))
      (is (true? (:stale? result)))))

  (testing "returns query with stale? true when explicitly marked stale"
    (let [qid (util/query-id :books/list {})
          queries {qid {:status :success
                        :data [{:id 1}]
                        :fetching? false
                        :fetched-at (util/now-ms)
                        :stale-time-ms 999999
                        :stale? true}}
          result (#'subs/resolve-query queries qid)]
      (is (true? (:stale? result))))))

;; ---------------------------------------------------------------------------
;; resolve-infinite-query tests
;; ---------------------------------------------------------------------------

(deftest resolve-infinite-query-test
  (testing "returns idle-infinite-state when query does not exist"
    (let [queries {}
          qid (util/query-id :feed/items {})
          result (#'subs/resolve-infinite-query queries qid)]
      (is (= :idle (:status result)))
      (is (= {:pages [] :page-params [] :has-next? false :has-prev? false} (:data result)))
      (is (false? (:fetching? result)))
      (is (false? (:fetching-next? result)))
      (is (false? (:fetching-prev? result)))
      (is (true? (:stale? result)))))

  (testing "returns idle-infinite-state for a qid not in the queries map"
    (let [qid (util/query-id :nonexistent/feed {:user "bob"})
          queries {(util/query-id :other/query {}) {:status :success}}]
      (is (= :idle (:status (#'subs/resolve-infinite-query queries qid))))))

  (testing "returns infinite query data with stale? computed"
    (let [now (util/now-ms)
          qid (util/query-id :feed/items {})
          queries {qid {:status :success
                        :data {:pages [{:items [{:id 1}]}]
                               :page-params [0]
                               :has-next? true
                               :next-cursor 10}
                        :error nil
                        :fetching? false
                        :fetching-next? false
                        :fetched-at now
                        :stale-time-ms 30000
                        :stale? false}}
          result (#'subs/resolve-infinite-query queries qid)]
      (is (= :success (:status result)))
      (is (= [{:items [{:id 1}]}] (get-in result [:data :pages])))
      (is (true? (get-in result [:data :has-next?])))
      (is (false? (:stale? result)))))

  (testing "strips :refetch-state from result"
    (let [now (util/now-ms)
          qid (util/query-id :feed/items {})
          queries {qid {:status :success
                        :data {:pages [] :page-params [] :has-next? false :has-prev? false}
                        :fetching? true
                        :fetched-at now
                        :stale-time-ms 30000
                        :refetch-state {:target-page-count 3
                                        :pages []
                                        :page-params []
                                        :current-cursor 0}}}
          result (#'subs/resolve-infinite-query queries qid)]
      (is (nil? (:refetch-state result))
          "refetch-state is stripped from the subscription result")
      (is (= :success (:status result))))))

;; ---------------------------------------------------------------------------
;; Derived subscriptions (query-data, query-status, query-fetching?, query-error)
;; ---------------------------------------------------------------------------
;;
;; These derive from ::rfq/query-state (passive), NOT ::rfq/query (effectful).
;; Subscribing to them must NOT trigger a fetch or create a cache entry.

(deftest derived-subs-return-correct-fields-test
  (testing "query-data returns :data field"
    (rf-test/run-test-sync
     (rfq/reg-query :books/list {:query-fn (fn [_] {:method :get :url "/api/books"})})
     (rf/dispatch-sync [:re-frame.query/query-success :books/list {} [{:id 1 :title "Dune"}]])
     (let [sub @(rf/subscribe [:re-frame.query/query-data :books/list {}])]
       (is (= [{:id 1 :title "Dune"}] sub)))))

  (testing "query-status returns :status field"
    (rf-test/run-test-sync
     (rfq/reg-query :books/list {:query-fn (fn [_] {:method :get :url "/api/books"})})
     (rf/dispatch-sync [:re-frame.query/query-success :books/list {} [{:id 1}]])
     (let [sub @(rf/subscribe [:re-frame.query/query-status :books/list {}])]
       (is (= :success sub)))))

  (testing "query-fetching? returns :fetching? field"
    (rf-test/run-test-sync
     (rfq/reg-query :books/list {:query-fn (fn [_] {:method :get :url "/api/books"})})
     (rf/dispatch-sync [:re-frame.query/query-success :books/list {} []])
     (let [sub @(rf/subscribe [:re-frame.query/query-fetching? :books/list {}])]
       (is (false? sub)))))

  (testing "query-error returns :error field"
    (rf-test/run-test-sync
     (rfq/reg-query :books/list {:query-fn (fn [_] {:method :get :url "/api/books"})})
     (rf/dispatch-sync [:re-frame.query/query-failure :books/list {} {:status 500}])
     (let [sub @(rf/subscribe [:re-frame.query/query-error :books/list {}])]
       (is (= {:status 500} sub))))))

(deftest derived-subs-do-not-trigger-fetch-test
  (testing "subscribing to query-data does not create a cache entry or trigger a fetch"
    (rf-test/run-test-sync
     (rfq/set-default-effect-fn! h/noop-effect-fn)
     (rfq/reg-query :books/list {:query-fn (fn [_] {:method :get :url "/api/books"})})
     ;; Subscribe to derived sub only — no ::rfq/query subscription
     (let [sub @(rf/subscribe [:re-frame.query/query-data :books/list {}])
           queries (get (h/app-db) :re-frame.query/queries {})]
       (is (nil? sub) "returns nil when cache is empty")
       (is (empty? queries) "no cache entry was created"))))

  (testing "subscribing to query-status returns :idle without fetching"
    (rf-test/run-test-sync
     (rfq/set-default-effect-fn! h/noop-effect-fn)
     (rfq/reg-query :books/list {:query-fn (fn [_] {:method :get :url "/api/books"})})
     (let [sub @(rf/subscribe [:re-frame.query/query-status :books/list {}])
           queries (get (h/app-db) :re-frame.query/queries {})]
       (is (= :idle sub))
       (is (empty? queries) "no cache entry was created"))))

  (testing "subscribing to query-error returns nil without fetching"
    (rf-test/run-test-sync
     (rfq/set-default-effect-fn! h/noop-effect-fn)
     (rfq/reg-query :books/list {:query-fn (fn [_] {:method :get :url "/api/books"})})
     (let [sub @(rf/subscribe [:re-frame.query/query-error :books/list {}])
           queries (get (h/app-db) :re-frame.query/queries {})]
       (is (nil? sub))
       (is (empty? queries) "no cache entry was created"))))

  (testing "subscribing to query-fetching? returns false without fetching"
    (rf-test/run-test-sync
     (rfq/set-default-effect-fn! h/noop-effect-fn)
     (rfq/reg-query :books/list {:query-fn (fn [_] {:method :get :url "/api/books"})})
     (let [sub @(rf/subscribe [:re-frame.query/query-fetching? :books/list {}])
           queries (get (h/app-db) :re-frame.query/queries {})]
       (is (false? sub))
       (is (empty? queries) "no cache entry was created")))))

;; ---------------------------------------------------------------------------
;; ::rfq/infinite-query-data
;; ---------------------------------------------------------------------------

(deftest infinite-query-data-returns-data-field-test
  (testing "returns :data from the infinite query state"
    (rf-test/run-test-sync
     (rfq/reg-query :feed/items
       {:query-fn (fn [_] {:method :get :url "/api/feed"})
        :infinite {:initial-cursor 0 :get-next-cursor (fn [r] (:next r))}})
     (rf/dispatch-sync [:re-frame.query/infinite-page-success :feed/items {} nil
                        {:items [{:id 1}] :next 10}])
     (let [data @(rf/subscribe [:re-frame.query/infinite-query-data :feed/items {}])]
       (is (= [{:items [{:id 1}] :next 10}] (:pages data)))
       (is (true? (:has-next? data)))
       (is (false? (:has-prev? data))))))

  (testing "returns idle data shape when no pages loaded yet"
    (rf-test/run-test-sync
     (rfq/reg-query :feed/items
       {:query-fn (fn [_] {:method :get :url "/api/feed"})
        :infinite {:initial-cursor 0 :get-next-cursor (fn [r] (:next r))}})
     (let [data @(rf/subscribe [:re-frame.query/infinite-query-data :feed/items {}])]
       ;; idle-infinite-state includes has-prev? false as a default
       (is (= [] (:pages data)))
       (is (false? (:has-next? data)))
       (is (false? (:has-prev? data))))))

  (testing "returns has-prev? when :get-previous-cursor is configured"
    (rf-test/run-test-sync
     (rfq/reg-query :feed/items
       {:query-fn (fn [_] {:method :get :url "/api/feed"})
        :infinite {:initial-cursor 0
                   :get-next-cursor (fn [r] (:next r))
                   :get-previous-cursor (fn [r] (:prev r))}})
     (rf/dispatch-sync [:re-frame.query/infinite-page-success :feed/items {} nil
                        {:items [{:id 1}] :next 10 :prev nil}])
     (let [data @(rf/subscribe [:re-frame.query/infinite-query-data :feed/items {}])]
       (is (false? (:has-prev? data)))
       (is (true? (:has-next? data)))))))

(deftest infinite-query-data-does-not-trigger-fetch-test
  (testing "subscribing does not create a cache entry"
    (rf-test/run-test-sync
     (rfq/set-default-effect-fn! h/noop-effect-fn)
     (rfq/reg-query :feed/items
       {:query-fn (fn [_] {:method :get :url "/api/feed"})
        :infinite {:initial-cursor 0 :get-next-cursor (fn [r] (:next r))}})
     (let [_ @(rf/subscribe [:re-frame.query/infinite-query-data :feed/items {}])
           queries (get (h/app-db) :re-frame.query/queries {})]
       (is (empty? queries) "no cache entry was created")))))

