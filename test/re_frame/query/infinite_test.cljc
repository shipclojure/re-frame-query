(ns re-frame.query.infinite-test
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
   [re-frame.query.test-helpers :as h]
   #?(:cljs [reagent.ratom :as ratom])))

(use-fixtures :each {:before h/reset-db! :after h/reset-db!})

;; ---------------------------------------------------------------------------
;; Infinite Query helpers
;; ---------------------------------------------------------------------------

(defn reg-infinite-feed!
  "Helper: register a standard infinite feed query for testing."
  ([] (reg-infinite-feed! {}))
  ([extra-config]
   (rfq/set-default-effect-fn! h/noop-effect-fn)
   (rfq/reg-query :feed/items
     (merge
      {:query-fn (fn [{:keys [cursor]}]
                   {:method :get :url (str "/api/feed?cursor=" (or cursor ""))})
       :infinite {:initial-cursor nil
                  :get-next-cursor (fn [resp] (:next_cursor resp))}
       :tags (constantly [[:feed]])}
      extra-config))))

;; ---------------------------------------------------------------------------
;; Infinite Query tests
;; ---------------------------------------------------------------------------

(deftest ensure-infinite-query-test
  (testing "sets status to :loading for first page when no data exists"
    (reg-infinite-feed!)
    (h/process-event [:re-frame.query/ensure-infinite-query :feed/items {}])
    (let [qid (util/query-id :feed/items {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query)))
      (is (true? (:fetching? query)))
      (is (false? (:fetching-next? query)))
      (is (= {:pages [] :page-params [] :has-next? false}
             (:data query)))))

  (testing "keeps :success status when stale data exists (background refetch)"
    (reg-infinite-feed! {:stale-time-ms 1000})
    (let [qid (util/query-id :feed/items {})]
      ;; Simulate first page loaded
      (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                        {:items [{:id 1}] :next_cursor "abc"}])
      ;; Make it stale
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
      (with-redefs [util/now-ms (constantly 1001)]
        (h/process-event [:re-frame.query/ensure-infinite-query :feed/items {}]))
      (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
        (is (= :success (:status query))
            "status stays :success during background refetch")
        (is (true? (:fetching? query)))))))

(deftest infinite-page-success-first-page-test
  (testing "first page stores data correctly"
    (reg-infinite-feed!)
    (h/process-event [:re-frame.query/ensure-infinite-query :feed/items {}])
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                      {:items [{:id 1} {:id 2}] :next_cursor "abc"}])
    (let [qid (util/query-id :feed/items {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])
          data (:data query)]
      (is (= :success (:status query)))
      (is (false? (:fetching? query)))
      (is (false? (:fetching-next? query)))
      (is (= [{:items [{:id 1} {:id 2}] :next_cursor "abc"}] (:pages data)))
      (is (= [nil] (:page-params data)))
      (is (true? (:has-next? data)))
      (is (= "abc" (:next-cursor data)))))

  (testing "last page sets has-next? to false when get-next-cursor returns nil"
    (reg-infinite-feed!)
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                      {:items [{:id 1}] :next_cursor nil}])
    (let [qid (util/query-id :feed/items {})
          data (get-in (h/app-db) [:re-frame.query/queries qid :data])]
      (is (false? (:has-next? data))))))

(deftest fetch-next-page-test
  (testing "appends second page to existing data"
    (reg-infinite-feed!)
    ;; Load first page
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                      {:items [{:id 1}] :next_cursor "abc"}])
    ;; Fetch next page
    (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
    (let [qid (util/query-id :feed/items {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (true? (:fetching-next? query))
          "fetching-next? is true while fetching"))
    ;; Page 2 arrives
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} :append
                      {:items [{:id 2}] :next_cursor "def"}])
    (let [qid (util/query-id :feed/items {})
          data (get-in (h/app-db) [:re-frame.query/queries qid :data])]
      (is (= 2 (count (:pages data))))
      (is (= [nil "abc"] (:page-params data)))
      (is (= "def" (:next-cursor data)))
      (is (true? (:has-next? data)))))

  (testing "no-op when has-next? is false"
    (reg-infinite-feed!)
    ;; Load a page with no next cursor
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                      {:items [{:id 1}] :next_cursor nil}])
    (let [qid (util/query-id :feed/items {})
          before (get-in (h/app-db) [:re-frame.query/queries qid])]
      (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
      (let [after (get-in (h/app-db) [:re-frame.query/queries qid])]
        (is (= (:data before) (:data after))
            "data unchanged when no next page"))))

  (testing "no-op when already fetching"
    (reg-infinite-feed!)
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                      {:items [{:id 1}] :next_cursor "abc"}])
    ;; First fetch-next-page
    (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
    (let [qid (util/query-id :feed/items {})]
      (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching-next?])))
      ;; Second fetch-next-page should be a no-op (already fetching)
      (let [before (get-in (h/app-db) [:re-frame.query/queries qid])]
        (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
        (is (= before (get-in (h/app-db) [:re-frame.query/queries qid]))
            "no change when already fetching next page")))))

