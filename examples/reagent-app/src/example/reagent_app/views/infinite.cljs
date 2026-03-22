(ns example.reagent-app.views.infinite
  "Infinite scroll demo: load more items from a feed using cursor-based
   pagination. Demonstrates ::rfq/infinite-query subscription and
   fetch-next-page event. Switching users shows independent cache entries.
   Mutation invalidation triggers sequential re-fetch with fresh cursors."
  (:require
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.query :as rfq]))

;; ---------------------------------------------------------------------------
;; Views
;; ---------------------------------------------------------------------------

(defn feed-item [{:keys [id title body author created_at]}]
  [:div {:style {:padding "0.75rem" :border "1px solid #e2e2e2"
                 :border-radius "8px" :margin-bottom "0.5rem"
                 :background "#fff"}}
   [:div {:style {:display "flex" :justify-content "space-between" :align-items "center"}}
    [:strong {:style {:font-size "0.95rem"}} title]
    [:span {:style {:font-size "0.75rem" :color "#999"}} (str "#" id)]]
   [:p {:style {:margin "0.5rem 0" :font-size "0.9rem" :color "#444"}} body]
   [:div {:style {:display "flex" :justify-content "space-between"
                  :font-size "0.8rem" :color "#888"}}
    [:span "by " author]
    [:span created_at]]])

(defn feed-stats [{:keys [pages page-params has-next?]} user]
  [:div {:style {:font-size "0.8rem" :color "#666" :padding "0.5rem 0.75rem"
                 :background "#f5f5f5" :border-radius "6px" :margin-bottom "1rem"}}
   [:div (str "User: " user)]
   [:div (str (count pages) " page(s) loaded")]
   [:div (str "Cursors: " (pr-str page-params))]
   [:div (str "Has next: " has-next?)]])

(defn user-switcher [active-user]
  [:div {:style {:display "flex" :gap "0.5rem" :margin-bottom "1rem"}}
   (for [user ["alex" "maria"]]
     ^{:key user}
     [:button {:class    (if (= user active-user) "primary" "secondary")
               :on-click #(rf/dispatch [:ui/set :infinite/user user])}
      (str "👤 " (str/capitalize user))])])

(defn panel []
  (let [user (or @(rf/subscribe [:ui/get :infinite/user]) "alex")
        feed-params {:user user}
        {:keys [status data error fetching? fetching-next?]}
        @(rf/subscribe [:re-frame.query/infinite-query :feed/items feed-params])
        {:keys [pages has-next?]} data
        items      (mapcat :items pages)
        show-stats @(rf/subscribe [:ui/get :infinite/show-stats])
        new-title  (or @(rf/subscribe [:ui/get :infinite/new-title]) "")
        mutation   @(rf/subscribe [:re-frame.query/mutation :feed/add-item {}])]
    [:div
     [:p {:style {:color "#666" :margin-bottom "1rem"}}
      "Cursor-based infinite feed " [:strong "per user"] ". "
      "Switch between Alex and Maria — each has an independent cache entry. "
      "Click " [:strong "Load More"] " to fetch the next page. "
      "Add a post to trigger " [:strong "tag invalidation"]
      " → sequential re-fetch of that user's pages with fresh cursors."]

     ;; --- User switcher ---
     [user-switcher user]

     ;; --- Add post form ---
     [:div.panel
      [:h3 "📝 Add Post to " (str/capitalize user) "'s Feed"]
      [:div {:style {:display "flex" :gap "0.5rem" :align-items "center"}}
       [:input {:type "text" :value new-title :placeholder "Post title…"
                :style {:flex "1"}
                :on-change #(rf/dispatch [:ui/set :infinite/new-title (.. % -target -value)])}]
       [:button.primary
        {:disabled (or (empty? new-title) (= :loading (:status mutation)))
         :on-click (fn []
                     (rf/dispatch [:re-frame.query/execute-mutation
                                   :feed/add-item {:user user :title new-title}])
                     (rf/dispatch [:ui/set :infinite/new-title ""]))}
        (if (= :loading (:status mutation)) "Adding…" "Add Post")]]]

     ;; --- Debug toggle ---
     [:div {:style {:margin "0.75rem 0"}}
      [:label {:style {:display "flex" :align-items "center" :gap "0.5rem"
                       :cursor "pointer" :font-size "0.9rem"}}
       [:input {:type "checkbox"
                :checked (boolean show-stats)
                :on-change #(rf/dispatch [:ui/set :infinite/show-stats (not show-stats)])}]
       "📊 Show pagination debug info"]]

     ;; --- Feed ---
     [:div.panel
      [:h3 "📰 " (str/capitalize user) "'s Feed"
       (when fetching?
         [:span {:style {:font-size "0.8rem" :color "#999" :margin-left "0.5rem"}}
          "🔄 Refreshing…"])]

      (when show-stats
        [feed-stats data user])

      (case status
        :loading [:div.loading "Loading feed…"]
        :error   [:div.error "Error: " (or (:message error) (pr-str error))]
        (:success :idle)
        [:div
         (if (seq items)
           [:div
            (for [item items]
              ^{:key (:id item)}
              [feed-item item])]
           [:div.empty-state "No posts yet. Add one above!"])

         ;; Load more button
         (when has-next?
           [:div {:style {:text-align "center" :padding "1rem 0"}}
            [:button.primary
             {:disabled fetching-next?
              :on-click #(rfq/fetch-next-page :feed/items feed-params)}
             (if fetching-next? "Loading more…" "Load More")]])

         (when (and (seq items) (not has-next?))
           [:div {:style {:text-align "center" :padding "1rem 0"
                          :font-size "0.85rem" :color "#999"}}
            "— End of feed —"])]

        [:div.loading "Initializing…"])]]))
