(ns example.reagent-app.http-fx
  "Registers an :http re-frame effect handler that uses js/fetch.
   Sends/receives JSON and dispatches re-frame events on success/failure.

   Supports request cancellation via :abort-key — each request stores
   an AbortController that can be cancelled with the :abort-request effect.
   Used by optimistic updates to cancel in-flight refetches."
  (:require
   [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Abort controller registry
;; ---------------------------------------------------------------------------

;; Map of abort-key → AbortController for in-flight requests.
(defonce ^:private abort-controllers (atom {}))

(rf/reg-fx
 :abort-request
 (fn [abort-key]
   (when-let [controller (get @abort-controllers abort-key)]
     (.abort controller)
     (swap! abort-controllers dissoc abort-key))))

;; ---------------------------------------------------------------------------
;; HTTP effect
;; ---------------------------------------------------------------------------

(rf/reg-fx
 :http
 (fn [{:keys [method url body on-success on-failure abort-key]}]
   (let [controller (js/AbortController.)
         signal     (.-signal controller)]
      ;; Store controller so it can be cancelled via :abort-request
     (when abort-key
       (swap! abort-controllers assoc abort-key controller))
     (-> (js/fetch url
                   (clj->js
                    (cond-> {:method  (name method)
                             :headers {"Content-Type" "application/json"}
                             :signal  signal}
                      body (assoc :body (js/JSON.stringify (clj->js body))))))
         (.then (fn [res]
                  (when abort-key
                    (swap! abort-controllers dissoc abort-key))
                  (if (.-ok res)
                    (-> (.json res)
                        (.then (fn [json]
                                 (let [data (js->clj json :keywordize-keys true)]
                                   (rf/dispatch (conj on-success data))))))
                    (-> (.json res)
                        (.then (fn [json]
                                 (let [data (js->clj json :keywordize-keys true)]
                                   (rf/dispatch (conj on-failure data)))))
                        (.catch (fn [_]
                                  (rf/dispatch
                                   (conj on-failure
                                         {:status  (.-status res)
                                          :message (.-statusText res)}))))))))
         (.catch (fn [err]
                   (when abort-key
                     (swap! abort-controllers dissoc abort-key))
                    ;; Silently drop aborted requests — no on-failure dispatch
                   (when-not (.-aborted signal)
                     (rf/dispatch
                      (conj on-failure {:message (.-message err)})))))))))
