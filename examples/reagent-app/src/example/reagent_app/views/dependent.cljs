(ns example.reagent-app.views.dependent
  "Dependent queries demo: Query B waits for Query A's data.
   Demonstrates :skip? to prevent a query from firing until
   a prerequisite query has loaded."
  (:require
   [re-frame.core :as rf]
   [re-frame.query :as rfq]))

(defn user-favorites
  "Demonstrates the dependent query pattern:
   1. First, fetch the current user via :user/current
   2. Then, fetch their favorites using the user-id from step 1
      (skipped until user data is available)"
  []
  (let [{user-status :status user-data :data}
        @(rf/subscribe [::rfq/query :user/current {}])

        user-id (:id user-data)

        {favs-status :status favs-data :data}
        @(rf/subscribe [::rfq/query :user/favorites {:user-id user-id}
                        {:skip? (nil? user-id)}])]
    [:div
     ;; User info
     [:div.panel
      [:h3 "👤 Current User"]
      (case user-status
        :loading [:div.loading "Loading user…"]
        :error [:div.error "Failed to load user"]
        :success [:div
                  [:div.detail-field [:span.label "Name: "] (:name user-data)]
                  [:div.detail-field [:span.label "Email: "] (:email user-data)]
                  [:div.detail-field [:span.label "ID: "] (str (:id user-data))]]
        [:div.loading "Initializing…"])]

     ;; Favorites — depends on user-id
     [:div.panel
      [:h3 "⭐ Favorites"]
      (cond
        (nil? user-id)
        [:div.loading "Waiting for user to load…"]

        (= :loading favs-status)
        [:div.loading "Loading favorites…"]

        (= :error favs-status)
        [:div.error "Failed to load favorites"]

        (= :success favs-status)
        (if (seq favs-data)
          [:div
           (for [{:keys [id title]} favs-data]
             ^{:key id}
             [:div.book-card
              [:div.title title]])]
          [:div.empty-state "No favorites yet."])

        :else
        [:div.loading "Initializing…"])]]))

(defn panel []
  [:div
   [:p {:style {:color "#666" :margin-bottom "1rem"}}
    "This demo shows " [:strong "dependent queries"] ": the favorites query uses "
    [:code ":skip? (nil? user-id)"] " to wait until the user query has loaded. "
    "Open the Network tab to see that " [:code "/api/users/:id/favorites"]
    " only fires after " [:code "/api/me"] " returns."]
   [user-favorites]])
