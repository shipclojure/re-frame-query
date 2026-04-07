(ns example.uix-app.views.mutations
  "Mutation lifecycle demo: execute → loading → success/error → reset."
  (:require
   [re-frame.core :as rf]
   [re-frame.query :as rfq]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as urf]))

(defui mutation-status-badge [{:keys [status]}]
  (let [styles {:idle {:bg "#e0e0e0" :fg "#666"}
                :loading {:bg "#e8f0fe" :fg "#0f3460"}
                :success {:bg "#e8f5e9" :fg "#2e7d32"}
                :error {:bg "#fdecea" :fg "#c62828"}}
        {:keys [bg fg]} (get styles status (:idle styles))]
    ($ :span {:style {:background bg :color fg :padding "0.25rem 0.75rem"
                      :border-radius "12px" :font-size "0.8rem" :font-weight 600}}
       (name status))))

(defui create-book-with-status []
  (let [title (or (urf/use-subscribe [:ui/get :mut/title]) "")
        author (or (urf/use-subscribe [:ui/get :mut/author]) "")
        {:keys [status data error]}
        (urf/use-subscribe [::rfq/mutation :books/create-demo {:title title :author author}])]
    ($ :div.panel
       ($ :h3 "Create Book — Mutation Lifecycle")
       ($ :div {:style {:display "flex" :align-items "center" :gap "0.75rem" :margin-bottom "1rem"}}
          ($ :span {:style {:font-weight 600}} "Status:")
          ($ mutation-status-badge {:status status}))

       (case status
         :success ($ :div {:style {:background "#e8f5e9" :padding "0.75rem" :border-radius "6px"
                                   :margin-bottom "0.75rem"}}
                     "✅ Book created: " ($ :strong (str (:title data)))
                     " by " (:author data)
                     " (id: " (:id data) ")")
         :error ($ :div {:style {:background "#fdecea" :padding "0.75rem" :border-radius "6px"
                                 :margin-bottom "0.75rem"}}
                   "❌ Error: " (pr-str error))
         nil)

       ($ :div.form-group
          ($ :label "Title")
          ($ :input {:type "text" :value title :placeholder "e.g. Ringworld"
                     :on-change #(rf/dispatch [:ui/set :mut/title (.. % -target -value)])}))
       ($ :div.form-group
          ($ :label "Author")
          ($ :input {:type "text" :value author :placeholder "e.g. Larry Niven"
                     :on-change #(rf/dispatch [:ui/set :mut/author (.. % -target -value)])}))

       ($ :div.button-group
          ($ :button.primary
             {:disabled (or (empty? title) (empty? author) (= :loading status))
              :on-click (fn []
                          (rf/dispatch [::rfq/execute-mutation
                                        :books/create-demo
                                        {:title title :author author}]))}
             (if (= :loading status) "Creating…" "Create Book"))
          (when (#{:success :error} status)
            ($ :button.secondary
               {:on-click #(rf/dispatch [::rfq/reset-mutation
                                         :books/create-demo {:title title :author author}])}
               "Reset Status"))))))

(defui delete-with-status []
  (let [params {:id 9999}
        {:keys [status error]}
        (urf/use-subscribe [::rfq/mutation :books/delete params])]
    ($ :div.panel
       ($ :h3 "Delete Book #9999 — Error Flow")
       ($ :div {:style {:display "flex" :align-items "center" :gap "0.75rem" :margin-bottom "1rem"}}
          ($ :span {:style {:font-weight 600}} "Status:")
          ($ mutation-status-badge {:status status}))

       (when (= :error status)
         ($ :div {:style {:background "#fdecea" :padding "0.75rem" :border-radius "6px"
                          :margin-bottom "0.75rem"}}
            "❌ " (or (:message error) (pr-str error))))

       ($ :div.button-group
          ($ :button.danger
             {:disabled (= :loading status)
              :on-click #(rf/dispatch [::rfq/execute-mutation
                                       :books/delete {:id 9999}])}
             "Try Delete #9999")
          (when (#{:success :error} status)
            ($ :button.secondary
               {:on-click #(rf/dispatch [::rfq/reset-mutation :books/delete params])}
               "Reset Status"))))))

(defui panel []
  ($ :div
     ($ :p {:style {:color "#666" :margin-bottom "1rem"}}
        "This demo shows the " ($ :strong "full mutation lifecycle") ": "
        ($ :code ":idle → :loading → :success/:error") ". "
        "Use the Reset button to clear the status back to " ($ :code ":idle")
        " (dispatches " ($ :code "::rfq/reset-mutation") ").")
     ($ create-book-with-status)
     ($ delete-with-status)))
