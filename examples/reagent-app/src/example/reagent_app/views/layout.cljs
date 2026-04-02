(ns example.reagent-app.views.layout
  "App shell with tab navigation between example demos.")

(def tabs
  [{:id :basic :label "Basic CRUD"}
   {:id :polling :label "Polling"}
   {:id :dependent :label "Dependent Queries"}
   {:id :prefetching :label "Prefetching"}
   {:id :mutations :label "Mutation Lifecycle"}
   {:id :websocket :label "WebSocket"}
   {:id :optimistic :label "Optimistic Updates"}
   {:id :infinite :label "Infinite Scroll"}])

(defn tab-bar
  "Horizontal tab bar. `active` is the current tab keyword,
   `on-select` is called with the new tab keyword."
  [active on-select]
  [:div.tab-bar
   (for [{:keys [id label]} tabs]
     ^{:key id}
     [:button {:class (if (= id active) "tab active" "tab")
               :on-click #(on-select id)}
      label])])
