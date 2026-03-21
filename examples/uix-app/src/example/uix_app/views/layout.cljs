(ns example.uix-app.views.layout
  "App shell with tab navigation between example demos."
  (:require
   [uix.core :refer [$ defui]]))

(def tabs
  [{:id :basic       :label "Basic CRUD"}
   {:id :polling     :label "Polling"}
   {:id :dependent   :label "Dependent Queries"}
   {:id :prefetching :label "Prefetching"}
   {:id :mutations   :label "Mutation Lifecycle"}])

(defui tab-bar [{:keys [active on-select]}]
  ($ :div.tab-bar
     (for [{:keys [id label]} tabs]
       ($ :button {:key id
                   :class (if (= id active) "tab active" "tab")
                   :on-click #(on-select id)}
          label))))
