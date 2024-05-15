(ns klor.multi.emit-form
  (:require [clojure.tools.analyzer.env :as env]
            [clojure.tools.analyzer.passes.emit-form :as clj-emit]
            [clojure.tools.analyzer.passes.jvm.emit-form :as jvm-emit]
            [clojure.tools.analyzer.passes.uniquify :refer [uniquify-locals]]
            [klor.multi.types :refer [render-type]]
            [klor.multi.util :refer [assoc-inv]]))

(defn make-unpack-binder [bindings]
  (reduce (fn [binder {:keys [name form position] :as binding}]
            (assoc-inv
             binder position
             ;; Copied from `clojure.tools.analyzer.passes.emit-form`.
             (with-meta name (meta form))
             '_))
          [] bindings))

;;; NOTE: We use `mapv` throughout the code to force the evaluation of sequences
;;; within the context of the dynamic binding of `clj-emit/-emit-form*`.

(defmulti -emit-form (fn [{:keys [op] :as ast} opts] op))

(defmethod -emit-form :at [{:keys [roles expr sugar?]} opts]
  (if (and (:sugar opts) sugar?)
    (let [{expr' :expr :keys [op src dst]} expr]
      (assert (= op :copy) "Expected a child `:copy` node when `sugar?` is set")
      `(~(symbol (str src "->" dst)) ~(clj-emit/-emit-form* expr' opts)))
    `(~'at ~roles ~(clj-emit/-emit-form* expr opts))))

(defmethod -emit-form :mask [{:keys [roles body sugar?]} opts]
  (if (and (:sugar opts) sugar?)
    `(~(first roles) ~(clj-emit/-emit-form* body opts))
    `(~'local ~roles ~(clj-emit/-emit-form* body opts))))

(defmethod -emit-form :copy [{:keys [src dst expr sugar?]} opts]
  (if (and (:sugar opts) sugar?)
    `(~(symbol (str src "=>" dst)) ~(clj-emit/-emit-form* expr opts))
    `(~'copy [~src ~dst] ~(clj-emit/-emit-form* expr opts))))

(defmethod -emit-form :pack [{:keys [exprs]} opts]
  `(~'pack ~@(mapv #(clj-emit/-emit-form* % opts) exprs)))

(defmethod -emit-form :unpack [{:keys [binder bindings init body]} opts]
  ;; NOTE: Recreate the binder from the bindings in case we are emitting
  ;; hygienically, since the `:binder` field is *not* uniquified.
  `(~'unpack*
    ~(if (:hygienic opts) (make-unpack-binder bindings) binder)
    ~(clj-emit/-emit-form* init opts)
    ~(clj-emit/-emit-form* body opts)))

(defmethod -emit-form :chor [{:keys [local signature params body]} opts]
  `(~'chor*
    ~@(when local [(clj-emit/-emit-form* local opts)])
    ~(render-type signature)
    ~(mapv #(clj-emit/-emit-form* % opts) params)
    ~(clj-emit/-emit-form* body opts)))

(defmethod -emit-form :inst [{:keys [name roles]} opts]
  `(~'inst ~name ~roles))

(defmethod -emit-form :invoke [{:keys [fn args sugar?] :as ast} opts]
  (if (and (:sugar opts) sugar?)
    (let [{:keys [op name roles]} fn]
      (assert (= op :inst) "Expected a child `:inst` node when `sugar?` is set")
      `(~name ~roles ~@(mapv #(clj-emit/-emit-form* % opts) args)))
    (jvm-emit/-emit-form ast opts)))

(defmethod -emit-form :default [ast opts]
  (jvm-emit/-emit-form ast opts))

;;; `-emit-form*` is copied from `clojure.tools.analyzer.passes.jvm.emit-form`,
;;; which is itself a copy from `clojure.tools.analyzer.passes.emit-form`.

(defn -emit-form*
  [{:keys [form] :as ast} opts]
  (let [expr (-emit-form ast opts)
        type-meta (when (:types opts)
                    (merge {:mask (:mask (:env ast))}
                           (if-let [t (:rtype ast)]
                             {:rtype (render-type t)})
                           (select-keys ast [:rmentions])))]
    (if (instance? clojure.lang.IObj expr)
      (with-meta expr (merge (meta form) (meta expr) type-meta))
      expr)))

(defn emit-form
  {:pass-info {:walk :none :depends #{#'uniquify-locals} :compiler true}}
  ([ast]
   (emit-form ast (:emit-form (:passes-opts (env/deref-env)))))
  ([ast opts]
   (binding [clj-emit/-emit-form* -emit-form*]
     (-emit-form* ast opts))))
