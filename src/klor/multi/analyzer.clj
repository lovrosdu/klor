(ns klor.multi.analyzer
  (:refer-clojure :exclude [macroexpand-1])
  (:require
   [clojure.string :as str]
   [clojure.tools.analyzer :as clj-analyzer]
   [clojure.tools.analyzer.env :as env]
   [clojure.tools.analyzer.jvm :as jvm-analyzer]
   [clojure.tools.analyzer.passes :refer [schedule]]
   [clojure.tools.analyzer.passes.constant-lifter]
   [clojure.tools.analyzer.passes.jvm.emit-form]
   [clojure.tools.analyzer.utils :refer [ctx dissoc-env resolve-sym]]
   [klor.multi.emit-form]
   [klor.multi.types
    :refer [parse-type postwalk-type normalize-type render-type]]
   [klor.multi.typecheck]
   [klor.multi.projection :as proj]
   [klor.multi.specials
    :refer [narrow agree! lifting copy pack unpack* chor* inst]]
   [klor.multi.util :refer [usym? unpack-binder? make-copy form-error]]))

;;; NOTE: The local environment's `:context` field is explicitly overriden with
;;; `:ctx/expr` whenever an expression is *not* in tail position with respect to
;;; the enclosing "recur block" (such as `loop`, `fn`, etc.), meaning that it
;;; cannot be a call to `recur`. Otherwise, `:context` is normally unchanged and
;;; inherited from the parent environment.

;;; NOTE: `clojure.tools.analyzer.jvm/macroexpand-1` expands namespaced symbols
;;; into host forms when the namespace names a statically-known class, e.g.
;;; `<ns>/<name>` expands to `(. <ns> <name>)` when `<ns>` names a class.
;;; Otherwise, the symbol is analyzed as usual and might produce a
;;; `:maybe-host-form` node if it doesn't resolve to a var.

;;; Util

(defn parse-error [msg form env & {:as kvs}]
  (form-error :klor/parse msg form env kvs))

;;; Special Operators

(defn parse-narrow [[_ roles expr :as form] env]
  (when-not (= (count form) 3)
    (parse-error "`narrow` needs exactly 2 arguments" form env))
  (when-not (and (vector? roles) (not-empty roles))
    (parse-error ["`narrow` needs a non-empty vector of roles: " roles]
                 form env))
  {:op       :narrow
   :form     form
   :env      env
   :roles    roles
   :expr     (clj-analyzer/analyze-form expr (ctx env :ctx/expr))
   :children [:expr]})

(defn parse-lifting [[_ roles & body :as form] env]
  (when-not (>= (count form) 2)
    (parse-error "`lifting` needs at least 1 argument" form env))
  (when-not (and (vector? roles) (not-empty roles))
    (parse-error ["`lifting` needs a non-empty vector of roles: " roles]
                 form env))
  {:op       :lifting
   :form     form
   :env      env
   :roles    roles
   :body     (clj-analyzer/analyze-body body env)
   :children [:body]})

(defn parse-agree! [[_ & exprs :as form] env]
  (when-not (>= (count form) 2)
    (parse-error "`agree!` needs at least 1 argument" form env))
  {:op       :agree
   :form     form
   :env      env
   :exprs    (mapv (clj-analyzer/analyze-in-env (ctx env :ctx/expr)) exprs)
   :children [:exprs]})

(defn parse-copy [[_ roles expr :as form] env]
  (when-not (= (count form) 3)
    (parse-error "`copy` needs exactly 2 arguments" form env))
  (when-not (and (vector? roles) (= (count roles) 2))
    (parse-error ["`copy` needs a vector of exactly 2 roles: " roles]
                 form env))
  {:op       :copy
   :form     form
   :env      env
   :src      (first roles)
   :dst      (second roles)
   :expr     (clj-analyzer/analyze-form expr (ctx env :ctx/expr))
   :children [:expr]})

