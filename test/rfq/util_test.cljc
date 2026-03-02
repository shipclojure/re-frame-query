(ns rfq.util-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rfq.util :as util]))

(deftest query-id-test
  (testing "creates a tuple of key and params"
    (is (= [:todos/list {:user-id 42}]
           (util/query-id :todos/list {:user-id 42}))))

  (testing "nil params default to empty map"
    (is (= [:todos/list {}]
           (util/query-id :todos/list nil)))))

(deftest stale?-test
  (testing "nil query is stale"
    (is (true? (util/stale? nil 1000))))

  (testing "explicitly stale query"
    (is (true? (util/stale? {:stale? true :fetched-at 999} 1000))))

  (testing "query within stale-time is not stale"
    (is (false? (util/stale?
                  {:stale?        false
                   :stale-time-ms 30000
                   :fetched-at    990}
                  1000))))

  (testing "query past stale-time is stale"
    (is (true? (util/stale?
                 {:stale?        false
                  :stale-time-ms 30000
                  :fetched-at    100}
                 100000))))

  (testing "query without stale-time-ms and not marked stale is not stale"
    (is (false? (util/stale?
                  {:stale?     false
                   :fetched-at 100}
                  100000))))

  (testing "errored query is stale"
    (is (true? (util/stale?
                 {:stale?  false
                  :status  :error
                  :error   {:status 500}}
                 1000))))

  (testing "successful query is not stale"
    (is (false? (util/stale?
                  {:stale?        false
                   :status        :success
                   :fetched-at    990
                   :stale-time-ms 30000}
                  1000)))))

(deftest tag-match?-test
  (testing "matching tag"
    (is (true? (util/tag-match?
                 #{[:todos :user 42] [:todos :all]}
                 [[:todos :user 42]]))))

  (testing "no matching tag"
    (is (false? (util/tag-match?
                  #{[:todos :user 42]}
                  [[:todos :user 99]]))))

  (testing "empty query tags"
    (is (false? (util/tag-match? #{} [[:todos :all]]))))

  (testing "empty invalidation tags"
    (is (false? (util/tag-match? #{[:todos :all]} []))))

  (testing "nil tags"
    (is (false? (util/tag-match? nil [[:todos :all]])))
    (is (false? (util/tag-match? #{[:todos :all]} nil)))))
