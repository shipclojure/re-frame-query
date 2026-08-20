(ns re-frame.query.supersession-test
  "Per-attempt :request-id supersession — a response belonging to an attempt
   that a newer one replaced must be dropped instead of overwriting fresher
   cache state. Covers the regular track, the infinite track, and the
   metadata plumbing every effect adapter depends on."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [re-frame.query :as rfq]
   [re-frame.query.test-helpers :as h]
   [re-frame.query.util :as util]))

(use-fixtures :each {:before h/reset-db! :after h/reset-db!})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- capturing-effect-fn
  "Effect adapter that records one entry per attempt into `calls` and fires no
   transport effect. The callbacks it receives already carry the per-attempt
   request-control stamp, exactly as a real adapter receives them."
  [calls]
  (fn [request on-success on-failure]
    (swap! calls conj {:request request
                       :on-success on-success
                       :on-failure on-failure})
    {}))

(defn- attempt
  "The nth captured attempt, in dispatch order."
  [calls n]
  (nth @calls n))

(defn- deliver!
  "Deliver `payload` through a captured callback the way an adapter must —
   with `conj`, which preserves the request-control metadata."
  [callback payload]
  (h/process-event (conj callback payload)))

(defn- query-entry
  "The raw cache entry for `qid`, or nil when not cached."
  [qid]
  (get-in (h/app-db) [:re-frame.query/queries qid]))

(defn- callback-request-id
  "The request id stamped on a captured callback event."
  [callback]
  (:request-id (util/request-control callback)))

(defn- reg-patients!
  "Register a paginated regular query wired to a capturing adapter.
   Returns the atom collecting one entry per attempt."
  []
  (let [calls (atom [])]
    (rfq/set-default-effect-fn! (capturing-effect-fn calls))
    (rfq/reg-query :patients/page
      {:query-fn (fn [{:keys [page]}]
                   {:method :get :url (str "/api/patients?page=" page)})})
    calls))

(defn- reg-feed!
  "Register an infinite feed query wired to a capturing adapter.
   Returns the atom collecting one entry per attempt."
  []
  (let [calls (atom [])]
    (rfq/set-default-effect-fn! (capturing-effect-fn calls))
    (rfq/reg-query :feed/items
      {:query-fn (fn [{:keys [cursor]}]
                   {:method :get :url (str "/api/feed?cursor=" (or cursor ""))})
       :infinite {:initial-cursor nil
                  :get-next-cursor (fn [resp] (:next_cursor resp))}})
    calls))

(defn- feed-page
  "One infinite-query page response — a single item tagged `id`."
  [id next-cursor]
  {:items [{:id id}] :next_cursor next-cursor})

(defn- page-ids
  "The item id of every cached page of an infinite query, in order."
  [qid]
  (mapv #(-> % :items first :id)
        (get-in (query-entry qid) [:data :pages])))

;; ---------------------------------------------------------------------------
;; Regular queries
;; ---------------------------------------------------------------------------

(deftest late-success-from-superseded-attempt-is-dropped
  (testing "an older attempt's response cannot overwrite newer data"
    (let [calls (reg-patients!)
          qid (util/query-id :patients/page {:page 1})]
      ;; Two overlapping attempts — the second supersedes the first.
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      (is (= 2 (count @calls)) "precondition: two attempts are in flight")
      ;; The current attempt commits first...
      (deliver! (:on-success (attempt calls 1)) [{:id :fresh}])
      (is (= [{:id :fresh}] (:data (query-entry qid)))
          "the current attempt's data is stored")
      ;; ...and the superseded one lands afterwards.
      (deliver! (:on-success (attempt calls 0)) [{:id :stale}])
      (is (= [{:id :fresh}] (:data (query-entry qid)))
          "the superseded attempt's data is dropped, not written"))))

