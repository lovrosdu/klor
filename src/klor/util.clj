(ns klor.util
  (:require [metabox.core :refer [box]]))

;;; Reporting

(defn make-message [message]
  (if (string? message) message (apply str message)))

(defn error [type message & {:as options}]
  (throw (ex-info (make-message message) (merge {:type type} options))))

(defn warn [message]
  (binding [*out* *err*]
    (println (str "WARNING: " (make-message message)))))

;;; Metadata

(defn merge-meta [x & {:as m}]
  "Return X with metadata that's the result of merging M into X's existing
  metadata (keys in M take precedence)."
  (vary-meta x #(merge % m)))

(defn metaify
  "If X doesn't implement `clojure.lang.IObj`, return a `MetaBox` containing X so
  that metadata can be attached. Otherwise, return X.

  If M is given, merge it into the metadata of X with `merge-meta`."
  ([x]
   (if (instance? clojure.lang.IObj x) x (box x)))
  ([x & {:as m}]
   (merge-meta (metaify x) m)))

(defn unmetaify
  "If X is a `MetaBox`, return the value contained inside. Otherwise, return X."
  [x]
  (if (instance? metabox.MetaBox x) @x x))

;;; Walking

(defn form-dispatch [ctx form]
  (cond
    ;; Non-list compound form
    (vector? form) :vector
    (map? form) :map
    (set? form) :set
    ;; Role form
    (and (seq? form) (contains? (:roles ctx) (first form))) :role
    ;; Other list compound form
    (seq? form) (first form)
    ;; Atom
    :else :atom))
