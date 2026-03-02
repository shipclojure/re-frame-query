(ns example.reagent-app.views
  "Reagent view components for the demo book app.
   Subscribing to [:re-frame.query/query ...] automatically fetches and tracks active state."
  (:require
   [re-frame.core :as rf]
   [reagent.core :as r]))

;; ---------------------------------------------------------------------------
;; Book list
;; ---------------------------------------------------------------------------

(defn book-list
  "Displays the list of books. Clicking a book calls `on-select` with its id.
   Simply subscribing to [:re-frame.query/query :books/list {}] triggers the fetch."
  [on-select]
  (let [query @(rf/subscribe [:re-frame.query/query :books/list {}])]
    [:div.panel
     [:h2 "📚 Books"]
     (case (:status query)
       :loading [:div.loading "Loading books…"]
       :error   [:div.error "Error: " (get-in query [:error :message] "Unknown error")]
       :success (let [books (:data query)]
                  (if (seq books)
                    [:div
                     (for [{:keys [id title author]} books]
                       ^{:key id}
                       [:div.book-card {:on-click #(on-select id)}
                        [:div.title title]
                        [:div.author "by " author]])]
                    [:div.empty-state "No books yet. Add one below!"]))
       ;; :idle — initial render before dispatch fires
       [:div.loading "Initializing…"])]))

;; ---------------------------------------------------------------------------
;; Book detail
;; ---------------------------------------------------------------------------

(defn book-detail
  "Shows details for a single book with edit/delete actions."
  [book-id on-deselect]
  (let [editing? (r/atom false)
        new-title (r/atom "")]
    (fn [book-id on-deselect]
      (let [query @(rf/subscribe [:re-frame.query/query :book/detail {:id book-id}])]
        [:div.panel
         [:h2 "Book Detail"]
         (case (:status query)
           :loading [:div.loading "Loading book…"]
           :error   [:div.error "Error: " (get-in query [:error :message] "Unknown error")]
           :success (let [{:keys [title author id]} (:data query)]
                      [:div
                       [:div.detail-field
                        [:span.label "ID: "] (str id)]
                       [:div.detail-field
                        [:span.label "Title: "] title]
                       [:div.detail-field
                        [:span.label "Author: "] author]

                       (when @editing?
                         [:div.form-group {:style {:margin-top "0.75rem"}}
                          [:label "New title"]
                          [:input {:type "text"
                                   :value @new-title
                                   :placeholder "Enter new title"
                                   :on-change #(reset! new-title (.. % -target -value))}]
                          [:div.button-group
                           [:button.primary
                            {:on-click (fn []
                                         (rf/dispatch [:re-frame.query/execute-mutation
                                                       :books/update
                                                       {:id id :title @new-title}])
                                         (reset! editing? false)
                                         (reset! new-title ""))}
                            "Save"]
                           [:button.secondary
                            {:on-click #(reset! editing? false)}
                            "Cancel"]]])

                       [:div.button-group
                        (when-not @editing?
                          [:button.primary
                           {:on-click (fn []
                                        (reset! new-title title)
                                        (reset! editing? true))}
                           "Edit Title"])
                        [:button.danger
                         {:on-click (fn []
                                      (rf/dispatch [:re-frame.query/execute-mutation
                                                    :books/delete {:id id}])
                                      (on-deselect))}
                         "Delete"]
                        [:button.secondary
                         {:on-click on-deselect}
                         "← Back"]]])
           [:div.loading "Initializing…"])]))))

;; ---------------------------------------------------------------------------
;; Add book form
;; ---------------------------------------------------------------------------

(defn add-book-form
  "Simple form to create a new book via mutation."
  []
  (let [title  (r/atom "")
        author (r/atom "")]
    (fn []
      [:div.panel
       [:h2 "➕ Add a Book"]
       [:div.form-group
        [:label "Title"]
        [:input {:type "text"
                 :value @title
                 :placeholder "e.g. The Left Hand of Darkness"
                 :on-change #(reset! title (.. % -target -value))}]]
       [:div.form-group
        [:label "Author"]
        [:input {:type "text"
                 :value @author
                 :placeholder "e.g. Ursula K. Le Guin"
                 :on-change #(reset! author (.. % -target -value))}]]
       [:button.primary
        {:disabled (or (empty? @title) (empty? @author))
         :on-click (fn []
                     (rf/dispatch [:re-frame.query/execute-mutation
                                   :books/create
                                   {:title @title :author @author}])
                     (reset! title "")
                     (reset! author ""))}
        "Add Book"]])))

;; ---------------------------------------------------------------------------
;; Paginated book list
;; ---------------------------------------------------------------------------

(defn paginated-book-list
  "Displays a paginated list of books. Each page is a separate query,
   so navigating back to a previously visited page is instant (cached).
   Demonstrates how re-frame-query handles parameterised queries."
  [on-select]
  (let [current-page (r/atom 1)
        per-page 3]
    (fn [on-select]
      (let [query @(rf/subscribe [:re-frame.query/query :books/page {:page @current-page :per-page per-page}])]
        [:div.panel
         [:h2 "📖 Books (Paginated)"]
         (case (:status query)
           :loading [:div.loading "Loading page…"]
           :error   [:div.error "Error: " (get-in query [:error :message] "Unknown error")]
           :success (let [{:keys [items page total_pages total]} (:data query)]
                      [:div
                       [:div.page-info
                        "Page " page " of " total_pages
                        " (" total " books total)"]
                       (if (seq items)
                         [:div
                          (for [{:keys [id title author]} items]
                            ^{:key id}
                            [:div.book-card {:on-click #(on-select id)}
                             [:div.title title]
                             [:div.author "by " author]])]
                         [:div.empty-state "No books on this page."])
                       [:div.pagination
                        [:button.secondary
                         {:disabled (= page 1)
                          :on-click #(swap! current-page dec)}
                         "← Prev"]
                        (for [p (range 1 (inc total_pages))]
                          ^{:key p}
                          [:button {:class (if (= p page) "primary" "secondary")
                                    :on-click #(reset! current-page p)}
                           (str p)])
                        [:button.secondary
                         {:disabled (= page total_pages)
                          :on-click #(swap! current-page inc)}
                         "Next →"]]])
           [:div.loading "Initializing…"])]))))

;; ---------------------------------------------------------------------------
;; Invalidate button
;; ---------------------------------------------------------------------------

(defn invalidate-button
  "Manually invalidates all book-related caches."
  []
  [:button.secondary
   {:on-click #(rf/dispatch [:re-frame.query/invalidate-tags [[:books :all]]])}
   "🔄 Invalidate All Books"])

;; ---------------------------------------------------------------------------
;; App root
;; ---------------------------------------------------------------------------

(defn app
  "Main application component."
  []
  (let [selected-id (r/atom nil)]
    (fn []
      [:div
       [:h1 "re-frame-query Demo"]
       [:p.subtitle "Declarative data fetching & caching for re-frame"]

       [:div.toolbar
        [invalidate-button]]

       (if @selected-id
         [book-detail @selected-id #(reset! selected-id nil)]
         [:<>
          [book-list #(reset! selected-id %)]
          [paginated-book-list #(reset! selected-id %)]])

       [add-book-form]])))
