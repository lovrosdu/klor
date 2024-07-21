(ns klor.multi.typecheck
  (:require
   [clojure.set :as set]
   [clojure.core.match :refer [match]]
   [clojure.tools.analyzer.ast :as ast :refer [children update-children]]
   [clojure.tools.analyzer.env :as env]
   [clojure.tools.analyzer.passes.jvm.validate :as jvm-validate]
   [klor.multi.types :refer
    [parse-type type-roles normalize-type render-type replace-roles]]
   [klor.multi.validate-roles :refer [validate-roles]]
   [klor.multi.util :refer
    [update-children* replace-children -str ast-error]]))

;;; Util

(defn type-error [msg ast & {:as kvs}]
  (ast-error :klor/type msg ast kvs))

(defn unpack-binder-matches-type? [binder {:keys [ctor elems] :as type}]
  (or (symbol? binder)
      (and (= ctor :tuple)
           (= (count binder) (count elems))
           (every? (fn [[b t]] (unpack-binder-matches-type? b t))
                   (map vector binder elems)))))

(defn unpack-binding-type [{:keys [position] :as binding} type]
  (loop [[p & ps :as position] position
         {:keys [elems] :as type} type]
    (if (not (seq position)) type (recur ps (get elems p)))))

;;; Lifting Masks

