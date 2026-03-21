(ns example.reagent-app.views.mutations
  "Mutation lifecycle demo: execute → loading → success/error → reset.
   Demonstrates mutation status tracking and reset-mutation."
  (:require
   [re-frame.core :as rf]))

(defn mutation-status-badge
  "Renders a colored badge for the mutation status."
  [status]
  (let [styles {:idle    {:bg "#e0e0e0" :fg "#666"}
                :loading {:bg "#e8f0fe" :fg "#0f3460"}
                :success {:bg "#e8f5e9" :fg "#2e7d32"}
                :error   {:bg "#fdecea" :fg "#c62828"}}
        {:keys [bg fg]} (get styles status (:idle styles))]
    [:span {:style {:background bg :color fg :padding "0.25rem 0.75rem"
                    :border-radius "12px" :font-size "0.8rem" :font-weight 600}}
     (name status)]))

(defn create-book-with-status
  "Form that creates a book and shows the full mutation lifecycle.
   Includes a Reset button to clear the status back to idle."
  []
  (let [title  (or @(rf/subscribe [:ui/get :mut/title]) "")
        author (or @(rf/subscribe [:ui/get :mut/author]) "")
        {:keys [status data error]}
        @(rf/subscribe [:re-frame.query/mutation :books/create-demo {:title title :author author}])]
    [:div.panel
     [:h3 "Create Book — Mutation Lifecycle"]
     [:div {:style {:display "flex" :align-items "center" :gap "0.75rem" :margin-bottom "1rem"}}
      [:span {:style {:font-weight 600}} "Status:"]
      [mutation-status-badge status]]

     ;; Show result data or error
     (case status
       :success [:div {:style {:background "#e8f5e9" :padding "0.75rem" :border-radius "6px"
                               :margin-bottom "0.75rem"}}
                 "✅ Book created: " [:strong (str (:title data))]
                 " by " (:author data)
                 " (id: " (:id data) ")"]
       :error   [:div {:style {:background "#fdecea" :padding "0.75rem" :border-radius "6px"
                               :margin-bottom "0.75rem"}}
                 "❌ Error: " (pr-str error)]
       nil)

     ;; Form
     [:div.form-group
      [:label "Title"]
      [:input {:type "text" :value title :placeholder "e.g. Ringworld"
               :on-change #(rf/dispatch [:ui/set :mut/title (.. % -target -value)])}]]
     [:div.form-group
      [:label "Author"]
      [:input {:type "text" :value author :placeholder "e.g. Larry Niven"
               :on-change #(rf/dispatch [:ui/set :mut/author (.. % -target -value)])}]]

     [:div.button-group
      [:button.primary
       {:disabled (or (empty? title) (empty? author) (= :loading status))
        :on-click (fn []
                    (rf/dispatch [:re-frame.query/execute-mutation
                                  :books/create-demo
                                  {:title title :author author}]))}
       (if (= :loading status) "Creating…" "Create Book")]

      ;; Reset button — clears mutation state back to :idle
      (when (#{:success :error} status)
        [:button.secondary
         {:on-click #(rf/dispatch [:re-frame.query/reset-mutation
                                   :books/create-demo {:title title :author author}])}
         "Reset Status"])]]))

(defn delete-with-status
  "Demonstrates mutation error handling. Tries to delete a non-existent
   book (id 9999) to show the error state and reset flow."
  []
  (let [params {:id 9999}
        {:keys [status error]}
        @(rf/subscribe [:re-frame.query/mutation :books/delete params])]
    [:div.panel
     [:h3 "Delete Book #9999 — Error Flow"]
     [:div {:style {:display "flex" :align-items "center" :gap "0.75rem" :margin-bottom "1rem"}}
      [:span {:style {:font-weight 600}} "Status:"]
      [mutation-status-badge status]]

     (when (= :error status)
       [:div {:style {:background "#fdecea" :padding "0.75rem" :border-radius "6px"
                      :margin-bottom "0.75rem"}}
        "❌ " (or (:message error) (pr-str error))])

     [:div.button-group
      [:button.danger
       {:disabled (= :loading status)
        :on-click #(rf/dispatch [:re-frame.query/execute-mutation
                                 :books/delete {:id 9999}])}
       "Try Delete #9999"]
      (when (#{:success :error} status)
        [:button.secondary
         {:on-click #(rf/dispatch [:re-frame.query/reset-mutation :books/delete params])}
         "Reset Status"])]]))

(defn panel []
  [:div
   [:p {:style {:color "#666" :margin-bottom "1rem"}}
    "This demo shows the " [:strong "full mutation lifecycle"] ": "
    [:code ":idle → :loading → :success/:error"] ". "
    "Use the Reset button to clear the status back to " [:code ":idle"]
    " (dispatches " [:code "::rfq/reset-mutation"] ")."]
   [create-book-with-status]
   [delete-with-status]])