(deftest late-failure-from-superseded-attempt-cannot-flip-success
  (testing "a superseded failure leaves a newer success untouched"
    (let [calls (reg-patients!)
          qid (util/query-id :patients/page {:page 1})]
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      (is (= 2 (count @calls)) "precondition: two attempts are in flight")
      ;; The current attempt succeeds, then the superseded one errors out.
      (deliver! (:on-success (attempt calls 1)) [{:id :fresh}])
      (deliver! (:on-failure (attempt calls 0)) {:status 500})
      (let [query (query-entry qid)]
        (is (= :success (:status query))
            "a late failure must not flip a newer success to :error")
        (is (nil? (:error query))
            ":error is left alone — there is nothing to report")
        (is (= [{:id :fresh}] (:data query))
            "the committed data survives the late failure")))))

(deftest dropping-superseded-result-keeps-fetching-true
  (let [calls (reg-patients!)
        qid (util/query-id :patients/page {:page 1})]
    ;; Seed committed data so we can assert it stays untouched.
    (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
    (deliver! (:on-success (attempt calls 0)) [{:id :seed}])
    ;; Two more overlapping attempts; only the newer one is current.
    (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
    (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])

    (testing "dropping a superseded result leaves the in-flight state intact"
      ;; Regression guard for "the spinner turns off early": the current
      ;; attempt is still outstanding, so nothing may be cleared.
      (deliver! (:on-success (attempt calls 1)) [{:id :stale}])
      (let [query (query-entry qid)]
        (is (true? (:fetching? query))
            "fetching? stays true — the current attempt is still outstanding")
        (is (= :success (:status query))
            "status is untouched by the dropped result")
        (is (= [{:id :seed}] (:data query))
            "data is untouched by the dropped result")))

    (testing "the current attempt still commits after an older one was dropped"
      (deliver! (:on-success (attempt calls 2)) [{:id :fresh}])
      (let [query (query-entry qid)]
        (is (= [{:id :fresh}] (:data query))
            "commit order is independent of the drop")
        (is (false? (:fetching? query))
            "fetching? clears once the current attempt lands")))))

(deftest distinct-params-do-not-supersede-each-other
  (testing "each param identity keeps its own attempt and its own result"
    (let [calls (reg-patients!)
          qid-1 (util/query-id :patients/page {:page 1})
          qid-2 (util/query-id :patients/page {:page 2})]
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 2}])
      (is (= 2 (count @calls)) "precondition: one attempt per param identity")
      (is (true? (:fetching? (query-entry qid-1))))
      (is (true? (:fetching? (query-entry qid-2)))
          "starting page 2 does not supersede page 1")
      ;; Resolve out of order — neither may affect the other.
      (deliver! (:on-success (attempt calls 1)) [{:id :page-2}])
      (is (true? (:fetching? (query-entry qid-1)))
          "page 1 is still in flight after page 2 resolved")
      (deliver! (:on-success (attempt calls 0)) [{:id :page-1}])
      (is (= [{:id :page-1}] (:data (query-entry qid-1)))
          "page 1 caches its own result")
      (is (= [{:id :page-2}] (:data (query-entry qid-2)))
          "page 2 caches its own result"))))

(deftest unstamped-results-commit-fail-open
  (testing "an unstamped query-success still commits"
    (let [calls (reg-patients!)
          qid (util/query-id :patients/page {:page 1})]
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      (is (= 1 (count @calls)) "precondition: an attempt is in flight")
      ;; No metadata — an adapter that rebuilt the vector, or any of the
      ;; hand-dispatched events the rest of the suite uses.
      (h/process-event [:re-frame.query/query-success :patients/page {:page 1} [{:id :unstamped}]])
      (let [query (query-entry qid)]
        (is (= [{:id :unstamped}] (:data query))
            "a result with no request-control is committed, not swallowed")
        (is (false? (:fetching? query))))))

  (testing "an unstamped query-failure still commits"
    (let [calls (reg-patients!)
          qid (util/query-id :patients/page {:page 2})]
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 2}])
      (is (= 1 (count @calls)) "precondition: an attempt is in flight")
      (h/process-event [:re-frame.query/query-failure :patients/page {:page 2} {:status 500}])
      (let [query (query-entry qid)]
        (is (= :error (:status query))
            "an unstamped failure is committed, not swallowed")
        (is (= {:status 500} (:error query)))))))

