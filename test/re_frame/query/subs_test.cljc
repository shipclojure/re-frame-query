(ns re-frame.query.subs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [re-frame.query.subs :as subs]
   [re-frame.query.util :as util]))

;; ---------------------------------------------------------------------------
;; resolve-query tests
;; ---------------------------------------------------------------------------

(deftest resolve-query-test
  (testing "returns idle-state when query does not exist"
    (let [queries {}
          qid     (util/query-id :books/list {})]
      (is (= {:status :idle :data nil :error nil :fetching? false :stale? true}
             (#'subs/resolve-query queries qid)))))

  (testing "returns idle-state for a qid not in the queries map"
    (let [qid     (util/query-id :nonexistent/query {:id 99})
          queries {(util/query-id :other/query {}) {:status :success :data [1]}}]
      (is (= :idle (:status (#'subs/resolve-query queries qid))))))

  (testing "returns query with computed stale? false when fresh"
    (let [now     (util/now-ms)
          qid     (util/query-id :books/list {})
          queries {qid {:status        :success
                        :data          [{:id 1}]
                        :error         nil
                        :fetching?     false
                        :fetched-at    now
                        :stale-time-ms 30000
                        :stale?        false}}
          result  (#'subs/resolve-query queries qid)]
      (is (= :success (:status result)))
      (is (= [{:id 1}] (:data result)))
      (is (false? (:stale? result)))))

  (testing "returns query with stale? true when stale-time-ms has elapsed"
    (let [qid     (util/query-id :books/list {})
          queries {qid {:status        :success
                        :data          [{:id 1}]
                        :error         nil
                        :fetching?     false
                        :fetched-at    0
                        :stale-time-ms 1000
                        :stale?        false}}
          result  (#'subs/resolve-query queries qid)]
      (is (= :success (:status result)))
      (is (true? (:stale? result)))))

  (testing "returns query with stale? true when explicitly marked stale"
    (let [qid     (util/query-id :books/list {})
          queries {qid {:status        :success
                        :data          [{:id 1}]
                        :fetching?     false
                        :fetched-at    (util/now-ms)
                        :stale-time-ms 999999
                        :stale?        true}}
          result  (#'subs/resolve-query queries qid)]
      (is (true? (:stale? result))))))

;; ---------------------------------------------------------------------------
;; resolve-infinite-query tests
;; ---------------------------------------------------------------------------

(deftest resolve-infinite-query-test
  (testing "returns idle-infinite-state when query does not exist"
    (let [queries {}
          qid     (util/query-id :feed/items {})
          result  (#'subs/resolve-infinite-query queries qid)]
      (is (= :idle (:status result)))
      (is (= {:pages [] :page-params [] :has-next? false} (:data result)))
      (is (false? (:fetching? result)))
      (is (false? (:fetching-next? result)))
      (is (true? (:stale? result)))))

  (testing "returns idle-infinite-state for a qid not in the queries map"
    (let [qid     (util/query-id :nonexistent/feed {:user "bob"})
          queries {(util/query-id :other/query {}) {:status :success}}]
      (is (= :idle (:status (#'subs/resolve-infinite-query queries qid))))))

  (testing "returns infinite query data with stale? computed"
    (let [now     (util/now-ms)
          qid     (util/query-id :feed/items {})
          queries {qid {:status         :success
                        :data           {:pages       [{:items [{:id 1}]}]
                                         :page-params [0]
                                         :has-next?   true
                                         :next-cursor 10}
                        :error          nil
                        :fetching?      false
                        :fetching-next? false
                        :fetched-at     now
                        :stale-time-ms  30000
                        :stale?         false}}
          result  (#'subs/resolve-infinite-query queries qid)]
      (is (= :success (:status result)))
      (is (= [{:items [{:id 1}]}] (get-in result [:data :pages])))
      (is (true? (get-in result [:data :has-next?])))
      (is (false? (:stale? result)))))

  (testing "strips :refetch-state from result"
    (let [now     (util/now-ms)
          qid     (util/query-id :feed/items {})
          queries {qid {:status        :success
                        :data          {:pages [] :page-params [] :has-next? false}
                        :fetching?     true
                        :fetched-at    now
                        :stale-time-ms 30000
                        :refetch-state {:target-page-count 3
                                        :pages             []
                                        :page-params       []
                                        :current-cursor    0}}}
          result  (#'subs/resolve-infinite-query queries qid)]
      (is (nil? (:refetch-state result))
          "refetch-state is stripped from the subscription result")
      (is (= :success (:status result))))))
