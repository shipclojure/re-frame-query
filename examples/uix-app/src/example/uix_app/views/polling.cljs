(ns example.uix-app.views.polling
  "Polling demo: auto-refreshing server stats on an interval."
  (:require
   [re-frame.core :as rf]
   [re-frame.query :as rfq]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as urf]))

(defui server-stats-auto []
  (let [{:keys [status data]}
        (urf/use-subscribe [::rfq/query :server/stats {}])]
    ($ :div.panel
       ($ :h3 "📊 Server Stats "
          ($ :span {:style {:font-size "0.75rem" :color "#999"}}
             "(query-level: 2s)"))
       (case status
         :loading ($ :div.loading "Loading stats…")
         :error ($ :div.error "Failed to load stats")
         :success ($ :div
                     ($ :div.detail-field ($ :span.label "Uptime: ") (:uptime data))
                     ($ :div.detail-field ($ :span.label "Requests: ") (:request_count data))
                     ($ :div.detail-field ($ :span.label "Server time: ") (:server_time data)))
         ($ :div.loading "Initializing…")))))

(defui server-stats-fast []
  (let [{:keys [status data]}
        (urf/use-subscribe [::rfq/query :server/stats {}
                            {:polling-interval-ms 1000}])]
    ($ :div.panel
       ($ :h3 "⚡ Fast Stats "
          ($ :span {:style {:font-size "0.75rem" :color "#999"}}
             "(subscription-level: 1s)"))
       (case status
         :loading ($ :div.loading "Loading stats…")
         :error ($ :div.error "Failed to load stats")
         :success ($ :div
                     ($ :div.detail-field ($ :span.label "Uptime: ") (:uptime data))
                     ($ :div.detail-field ($ :span.label "Requests: ") (:request_count data))
                     ($ :div.detail-field ($ :span.label "Server time: ") (:server_time data)))
         ($ :div.loading "Initializing…")))))

(defui panel []
  (let [show-fast? (urf/use-subscribe [:ui/get :polling/show-fast?])]
    ($ :div
       ($ :p {:style {:color "#666" :margin-bottom "1rem"}}
          "This demo polls " ($ :code "/api/server-stats") " automatically. "
          "The query is registered with " ($ :code ":polling-interval-ms 2000") ". "
          "Toggle the fast subscriber to see the interval drop to 1s (lowest wins).")
       ($ server-stats-auto)
       ($ :div {:style {:margin-bottom "1rem"}}
          ($ :button {:class (if show-fast? "danger" "primary")
                      :on-click #(rf/dispatch [:ui/set :polling/show-fast? (not show-fast?)])}
             (if show-fast?
               "Hide Fast Subscriber (stop 1s polling)"
               "Show Fast Subscriber (1s polling)")))
       (when show-fast?
         ($ server-stats-fast)))))
