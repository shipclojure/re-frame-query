(ns example.uix-app.views.prefetching
  "Prefetching demo: hover over a book to prefetch its detail."
  (:require
   [re-frame.core :as rf]
   [re-frame.query :as rfq]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as urf]))

(defui book-card-with-prefetch [{:keys [book]}]
  (let [{:keys [id title author]} book]
    ($ :div.book-card
       {:on-mouse-enter #(rfq/prefetch :book/detail {:id id})
        :on-click       #(rf/dispatch [:ui/set :prefetch/selected-id id])}
       ($ :div.title title)
       ($ :div.author "by " author)
       ($ :div {:style {:font-size "0.75rem" :color "#999" :margin-top "0.25rem"}}
          "Hover to prefetch → click for instant detail"))))

(defui book-detail-instant [{:keys [book-id]}]
  (let [{:keys [status data fetching?]}
        (urf/use-subscribe [:re-frame.query/query :book/detail {:id book-id}])]
    ($ :div.panel
       ($ :h3 "Book Detail")
       (case status
         :loading ($ :div.loading "Loading book…")
         :error   ($ :div.error "Error loading book")
         :success ($ :div
                     ($ :div.detail-field ($ :span.label "ID: ") (str (:id data)))
                     ($ :div.detail-field ($ :span.label "Title: ") (:title data))
                     ($ :div.detail-field ($ :span.label "Author: ") (:author data))
                     (when fetching?
                       ($ :div {:style {:font-size "0.8rem" :color "#0f3460" :margin-top "0.5rem"}}
                          "⟳ Refreshing in background…"))
                     ($ :div.button-group
                        ($ :button.secondary
                           {:on-click #(rf/dispatch [:ui/set :prefetch/selected-id nil])}
                           "← Back to list")))
         ($ :div.loading "Initializing…")))))

(defui book-list-prefetch []
  (let [{:keys [status data]}
        (urf/use-subscribe [:re-frame.query/query :books/list {}])]
    ($ :div.panel
       ($ :h3 "📚 Books (hover to prefetch)")
       (case status
         :loading ($ :div.loading "Loading books…")
         :error   ($ :div.error "Failed to load books")
         :success ($ :<>
                     (for [book data]
                       ($ book-card-with-prefetch {:key (:id book) :book book})))
         ($ :div.loading "Initializing…")))))

(defui panel []
  (let [selected-id (urf/use-subscribe [:ui/get :prefetch/selected-id])]
    ($ :div
       ($ :p {:style {:color "#666" :margin-bottom "1rem"}}
          "Hover over a book to " ($ :strong "prefetch") " its detail. "
          "Then click it — the detail loads instantly from cache (no loading spinner). "
          "Open the Network tab to see the prefetch request fire on hover.")
       (if selected-id
         ($ book-detail-instant {:book-id selected-id})
         ($ book-list-prefetch)))))
