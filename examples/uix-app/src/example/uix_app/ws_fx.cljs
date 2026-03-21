(ns example.uix-app.ws-fx
  "Registers a :ws-send re-frame effect handler that uses MockWebSocket.
   Demonstrates a custom transport (non-HTTP) for re-frame-query."
  (:require
   [re-frame.core :as rf]))

;; Singleton mock WebSocket connection
(defonce ^:private ws-conn (atom nil))
(defonce ^:private pending-queue (atom []))

(defn- ensure-ws!
  "Returns the shared MockWebSocket instance, creating it if needed.
   Queues sends until the connection is open."
  []
  (when-not @ws-conn
    (let [ws (js/MockWebSocket. "ws://mock/api")]
      (reset! ws-conn ws)
      (.addEventListener ws "open"
                         (fn [_]
                           ;; Flush any queued messages
                           (doseq [msg @pending-queue]
                             (.send ws msg))
                           (reset! pending-queue [])))))
  @ws-conn)

(defn- ws-send! [ws data]
  (if (= 1 (.-readyState ws))
    (.send ws data)
    (swap! pending-queue conj data)))

(rf/reg-fx
  :ws-send
  (fn [{:keys [channel payload on-success on-failure]}]
    (let [ws         (ensure-ws!)
          request-id (str (random-uuid))]
      ;; Register a one-shot listener for the response
      (let [handler (fn handler [event]
                      (let [msg (js/JSON.parse (.-data event))
                            rid (.-request_id msg)]
                        (when (= rid request-id)
                          (.removeEventListener ws "message" handler)
                          (if (.-error msg)
                            (rf/dispatch (conj on-failure
                                              (js->clj (.-error msg)
                                                       :keywordize-keys true)))
                            (rf/dispatch (conj on-success
                                              (js->clj (.-data msg)
                                                       :keywordize-keys true)))))))]
        (.addEventListener ws "message" handler))
      ;; Send (or queue) the request
      (ws-send! ws (js/JSON.stringify
                     (clj->js {:channel    channel
                               :payload    payload
                               :request_id request-id}))))))
