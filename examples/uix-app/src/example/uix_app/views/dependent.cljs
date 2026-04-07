(ns example.uix-app.views.dependent
  "Dependent queries demo: Query B waits for Query A's data."
  (:require
   [re-frame.query :as rfq]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as urf]))

(defui user-favorites []
  (let [{user-status :status user-data :data}
        (urf/use-subscribe [::rfq/query :user/current {}])

        user-id (:id user-data)

        {favs-status :status favs-data :data}
        (urf/use-subscribe [::rfq/query :user/favorites {:user-id user-id}
                            {:skip? (nil? user-id)}])]
    ($ :div
       ($ :div.panel
          ($ :h3 "👤 Current User")
          (case user-status
            :loading ($ :div.loading "Loading user…")
            :error ($ :div.error "Failed to load user")
            :success ($ :div
                        ($ :div.detail-field ($ :span.label "Name: ") (:name user-data))
                        ($ :div.detail-field ($ :span.label "Email: ") (:email user-data))
                        ($ :div.detail-field ($ :span.label "ID: ") (str (:id user-data))))
            ($ :div.loading "Initializing…")))

       ($ :div.panel
          ($ :h3 "⭐ Favorites")
          (cond
            (nil? user-id)
            ($ :div.loading "Waiting for user to load…")

            (= :loading favs-status)
            ($ :div.loading "Loading favorites…")

            (= :error favs-status)
            ($ :div.error "Failed to load favorites")

            (= :success favs-status)
            (if (seq favs-data)
              ($ :<>
                 (for [{:keys [id title]} favs-data]
                   ($ :div.book-card {:key id}
                      ($ :div.title title))))
              ($ :div.empty-state "No favorites yet."))

            :else
            ($ :div.loading "Initializing…"))))))

(defui panel []
  ($ :div
     ($ :p {:style {:color "#666" :margin-bottom "1rem"}}
        "This demo shows " ($ :strong "dependent queries") ": the favorites query uses "
        ($ :code ":skip? (nil? user-id)") " to wait until the user query has loaded. "
        "Open the Network tab to see that " ($ :code "/api/users/:id/favorites")
        " only fires after " ($ :code "/api/me") " returns.")
     ($ user-favorites)))
