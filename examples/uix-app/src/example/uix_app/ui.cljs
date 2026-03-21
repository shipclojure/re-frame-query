(ns example.uix-app.ui
  "Generic UI state management in app-db under :ui/* keys.
   Keeps all view state in re-frame — no local component state."
  (:require
   [re-frame.core :as rf]))

(rf/reg-event-db :ui/set
  (fn [db [_ k v]]
    (assoc-in db [:ui k] v)))

(rf/reg-event-db :ui/update
  (fn [db [_ k f & args]]
    (apply update-in db [:ui k] f args)))

(rf/reg-event-db :ui/merge
  (fn [db [_ k m]]
    (update-in db [:ui k] merge m)))

(rf/reg-sub :ui/get
  (fn [db [_ k]]
    (get-in db [:ui k])))

(rf/reg-sub :ui/get-in
  (fn [db [_ path]]
    (get-in db (into [:ui] path))))
