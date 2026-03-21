(ns example.reagent-app.views.polling
  "Polling demo: auto-refreshing server stats on an interval.
   Demonstrates :polling-interval-ms at query and subscription level,
   multiple subscribers with different intervals, and stopping polling."
  (:require
   [re-frame.core :as rf]))

(defn server-stats-auto
  "Subscribes to :server/stats with the query-level default interval (2s).
   Polling starts automatically when this component mounts."
  []
  (let [{:keys [status data]}
        @(rf/subscribe [:re-frame.query/query :server/stats {}])]
    [:div.panel
     [:h3 "📊 Server Stats "
      [:span {:style {:font-size "0.75rem" :color "#999"}}
       "(query-level: 2s)"]]
     (case status
       :loading [:div.loading "Loading stats…"]
       :error   [:div.error "Failed to load stats"]
       :success [:div
                 [:div.detail-field
                  [:span.label "Uptime: "] (:uptime data)]
                 [:div.detail-field
                  [:span.label "Requests: "] (:request_count data)]
                 [:div.detail-field
                  [:span.label "Server time: "] (:server_time data)]]
       [:div.loading "Initializing…"])]))

(defn server-stats-fast
  "Subscribes to the same :server/stats query but overrides the interval to 1s.
   When this component is mounted alongside `server-stats-auto`, the effective
   interval becomes 1s (the lowest non-zero)."
  []
  (let [{:keys [status data]}
        @(rf/subscribe [:re-frame.query/query :server/stats {}
                        {:polling-interval-ms 1000}])]
    [:div.panel
     [:h3 "⚡ Fast Stats "
      [:span {:style {:font-size "0.75rem" :color "#999"}}
       "(subscription-level: 1s)"]]
     (case status
       :loading [:div.loading "Loading stats…"]
       :error   [:div.error "Failed to load stats"]
       :success [:div
                 [:div.detail-field
                  [:span.label "Uptime: "] (:uptime data)]
                 [:div.detail-field
                  [:span.label "Requests: "] (:request_count data)]
                 [:div.detail-field
                  [:span.label "Server time: "] (:server_time data)]]
       [:div.loading "Initializing…"])]))

(defn panel []
  (let [show-fast? @(rf/subscribe [:ui/get :polling/show-fast?])]
    [:div
     [:p {:style {:color "#666" :margin-bottom "1rem"}}
      "This demo polls " [:code "/api/server-stats"] " automatically. "
      "The query is registered with " [:code ":polling-interval-ms 2000"] ". "
      "Toggle the fast subscriber to see the interval drop to 1s (lowest wins)."]
     [server-stats-auto]
     [:div {:style {:margin-bottom "1rem"}}
      [:button {:class (if show-fast? "danger" "primary")
                :on-click #(rf/dispatch [:ui/set :polling/show-fast? (not show-fast?)])}
       (if show-fast?
         "Hide Fast Subscriber (stop 1s polling)"
         "Show Fast Subscriber (1s polling)")]]
     (when show-fast?
       [server-stats-fast])]))