(defn parse-pack [[_ & exprs :as form] env]
  (when-not (>= (count form) 2)
    (parse-error ["`pack` needs at least 1 argument"] form env))
  {:op       :pack
   :form     form
   :env      env
   :exprs    (mapv (clj-analyzer/analyze-in-env (ctx env :ctx/expr)) exprs)
   :children [:exprs]})

(defn traverse-unpack-binder [binder]
  ((fn rec [binder prefix]
     (if (symbol? binder)
       (list [binder prefix])
       (->> binder
            (map-indexed (fn [i b] (rec b (conj prefix i))))
            (mapcat identity))))
   binder []))

(defn analyze-unpack-binder [form env binder]
  (when-not (unpack-binder? binder)
    (parse-error ["Invalid `unpack*` binder: " binder] form env))
  (for [[name position] (traverse-unpack-binder binder)]
    {:op       :binding
     :form     name
     :env      env
     :local    :unpack
     :name     name
     :position position
     :children []}))

(defn parse-unpack* [[_ binder init & body :as form] env]
  (when-not (>= (count form) 3)
    (parse-error "`unpack*` needs at least 2 arguments" form env))
  (let [init (clj-analyzer/analyze-form init (ctx env :ctx/expr))
        bindings (analyze-unpack-binder form env binder)
        locals (into {} (for [b bindings] [(:name b) (dissoc-env b)]))
        env' (update env :locals merge locals)]
    {:op       :unpack
     :form     form
     :env      env
     ;; NOTE: We preserve the binder but only for its shape and error reporting.
     ;; The source of truth for the bindings is always `:bindings`.
     :binder   binder
     :bindings (vec bindings)
     :init     init
     :body     (clj-analyzer/analyze-body body env')
     :children [:bindings :init :body]}))

(defn adjust-chor-signature [{:keys [aux] :as type}]
  (-> (fn [{:keys [ctor] :as type}]
        (case ctor
          (:agree :tuple) type
          :chor (update type :aux #(if (= % :none) #{} %))))
      (postwalk-type type)
      ;; NOTE: Preserve the top-level aux set.
      (assoc :aux aux)))

(defn parse-chor-args [[_ & [name & _ :as args] :as form] env]
  (when-not (>= (count form) 3)
    (parse-error "`chor*` needs at least 2 arguments" form env))
  (let [[name tspec params & body] (if (symbol? name) args (cons nil args))
        {:keys [ctor aux] :as signature} (parse-type tspec)]
    (when (and name (not (usym? name)))
      (parse-error ["`chor`'s name must be an unqualified symbol: " name]
                   form env))
    (when-not signature
      (parse-error ["`chor`'s signature is invalid: " tspec] form env))
    (when-not (= ctor :chor)
      (parse-error ["`chor`'s signature must be a choreography type: " tspec]
                   form env))
    (when (and name (= aux :none))
      (parse-error ["`chor`'s auxiliary roles must be explicitly specified "
                    "when a self-reference name is used: " tspec]
                   form env))
    (let [normalized (normalize-type signature)]
      (when-not (= signature normalized)
        (parse-error ["`chor`'s auxiliary part must be normalized: got "
                      tspec ", expected " (render-type normalized)]
                     form env)))
    (when-not (vector? params)
      (parse-error ["`chor` needs a vector of parameters: " params]
                   form env))
    (when (some '#{&} params)
      (parse-error ["`chor` does not support variadic arguments: " params]
                   form env))
    (list* name (adjust-chor-signature signature) params body)))

(defn analyze-chor-param [form env param]
  (when-not (usym? param)
    (parse-error ["Invalid `chor*` param: " param] form env))
  {:op    :binding
   :form  param
   :env   env
   :local :chor
   :name  param})

(defn parse-chor* [form env]
  (let [[name signature params & body] (parse-chor-args form env)
        local (and name {:op    :binding
                         :form  name
                         :env   env
                         :local :chor
                         :name  name})
        bindings (mapv (partial analyze-chor-param form env) params)
        locals (into (if name {name (dissoc-env local)} {})
                     (for [b bindings] [(:name b) (dissoc-env b)]))
        loop-id (gensym "loop_")
        env' (into (update env :locals merge locals)
                   {:context     :ctx/return
                    :loop-id     loop-id
                    :loop-locals (count params)})]
    (merge {:op      :chor
            :form    form
            :env     env
            :loop-id loop-id}
           (and name {:local local})
           {:signature signature
            :params    bindings
            :body      (clj-analyzer/analyze-body body env')
            :children  (into (if name [:local] []) [:params :body])})))

(defn parse-inst [[_ name roles :as form] env]
  (when-not (= (count form) 3)
    (parse-error "`inst` needs exactly 2 arguments" form env))
  (when-not (symbol? name)
    (parse-error ["`inst` needs a symbol for the name: " name] form env))
  (when-not (and (vector? roles) (not-empty roles))
    (parse-error ["`inst` needs a non-empty vector of roles: " roles]
                 form env))
  (if-let [var (resolve-sym name env)]
    (if (contains? (meta var) :klor/chor)
      {:op       :inst
       :form     form
       :env      env
       :name     name
       :var      var
       :roles    roles
       :children []}
      (parse-error [name " does not name a Klor choreography"] form env))
    (parse-error ["Unknown var: " name] form env)))

(defn special [var parser]
  (let [sym (:name (meta var))]
    {sym                                  parser
     ;; NOTE: Special case `klor.multi.core` which is meant to be used as a
     ;; central namespace from which users import Klor operators. Sadly, Clojure
     ;; does not support re-exporting imported vars and the Potemkin library we
     ;; use in `klor.multi.core` fakes it by creating *new* vars, which are
     ;; distinct from the ones imported from `klor.multi.specials`.
     (symbol "klor.multi.core" (str sym)) parser
     var                                  parser}))

(def specials
  (merge (special #'narrow  #'parse-narrow)
         (special #'lifting #'parse-lifting)
         (special #'copy    #'parse-copy)
         (special #'pack    #'parse-pack)
         (special #'unpack* #'parse-unpack*)
         (special #'chor*   #'parse-chor*)
         (special #'inst    #'parse-inst)
         (special #'agree!  #'parse-agree!)))

(defn get-special [form env]
  ;; Assuming that the operator position is a symbol, we first check if it names
  ;; a special operator by name, unqualified. This is both for
  ;; convenience (since it doesn't require the corresponding Klor macro to be
  ;; imported into the current namespace) and to mimic how special operators
  ;; normally behave (namely, that they cannot be shadowed).
  ;;
  ;; We then try to check if the symbol resolves to one of Klor's specials'
  ;; vars, which allows us to correctly handle cases when the symbol is fully
  ;; qualified, qualified with an alias or has been renamed (see `alias` and
  ;; `refer`). This is useful in the context of Klor macros, since Clojure's
  ;; backquote fully qualifies all literal symbols. Interestingly, backquote
  ;; *never* fully qualifies Clojure's special operators (`if`, `let*`, etc.),
  ;; so tools.analyzer doesn't have to worry about this issue. E.g. `(= `if
  ;; 'if)` and `(= `let* 'let*)`, but `(= `pack 'klor.multi.specials/pack)`.
  ;;
  ;; Note that any local bindings named after a special operator are ignored,
  ;; just like in Clojure. More precisely, naming a global var or local binding
  ;; after a special operator is not an error, but it does *not* shadow the
  ;; special operator when such a name is used in operator position unqualified.
  ;; However, a reference to the name works as expected when used
  ;; qualified (which is impossible to do for local bindings, so only applies to
  ;; global vars) or when used in non-operator position.
  (and (seq? form)
       (let [op (first form)]
         (and (symbol? op)
              (or (get specials op)
                  (get specials (resolve-sym op env)))))))

;;; Syntax Sugar

(defn role? [form {:keys [roles] :as env}]
  (some #{form} roles))

(defn parse-role-expr [[role & body :as form] env]
  (assoc (parse-lifting `(~'lifting [~role] ~@body) env)
         :form form :sugar? true))

(defn role-op [op form env]
  (and (usym? form)
       (let [roles (map symbol (str/split (name form) op))]
         (and (= (count roles) 2) (every? #(role? % env) roles) roles))))

(defn parse-copy-expr [roles [_ expr :as form] env]
  (assoc (parse-copy `(~'copy [~@roles] ~expr) env) :form form :sugar? true))

(defn parse-move-expr [[src dst] [_ expr :as form] env]
  (assoc (parse-narrow `(~'narrow [~dst] (~(make-copy src dst) ~expr)) env)
         :form form :sugar? true))

(defn inline-inst-expr? [[name roles & _ :as form] env]
  (when-let [var (resolve-sym name env)]
    (and (vector? roles)
         (every? #(role? % env) roles)
         (or (:klor/chor (meta var))
             (parse-error ["Trying to invoke a choreographic definition but "
                           (symbol var) " doesn't name a choreography"]
                          form env)))))

(defn parse-inline-inst-expr [[name roles & exprs :as form] env]
  (assoc (jvm-analyzer/parse `((inst ~name ~roles) ~@exprs) env)
         :form form :sugar? true))

;;; Entry Points

(defn parse [[op & _ :as form] env]
  (if-let [parser (get-special form env)]
    (parser form env)
    ;; The syntax sugar can be thought of as a macro, but it also has the
    ;; characteristic of a special operator in that it cannot be shadowed by
    ;; local bindings. For that reason we implement it as part of the parsing
    ;; phase and not macroexpansion.
    (if (role? op env)
      (parse-role-expr form env)
      (if-let [roles (role-op #"=>" op env)]
        (parse-copy-expr roles form env)
        (if-let [roles (role-op #"->" op env)]
          (parse-move-expr roles form env)
          (if (inline-inst-expr? form env)
            (parse-inline-inst-expr form env)
            (jvm-analyzer/parse form env)))))))

(defn macroexpand-1 [form env]
  ;; Klor's special operators have accompanying dummy Clojure macros purely for
  ;; indentation and error catching purposes, so we override `macroexpand-1` to
  ;; ignore them.
  (if (get-special form env) form (jvm-analyzer/macroexpand-1 form env)))

(defn analyze* [form & {:keys [env run-passes passes-opts] :as opts}]
  ;; NOTE: We set the environment, the parser and the macroexpander
  ;; unconditionally. We set `run-passes` only if explicitly provided (and
  ;; non-nil), or use `identity` if not already set. We set `passes-opts` only
  ;; if explicitly provided, or use `{}` if not already set.
  (let [env' (merge (jvm-analyzer/empty-env) env)
        bindings (merge {#'clj-analyzer/parse parse
                         #'clj-analyzer/macroexpand-1 macroexpand-1}
                        (cond
                          run-passes {#'jvm-analyzer/run-passes run-passes}
                          (not env/*env*) {#'jvm-analyzer/run-passes identity}
                          :else nil))
        {:keys [roles]} env]
    (assert (and (seqable? roles) (not-empty roles) (every? usym? roles))
            "Roles must be a non-empty seq of unqualified symbols")
    ;; NOTE: `jvm-analyzer/analyzer` merges `:passes-opts` in a way where the
    ;; options in the environment win. We do the opposite here, since we want
    ;; the passed in `passes-opts` to always override the current ones.
    (if env/*env*
      (env/with-env
        (merge-with merge (env/deref-env) {:passes-opts passes-opts})
        (jvm-analyzer/analyze form env' {:bindings bindings :passes-opts {}}))
      (jvm-analyzer/analyze form env' {:bindings bindings
                                       :passes-opts (or passes-opts {})}))))
