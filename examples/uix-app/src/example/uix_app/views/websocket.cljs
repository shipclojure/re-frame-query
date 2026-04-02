(ns example.uix-app.views.websocket
  "WebSocket transport demo: queries and mutations over a mock WebSocket."
  (:require
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as urf]))

(defui notification-list []
  (let [{:keys [status data]}
        (urf/use-subscribe [:re-frame.query/query :ws/notifications {}])]
    ($ :div.panel
       ($ :h3 "🔔 Notifications (WebSocket query)")
       (case status
         :loading ($ :div.loading "Loading notifications…")
         :error ($ :div.error "Failed to load notifications")
         :success (if (seq data)
                    ($ :<>
                       (for [{:keys [id message severity timestamp]} data]
                         ($ :div.book-card {:key id}
                            ($ :div.title
                               ($ :span {:style {:margin-right "0.5rem"}}
                                  (case severity "warning" "⚠️" "info" "ℹ️" "📌"))
                               message)
                            ($ :div.author timestamp))))
                    ($ :div.empty-state "No notifications."))
         ($ :div.loading "Initializing…")))))

(defui latest-notification []
  (let [{:keys [status data]}
        (urf/use-subscribe [:re-frame.query/query :ws/latest-notification {}])]
    ($ :div.panel
       ($ :h3 "📡 Latest Notification "
          ($ :span {:style {:font-size "0.75rem" :color "#999"}} "(polling every 3s via WS)"))
       (case status
         :loading ($ :div.loading "Loading…")
         :error ($ :div.error "Failed to load")
         :success ($ :div
                     ($ :div.detail-field ($ :span.label "Message: ") (:message data))
                     ($ :div.detail-field ($ :span.label "Severity: ") (:severity data))
                     ($ :div.detail-field ($ :span.label "Time: ") (:timestamp data)))
         ($ :div.loading "Initializing…")))))

(defui chat-panel []
  (let [{:keys [status data]}
        (urf/use-subscribe [:re-frame.query/query :ws/chat-messages {}])
        new-text (or (urf/use-subscribe [:ui/get :ws/chat-text]) "")]
    ($ :div.panel
       ($ :h3 "💬 Chat (WebSocket mutation + invalidation)")
       (case status
         :loading ($ :div.loading "Loading messages…")
         :error ($ :div.error "Failed to load messages")
         :success
         ($ :div
            (if (seq data)
              ($ :div {:style {:max-height "200px" :overflow-y "auto"
                               :margin-bottom "0.75rem"}}
                 (for [{:keys [id user text time]} data]
                   ($ :div {:key id :style {:padding "0.4rem 0" :border-bottom "1px solid #eee"}}
                      ($ :span {:style {:font-weight 600 :color "#0f3460"}} user)
                      ($ :span {:style {:color "#999" :font-size "0.8rem" :margin-left "0.5rem"}} time)
                      ($ :div {:style {:margin-top "0.15rem"}} text))))
              ($ :div.empty-state "No messages yet."))
            ($ :div {:style {:display "flex" :gap "0.5rem"}}
               ($ :input {:type "text" :value new-text
                          :placeholder "Type a message…"
                          :style {:flex 1}
                          :on-change #(rf/dispatch [:ui/set :ws/chat-text (.. % -target -value)])
                          :on-key-down #(when (and (= "Enter" (.-key %)) (seq new-text))
                                          (rf/dispatch [:re-frame.query/execute-mutation
                                                        :ws/chat-send {:user "You" :text new-text}])
                                          (rf/dispatch [:ui/set :ws/chat-text ""]))})
               ($ :button.primary
                  {:disabled (empty? new-text)
                   :on-click (fn []
                               (rf/dispatch [:re-frame.query/execute-mutation
                                             :ws/chat-send {:user "You" :text new-text}])
                               (rf/dispatch [:ui/set :ws/chat-text ""]))}
                  "Send")))
         ($ :div.loading "Initializing…")))))

(defui panel []
  ($ :div
     ($ :p {:style {:color "#666" :margin-bottom "1rem"}}
        "This demo uses a " ($ :strong "mock WebSocket") " transport instead of HTTP. "
        "Each query registers a per-query " ($ :code ":effect-fn") " that sends messages via "
        ($ :code ":ws-send") " instead of " ($ :code ":http") ". "
        "Everything else — caching, invalidation, polling, GC — works identically.")
     ($ notification-list)
     ($ latest-notification)
     ($ chat-panel)))
