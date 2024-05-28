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

;;; NOTE: We use `doall` and `mapv` throughout the code to force evaluation to
;;; occur within the context of our dynamic binding of `clj-emit/-emit-form*`.

(defmulti -emit-form (fn [{:keys [op] :as ast} opts] op))

(defmethod -emit-form :narrow [{:keys [roles expr sugar?] :as ast} opts]
  ;; NOTE: We expect a child `:copy` node if `:sugar?` is set, but analyzer
  ;; passes might rewrite the AST so that that's no longer the case.
  (if (and (:sugar opts) sugar? (= (:op expr) :copy))
    (let [{expr' :expr :keys [src dst]} expr]
      `(~(symbol (str src "->" dst)) ~(clj-emit/-emit-form* expr' opts)))
    `(~'narrow ~roles ~(clj-emit/-emit-form* expr opts))))

(defmethod -emit-form :lifting [{:keys [roles body sugar?] :as ast} opts]
  (if (and (:sugar opts) sugar?)
    `(~(first roles) ~(clj-emit/-emit-form* body opts))
    `(~'lifting ~roles ~(clj-emit/-emit-form* body opts))))

(defmethod -emit-form :agree [{:keys [exprs] :as ast} opts]
  `(~'agree! ~@(doall (map #(clj-emit/-emit-form* % opts) exprs))))

(defmethod -emit-form :copy [{:keys [src dst expr sugar?] :as ast} opts]
  (if (and (:sugar opts) sugar?)
    `(~(symbol (str src "=>" dst)) ~(clj-emit/-emit-form* expr opts))
    `(~'copy [~src ~dst] ~(clj-emit/-emit-form* expr opts))))

(defmethod -emit-form :pack [{:keys [exprs] :as ast} opts]
  `(~'pack ~@(doall (map #(clj-emit/-emit-form* % opts) exprs))))

(defmethod -emit-form :unpack [{:keys [binder bindings init body] :as ast} opts]
  ;; NOTE: Recreate the binder from the bindings in case we are emitting
  ;; hygienically, since the `:binder` field is *not* uniquified.
  `(~'unpack*
    ~(if (:hygienic opts) (make-unpack-binder bindings) binder)
    ~(clj-emit/-emit-form* init opts)
    ~(clj-emit/-emit-form* body opts)))

(defmethod -emit-form :chor [{:keys [local signature params body] :as ast} opts]
  `(~'chor*
    ~@(when local [(clj-emit/-emit-form* local opts)])
    ~(render-type signature)
    ~(mapv #(clj-emit/-emit-form* % opts) params)
    ~(clj-emit/-emit-form* body opts)))

(defmethod -emit-form :inst [{:keys [name roles] :as ast} opts]
  `(~'inst ~name ~roles))

(defmethod -emit-form :invoke [{:keys [fn args sugar?] :as ast} opts]
  ;; NOTE: We expect a child `:copy` node if `:sugar?` is set, but analyzer
  ;; passes might rewrite the AST so that that's no longer the case.
  (if (and (:sugar opts) sugar? (= (:op fn) :inst))
    (let [{:keys [name roles]} fn]
      `(~name ~roles ~@(doall (map #(clj-emit/-emit-form* % opts) args))))
    (jvm-emit/-emit-form ast opts)))

(defmethod -emit-form :default [ast opts]
  (jvm-emit/-emit-form ast opts))

;;; `-emit-form*` is copied from `clojure.tools.analyzer.passes.jvm.emit-form`,
;;; which is itself a copy from `clojure.tools.analyzer.passes.emit-form`.

(defn -emit-form*
  [{:keys [form] :as ast} opts]
  (let [expr (-emit-form ast opts)
        form-meta (meta form)
        expr-meta (meta expr)
        type-meta (when (:type-meta opts)
                    (merge {:mask (:mask (:env ast))}
                           (when-let [t (:rtype ast)]
                             {:rtype (render-type t)})
                           (select-keys ast [:rmentions])))]
    (if (and (instance? clojure.lang.IObj expr)
             (or form-meta expr-meta type-meta))
      (with-meta expr (merge form-meta expr-meta type-meta))
      expr)))

(defn emit-form
  {:pass-info {:walk :none :depends #{#'uniquify-locals} :compiler true}}
  ([ast]
   (emit-form ast (:emit-form (:passes-opts (env/deref-env)))))
  ([ast opts]
   (binding [clj-emit/-emit-form* -emit-form*]
     (-emit-form* ast opts))))
