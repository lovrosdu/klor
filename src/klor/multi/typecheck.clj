(ns klor.multi.typecheck
  (:require
   [clojure.set :as set]
   [clojure.tools.analyzer.ast :refer [children update-children]]
   [clojure.tools.analyzer.utils :refer [mmerge]]
   [klor.multi.types :refer [parse-type type-roles normalize-type render-type
                             substitute-roles]]
   [klor.multi.roles :refer [validate-roles]]
   [klor.multi.util :refer [make-unpack-binder update-children*
                            analysis-error]]))

(defn unpack-binder-matches-type? [binder {:keys [ctor elems] :as type}]
  (or (not (vector? binder))
      (and (= ctor :tuple)
           (= (count binder) (count elems))
           (every? (fn [[b t]] (unpack-binder-matches-type? b t))
                   (map vector binder elems)))))

(defn unpack-binding-type [{:keys [position] :as binding} type]
  (loop [[p & ps :as position] position
         {:keys [ctor elems] :as type} type]
    (if (not (seq position)) type (recur ps (get elems p)))))

;;; Role Masks

(defn init-mask
  {:pass-info {:walk :none :depends #{#'validate-roles}}}
  [{:keys [env] :as ast}]
  (assoc-in ast [:env :mask] (:roles env)))

(defn propagate-mask [mask ast]
  (update-children ast #(assoc-in % [:env :mask] mask)))

(defmulti propagate-masks
  {:pass-info {:walk :pre :depends #{#'init-mask}}}
  :op)

(defmethod propagate-masks :mask [{:keys [roles] :as ast}]
  (propagate-mask (set roles) ast))

(defmethod propagate-masks :default [{:keys [env] :as ast}]
  (propagate-mask (:mask env) ast))

;;; Type Checking
;;;
;;; The type checking phase adds 2 keys to each AST node:
;;;
;;; - `:rtype` -- the node's type. `:type` is already used by the `:binding`
;;;   node so we use `:rtype` ("role type") to avoid conflict.
;;;
;;; - `:rmentions` -- a set of roles "mentioned" by the AST or any of its
;;;   children. "Mentioned" means that the role somehow participates in the
;;;   *computation* of the expression. For this purpose, `local` does *not*
;;;   represent any computation (as it only modifies the lifting rule),
;;;   so `(local [Ana] ...)` does not count as a mention of `Ana`.
;;;
;;; The phase maintains its own typing environment ("tenv") to make the code
;;; easier to follow, but the local environments of each AST node (under
;;; `[:env :locals]`) are updated with the above type information as well.
;;;
;;; The typing environment is similar to the local environment: it maps
;;; unqualified symbols (the name of a local binding, as it appears in the
;;; source, i.e. the `:form` field of a `:binding` node) to maps with the
;;; `:rtype` and `:rmentions` keys.
;;;
;;; The type checker uses a bidirectional type checking algorithm: some types
;;; are inferred (sometimes with the help of user-provided type annotations),
;;; and other types are checked.

(defmulti -typecheck (fn [tenv {:keys [op] :as ast}] op))

(defn typecheck
  {:pass-info {:walk :none :depends #{#'validate-roles #'propagate-masks}}}
  [ast]
  (-typecheck {} ast))

(defn with-locals [ast tenv]
  (let [k1 (set (keys tenv))
        k2 (set (keys (:locals (:env ast))))]
    (assert (= k1 k2) (str "The typing and local environments have differing "
                           "keys: got " k1 ", expected " k2)))
  (update-in ast [:env :locals] mmerge tenv))

(defn with-type [ast type tenv]
  (let [roles (type-roles type)
        mentions (apply set/union roles (map :rmentions (children ast)))]
    (assoc (with-locals ast tenv) :rtype type :rmentions mentions)))

(defn extend-tenv [tenv bindings]
  (into tenv (for [{:keys [form] :as b} bindings]
               [form (select-keys b [:rtype :rmentions])])))

(defn -typecheck*
  ([tenv ast]
   (update-children ast (partial -typecheck tenv)))
  ([tenv ast children]
   (update-children* ast children (partial -typecheck tenv))))

(defmethod -typecheck :at [tenv ast]
  (let [{:keys [form env roles expr] :as ast'} (-typecheck* tenv ast)
        roles (set roles)
        {eroles :roles :keys [ctor] :as type} (:rtype expr)]
    (when-not (= ctor :agree)
      (analysis-error ["Argument to `at` is not of agreement type: " type]
                      form env))
    (if-let [diff (not-empty (set/difference roles eroles))]
      (analysis-error ["Argument to `at` is missing roles: " diff] form env))
    (with-type ast' (assoc type :roles roles) tenv)))

(defmethod -typecheck :mask [tenv ast]
  (let [{:keys [body] :as ast'} (-typecheck* tenv ast)]
    (with-type ast' (:rtype body) tenv)))

(defmethod -typecheck :copy [tenv ast]
  (let [{:keys [form env src dst expr] :as ast'} (-typecheck* tenv ast)
        {:keys [ctor roles] :as type} (:rtype expr)]
    (when-not (= ctor :agree)
      (analysis-error ["Argument to `copy` is not of agreement type: " type]
                      form env))
    (when-not (contains? roles src)
      (analysis-error ["Argument to `copy` is missing a role: " src] form env))
    (when (contains? roles dst)
      (analysis-error ["Argument to `copy` already contains role: " dst]
                      form env))
    (with-type ast' (assoc type :roles (conj roles dst)) tenv)))

(defmethod -typecheck :pack [tenv ast]
  (let [{:keys [exprs] :as ast'} (-typecheck* tenv ast)]
    (with-type ast' {:ctor :tuple :elems (mapv :rtype exprs)} tenv)))

(defmethod -typecheck :unpack [tenv {:keys [form env] :as ast}]
  (let [{:keys [binder bindings init] :as ast'} (-typecheck* tenv ast [:init])
        {:keys [ctor] :as type} (:rtype init)]
    (when-not (= ctor :tuple)
      (analysis-error ["`unpack*`'s initializer must be of tuple type: "
                       (render-type type)]
                      form env))
    (when-not (unpack-binder-matches-type? binder type)
      (analysis-error ["`unpack*`'s binder's shape doesn't match the type of "
                       "its initializer: got " binder ", expected"
                       (render-type type)]
                      form env))
    (let [;; Infer the type of each binding
          bindings' (mapv #(with-type % (unpack-binding-type % type) tenv)
                          bindings)
          ;; Update the node's bindings
          ast'' (assoc ast' :bindings bindings')
          ;; Update the typing environment
          tenv' (extend-tenv tenv bindings')
          ;; Typecheck the body
          {:keys [body] :as ast'''} (-typecheck* tenv' ast'' [:body])]
      ;; Update the node's type
      (with-type ast''' (:rtype body) tenv))))

(defmethod -typecheck :chor [tenv {:keys [form env params signature] :as ast}]
  (let [{sparams :params :keys [ret]} signature]
    (when-not (= (count params) (count sparams))
      (analysis-error ["`chor`'s parameter vector's shape doesn't match its "
                       "signature: got " (mapv :form params) ", expected "
                       (mapv render-type sparams)]
                      form env))
    (let [;; Infer the type of each param
          params' (mapv #(with-type %1 %2 tenv) params sparams)
          ;; Update the node's params
          ast' (assoc ast :params params')
          ;; Update the typing environment
          tenv' (extend-tenv tenv params')
          ;; Typecheck the body
          {:keys [body] :as ast''} (-typecheck* tenv' ast' [:body])
          {type :rtype mentions :rmentions} body]
      (when-not (= ret type)
        (analysis-error ["`chor`'s return type doesn't match its signature: "
                         "got " (render-type type) ", expected "
                         (render-type ret)]
                        form env))
      (with-type ast'' (normalize-type (assoc signature :aux mentions)) tenv))))

(defmethod -typecheck :inst [tenv {:keys [form env var roles] :as ast}]
  (let [{:keys [var meta]} var
        {croles :roles :keys [signature]} (:klor/chor meta)]
    (when-not (= (count croles) (count roles))
      (analysis-error ["`inst`'s number of roles doesn't match the "
                       "choreography's (" var "): got " roles ", expected "
                       croles]
                      form env))
    (with-type ast (substitute-roles signature (zipmap croles roles)) tenv)))

(defmethod -typecheck :fn [tenv {:keys [form env local] :as ast}]
  (let [;; Infer the type of the function's name, if present
        ltype {:ctor :agree :roles (:mask env)}
        local' (and local (with-type local ltype tenv))
        ;; Update the node's name
        ast' (assoc ast :local local')
        ;; Update the typing environment, if necessary
        tenv' (if local' (extend-tenv tenv [local']) tenv)
        ;; Typecheck each method
        ast'' (-typecheck* tenv' ast' [:methods])]
    (with-type ast'' ltype tenv)))

(defmethod -typecheck :fn-method [tenv {:keys [form env params] :as ast}]
  (let [;; Infer the type of each param
        ptype {:ctor :agree :roles (:mask env)}
        params' (mapv #(with-type %1 ptype tenv) params)
        ;; Update the node's params
        ast' (assoc ast :params params')
        ;; Update the typing environment
        tenv' (extend-tenv tenv params')
        ;; Typecheck the body
        {:keys [body] :as ast''} (-typecheck* tenv' ast' [:body])
        {:keys [ctor] :as type} (:rtype body)]
    (when-not (= type ptype)
      (analysis-error ["`fn`'s return type must be " (render-type ptype)]
                      form env))
    (with-type ast'' type tenv)))

(defmethod -typecheck :with-meta [tenv ast]
  (let [{:keys [expr] :as ast'} (-typecheck* tenv ast)]
    (with-type ast' (:rtype expr) tenv)))

(defmethod -typecheck :invoke [tenv {:keys [form env] :as ast}]
  (let [;; NOTE: Use `fn'` so that we don't shadow `fn`.
        {fn' :fn :keys [args] :as ast'} (-typecheck* tenv ast)
        {:keys [ctor params ret] :as type} (:rtype fn')]
    (case ctor
      ;; The operator is of agreement type: the type of the arguments must be of
      ;; that same agreement type
      :agree
      (if-let [arg (first (filter #(not= (:rtype %) type) args))]
        (analysis-error ["Invocation argument must be of the same agreement "
                         "type as its non-choreography operator: got "
                         (:form arg) " of type " (render-type (:rtype arg))
                         ", expected " (render-type type)]
                        form env)
        (with-type ast' type tenv))
      ;; The operator is a choreography: the types of the arguments must match
      ;; the types of their respective parameters
      :chor
      (let [c1 (count args)
            c2 (count params)]
        (when-not (= c1 c2)
          (analysis-error ["Wrong number of arguments in a choreography "
                           "invocation: got " c1 ", expected " c2]
                          form env))
        (when-let [[arg param] (first (filter (fn [[a p]] (not= (:rtype a) p))
                                              (map vector args params)))]
          (analysis-error ["Invocation argument doesn't match the "
                           "choreography's parameter type: got " (:form arg)
                           " of type " (render-type (:rtype arg)) ", expected "
                           (render-type param)]
                          form env))
        (with-type ast' ret tenv))
      ;; Otherwise, error
      (analysis-error ["Cannot invoke a value of non-agreement or "
                       "non-choreography type: " (render-type type)]
                      form env))))

(defmethod -typecheck :vector [tenv {:keys [form env] :as ast}]
  (let [type {:ctor :agree :roles (:mask env)}
        {:keys [items] :as ast'} (-typecheck* tenv ast)]
    (when-let [item (first (filter #(not= (:rtype %) type) items))]
      (analysis-error ["Element of a vector doesn't match its agreement type: "
                       "got " (:form item) " of type "
                       (render-type (:rtype item)) ", expected "
                       (render-type type)]
                      form env))
    (with-type ast' type tenv)))

(defmethod -typecheck :map [tenv {:keys [form env] :as ast}]
  (let [type {:ctor :agree :roles (:mask env)}
        {:keys [keys vals] :as ast'} (-typecheck* tenv ast)]
    (when-let [item (first (filter #(not= (:rtype %) type) (concat keys vals)))]
      (analysis-error ["Element of a map doesn't match its agreement type: "
                       "got " (:form item) " of type "
                       (render-type (:rtype item)) ", expected "
                       (render-type type)]
                      form env))
    (with-type ast' type tenv)))

(defmethod -typecheck :set [tenv {:keys [form env] :as ast}]
  (let [type {:ctor :agree :roles (:mask env)}
        {:keys [items] :as ast'} (-typecheck* tenv ast)]
    (when-let [item (first (filter #(not= (:rtype %) type) items))]
      (analysis-error ["Element of a set doesn't match its agreement type: "
                       "got " (:form item) " of type "
                       (render-type (:rtype item)) ", expected "
                       (render-type type)]
                      form env))
    (with-type ast' type tenv)))

(defmethod -typecheck :var [tenv {:keys [form env var meta] :as ast}]
  (when (get meta :klor/chor)
    (analysis-error ["Cannot refer to a choreographic definition without "
                     "instantiating it: " var]
                    form env))
  (with-type ast {:ctor :agree :roles (:mask env)} tenv))

(defmethod -typecheck :the-var [tenv {:keys [env] :as ast}]
  (with-type ast {:ctor :agree :roles (:mask env)} tenv))

(defmethod -typecheck :do [tenv ast]
  (let [{:keys [ret] :as ast'} (-typecheck* tenv ast)]
    (with-type ast' (:rtype ret) tenv)))

(defn typecheck-let-bindings [tenv bindings]
  (reduce (fn [[tenv' bindings'] b]
            (let [{:keys [init] :as b'} (-typecheck* tenv' b [:init])
                  b'' (with-type b' (:rtype init) tenv')]
              [(extend-tenv tenv' [b'']) (conj bindings' b'')]))
          [tenv []] bindings))

(defmethod -typecheck :let [tenv {:keys [form env bindings] :as ast}]
  (let [;; Infer the type of each binding and update the typing environment
        [tenv' bindings'] (typecheck-let-bindings tenv bindings)
        ;; Update the node's bindings
        ast' (assoc ast :bindings bindings')
        ;; Typecheck the body
        {:keys [body] :as ast''} (-typecheck* tenv' ast' [:body])]
    (with-type ast'' (:rtype body) tenv)))

(defmethod -typecheck :local [tenv {:keys [form] :as ast}]
  (assert (contains? tenv form)
          (str "Local missing from typing environment: " form ", " tenv))
  (with-type ast (get-in tenv [form :rtype]) tenv))

(defmethod -typecheck :if [tenv {:keys [form env] :as ast}]
  (let [{:keys [test then else] :as ast'} (-typecheck* tenv ast)
        [{:keys [ctor] :as type} type1 type2] (map :rtype [test then else])]
    (when (not= ctor :agree)
      (analysis-error ["`if`'s condition must be of agreement type: "
                       (render-type type)]
                      form env))
    (when (not= type1 type2)
      (analysis-error ["`if`'s branches must be of the same type: "
                       (render-type type1) " vs. " (render-type type2)]
                      form env))
    (with-type ast' type1 tenv)))

(defmethod -typecheck :quote [tenv ast]
  (let [{:keys [expr] :as ast'} (-typecheck* tenv ast)]
    (with-type ast' (:rtype expr) tenv)))

(defmethod -typecheck :const [tenv {:keys [env] :as ast}]
  (let [ast' (-typecheck* tenv ast)]
    (with-type ast' {:ctor :agree :roles (:mask env)} tenv)))

(defmethod -typecheck :default [tenv {:keys [op form env] :as ast}]
  (analysis-error ["Don't know how to typecheck " op ", yet!"] form env))

;;; Sanity Check

(defn -sanity-check [local? {:keys [op meta form env rtype rmentions] :as ast}]
  ;; NOTE: We skip references to vars that are choreography definitions, as they
  ;; are technically of a polymorphic type which we do not represent in our
  ;; types for now.
  (when-not (or (and (= op :var) (:klor/chor meta)))
    (assert rtype (str "Missing type: " [local? form]))
    (assert (try (parse-type (render-type rtype)) (catch Throwable t))
            (str "Invalid type: " rtype ", " [local? form]))
    (assert (not-empty rmentions)
            (str "Missing role mentions: " [local? form]))
    (when-not local?
      (assert (:mask env) (str "Missing mask: " form))
      (assert (every? (partial -sanity-check true) (vals (:locals env)))
              (str "Incorrect locals: " (:locals env)))))
  true)

(defn sanity-check
  {:pass-info {:walk :post :depends #{#'typecheck}}}
  [ast]
  (-sanity-check false ast)
  ast)
