(ns example.uix-app.views
  "Main app component with tab navigation between example demos."
  (:require
   [example.uix-app.views.basic :as basic]
   [example.uix-app.views.dependent :as dependent]
   [example.uix-app.views.infinite :as infinite]
   [example.uix-app.views.inspector :as inspector]
   [example.uix-app.views.layout :as layout]
   [example.uix-app.views.mutations :as mutations]
   [example.uix-app.views.optimistic :as optimistic]
   [example.uix-app.views.polling :as polling]
   [example.uix-app.views.prefetching :as prefetching]
   [example.uix-app.views.websocket :as websocket]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as urf]))

(defui app []
  (let [active-tab (or (urf/use-subscribe [:ui/get :active-tab]) :basic)]
    ($ :div
       ($ :h1 "re-frame-query Demo")
       ($ :p.subtitle "Declarative data fetching & caching for re-frame (UIx)")
       ($ :p {:style {:font-size "0.8rem" :color "#888" :margin-bottom "1rem"
                      :background "#f8f8f8" :padding "0.5rem 0.75rem" :border-radius "6px"}}
          "💡 Open your browser's " ($ :strong "Console") " to see re-frame-query events, or the "
          ($ :strong "Network") " tab to watch requests fire automatically.")
       ($ layout/tab-bar {:active active-tab
                          :on-select #(rf/dispatch [:ui/set :active-tab %])})
       ($ :div {:style {:margin-top "1.25rem"}}
          (case active-tab
            :basic ($ basic/panel)
            :polling ($ polling/panel)
            :dependent ($ dependent/panel)
            :prefetching ($ prefetching/panel)
            :mutations ($ mutations/panel)
            :websocket ($ websocket/panel)
            :optimistic ($ optimistic/panel)
            :infinite ($ infinite/panel)
            ($ basic/panel)))
       ($ inspector/panel))))