(deftest reset-api-state-is-not-resurrected-by-a-stamped-response
  (testing "a stamped in-flight response does not recreate a wiped entry"
    (let [calls (reg-patients!)
          qid (util/query-id :patients/page {:page 1})]
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      (h/process-event [:re-frame.query/reset-api-state])
      (is (nil? (query-entry qid)) "precondition: the cache was wiped")
      (deliver! (:on-success (attempt calls 0)) [{:id :late}])
      (is (nil? (query-entry qid))
          "a stamped response cannot resurrect an entry that no longer exists")))

  (testing "an unstamped response still resurrects the entry (fail-open boundary)"
    (let [_calls (reg-patients!)
          qid (util/query-id :patients/page {:page 2})]
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 2}])
      (h/process-event [:re-frame.query/reset-api-state])
      (is (nil? (query-entry qid)) "precondition: the cache was wiped")
      (h/process-event [:re-frame.query/query-success :patients/page {:page 2} [{:id :late}]])
      (is (= [{:id :late}] (:data (query-entry qid)))
          "unstamped results fail open, so they do recreate the entry"))))

(deftest gc-evicted-entry-is-not-resurrected-by-a-stamped-response
  (testing "a stamped in-flight response does not recreate an evicted entry"
    (let [calls (reg-patients!)
          qid (util/query-id :patients/page {:page 1})]
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      ;; GC eviction — only fires while the query is inactive, which it is here.
      (h/process-event [:re-frame.query/remove-query qid])
      (is (nil? (query-entry qid)) "precondition: the entry was evicted")
      (deliver! (:on-success (attempt calls 0)) [{:id :late}])
      (is (nil? (query-entry qid))
          "a stamped response cannot resurrect a garbage-collected entry")))

  (testing "an unstamped response still resurrects the entry (fail-open boundary)"
    (let [_calls (reg-patients!)
          qid (util/query-id :patients/page {:page 2})]
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 2}])
      (h/process-event [:re-frame.query/remove-query qid])
      (is (nil? (query-entry qid)) "precondition: the entry was evicted")
      (h/process-event [:re-frame.query/query-success :patients/page {:page 2} [{:id :late}]])
      (is (= [{:id :late}] (:data (query-entry qid)))
          "unstamped results fail open, so they do recreate the entry"))))

(deftest cancel-query-supersedes-in-flight-request
  (testing "the in-flight response is dropped and :fetching? cleared"
    (let [calls (reg-patients!)
          qid (util/query-id :patients/page {:page 1})]
      ;; Seed committed data, then start a refetch we are going to cancel.
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      (deliver! (:on-success (attempt calls 0)) [{:id :seed}])
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      (is (true? (:fetching? (query-entry qid))) "precondition: fetch in flight")

      (h/process-event [:re-frame.query/cancel-query :patients/page {:page 1}])
      (let [query (query-entry qid)]
        (is (false? (:fetching? query)) "cancel clears fetching?")
        (is (= :success (:status query)) "cancel does not touch status")
        (is (= [{:id :seed}] (:data query)) "cancel does not touch data"))

      ;; The cancelled request still answers — its result must be dropped.
      (deliver! (:on-success (attempt calls 1)) [{:id :cancelled}])
      (let [query (query-entry qid)]
        (is (= [{:id :seed}] (:data query))
            "the cancelled attempt's data is dropped on arrival")
        (is (= :success (:status query)))
        (is (false? (:fetching? query))))))

  (testing "cancel-query is a no-op when the query is not cached"
    (reg-patients!)
    (let [qid (util/query-id :patients/page {:page 99})]
      (h/process-event [:re-frame.query/cancel-query :patients/page {:page 99}])
      (is (nil? (query-entry qid))
          "cancelling an uncached query does not create an entry"))))

