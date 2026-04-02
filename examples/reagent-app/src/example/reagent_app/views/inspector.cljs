(ns example.reagent-app.views.inspector
  "Live app-db inspector panel showing re-frame-query state."
  (:require
   [cljs.pprint :as pprint]
   [re-frame.core :as rf]))

(defn- pprint-str [x]
  (with-out-str (pprint/pprint x)))

(defn- entry-panel
  "A single collapsible query/mutation entry."
  [qid state]
  (let [open-key (str "inspector/" (pr-str qid))]
    (fn [[k params] state]
      (let [open? @(rf/subscribe [:ui/get open-key])
            status (:status state)
            status-colors {:idle "#888" :loading "#0f3460" :success "#2e7d32" :error "#c62828"}]
        [:div {:style {:border "1px solid #e0e0e0" :border-radius "6px"
                       :margin-bottom "0.4rem" :background "#fff"}}
         [:div {:style {:padding "0.4rem 0.75rem" :cursor "pointer" :display "flex"
                        :align-items "center" :gap "0.5rem" :font-size "0.8rem"}
                :on-click #(rf/dispatch [:ui/set open-key (not open?)])}
          [:span {:style {:color "#999" :font-size "0.75rem"}} (if open? "▼" "▶")]
          [:code {:style {:font-weight 600 :color "#333"}} (str k)]
          (when (seq params)
            [:code {:style {:color "#666"}} (pr-str params)])
          [:span {:style {:margin-left "auto" :font-size "0.75rem" :font-weight 600
                          :color (get status-colors status "#888")}}
           (some-> status name)]]
         (when open?
           [:pre {:style {:margin 0 :padding "0.5rem 0.75rem" :border-top "1px solid #eee"
                          :font-size "0.75rem" :line-height "1.5" :white-space "pre-wrap"
                          :background "#fafafa" :border-radius "0 0 6px 6px"}}
            (pprint-str state)])]))))

(defn panel
  "Collapsible panel showing the current re-frame-query state in app-db."
  []
  (let [open? @(rf/subscribe [:ui/get :inspector/open?])
        queries @(rf/subscribe [:re-frame.query/queries])
        mutations @(rf/subscribe [:re-frame.query/mutations])]
    [:div {:style {:margin-top "2rem"}}
     [:button
      {:class "secondary"
       :style {:width "100%" :text-align "left" :font-size "0.85rem"}
       :on-click #(rf/dispatch [:ui/set :inspector/open? (not open?)])}
      (if open? "▼" "▶")
      " 🔍 app-db — re-frame-query state"
      (when (or (seq queries) (seq mutations))
        [:span {:style {:color "#999" :margin-left "0.5rem"}}
         (str (count queries) " queries, " (count mutations) " mutations")])]
     (when open?
       [:div {:style {:background "#f5f5f5" :padding "0.75rem"
                      :border "1px solid #e0e0e0" :border-top "none"
                      :border-radius "0 0 8px 8px"
                      :max-height "600px" :overflow-y "auto"}}
        (when (seq queries)
          [:div
           [:div {:style {:color "#0f3460" :font-weight 600 :font-size "0.8rem"
                          :margin-bottom "0.4rem"}}
            "Queries"]
           (for [[qid state] (sort-by (comp str first) queries)]
             ^{:key (pr-str qid)}
             [entry-panel qid state])])
        (when (seq mutations)
          [:div {:style {:margin-top (if (seq queries) "0.75rem" 0)}}
           [:div {:style {:color "#0f3460" :font-weight 600 :font-size "0.8rem"
                          :margin-bottom "0.4rem"}}
            "Mutations"]
           (for [[mid state] (sort-by (comp str first) mutations)]
             ^{:key (pr-str mid)}
             [entry-panel mid state])])
        (when (and (empty? queries) (empty? mutations))
          [:div {:style {:color "#999" :font-size "0.85rem" :padding "0.5rem"}}
           "Empty — no queries or mutations in cache."])])]))
