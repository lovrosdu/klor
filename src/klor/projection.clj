(ns klor.projection
  (:refer-clojure :exclude [send])
  (:require
   [clojure.tools.analyzer.env :as env]
   [clojure.tools.analyzer.ast :refer [children]]
   [clojure.tools.analyzer.passes.jvm.emit-form :as jvm-emit]
   [klor.runtime :refer [noop send recv make-proj]]
   [klor.types :refer [type-roles]]
   [klor.typecheck :refer [typecheck sanity-check]]
   [klor.util :refer [usym? ast-error]]))

;;; Util

(defn projection-error [msg ast & {:as kvs}]
  (ast-error :klor/projection msg ast kvs))

;;; Role Checks

(defn mentions? [{:keys [role] :as ctx} {:keys [rmentions] :as ast}]
  (contains? rmentions role))

(defn has-result-for-type? [{:keys [role] :as ctx} type]
  (contains? (type-roles type) role))

(defn has-result-for-node? [ctx {:keys [rtype] :as ast}]
  (has-result-for-type? ctx rtype))

(defn role= [{:keys [role] :as ctx} r]
  (= role r))

;;; Emission

(defn emit-tag [expr]
  (with-meta expr {:klor/proj true}))

(defn emit-do [body]
  (if (empty? body) `noop (emit-tag `(do ~@body))))

