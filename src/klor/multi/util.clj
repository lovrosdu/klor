(ns klor.multi.util
  (:require [clojure.tools.analyzer.ast :refer [update-children]]
            [clojure.tools.analyzer.utils :refer [-source-info]]))

;;; Clojure

(defn assoc-inv [vec [k & _ :as ks] val init]
  (if (empty? ks)
    val
    (let [c (count vec)
          vec (if (< k c) vec (into (or vec []) (repeat (inc (- k c)) init)))
          cur (get vec k)]
      (assoc vec k (assoc-inv (if (= cur init) [] cur) (next ks) val init)))))

(defn usym? [x]
  (and (symbol? x) (not (namespace x))))

(defn -str [& xs]
  ;; NOTE: Clojure's `str` returns the empty string for nil, while `print-str`
  ;; unconditionally adds spaces between arguments.
  (apply str (replace {nil "nil"} xs)))

(defmacro do1 [expr & exprs]
  `(let [val# ~expr]
     ~@exprs
     val#))

;;; Klor

(defn unpack-binder? [x]
  (and (vector? x)
       (not-empty x)
       (every? (some-fn usym? unpack-binder?) x)))

(defn make-copy [src dst]
  (symbol (str src '=> dst)))

(defn make-move [src dst]
  (symbol (str src '-> dst)))

;;; AST

(defn update-children* [ast children f]
  (-> ast
      (assoc :children children)
      (update-children f)
      (assoc :children (:children ast))))

(defn replace-children [ast smap]
  (update-children ast #(get smap % %)))

;;; Errors

(defn make-message [message]
  (if (string? message) message (apply -str message)))

(defn error [tag message & {:as options}]
  (throw (ex-info (make-message message) (merge {:tag tag} options))))

(defn warn [message]
  (binding [*out* *err*]
    (println (str "WARNING: " (make-message message)))))

(defn form-error [tag msg form env & {:as kvs}]
  (error tag [form ": " (make-message msg)]
         (merge {:form form} (-source-info form env) kvs)))

(defn ast-error [tag msg {:keys [raw-forms form env] :as ast} & {:as kvs}]
  (form-error tag msg (or (first raw-forms) form) env kvs))

;;; Virtual Threads

(defn virtual-thread-call [f]
  (let [p (promise)]
    ;; NOTE: Capture the currently active dynamic bindings.
    (.. Thread (ofVirtual) (start (bound-fn [] (deliver p (f)))))
    p))

(defmacro virtual-thread [& body]
  `(virtual-thread-call (fn [] ~@body)))

(defn virtual-thread-call* [f]
  ;; NOTE: Capture the currently active dynamic bindings.
  (.. Thread (ofVirtual) (unstarted (bound-fn [] (f)))))

(defmacro virtual-thread* [& body]
  `(virtual-thread-call* (fn [] ~@body)))
