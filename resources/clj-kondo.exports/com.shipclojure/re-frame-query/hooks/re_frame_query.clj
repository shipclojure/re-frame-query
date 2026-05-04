(ns hooks.re-frame-query
  (:require [clj-kondo.hooks-api :as api]))

(defn reg-query [{:keys [node]}]
  (let [[fn-node kw-node & rest-children] (:children node)]
    (if (api/keyword-node? kw-node)
      {:node (api/list-node
              (list* fn-node
                     (api/reg-keyword! kw-node 're-frame.query/reg-query)
                     rest-children))}
      {:node node})))

(defn reg-mutation [{:keys [node]}]
  (let [[fn-node kw-node & rest-children] (:children node)]
    (if (api/keyword-node? kw-node)
      {:node (api/list-node
              (list* fn-node
                     (api/reg-keyword! kw-node 're-frame.query/reg-mutation)
                     rest-children))}
      {:node node})))

(defn init! [{:keys [node]}]
  (let [[fn-node config-node] (:children node)]
    (if (api/map-node? config-node)
      (let [new-children
            (mapcat
             (fn [[section-kw section-map]]
               (if (and (api/keyword-node? section-kw)
                        (api/map-node? section-map))
                 (let [section (api/sexpr section-kw)
                       reg-sym (case section
                                 :queries   're-frame.query/reg-query
                                 :mutations 're-frame.query/reg-mutation
                                 nil)]
                   (if reg-sym
                     [section-kw
                      (api/map-node
                       (mapcat (fn [[kw-node v]]
                                 (if (api/keyword-node? kw-node)
                                   [(api/reg-keyword! kw-node reg-sym) v]
                                   [kw-node v]))
                               (partition 2 (:children section-map))))]
                     [section-kw section-map]))
                 [section-kw section-map]))
             (partition 2 (:children config-node)))]
        {:node (api/list-node
                [fn-node (api/map-node new-children)])})
      {:node node})))
