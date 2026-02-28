(ns example.reagent-app.http-fx
  "Registers an :http re-frame effect handler that uses js/fetch.
   Sends/receives JSON and dispatches re-frame events on success/failure."
  (:require
   [re-frame.core :as rf]))

(rf/reg-fx
 :http
 (fn [{:keys [method url body on-success on-failure]}]
   (-> (js/fetch url
                 (clj->js
                  (cond-> {:method (name method)
                           :headers {"Content-Type" "application/json"}}
                    body (assoc :body (js/JSON.stringify (clj->js body))))))
       (.then (fn [res]
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
                                       {:status (.-status res)
                                        :message (.-statusText res)}))))))))
       (.catch (fn [err]
                 (rf/dispatch
                  (conj on-failure {:message (.-message err)})))))))