(deftest cancel-query-allows-an-initial-request-to-retry
  (testing "cancelling the initial load leaves the query stale"
    (let [calls (reg-patients!)
          qid (util/query-id :patients/page {:page 1})]
      (h/process-event [:re-frame.query/ensure-query
                        :patients/page {:page 1}])
      (h/process-event [:re-frame.query/cancel-query
                        :patients/page {:page 1}])
      (let [query (query-entry qid)]
        (is (= :idle (:status query))
            "cancel reverts to :idle when there was no previous success")
        (is (false? (:fetching? query)))
        (is (true? (:stale? query))
            "a cancelled initial request must remain retryable"))

      (h/process-event [:re-frame.query/ensure-query
                        :patients/page {:page 1}])
      (is (= 2 (count @calls))
          "ensure-query retries after the initial request is cancelled")
      (deliver! (:on-success (attempt calls 0)) [{:id :cancelled}])
      (is (nil? (:data (query-entry qid)))
          "the cancelled initial response is still dropped")
      (deliver! (:on-success (attempt calls 1)) [{:id :fresh}])
      (is (= [{:id :fresh}] (:data (query-entry qid)))))))

(deftest cancel-query-only-clears-paging-flags-for-infinite-queries
  (testing "a cancelled regular query gains no infinite-only keys"
    (reg-patients!)
    (let [qid (util/query-id :patients/page {:page 1})]
      (h/process-event [:re-frame.query/refetch-query :patients/page {:page 1}])
      (h/process-event [:re-frame.query/cancel-query :patients/page {:page 1}])
      (let [query (query-entry qid)]
        (is (false? (:fetching? query)) "precondition: the entry was cancelled")
        (is (not (contains? query :fetching-next?))
            ":fetching-next? belongs to infinite queries only")
        (is (not (contains? query :fetching-prev?))
            ":fetching-prev? belongs to infinite queries only")
        (is (not (contains? query :refetch-state))
            ":refetch-state belongs to infinite queries only")
        ;; resolve-query only strips :request-id, so anything else written here
        ;; surfaces in every ::query / ::query-state subscriber's map.
        (is (not-any? (set (keys query))
                      [:fetching-next? :fetching-prev? :refetch-state])
            "no infinite-only key can leak into the regular query subscription"))))

  (testing "a cancelled infinite query has its paging flags cleared"
    (let [calls (reg-feed!)
          qid (util/query-id :feed/items {})]
      (h/process-event [:re-frame.query/ensure-infinite-query :feed/items {}])
      (deliver! (:on-success (attempt calls 0)) (feed-page :p1 "c1"))
      (h/process-event [:re-frame.query/refetch-infinite-query :feed/items {}])
      (is (some? (:refetch-state (query-entry qid)))
          "precondition: a refetch chain is in flight")

      (h/process-event [:re-frame.query/cancel-query :feed/items {}])
      (let [query (query-entry qid)]
        (is (false? (:fetching? query)) "cancel clears fetching?")
        (is (false? (:fetching-next? query)) "cancel clears fetching-next?")
        (is (false? (:fetching-prev? query)) "cancel clears fetching-prev?")
        (is (nil? (:refetch-state query)) "cancel abandons the refetch chain")
        (is (= [:p1] (page-ids qid)) "cancel does not touch loaded pages"))

      ;; The abandoned chain still answers — it must not resume or collapse data.
      (deliver! (:on-success (attempt calls 1)) (feed-page :ghost "c9"))
      (is (= [:p1] (page-ids qid))
          "the cancelled chain's page is dropped on arrival"))))

;; ---------------------------------------------------------------------------
;; Infinite queries
;; ---------------------------------------------------------------------------

