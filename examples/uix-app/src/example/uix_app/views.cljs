(ns example.uix-app.views
  "UIx view components for the demo book app.
   Subscribing to [:rfq/query ...] automatically fetches and tracks active state."
  (:require
   [uix.core :as uix :refer [defui $]]
   [uix.re-frame :as urf]
   [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Book card
;; ---------------------------------------------------------------------------

(defui book-card
  "A single book entry in the list. Clicking it selects the book."
  [{:keys [book on-select]}]
  (let [{:keys [id title author]} book]
    ($ :div.book-card {:on-click #(on-select id)}
      ($ :div.title title)
      ($ :div.author "by " author))))

;; ---------------------------------------------------------------------------
;; Book list
;; ---------------------------------------------------------------------------

(defui book-list
  "Displays the list of books. Clicking a book calls `on-select` with its id.
   Simply subscribing to [:rfq/query :books/list {}] triggers the fetch."
  [{:keys [on-select]}]
  (let [query (urf/use-subscribe [:rfq/query :books/list {}])]
    ($ :div.panel
      ($ :h2 "📚 Books")
      (case (:status query)
        :loading ($ :div.loading "Loading books…")
        :error   ($ :div.error
                   "Error: " (get-in query [:error :message] "Unknown error"))
        :success (let [books (:data query)]
                   (if (seq books)
                     ($ :<>
                       (for [{:keys [id] :as book} books]
                         ($ book-card {:key id
                                       :book book
                                       :on-select on-select})))
                     ($ :div.empty-state "No books yet. Add one below!")))
        ;; :idle — initial render before dispatch fires
        ($ :div.loading "Initializing…")))))

;; ---------------------------------------------------------------------------
;; Book detail
;; ---------------------------------------------------------------------------

(defui book-detail
  "Shows details for a single book with edit/delete actions."
  [{:keys [book-id on-back]}]
  (let [query                        (urf/use-subscribe [:rfq/query :book/detail {:id book-id}])
        [editing? set-editing!]      (uix/use-state false)
        [new-title set-new-title!]   (uix/use-state "")]
    ($ :div.panel
      ($ :h2 "Book Detail")
      (case (:status query)
        :loading ($ :div.loading "Loading book…")
        :error   ($ :div.error
                   "Error: " (get-in query [:error :message] "Unknown error"))
        :success
        (let [{:keys [title author id]} (:data query)]
          ($ :div
            ($ :div.detail-field
              ($ :span.label "ID: ") (str id))
            ($ :div.detail-field
              ($ :span.label "Title: ") title)
            ($ :div.detail-field
              ($ :span.label "Author: ") author)

            (when editing?
              ($ :div.form-group {:style {:margin-top "0.75rem"}}
                ($ :label "New title")
                ($ :input {:type "text"
                           :value new-title
                           :placeholder "Enter new title"
                           :on-change #(set-new-title! (.. % -target -value))})
                ($ :div.button-group
                  ($ :button.primary
                    {:on-click (fn []
                                 (rf/dispatch [:rfq/execute-mutation
                                               :books/update
                                               {:id id :title new-title}])
                                 (set-editing! false)
                                 (set-new-title! ""))}
                    "Save")
                  ($ :button.secondary
                    {:on-click #(set-editing! false)}
                    "Cancel"))))

            ($ :div.button-group
              (when-not editing?
                ($ :button.primary
                  {:on-click (fn []
                               (set-new-title! title)
                               (set-editing! true))}
                  "Edit Title"))
              ($ :button.danger
                {:on-click (fn []
                             (rf/dispatch [:rfq/execute-mutation
                                           :books/delete {:id id}])
                             (on-back))}
                "Delete")
              ($ :button.secondary
                {:on-click on-back}
                "← Back"))))
        ;; :idle
        ($ :div.loading "Initializing…")))))

;; ---------------------------------------------------------------------------
;; Add book form
;; ---------------------------------------------------------------------------

(defui add-book-form
  "Simple form to create a new book via mutation."
  []
  (let [[title set-title!]       (uix/use-state "")
        [author set-author!]     (uix/use-state "")]
    ($ :div.panel
      ($ :h2 "➕ Add a Book")
      ($ :div.form-group
        ($ :label "Title")
        ($ :input {:type "text"
                   :value title
                   :placeholder "e.g. The Left Hand of Darkness"
                   :on-change #(set-title! (.. % -target -value))}))
      ($ :div.form-group
        ($ :label "Author")
        ($ :input {:type "text"
                   :value author
                   :placeholder "e.g. Ursula K. Le Guin"
                   :on-change #(set-author! (.. % -target -value))}))
      ($ :button.primary
        {:disabled (or (empty? title) (empty? author))
         :on-click (fn []
                     (rf/dispatch [:rfq/execute-mutation
                                   :books/create
                                   {:title title :author author}])
                     (set-title! "")
                     (set-author! ""))}
        "Add Book"))))

;; ---------------------------------------------------------------------------
;; Invalidate button
;; ---------------------------------------------------------------------------

(defui invalidate-button
  "Manually invalidates all book-related caches."
  []
  ($ :button.secondary
    {:on-click #(rf/dispatch [:rfq/invalidate-tags [[:books :all]]])}
    "🔄 Invalidate All Books"))

;; ---------------------------------------------------------------------------
;; App root
;; ---------------------------------------------------------------------------

(defui app
  "Main application component."
  []
  (let [[selected-id set-selected-id!] (uix/use-state nil)]
    ($ :div
      ($ :h1 "re-frame-query Demo")
      ($ :p.subtitle "Declarative data fetching & caching for re-frame (UIx)")

      ($ :div.toolbar
        ($ invalidate-button))

      (if selected-id
        ($ book-detail {:book-id selected-id
                        :on-back #(set-selected-id! nil)})
        ($ book-list {:on-select #(set-selected-id! %)}))

      ($ add-book-form))))