(deftest infinite-query-max-pages-test
  (testing "max-pages drops oldest pages (sliding window)"
    (reg-infinite-feed! {:max-pages 2})
    ;; Load 3 pages
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                      {:items [{:id 1}] :next_cursor "abc"}])
    (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} :append
                      {:items [{:id 2}] :next_cursor "def"}])
    (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} :append
                      {:items [{:id 3}] :next_cursor "ghi"}])
    (let [qid (util/query-id :feed/items {})
          data (get-in (h/app-db) [:re-frame.query/queries qid :data])]
      (is (= 2 (count (:pages data)))
          "only 2 pages kept")
      (is (= [{:items [{:id 2}] :next_cursor "def"}
              {:items [{:id 3}] :next_cursor "ghi"}]
             (:pages data))
          "oldest page was dropped")
      (is (= ["abc" "def"] (:page-params data))
          "oldest page-param was dropped"))))

(deftest infinite-query-sequential-refetch-test
  (testing "invalidation triggers sequential re-fetch with atomic swap"
    (rf-test/run-test-sync
     (reg-infinite-feed!)
      ;; Load 2 pages
     (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} nil
                   {:items [{:id 1}] :next_cursor "abc"}])
     (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} :append
                   {:items [{:id 2}] :next_cursor "def"}])
      ;; Store snapshot of data before refetch
     (let [qid (util/query-id :feed/items {})
           old-data (get-in (h/app-db) [:re-frame.query/queries qid :data])]
       (is (= 2 (count (:pages old-data))))
        ;; Mark active so invalidation triggers refetch
       (rf/dispatch [:re-frame.query/mark-active :feed/items {}])
        ;; Start sequential re-fetch
       (rf/dispatch [:re-frame.query/refetch-infinite-query :feed/items {}])
       (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
         (is (true? (:fetching? query)))
         (is (some? (:refetch-state query))
             "refetch-state is set during sequential re-fetch")
         (is (= 2 (get-in query [:refetch-state :target-page-count]))
             "target is 2 pages"))
        ;; Page 1 arrives with fresh data
       (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} nil
                     {:items [{:id 10}] :next_cursor "xyz"}])
        ;; Still fetching — page 1 accumulated in refetch-state, not in :data yet
       (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
         (is (true? (:fetching? query))
             "still fetching after page 1")
         (is (= 1 (count (get-in query [:refetch-state :pages])))
             "one page accumulated in refetch-state"))
        ;; Page 2 arrives — re-fetch complete
       (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} nil
                     {:items [{:id 11}] :next_cursor "zzz"}])
       (let [query (get-in (h/app-db) [:re-frame.query/queries qid])
             data (:data query)]
         (is (false? (:fetching? query))
             "fetching complete")
         (is (nil? (:refetch-state query))
             "refetch-state cleared after completion")
         (is (= 2 (count (:pages data)))
             "2 fresh pages in data")
         (is (= [{:items [{:id 10}] :next_cursor "xyz"}
                 {:items [{:id 11}] :next_cursor "zzz"}]
                (:pages data))
             "data contains fresh pages, not old ones")
         (is (= [nil "xyz"] (:page-params data))
             "page-params use fresh cursors"))))))

(deftest infinite-query-refetch-error-preserves-old-data-test
  (testing "error during sequential re-fetch preserves old data"
    (rf-test/run-test-sync
     (reg-infinite-feed!)
      ;; Load 2 pages
     (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} nil
                   {:items [{:id 1}] :next_cursor "abc"}])
     (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} :append
                   {:items [{:id 2}] :next_cursor "def"}])
     (let [qid (util/query-id :feed/items {})
           old-data (get-in (h/app-db) [:re-frame.query/queries qid :data])]
        ;; Start re-fetch
       (rf/dispatch [:re-frame.query/refetch-infinite-query :feed/items {}])
        ;; Page 1 succeeds
       (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} nil
                     {:items [{:id 10}] :next_cursor "xyz"}])
        ;; Page 2 FAILS
       (rf/dispatch [:re-frame.query/infinite-page-failure :feed/items {} {:status 500}])
       (let [query (get-in (h/app-db) [:re-frame.query/queries qid])]
         (is (= :error (:status query)))
         (is (= {:status 500} (:error query)))
         (is (false? (:fetching? query)))
         (is (nil? (:refetch-state query))
             "refetch-state cleared on error")
         (is (= old-data (:data query))
             "old data preserved after failed re-fetch"))))))