(defn emit-effects [body]
  (emit-do (concat body [`noop])))

(defn emit-let [bindings body]
  (emit-tag `(let [~@(apply concat bindings)] ~(emit-do body))))

;;; Projection

(defmulti -project (fn [ctx {:keys [op] :as ast}] op))

(defn -project* [ctx {:keys [form] :as ast}]
  ;; NOTE: Similarly to `clojure.tools.analyzer.passes.emit-form/-emit-form*`,
  ;; we reattach any source metadata to the projected forms.
  (let [proj (if (mentions? ctx ast) (-project ctx ast) `noop)
        form-meta (meta form)
        proj-meta (meta proj)]
    (if (and (instance? clojure.lang.IObj proj) (or form-meta proj-meta))
      (with-meta proj (merge form-meta proj-meta))
      proj)))

(defn project
  {:pass-info {:walk :none
               :depends #{#'typecheck}
               :after #{#'sanity-check}
               :compiler true}}
  ([ast]
   (project ast (:project (:passes-opts (env/deref-env)))))
  ([ast & {:keys [role] :as ctx}]
   (assert (usym? role) "Role must be an unqualified symbol")
   (assert (:rtype ast) "AST is missing type information")
   (-project* ctx ast)))

;;; Utilities

(defn project-with-names
  ([ctx exprs]
   (project-with-names ctx (repeatedly gensym) exprs))
  ([ctx names exprs]
   (keep (fn [[name expr]]
           (cond
             (not (mentions? ctx expr))      nil
             (has-result-for-node? ctx expr) [name (-project* ctx expr)]
             :else                           ['_ (-project* ctx expr)]))
         (map vector names exprs))))

(defn project-vals [ctx exprs emit-fn]
  (let [projs (project-with-names ctx exprs)
        effects? (some #{'_} (map first projs))
        bindings (remove (comp #{'_} first) projs)]
    (cond
      (not effects?)    (emit-fn (map second projs))
      (empty? bindings) (emit-effects (concat (map second projs)))
      :else             (emit-let projs [(emit-fn (map first bindings))]))))

;;; Choreographic

(defmethod -project :narrow [ctx {:keys [expr] :as ast}]
  ;; NOTE: If we have a result for the `narrow`, then we also have a result for
  ;; `expr`. The converse is not true: we might not have a result for `narrow`,
  ;; *even* if we have a result for `expr`, because the point of `narrow` is to
  ;; restrict the location of `expr`'s result.
  (if (has-result-for-node? ctx ast)
    (-project* ctx expr)
    (emit-effects [(-project* ctx expr)])))

(defmethod -project :lifting [ctx {:keys [body] :as ast}]
  (-project* ctx body))

(defmethod -project :agree [ctx {:keys [exprs] :as ast}]
  ;; NOTE: There is at most one expression in `exprs` for which we have a
  ;; result, and its result is also the result of the `agree!`.
  (project-vals ctx exprs first))

(defmethod -project :copy [ctx {:keys [src dst expr env] :as ast}]
  (cond
    (role= ctx src) (let [idx (.indexOf (:roles env) dst)]
                      `(send ~idx ~(-project* ctx expr)))
    (role= ctx dst) (let [idx (.indexOf (:roles env) src)]
                      (emit-do [(-project* ctx expr) `(recv ~idx)]))
    :else           (-project* ctx expr)))

(defmethod -project :pack [ctx {:keys [exprs] :as ast}]
  (project-vals ctx exprs vec))

(defn project-unpack-binder [ctx binder {:keys [rtype] :as init}]
  ((fn rec [[binder {:keys [elems] :as type}]]
     (cond
       (not (has-result-for-type? ctx type)) nil
       (symbol? binder) binder
       :else (vec (keep rec (map vector binder elems)))))
   [binder rtype]))

(defmethod -project :unpack [ctx {:keys [binder init body] :as ast}]
  (if-let [binder' (project-unpack-binder ctx binder init)]
    (emit-let [[binder' (-project* ctx init)]] [(-project* ctx body)])
    (emit-let [] [(-project* ctx init) (-project* ctx body)])))

(defmethod -project :chor [ctx {:keys [top-level local params body] :as ast}]
  (let [params' (filter (partial has-result-for-node? ctx) params)
        f `(fn ~@(when local [(:form local)]) [~@(map :form params')]
             ~(-project* ctx body))]
    (if top-level f `(make-proj ~f))))

(defmethod -project :inst
  [{:keys [role] :as ctx} {:keys [name roles env] :as ast}]
  (let [idx (.indexOf roles role)
        idxs (mapv #(.indexOf (:roles env) %) roles)]
    `(make-proj ~name ~idx ~idxs)))

;;; Binding & Control Flow

(defmethod -project :let [ctx {:keys [bindings body] :as ast}]
  (emit-let (project-with-names ctx (map :form bindings) (map :init bindings))
            [(-project* ctx body)]))

(defmethod -project :do [ctx {:keys [statements ret body?] :as ast}]
  (let [exprs (map (partial -project* ctx) (concat statements [ret]))]
    (if body? (emit-do exprs) `(do ~@exprs))))

(defmethod -project :if [ctx {:keys [test then else] :as ast}]
  (if (and (has-result-for-node? ctx test)
           (some (partial mentions? ctx) [then else]))
    `(if ~@(map (partial -project* ctx) [test then else]))
    (emit-effects [(-project* ctx test)])))

(defmethod -project :case
  [ctx {:keys [test default tests thens shift mask
               switch-type test-type skip-check?]
        :as ast}]
  (if (and (has-result-for-node? ctx test)
           (some (partial mentions? ctx) (concat thens [default])))
    ;; Taken and adjusted from `clojure.tools.analyzer.passes.jvm.emit-form`.
    `(case* ~(-project* ctx test)
            ~shift ~mask
            ~(-project* ctx default)
            ~(apply sorted-map
                    (mapcat (fn [{:keys [hash test]} {:keys [then]}]
                              [hash (mapv (partial -project* ctx) [test then])])
                            tests thens))
            ~switch-type ~test-type ~skip-check?)
    (emit-effects [(-project* ctx test)])))

(defmethod -project :try [ctx ast]
  (jvm-emit/emit-form ast))

(defmethod -project :catch [ctx ast]
  (jvm-emit/emit-form ast))

(defmethod -project :throw [ctx {:keys [exception] :as ast}]
  (project-vals ctx [exception] (fn [projs] `(throw ~@projs))))

;;; Functions & Invocation

(defmethod -project :fn [ctx ast]
  ;; NOTE: We let `emit-form` recursively emit the whole `fn`, i.e. all of its
  ;; methods, including their parameters and bodies, because `fn` is type
  ;; checked as homogeneous code that has to be the same at all mentioned roles!
  (jvm-emit/emit-form ast))

(defmethod -project :invoke [ctx {fn' :fn :keys [args] :as ast}]
  (project-vals ctx (cons fn' args) list*))

(defmethod -project :recur [ctx {:keys [exprs] :as ast}]
  (project-vals ctx exprs (fn [projs] `(recur ~@projs))))

;;; References

(defmethod -project :local [ctx ast]
  (jvm-emit/emit-form ast))

(defmethod -project :var [ctx ast]
  (jvm-emit/emit-form ast))

(defmethod -project :the-var [ctx ast]
  (jvm-emit/emit-form ast))

;;; Collections

(defmethod -project :vector [ctx {:keys [items] :as ast}]
  (project-vals ctx items vec))

(defn ensure-distinct [projs]
  (if (not (apply distinct? projs))
    (map (fn [proj] `((fn ~(gensym) [] ~proj))) projs)
    projs))

(defmethod -project :map [ctx {:keys [keys vals] :as ast}]
  (project-vals ctx (concat keys vals)
                (fn [projs]
                  (let [[keys vals] (split-at (count keys) projs)]
                    (zipmap (ensure-distinct keys) vals)))))

(defmethod -project :set [ctx {:keys [items] :as ast}]
  (project-vals ctx items (comp set ensure-distinct)))

;;; Constants

(defmethod -project :const [ctx ast]
  (jvm-emit/emit-form ast))

(defmethod -project :quote [ctx ast]
  (jvm-emit/emit-form ast))

(defmethod -project :with-meta [ctx {:keys [expr meta] :as ast}]
  ;; NOTE: The expression is evaluated before its metadata.
  (project-vals ctx [expr meta] #(apply with-meta %1)))

;;; Host Interop

(defmethod -project :new [ctx {:keys [class args] :as ast}]
  (project-vals ctx (cons class args) (fn [projs] `(new ~@projs))))

(defmethod -project :host-interop [ctx {:keys [target m-or-f] :as ast}]
  (project-vals ctx [target] (fn [projs] `(. ~@projs ~m-or-f))))

(defmethod -project :instance-field [ctx {:keys [instance field] :as ast}]
  (project-vals ctx [instance]
                (fn [projs] `(~(symbol (str ".-" (name field))) ~@projs))))

(defmethod -project :instance-call [ctx {:keys [instance method args] :as ast}]
  (project-vals ctx (cons instance args)
                (fn [projs] `(~(symbol (str "." (name method))) ~@projs))))

(defmethod -project :static-field [ctx ast]
  (jvm-emit/emit-form ast))

(defmethod -project :static-call [ctx {:keys [class method args] :as ast}]
  (project-vals ctx args (fn [projs]
                           `(~(symbol (.getName ^Class class) (name method))
                             ~@projs))))

(defmethod -project :default [ctx {:keys [op] :as ast}]
  (projection-error ["Don't know how to project " op ", yet!"] ast))

;;; Cleanup

(defmulti -cleanup (fn [opts {:keys [op] :as ast}] op))

(defn cleanup
  {:pass-info {:walk :post}}
  ([ast]
   (-cleanup (:cleanup (:passes-opts (env/deref-env))) ast))
  ([ast & {:as opts}]
   (-cleanup opts ast)))

(defn cleanup? [{:keys [style] :as opts} {:keys [form] :as ast}]
  (case style
    :aggressive true
    :normal (:klor/proj (meta form))
    false))

(defn inline-immediate-dos [opts ast]
  (letfn [(inline [{:keys [op] :as ast}]
            (if (and (= op :do) (cleanup? opts ast))
              (children ast)
              [ast]))]
    (let [exprs (mapcat inline (children ast))]
      (assoc ast :statements (vec (butlast exprs)) :ret (last exprs)))))

(defn pure? [{:keys [style] :as opts} {:keys [op] :as ast}]
  (or (and (= style :aggressive)
           (contains? #{:const :fn :local :var :the-var :quote} op))
      (and (contains? #{:aggressive :normal} style)
           (case op
             :do (every? (partial pure? opts) (children ast))
             :var (= (:var ast) #'noop)
             false))))

(defmethod -cleanup :let [opts {:keys [bindings body] :as ast}]
  (if (and (cleanup? opts ast) (empty? bindings)) body ast))

(defmethod -cleanup :do [opts ast]
  (let [{:keys [statements ret] :as ast'} (inline-immediate-dos opts ast)
        statements' (remove (partial pure? opts) statements)]
    (if (and (empty? statements') (cleanup? opts ast))
      ret
      (assoc ast' :statements (vec statements')))))

(defmethod -cleanup :default [opts ast]
  ast)