(defn init-mask
  {:pass-info {:walk :none :depends #{#'validate-roles}}}
  [{:keys [env] :as ast}]
  (assoc-in ast [:env :mask] (set (:roles env))))

(defn propagate-mask [mask ast]
  (update-children ast #(assoc-in % [:env :mask] mask)))

(defmulti propagate-masks
  {:pass-info {:walk :pre :depends #{#'init-mask}}}
  :op)

(defmethod propagate-masks :lifting [{:keys [roles] :as ast}]
  (propagate-mask (set roles) ast))

(defmethod propagate-masks :default [{:keys [env] :as ast}]
  (propagate-mask (:mask env) ast))

;;; Type Checking
;;;
;;; The type checking phase adds 2 keys to each AST node:
;;;
;;; - `:rtype` -- the choreographic type of the expression represented by the
;;;   node. `:type` is already used as a key by the `:binding` node so we use
;;;   `:rtype` ("role type") to avoid conflict.
;;;
;;; - `:rmentions` -- a set of roles "mentioned" by the expression represented
;;;   by the node or any of its children. "Mentioned" means that the role
;;;   somehow participates/is involved in the computation of the expression.
;;;   This does not imply that the role is present within `:rtype` however -- a
;;;   role might be mentioned by an expression but have no resulting value.
;;;
;;;   The set of mentions is essentially the union of all roles that occur in
;;;   the types of all of the expression's subexpressions. Since `lifting` only
;;;   modifies the lifting rule, `(lifting [Ana] <body>)` will not contain `Ana`
;;;   in its mentions unless an expression within the body contains `Ana` in its
;;;   type.
;;;
;;; The phase maintains its own typing environment ("tenv") to make the code
;;; easier to follow, but the local binding map of each AST node (under
;;; `[:env :locals]`) is updated with the above type information as well.
;;;
;;; The typing environment is similar to the local environment:
;;;
;;; - Its `:locals` key is a map that maps unqualified symbols (the name of a
;;;   local binding, as it appears in the source, i.e. the `:form` field of a
;;;   `:binding` node) to maps with the `:rtype` and `:rmentions` keys.
;;;
;;; - Its `:recur-params` and `:recur-ret` keys store a vector of types of the
;;;   parameters of a call to `recur` in the current context, and the type of
;;;   its return value, respectively.
;;;
;;; - Its `:homo-type` key stores the agreement type to which a homogeneous
;;;   operator (`fn`/`try`/`catch`) currently being type checked is restricted.
;;;
;;; The type checker uses a bidirectional type checking algorithm: some types
;;; are inferred (sometimes with the help of user-provided type annotations),
;;; and other types are checked.

(defmulti -typecheck (fn [tenv {:keys [op] :as ast}] op))

(defn typecheck
  {:pass-info
   {:walk :none
    :depends #{#'jvm-validate/validate #'validate-roles #'propagate-masks}}}
  [ast]
  (-typecheck {:locals {}} ast))

(defn -typecheck*
  ([tenv ast]
   (update-children ast (partial -typecheck tenv)))
  ([tenv ast children]
   (update-children* ast children (partial -typecheck tenv))))

(defn type= [x y & more]
  (apply = x y more))

(defn subtype? [{ctor1 :ctor roles1 :roles :as t1}
                {ctor2 :ctor roles2 :roles :as t2}]
  (and (= ctor1 :agree) (= ctor2 :agree) (set/superset? roles1 roles2)))

(defn lifted-type [{:keys [mask] :as env}]
  {:ctor :agree :roles mask})

(defn with-locals
  {:style/indent 0}
  [{:keys [env] :as ast} {:keys [locals] :as tenv}]
  (let [k1 (set (keys locals))
        k2 (set (keys (:locals env)))]
    (assert (= k1 k2) (str "The typing and local environments have differing "
                           "keys: got " k1 ", expected " k2)))
  (update-in ast [:env :locals] (partial merge-with merge) locals))

(defn with-type
  {:style/indent 0}
  [ast type {:keys [homo-type] :as tenv}]
  (when (and homo-type (not (type= type homo-type)))
    (type-error ["`fn`/`try`/`catch`'s body must be homogeneous; it cannot "
                 "mention types different from the lifted type: got "
                 (render-type type) ", expected " (render-type homo-type)]
                ast))
  (let [roles (type-roles type)
        mentions (apply set/union roles (map :rmentions (children ast)))]
    (assoc (with-locals ast tenv) :rtype type :rmentions mentions)))

(defn wrap-narrow [{:keys [form env] :as ast} type]
  (let [roles (vec (type-roles type))]
    {:op       :narrow
     :form     `(~'narrow ~roles ~form)
     :env      env
     :roles    roles
     :expr     ast
     :children [:expr]}))

(defn type-match [tenv asts types & {:as opts}]
  (reduce (fn [[_ res] [{t' :rtype :as a} t :as item]]
            (cond
              (type= t' t)
              [:ok (conj res a)]

              (and (:subtype? opts) (subtype? t' t))
              [:ok (conj res (with-type (wrap-narrow a t) t tenv))]

              :else
              (reduced [:err item])))
          [:ok []]
          (map vector asts types)))

(defn type-mismatch [tenv asts types]
  (match (type-match tenv asts types)
    [:err item] item
    [:ok _] nil))

(defn type-mismatch-1 [tenv asts type]
  (first (type-mismatch tenv asts (repeat type))))

(defn ensure-type-match [tenv type ast children err-fn]
  (let [asts (ast/children (assoc ast :children children))]
    (match (type-match tenv asts (repeat type) :subtype? true)
      [:err [arg _]] (err-fn arg)
      [:ok args] (replace-children ast (zipmap asts args)))))

(defn typecheck-homogeneous-op
  ([tenv type {:keys [children] :as ast}]
   (typecheck-homogeneous-op tenv type ast children))
  ([tenv type ast children]
   (letfn [(err [arg]
             (type-error
              ["Argument must be an agreement subtype of its non-choreography "
               "operator's type: got " (:form arg) " of type "
               (render-type (:rtype arg)) ", expected subtype of "
               (render-type type)]
              ast))]
     (with-type (ensure-type-match tenv type ast children err) type tenv))))

(defn extend-tenv [tenv bindings]
  (update tenv :locals into
          (for [{:keys [form] :as b} bindings]
            [form (select-keys b [:rtype :rmentions])])))

(defn add-recur-block [tenv params ret]
  (assoc tenv :recur-params params :recur-ret ret))

(defn restrict-type [tenv type]
  (assoc tenv :homo-type type))

;;; Choreographic

(defmethod -typecheck :narrow [tenv ast]
  (let [{:keys [roles expr] :as ast'} (-typecheck* tenv ast)
        roles (set roles)
        {eroles :roles :keys [ctor] :as type} (:rtype expr)]
    (when-not (= ctor :agree)
      (type-error ["Argument to `narrow` is not of agreement type: "
                   (render-type type)]
                  ast))
    (when-let [diff (not-empty (set/difference roles eroles))]
      (type-error ["Argument to `narrow` is missing roles: " diff] ast))
    (with-type ast' (assoc type :roles roles) tenv)))

(defmethod -typecheck :lifting [tenv ast]
  (let [{:keys [body] :as ast'} (-typecheck* tenv ast)]
    (with-type ast' (:rtype body) tenv)))

(defmethod -typecheck :agree [tenv ast]
  (let [{:keys [exprs] :as ast'} (-typecheck* tenv ast)]
    (when-let [expr (first (filter #(not= (:ctor (:rtype %)) :agree) exprs))]
      (type-error ["Argument to `agree!` must be of agreement type: "
                   (render-type (:rtype expr))]
                  expr))
    (let [rolesets (map #(:roles (:rtype %)) exprs)
          counts (apply merge-with + (map #(zipmap % (repeat 1)) rolesets))
          non-unique (set (for [[role count] counts :when (>= count 2)] role))]
      (when (not-empty non-unique)
        (type-error ["Arguments to `agree!` mention some roles more than once: "
                     non-unique]
                    ast))
      (with-type ast' {:ctor :agree :roles (apply set/union rolesets)} tenv))))

(defmethod -typecheck :copy [tenv ast]
  (let [{:keys [src dst expr] :as ast'} (-typecheck* tenv ast)
        {:keys [ctor roles] :as type} (:rtype expr)]
    (when-not (= ctor :agree)
      (type-error ["Argument to `copy` is not of agreement type: "
                   (render-type type)]
                  ast))
    (when-not (contains? roles src)
      (type-error ["Argument to `copy` is missing role " src] ast))
    (when (contains? roles dst)
      (type-error ["Argument to `copy` already contains role " dst] ast))
    (with-type ast' (assoc type :roles (conj roles dst)) tenv)))

(defmethod -typecheck :pack [tenv ast]
  (let [{:keys [exprs] :as ast'} (-typecheck* tenv ast)]
    (with-type ast' {:ctor :tuple :elems (mapv :rtype exprs)} tenv)))

(defmethod -typecheck :unpack [tenv ast]
  (let [{:keys [binder bindings init] :as ast'} (-typecheck* tenv ast [:init])
        {:keys [ctor] :as type} (:rtype init)]
    (when-not (= ctor :tuple)
      (type-error ["`unpack*`'s initializer must be of tuple type: "
                   (render-type type)]
                  ast))
    (when-not (unpack-binder-matches-type? binder type)
      (type-error ["`unpack*`'s binder's shape doesn't match the type of its "
                   "initializer: got " binder ", expected " (render-type type)]
                  ast))
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

(defn finalize-recur-mentions [mentions {:keys [op] :as ast}]
  (case op
    :recur (assoc ast :rmentions mentions)
    ;; Do not descend into nodes that introduce new recur blocks
    (:fn-method :chor) ast
    (update-children ast (partial finalize-recur-mentions mentions))))

(defmethod -typecheck :chor
  [tenv {:keys [local signature params] :as ast}]
  (let [{sparams :params :keys [ret aux]} signature]
    (when-not (= (count params) (count sparams))
      (type-error ["`chor`'s parameter vector's shape doesn't match its "
                   "signature: got " (mapv :form params) ", expected "
                   (mapv render-type sparams)]
                  ast))
    (let [;; Infer the type of the name, if present
          local' (and local (with-type local signature tenv))
          ;; Infer the type of each param
          params' (mapv #(with-type %1 %2 tenv) params sparams)
          ;; Update the node's params and name, if present
          ast' (cond-> (assoc ast :params params') local (assoc :local local'))
          ;; Update the typing environment
          tenv' (extend-tenv tenv (concat (and local [local']) params'))
          ;; Add a recur block
          tenv'' (add-recur-block tenv' sparams ret)
          ;; Typecheck the body
          {:keys [body] :as ast''} (-typecheck* tenv'' ast' [:body])
          {type :rtype mentions :rmentions} body]
      (when-not (type= ret type)
        (type-error ["`chor`'s return type doesn't match its signature: got "
                     (render-type type) ", expected " (render-type ret)]
                    ast))
      (if (= aux :none)
        ;; Infer the auxiliary roles from the mentions
        (with-type (update ast'' :body #(finalize-recur-mentions mentions %))
                   (normalize-type (assoc signature :aux mentions))
                   tenv)
        (with-type ast'' signature tenv)))))

(defmethod -typecheck :inst [tenv {:keys [var roles] :as ast}]
  (let [{croles :roles :keys [signature]} (:klor/chor (meta var))]
    (when-not (= (count croles) (count roles))
      (type-error ["`inst`'s number of roles doesn't match the choreography's "
                   "(" (symbol var) "): got " roles ", expected " croles]
                  ast))
    (with-type ast (replace-roles signature (zipmap croles roles)) tenv)))

;;; Binding & Control Flow

(defn typecheck-let-bindings [tenv bindings]
  (reduce (fn [[tenv' bindings'] b]
            (let [{:keys [init] :as b'} (-typecheck* tenv' b [:init])
                  b'' (with-type b' (:rtype init) tenv')]
              [(extend-tenv tenv' [b'']) (conj bindings' b'')]))
          [tenv []] bindings))

(defmethod -typecheck :let [tenv {:keys [bindings] :as ast}]
  (let [;; Infer the type of each binding and update the typing environment
        [tenv' bindings'] (typecheck-let-bindings tenv bindings)
        ;; Update the node's bindings
        ast' (assoc ast :bindings bindings')
        ;; Typecheck the body
        {:keys [body] :as ast''} (-typecheck* tenv' ast' [:body])]
    (with-type ast'' (:rtype body) tenv)))

(defmethod -typecheck :do [tenv ast]
  (let [{:keys [ret] :as ast'} (-typecheck* tenv ast)]
    (with-type ast' (:rtype ret) tenv)))

(defmethod -typecheck :if [tenv ast]
  (let [{:keys [test then else] :as ast'} (-typecheck* tenv ast)
        {:keys [ctor roles] :as test-type} (:rtype test)
        [type1 type2] (map :rtype [then else])]
    (when-not (= ctor :agree)
      (type-error ["`if`'s condition must be of agreement type: "
                   (render-type test-type)]
                  ast))
    (when-not (type= type1 type2)
      (type-error ["`if`'s branches must be of the same type: "
                   (render-type type1) " vs. " (render-type type2)]
                  ast))
    (when-let [diff (not-empty (set/difference
                                (set/union (:rmentions then) (:rmentions else))
                                roles))]
      (type-error ["`if`'s branches cannot mention roles not part of the "
                   "condition: " diff]
                  ast))
    (with-type ast' type1 tenv)))

(defmethod -typecheck :case [tenv ast]
  (let [{:keys [test tests thens default] :as ast'} (-typecheck* tenv ast)
        tests' (cons test tests)
        branches (concat thens [default])
        test-type (:rtype test)
        type (:rtype (first branches))]
    (when-not (= (:ctor test-type) :agree)
      (type-error ["`case`'s test expression must be of agreement type: "
                   (render-type test-type)]
                  ast))
    (when-let [test' (type-mismatch-1 tenv tests test-type)]
      (type-error ["`case`'s test constants must be of the same agreement "
                   "type as its test expression: got "
                   (render-type (:rtype test')) ", expected "
                   (render-type test-type)]
                  ast))
    (when-let [branch (type-mismatch-1 tenv (next branches) type)]
      (type-error ["`case`'s branches must all be of the same type: "
                   (render-type (:rtype branch)) " vs. " (render-type type)]
                  ast))
    (when-let [diff (not-empty (set/difference
                                (apply set/union (map :rmentions branches))
                                (:roles test-type)))]
      (type-error ["`case`'s branches cannot mention roles not part of the "
                   "test expression: " diff]
                  ast))
    (with-type ast' type tenv)))

(defmethod -typecheck :case-test [tenv ast]
  (let [{:keys [test] :as ast'} (-typecheck* tenv ast)]
    (assert (= (:ctor (:rtype test)) :agree)
            "Expected `case` test constant to be of agreement type")
    (with-type ast' (:rtype test) tenv)))

(defmethod -typecheck :case-then [tenv ast]
  (let [{:keys [then] :as ast'} (-typecheck* tenv ast)]
    (with-type ast' (:rtype then) tenv)))

(defmethod -typecheck :try [tenv {:keys [env] :as ast}]
  (let [ltype (lifted-type env)
        ;; Restrict all children to only ever mention a specific agreement type
        tenv' (restrict-type tenv ltype)
        ;; Type check the children
        ast' (-typecheck* tenv' ast)]
    (assert (apply type= ltype (map :rtype (children ast')))
            (str "Expected `try`'s children's return types to be equal to "
                 "the lifted type"))
    (with-type ast' ltype tenv)))

(defmethod -typecheck :catch [tenv {:keys [env local] :as ast}]
  ;; NOTE: Our `tenv` inherits the type restriction set up by `try`.
  (let [ltype (lifted-type env)
        {:keys [class local] :as ast'} (-typecheck* tenv ast [:class])
        ;; Infer the type of the binding
        local' (with-type local ltype tenv)
        ;; Update the node's local
        ast'' (assoc ast' :local local')
        ;; Update the typing environment
        tenv' (extend-tenv tenv [local'])
        ;; Type check the body
        {:keys [body] :as ast'''} (-typecheck* tenv' ast'' [:body])]
    (assert (apply type= ltype (map :rtype (children ast''')))
            (str "Expected `catch`'s children's return types to be equal to "
                 "the lifted type"))
    (with-type ast''' ltype tenv)))

(defmethod -typecheck :throw [tenv {:keys [env] :as ast}]
  (typecheck-homogeneous-op tenv (lifted-type env) (-typecheck* tenv ast)))

;;; Functions & Invocation

(defmethod -typecheck :fn [tenv {:keys [env local] :as ast}]
  (let [;; Infer the type of the function's name, if present
        ltype (lifted-type env)
        local' (and local (with-type local ltype tenv))
        ;; Update the node's name, if present
        ast' (cond-> ast local (assoc :local local'))
        ;; Update the typing environment, if necessary
        tenv' (cond-> tenv local (extend-tenv [local']))
        ;; Typecheck each method
        ast'' (-typecheck* tenv' ast' [:methods])]
    (with-type ast'' ltype tenv)))

(defmethod -typecheck :fn-method [tenv {:keys [env params] :as ast}]
  (let [;; Infer the type of each param
        ltype (lifted-type env)
        params' (mapv #(with-type %1 ltype tenv) params)
        ;; Update the node's params
        ast' (assoc ast :params params')
        ;; Update the typing environment
        tenv' (extend-tenv tenv params')
        ;; Add a recur block
        tenv'' (add-recur-block tenv' (repeat (count params) ltype) ltype)
        ;; Restrict the body to only ever mention a specific agreement type
        tenv''' (restrict-type tenv'' ltype)
        ;; Typecheck the body
        {:keys [body] :as ast''} (-typecheck* tenv''' ast' [:body])
        {:keys [ctor] :as type} (:rtype body)]
    (assert (type= type ltype)
            "Expected `fn`'s return type to be equal to its own type")
    (with-type ast'' type tenv)))

(defmethod -typecheck :invoke [tenv ast]
  (let [;; Use `fn'` so that we don't shadow `fn`.
        {fn' :fn :keys [args] :as ast'} (-typecheck* tenv ast)
        {:keys [ctor params ret] :as type} (:rtype fn')]
    (case ctor
      ;; The operator is of agreement type: the types of the arguments must be
      ;; of that same agreement type
      :agree
      (typecheck-homogeneous-op tenv type ast' [:args])
      ;; The operator is a choreography: the types of the arguments must match
      ;; the types of their respective parameters
      :chor
      (let [c1 (count args)
            c2 (count params)]
        (when-not (= c1 c2)
          (type-error ["Wrong number of arguments in a choreography "
                       "invocation: got " c1 ", expected " c2]
                      ast))
        (when-let [[arg param] (type-mismatch tenv args params)]
          (type-error ["Argument doesn't match the choreography's parameter "
                       "type: got " (:form arg) " of type "
                       (render-type (:rtype arg)) ", expected "
                       (render-type param)]
                      ast))
        (with-type ast' ret tenv))
      ;; Otherwise, error
      (type-error ["Cannot invoke a value of non-agreement or non-choreography "
                   "type: " (render-type type)]
                  ast))))

(defmethod -typecheck :recur [{:keys [recur-params recur-ret] :as tenv} ast]
  (let [{:keys [exprs] :as ast'} (-typecheck* tenv ast [:exprs])]
    (when-let [[expr param] (type-mismatch tenv exprs recur-params)]
      (type-error ["Argument to `recur` doesn't match the binding's type: got "
                   (:form expr) " of type " (render-type (:rtype expr))
                   ", expected " (render-type param)]
                  ast))
    (with-type ast' recur-ret tenv)))

;;; References

(defmethod -typecheck :local [{:keys [locals] :as tenv} {:keys [form] :as ast}]
  (assert (contains? locals form)
          (str "Local missing from typing environment: " form ", " tenv))
  (with-type ast (get-in locals [form :rtype]) tenv))

(defmethod -typecheck :var [tenv {:keys [env var] :as ast}]
  (when (contains? (meta var) :klor/chor)
    (type-error ["Cannot refer to a choreographic definition without "
                 "instantiating it: " (symbol var)]
                ast))
  (with-type ast (lifted-type env) tenv))

(defmethod -typecheck :the-var [tenv {:keys [env] :as ast}]
  (with-type ast (lifted-type env) tenv))

;;; Collections

(defn typecheck-collection [tenv type {:keys [children] :as ast}]
  (letfn [(err [arg]
            (type-error
             ["Element must be an agreement subtype of its collection's type: "
              "got " (:form arg) " of type " (render-type (:rtype arg))
              ", expected " "subtype of " (render-type type)]
             ast))]
    (with-type (ensure-type-match tenv type ast children err) type tenv)))

(defmethod -typecheck :vector [tenv {:keys [env] :as ast}]
  (typecheck-collection tenv (lifted-type env) (-typecheck* tenv ast)))

(defmethod -typecheck :map [tenv {:keys [env] :as ast}]
  (typecheck-collection tenv (lifted-type env) (-typecheck* tenv ast)))

(defmethod -typecheck :set [tenv {:keys [env] :as ast}]
  (typecheck-collection tenv (lifted-type env) (-typecheck* tenv ast)))

;;; Constants

(defmethod -typecheck :const [tenv {:keys [env] :as ast}]
  (with-type (-typecheck* tenv ast) (lifted-type env) tenv))

(defmethod -typecheck :quote [tenv ast]
  (let [{:keys [expr] :as ast'} (-typecheck* tenv ast)]
    (with-type ast' (:rtype expr) tenv)))

(defmethod -typecheck :with-meta [tenv ast]
  (let [{:keys [expr meta] :as ast'} (-typecheck* tenv ast)]
    ;; NOTE: I believe it is syntactically impossible in Klor to arrange for an
    ;; expression and its metadata to be of different types (agreement types in
    ;; this case, since `expr` is one of `:vector`, `:map`, `:set` or `:fn`).
    (assert (type= (:rtype expr) (:rtype meta))
            "Expected an expression and its metadata to have the same type")
    (with-type ast' (:rtype expr) tenv)))

;;; Host Interop

(defmethod -typecheck :new [tenv {:keys [env] :as ast}]
  (let [;; NOTE: Type check the class argument in an empty lexical environment,
        ;; the same way `tools.analyzer` analyzes it.
        ast' (-typecheck* (assoc tenv :locals {}) ast [:class])
        ast'' (-typecheck* tenv ast' [:args])]
    (typecheck-homogeneous-op tenv (lifted-type env) ast'')))

(defmethod -typecheck :host-interop [tenv {:keys [env] :as ast}]
  (typecheck-homogeneous-op tenv (lifted-type env) (-typecheck* tenv ast)))

(defmethod -typecheck :instance-field [tenv {:keys [env] :as ast}]
  (typecheck-homogeneous-op tenv (lifted-type env) (-typecheck* tenv ast)))

(defmethod -typecheck :instance-call [tenv {:keys [env] :as ast}]
  (typecheck-homogeneous-op tenv (lifted-type env) (-typecheck* tenv ast)))

(defmethod -typecheck :static-field [tenv {:keys [env] :as ast}]
  (with-type ast (lifted-type env) tenv))

(defmethod -typecheck :static-call [tenv {:keys [env] :as ast}]
  (typecheck-homogeneous-op tenv (lifted-type env) (-typecheck* tenv ast)))

(defmethod -typecheck :default [tenv {:keys [op] :as ast}]
  (type-error ["Don't know how to typecheck " op ", yet!"] ast))

;;; Sanity Check

(defn -sanity-check [local? {:keys [form env op rtype rmentions] :as ast}]
  (assert rtype (-str "Missing type: " op ", " form))
  (assert (try (parse-type (render-type rtype)) (catch Throwable t))
          (-str "Invalid type: " rtype ", " op ", " form))
  (assert (not-empty rmentions)
          (-str "Missing role mentions: " op ", " form))
  (when-not local?
    (assert (:mask env) (-str "Missing mask: " form))
    (assert (every? (partial -sanity-check true) (vals (:locals env)))
            (-str "Incorrect locals: " (:locals env))))
  true)

(defn sanity-check
  {:pass-info {:walk :post :depends #{#'typecheck}}}
  [ast]
  (-sanity-check false ast)
  ast)
