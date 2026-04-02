(ns example.uix-app.views.basic
  "Basic CRUD demo: book list, detail, create, pagination, invalidation."
  (:require
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as urf]))

(defui book-list []
  (let [query (urf/use-subscribe [:re-frame.query/query :books/list {}])]
    ($ :div.panel
       ($ :h2 "📚 Books")
       (case (:status query)
         :loading ($ :div.loading "Loading books…")
         :error ($ :div.error "Error: " (get-in query [:error :message] "Unknown error"))
         :success (let [books (:data query)]
                    (if (seq books)
                      ($ :<>
                         (for [{:keys [id title author]} books]
                           ($ :div.book-card {:key id
                                              :on-click #(rf/dispatch [:ui/set :basic/selected-id id])}
                              ($ :div.title title)
                              ($ :div.author "by " author))))
                      ($ :div.empty-state "No books yet. Add one below!")))
         ($ :div.loading "Initializing…")))))

(defui book-detail [{:keys [book-id]}]
  (let [query (urf/use-subscribe [:re-frame.query/query :book/detail {:id book-id}])
        editing? (urf/use-subscribe [:ui/get :basic/editing?])
        new-title (or (urf/use-subscribe [:ui/get :basic/new-title]) "")]
    ($ :div.panel
       ($ :h2 "Book Detail")
       (case (:status query)
         :loading ($ :div.loading "Loading book…")
         :error ($ :div.error "Error: " (get-in query [:error :message] "Unknown error"))
         :success
         (let [{:keys [title author id]} (:data query)]
           ($ :div
              ($ :div.detail-field ($ :span.label "ID: ") (str id))
              ($ :div.detail-field ($ :span.label "Title: ") title)
              ($ :div.detail-field ($ :span.label "Author: ") author)
              (when editing?
                ($ :div.form-group {:style {:margin-top "0.75rem"}}
                   ($ :label "New title")
                   ($ :input {:type "text" :value new-title :placeholder "Enter new title"
                              :on-change #(rf/dispatch [:ui/set :basic/new-title (.. % -target -value)])})
                   ($ :div.button-group
                      ($ :button.primary
                         {:on-click (fn []
                                      (rf/dispatch [:re-frame.query/execute-mutation
                                                    :books/update {:id id :title new-title}])
                                      (rf/dispatch [:ui/set :basic/editing? false])
                                      (rf/dispatch [:ui/set :basic/new-title ""]))}
                         "Save")
                      ($ :button.secondary
                         {:on-click #(rf/dispatch [:ui/set :basic/editing? false])}
                         "Cancel"))))
              ($ :div.button-group
                 (when-not editing?
                   ($ :button.primary
                      {:on-click (fn []
                                   (rf/dispatch [:ui/set :basic/new-title title])
                                   (rf/dispatch [:ui/set :basic/editing? true]))}
                      "Edit Title"))
                 ($ :button.danger
                    {:on-click (fn []
                                 (rf/dispatch [:re-frame.query/execute-mutation
                                               :books/delete {:id id}])
                                 (rf/dispatch [:ui/set :basic/selected-id nil]))}
                    "Delete")
                 ($ :button.secondary
                    {:on-click #(rf/dispatch [:ui/set :basic/selected-id nil])}
                    "← Back"))))
         ($ :div.loading "Initializing…")))))

(defui add-book-form []
  (let [title (or (urf/use-subscribe [:ui/get :basic/add-title]) "")
        author (or (urf/use-subscribe [:ui/get :basic/add-author]) "")]
    ($ :div.panel
       ($ :h2 "➕ Add a Book")
       ($ :div.form-group
          ($ :label "Title")
          ($ :input {:type "text" :value title :placeholder "e.g. The Left Hand of Darkness"
                     :on-change #(rf/dispatch [:ui/set :basic/add-title (.. % -target -value)])}))
       ($ :div.form-group
          ($ :label "Author")
          ($ :input {:type "text" :value author :placeholder "e.g. Ursula K. Le Guin"
                     :on-change #(rf/dispatch [:ui/set :basic/add-author (.. % -target -value)])}))
       ($ :button.primary
          {:disabled (or (empty? title) (empty? author))
           :on-click (fn []
                       (rf/dispatch [:re-frame.query/execute-mutation
                                     :books/create {:title title :author author}])
                       (rf/dispatch [:ui/set :basic/add-title ""])
                       (rf/dispatch [:ui/set :basic/add-author ""]))}
          "Add Book"))))

(defui paginated-book-list []
  (let [current-page (or (urf/use-subscribe [:ui/get :basic/page]) 1)
        per-page 3
        query (urf/use-subscribe [:re-frame.query/query :books/page
                                  {:page current-page :per-page per-page}])]
    ($ :div.panel
       ($ :h2 "📖 Books (Paginated)")
       (case (:status query)
         :loading ($ :div.loading "Loading page…")
         :error ($ :div.error "Error: " (get-in query [:error :message] "Unknown error"))
         :success
         (let [{:keys [items page total_pages total]} (:data query)]
           ($ :div
              ($ :div.page-info "Page " page " of " total_pages " (" total " books total)")
              (if (seq items)
                ($ :<>
                   (for [{:keys [id title author]} items]
                     ($ :div.book-card {:key id
                                        :on-click #(rf/dispatch [:ui/set :basic/selected-id id])}
                        ($ :div.title title)
                        ($ :div.author "by " author))))
                ($ :div.empty-state "No books on this page."))
              ($ :div.pagination
                 ($ :button.secondary {:disabled (= page 1)
                                       :on-click #(rf/dispatch [:ui/set :basic/page (dec current-page)])}
                    "← Prev")
                 (for [p (range 1 (inc total_pages))]
                   ($ :button {:key p
                               :class (if (= p page) "primary" "secondary")
                               :on-click #(rf/dispatch [:ui/set :basic/page p])}
                      (str p)))
                 ($ :button.secondary {:disabled (= page total_pages)
                                       :on-click #(rf/dispatch [:ui/set :basic/page (inc current-page)])}
                    "Next →"))))
         ($ :div.loading "Initializing…")))))

(defui panel []
  (let [selected-id (urf/use-subscribe [:ui/get :basic/selected-id])]
    ($ :div
       ($ :div.toolbar
          ($ :button.secondary
             {:on-click #(rf/dispatch [:re-frame.query/invalidate-tags [[:books :all]]])}
             "🔄 Invalidate All Books"))
       (if selected-id
         ($ book-detail {:book-id selected-id})
         ($ :<>
            ($ book-list)
            ($ paginated-book-list)))
       ($ add-book-form))))
