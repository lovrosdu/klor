(ns klor.multi.emit-form
  (:require [clojure.tools.analyzer.passes.emit-form :as clj-emit]
            [clojure.tools.analyzer.passes.jvm.emit-form :as jvm-emit]
            [clojure.tools.analyzer.passes.uniquify :refer [uniquify-locals]]
            [klor.multi.types :refer [render-type]]
            [klor.multi.util :refer [make-unpack-binder]]))

(defmulti -emit-form (fn [{:keys [op]} _] op))

(defmethod -emit-form :at [{:keys [roles expr]} opts]
  `(~'at ~roles ~(clj-emit/-emit-form* expr opts)))

(defmethod -emit-form :mask [{:keys [roles body role?]} opts]
  (if role?
    `(~(first roles) ~(clj-emit/-emit-form* body opts))
    `(~'local ~roles ~(clj-emit/-emit-form* body opts))))

(defmethod -emit-form :copy [{:keys [src dst expr]} opts]
  `(~'copy [~src ~dst] ~(clj-emit/-emit-form* expr opts)))

(defmethod -emit-form :pack [{:keys [exprs]} opts]
  ;; NOTE: We use `mapv` to force the evaluation of the whole list while our
  ;; dynamic binding of `clj-emit/-emit-form*` is active!
  `(~'pack ~@(mapv #(clj-emit/-emit-form* % opts) exprs)))

(defmethod -emit-form :unpack [{:keys [bindings init body]} opts]
  ;; NOTE: Recreate the binder from the bindings in case we are emitting
  ;; hygienically.
  `(~'unpack* ~(make-unpack-binder bindings opts)
    ~(clj-emit/-emit-form* init opts)
    ~(clj-emit/-emit-form* body opts)))

(defmethod -emit-form :chor [{:keys [signature params body]} opts]
  `(~'chor* ~(render-type signature)
    ~(mapv #(clj-emit/-emit-form* % opts) params)
    ~(clj-emit/-emit-form* body opts)))

(defmethod -emit-form :inst [{:keys [var roles]} opts]
  `(~'inst ~(clj-emit/-emit-form* var opts) ~roles))

(defmethod -emit-form :default [ast opts]
  (jvm-emit/-emit-form ast opts))

;;; `-emit-form*` is copied from `clojure.tools.analyzer.passes.jvm.emit-form`,
;;; which is itself a copy from `clojure.tools.analyzer.passes.emit-form`.

(defn -emit-form*
  [{:keys [form] :as ast} opts]
  (let [expr (-emit-form ast opts)
        expr (if-let [m (and (instance? clojure.lang.IObj expr) (meta form))]
               (with-meta expr (merge m (meta expr)))
               expr)]
    expr))

(defn emit-form
  {:pass-info {:walk :none :depends #{#'uniquify-locals} :compiler true}}
  ([ast]
   (emit-form ast #{}))
  ([ast opts]
   (binding [clj-emit/-emit-form* -emit-form*]
     (-emit-form* ast opts))))

(defn emit-hygienic-form
  {:pass-info {:walk :none :depends #{#'uniquify-locals} :compiler true}}
  [ast]
  (emit-form ast #{:hygienic}))