(deftest overlapping-refetch-chains-do-not-scramble-refetch-state
  (testing "pages from a superseded chain never enter the surviving chain's accumulator"
    (let [calls (reg-feed!)
          qid (util/query-id :feed/items {})]
      ;; Load two pages.
      (h/process-event [:re-frame.query/ensure-infinite-query :feed/items {}])
      (deliver! (:on-success (attempt calls 0)) (feed-page :p1 "c1"))
      (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
      (deliver! (:on-success (attempt calls 1)) (feed-page :p2 "c2"))
      (is (= [:p1 :p2] (page-ids qid)) "precondition: two pages loaded")

      ;; Chain A starts, then chain B supersedes it before A's first page lands.
      (h/process-event [:re-frame.query/refetch-infinite-query :feed/items {}])
      (h/process-event [:re-frame.query/refetch-infinite-query :feed/items {}])
      (let [chain-a (attempt calls 2)
            chain-b (attempt calls 3)]
        (deliver! (:on-success chain-a) (feed-page :a1 "ca"))
        (is (= [] (get-in (query-entry qid) [:refetch-state :pages]))
            "the superseded chain's page does not accumulate into chain B")

        ;; Chain B runs to completion — its follow-up page is captured next.
        (deliver! (:on-success chain-b) (feed-page :b1 "cb"))
        (deliver! (:on-success (attempt calls 4)) (feed-page :b2 nil))
        (let [query (query-entry qid)]
          (is (= [:b1 :b2] (page-ids qid))
              "the surviving chain's pages are stored in order")
          (is (nil? (:refetch-state query)) "refetch-state is cleared on completion")
          (is (false? (:fetching? query))))))))

(deftest late-page-from-superseded-chain-does-not-collapse-data
  (testing "a superseded page arriving after refetch-state cleared keeps every page"
    (let [calls (reg-feed!)
          qid (util/query-id :feed/items {})]
      ;; Load two pages.
      (h/process-event [:re-frame.query/ensure-infinite-query :feed/items {}])
      (deliver! (:on-success (attempt calls 0)) (feed-page :p1 "c1"))
      (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
      (deliver! (:on-success (attempt calls 1)) (feed-page :p2 "c2"))
      (is (= [:p1 :p2] (page-ids qid)) "precondition: two pages loaded")

      ;; Chain A is superseded by chain B, which then finishes and clears
      ;; :refetch-state.
      (h/process-event [:re-frame.query/refetch-infinite-query :feed/items {}])
      (h/process-event [:re-frame.query/refetch-infinite-query :feed/items {}])
      (let [chain-a (attempt calls 2)
            chain-b (attempt calls 3)]
        (deliver! (:on-success chain-b) (feed-page :b1 "cb"))
        (deliver! (:on-success (attempt calls 4)) (feed-page :b2 nil))
        (is (nil? (:refetch-state (query-entry qid)))
            "precondition: the surviving chain finished and cleared refetch-state")

        ;; With no :refetch-state and mode nil, an unguarded page would take
        ;; the initial-load branch and replace :data with one single page.
        (deliver! (:on-success chain-a) (feed-page :a1 "ca"))
        (is (= 2 (count (page-ids qid)))
            "the late page must not collapse :data to a single page")
        (is (= [:b1 :b2] (page-ids qid))
            "the surviving chain's pages are still the cached ones")))))

(deftest refetch-infinite-query-supersedes-in-flight-append
  (testing "a superseded append does not land on top of the refetched pages"
    (let [calls (reg-feed!)
          qid (util/query-id :feed/items {})]
      (h/process-event [:re-frame.query/ensure-infinite-query :feed/items {}])
      (deliver! (:on-success (attempt calls 0)) (feed-page :p1 "c1"))
      ;; An append is in flight...
      (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
      (is (true? (:fetching-next? (query-entry qid)))
          "precondition: fetch-next-page is in flight")
      ;; ...when a full re-fetch bumps the request id.
      (h/process-event [:re-frame.query/refetch-infinite-query :feed/items {}])

      (deliver! (:on-success (attempt calls 1)) (feed-page :appended "c2"))
      (is (= [] (get-in (query-entry qid) [:refetch-state :pages]))
          "the superseded append does not enter the re-fetch accumulator")
      (is (= [:p1] (page-ids qid)) "the superseded append is not appended to :data")

      ;; The re-fetch chain completes — one page was the target.
      (deliver! (:on-success (attempt calls 2)) (feed-page :refetched nil))
      (is (= [:refetched] (page-ids qid))
          "only the refetched page is cached"))))

(deftest refetch-chain-pages-share-one-request-id
  (testing "a chain is one logical attempt, so it does not supersede itself"
    (let [calls (reg-feed!)
          qid (util/query-id :feed/items {})]
      ;; Load three pages.
      (h/process-event [:re-frame.query/ensure-infinite-query :feed/items {}])
      (deliver! (:on-success (attempt calls 0)) (feed-page :p1 "c1"))
      (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
      (deliver! (:on-success (attempt calls 1)) (feed-page :p2 "c2"))
      (h/process-event [:re-frame.query/fetch-next-page :feed/items {}])
      (deliver! (:on-success (attempt calls 2)) (feed-page :p3 "c3"))
      (is (= [:p1 :p2 :p3] (page-ids qid)) "precondition: three pages loaded")

      ;; One sequential re-fetch chain of three pages.
      (h/process-event [:re-frame.query/refetch-infinite-query :feed/items {}])
      (deliver! (:on-success (attempt calls 3)) (feed-page :r1 "x1"))
      (deliver! (:on-success (attempt calls 4)) (feed-page :r2 "x2"))
      (deliver! (:on-success (attempt calls 5)) (feed-page :r3 nil))

      (let [chain-ids (map #(callback-request-id (:on-success %)) (subvec @calls 3))]
        (is (= 1 (count (set chain-ids)))
            "every page of the chain carries the same request id"))
      (let [query (query-entry qid)]
        (is (= [:r1 :r2 :r3] (page-ids qid))
            "the chain completes and commits all of its pages in order")
        (is (nil? (:refetch-state query)))
        (is (false? (:fetching? query)))))))

;; ---------------------------------------------------------------------------
;; Metadata plumbing
;; ---------------------------------------------------------------------------

(deftest request-control-survives-conj-but-not-a-rebuild
  (let [control {:query-id (util/query-id :patients/page {:page 1})
                 :request-id (util/gen-request-id)
                 :issued-at 0}
        on-success (util/with-request-control
                     [:re-frame.query/query-success :patients/page {:page 1}]
                     control)]
    (testing "conj round-trips the stamp — the contract every adapter depends on"
      (let [event (conj on-success [{:id 1}])]
        (is (= [:re-frame.query/query-success :patients/page {:page 1} [{:id 1}]]
               event)
            "the positional event vector is unchanged by the stamp")
        (is (= control (util/request-control event))
            "conj carries the request-control metadata through")))

    (testing "rebuilding the vector loses the stamp (the documented footgun)"
      (is (nil? (util/request-control (vec (concat on-success [[{:id 1}]]))))
          "(vec (concat ...)) strips the metadata, disabling supersession"))))

(deftest parse-result-event-surfaces-request-control
  (let [control {:query-id (util/query-id :patients/page {:page 1})
                 :request-id (util/gen-request-id)
                 :issued-at 0}
        on-success (util/with-request-control
                     [:re-frame.query/query-success :patients/page {:page 1}]
                     control)]
    (testing "parse-result-event exposes the stamp without reaching into metadata"
      (is (= {:event-id :re-frame.query/query-success
              :k :patients/page
              :params {:page 1}
              :request-control control
              :data [{:id 1}]}
             (rfq/parse-result-event (conj on-success [{:id 1}])))))

    (testing "an unstamped result event simply has no :request-control key"
      (let [parsed (rfq/parse-result-event
                    [:re-frame.query/query-success :patients/page {:page 1} [{:id 1}]])]
        (is (not (contains? parsed :request-control)))
        (is (= :patients/page (:k parsed)))))))
