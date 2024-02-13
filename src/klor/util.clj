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

(defn fully-qualify [ns symbol]
  (clojure.core/symbol (or (namespace symbol) ns) (name symbol)))

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

;;; The 3 functions below are taken from
;;; <https://clojure.atlassian.net/browse/CLJ-2568>.

(defn walk
  "Like `clojure.walk/walk`, except it preserves metadata."
  [inner outer form]
  (cond
    (list? form)
    (outer (with-meta (apply list (map inner form)) (meta form)))

    (instance? clojure.lang.IMapEntry form)
    (outer (clojure.lang.MapEntry/create (inner (key form)) (inner (val form))))

    (seq? form)
    (outer (with-meta (doall (map inner form)) (meta form)))

    (instance? clojure.lang.IRecord form)
    (outer (reduce (fn [r x] (conj r (inner x))) form form))

    (coll? form)
    (outer (with-meta (into (empty form) (map inner form)) (meta form)))

    :else
    (outer form)))

(defn postwalk
  "Like `clojure.walk/postwalk`, except it preserves metadata."
  [f form]
  (walk (partial postwalk f) f form))

(defn prewalk
  "Like `clojure.walk/prewalk`, except it preserves metadata."
  [f form]
  (walk (partial prewalk f) identity (f form)))

;;; Virtual Threads

(defn virtual-thread-call [f]
  (let [p (promise)]
    ;; NOTE: Capture the currently active dynamic bindings.
    (.. Thread (ofVirtual) (start (bound-fn [] (deliver p (f)))))
    p))

(defmacro virtual-thread [& body]
  `(virtual-thread-call (fn [] ~@body)))
