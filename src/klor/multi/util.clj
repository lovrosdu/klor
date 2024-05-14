(ns klor.multi.util
  (:require [clojure.tools.analyzer.utils :refer [-source-info]]
            [clojure.tools.analyzer.ast :refer [update-children]]
            [klor.util :refer [error]]))

(defn assoc-inv [vec [k & _ :as ks] val init]
  (if (empty? ks)
    val
    (let [c (count vec)
          vec (if (< k c) vec (into (or vec []) (repeat (inc (- k c)) init)))
          cur (get vec k)]
      (assoc vec k (assoc-inv (if (= cur init) [] cur) (next ks) val init)))))

(defn usym? [x]
  (and (symbol? x) (not (namespace x))))

(defn unpack-binder? [x]
  (and (vector? x)
       (not-empty x)
       (every? (some-fn usym? unpack-binder?) x)))

(defn update-children* [ast children f]
  (-> ast
      (assoc :children children)
      (update-children f)
      (assoc :children (:children ast))))

(defn replace-children [ast smap]
  (update-children ast #(get smap % %)))

(defn analysis-error [msg form env & {:as kvs}]
  (error :klor/analyzer msg (merge {:form form} (-source-info form env) kvs)))