(deftest infinite-query-refetch-stops-when-no-next-cursor-test
  (testing "sequential re-fetch stops early when server returns no next cursor"
    (rf-test/run-test-sync
     (reg-infinite-feed!)
      ;; Load 3 pages
     (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} nil
                   {:items [{:id 1}] :next_cursor "abc"}])
     (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} :append
                   {:items [{:id 2}] :next_cursor "def"}])
     (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} :append
                   {:items [{:id 3}] :next_cursor "ghi"}])
     (let [qid (util/query-id :feed/items {})]
        ;; Start re-fetch (target: 3 pages)
       (rf/dispatch [:re-frame.query/refetch-infinite-query :feed/items {}])
        ;; Page 1 succeeds
       (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} nil
                     {:items [{:id 10}] :next_cursor "xyz"}])
        ;; Page 2 succeeds but with no next cursor (server has fewer items now)
       (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} nil
                     {:items [{:id 11}] :next_cursor nil}])
       (let [query (get-in (h/app-db) [:re-frame.query/queries qid])
             data (:data query)]
         (is (false? (:fetching? query))
             "fetching complete — stopped early due to no next cursor")
         (is (nil? (:refetch-state query)))
         (is (= 2 (count (:pages data)))
             "only 2 pages — server had fewer items after mutation")
         (is (false? (:has-next? data))))))))

(deftest fetch-next-page-during-refetch-is-noop
  (testing "fetch-next-page is a no-op when a sequential refetch is in progress"
    (reg-infinite-feed!)
    ;; Load 2 pages, start refetch
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                      {:items [{:id 1}] :next_cursor "abc"}])
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} :append
                      {:items [{:id 2}] :next_cursor "def"}])
    (h/process-event [:re-frame.query/refetch-infinite-query :feed/items {}])
    (let [qid (util/query-id :feed/items {})
          before (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (some? (:refetch-state before)))
      ;; fetch-next-page during refetch — should be a no-op
      (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
      (is (= before (get-in (h/app-db) [:re-frame.query/queries qid]))
          "no change during active refetch"))))

(deftest set-query-data-on-infinite-query-test
  (testing "set-query-data replaces infinite query data structure"
    (reg-infinite-feed!)
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                      {:items [{:id 1}] :next_cursor "abc"}])
    (let [qid (util/query-id :feed/items {})
          new-data {:pages [{:items [{:id 99}] :next_cursor "zzz"}]
                    :page-params [nil]
                    :has-next? true
                    :next-cursor "zzz"}]
      (h/process-event [:re-frame.query/set-query-data :feed/items {} new-data])
      (is (= new-data (get-in (h/app-db) [:re-frame.query/queries qid :data]))))))

(deftest infinite-query-transform-response-test
  (testing "transform-response is applied per page"
    (rfq/set-default-effect-fn! h/noop-effect-fn)
    (rfq/reg-query :feed/items
      {:query-fn (fn [_] {:url "/api/feed"})
       :infinite {:initial-cursor nil
                  :get-next-cursor (fn [resp] (:next_cursor resp))}
       :transform-response (fn [page _params]
                             (update page :items
                                     (fn [items] (mapv #(assoc % :transformed? true) items))))})
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                      {:items [{:id 1}] :next_cursor "abc"}])
    (let [qid (util/query-id :feed/items {})
          page (first (get-in (h/app-db) [:re-frame.query/queries qid :data :pages]))]
      (is (true? (get-in page [:items 0 :transformed?]))
          "transform-response applied to page data"))))

(deftest infinite-query-invalidate-tags-routes-correctly-test
  (testing "invalidate-tags routes infinite queries through refetch-infinite-query"
    (rf-test/run-test-sync
     (reg-infinite-feed!)
      ;; Load a page
     (rf/dispatch [:re-frame.query/infinite-page-success :feed/items {} nil
                   {:items [{:id 1}] :next_cursor "abc"}])
      ;; Mark active
     (rf/dispatch [:re-frame.query/mark-active :feed/items {}])
      ;; Invalidate
     (rf/dispatch [:re-frame.query/invalidate-tags [[:feed]]])
     (let [qid (util/query-id :feed/items {})
           query (get-in (h/app-db) [:re-frame.query/queries qid])]
       (is (true? (:fetching? query))
           "infinite query is being refetched after invalidation")
       (is (some? (:refetch-state query))
           "refetch-state present — using sequential re-fetch")))))

(deftest infinite-query-gc-test
  (testing "inactive infinite query is garbage collected"
    (reg-infinite-feed! {:cache-time-ms 5000})
    ;; Load a page
    (h/process-event [:re-frame.query/infinite-page-success :feed/items {} nil
                      {:items [{:id 1}] :next_cursor "abc"}])
    (let [qid (util/query-id :feed/items {})]
      ;; Mark inactive
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :active?] false)
      ;; Remove query (simulating GC timer firing)
      (h/process-event [:re-frame.query/remove-query qid])
      (is (nil? (get-in (h/app-db) [:re-frame.query/queries qid]))
          "infinite query removed by GC"))))
