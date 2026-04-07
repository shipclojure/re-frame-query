(ns example.reagent-app.views.optimistic
  "Optimistic updates demo: toggle a todo checkbox instantly,
   rollback on server failure. Uses mutation lifecycle hooks
   + set-query-data to patch/restore the cache."
  (:require
   [re-frame.core :as rf]
   [re-frame.query :as rfq]))

;; ---------------------------------------------------------------------------
;; Hook event handlers (pure re-frame, no library magic)
;; ---------------------------------------------------------------------------

(rf/reg-event-fx :todos/optimistic-toggle
  (fn [{:keys [db]} [_ {:keys [id done]}]]
    (let [qid [:todos/list {}]
          old (get-in db [::rfq/queries qid :data])
          new (mapv #(if (= (:id %) id) (assoc % :done done) %) old)]
      {:db (assoc-in db [:snapshots qid] old)
       :abort-request qid                                        ;; cancel in-flight refetch
       :dispatch [::rfq/set-query-data :todos/list {} new]})))

(rf/reg-event-fx :todos/rollback
  (fn [{:keys [db]} [_ _params _error]]
    (let [qid [:todos/list {}]
          old (get-in db [:snapshots qid])]
      {:db (update db :snapshots dissoc qid)
       :dispatch [::rfq/set-query-data :todos/list {} old]})))

(rf/reg-event-db :todos/clear-snapshot
  (fn [db [_ _params _data]]
    (update db :snapshots dissoc [:todos/list {}])))

;; ---------------------------------------------------------------------------
;; Views
;; ---------------------------------------------------------------------------

(defn todo-item [{:keys [id text done]} fail-mode?]
  [:div {:style {:display "flex" :align-items "center" :gap "0.75rem"
                 :padding "0.5rem 0" :border-bottom "1px solid #eee"}}
   [:input {:type "checkbox"
            :checked done
            :on-change (fn [_]
                         (rf/dispatch
                          [::rfq/execute-mutation :todos/toggle
                           {:id id :done (not done) :fail-mode? fail-mode?}
                           {:on-start [[:todos/optimistic-toggle]]
                            :on-success [[:todos/clear-snapshot]]
                            :on-failure [[:todos/rollback]]}]))}]
   [:span {:style (cond-> {:font-size "0.95rem"}
                    done (assoc :text-decoration "line-through"
                                :color "#999"))}
    text]])

(defn panel []
  (let [{:keys [status data]}
        @(rf/subscribe [::rfq/query :todos/list {}])
        fail-mode? @(rf/subscribe [:ui/get :todos/fail-mode?])]
    [:div
     [:p {:style {:color "#666" :margin-bottom "1rem"}}
      "Toggle a todo checkbox — it updates " [:strong "instantly"] " via "
      [:code "set-query-data"] " before the server responds. "
      "Enable Fail Mode to see " [:strong "rollback"] " when the server rejects (50% chance)."]

     [:div {:style {:margin-bottom "1rem"}}
      [:label {:style {:display "flex" :align-items "center" :gap "0.5rem"
                       :cursor "pointer" :font-size "0.9rem"}}
       [:input {:type "checkbox"
                :checked (boolean fail-mode?)
                :on-change #(rf/dispatch [:ui/set :todos/fail-mode? (not fail-mode?)])}]
       "💥 Fail Mode "
       [:span {:style {:font-size "0.8rem" :color "#999"}}
        "(server rejects ~50% of toggles)"]]]

     [:div.panel
      [:h3 "✅ Todos (optimistic updates)"]
      (case status
        :loading [:div.loading "Loading todos…"]
        :error [:div.error "Failed to load todos"]
        :success [:div
                  (for [todo data]
                    ^{:key (:id todo)}
                    [todo-item todo fail-mode?])]
        [:div.loading "Initializing…"])]]))
