(ns klor.multi.analyzer
  (:refer-clojure :exclude [macroexpand-1])
  (:require
   [clojure.set :as set]
   [clojure.tools.analyzer :as clj-analyzer]
   [clojure.tools.analyzer.env :as env]
   [clojure.tools.analyzer.jvm :as jvm-analyzer]
   [clojure.tools.analyzer.passes :refer [schedule]]
   [clojure.tools.analyzer.passes elide-meta source-info constant-lifter]
   [clojure.tools.analyzer.utils :refer [ctx dissoc-env resolve-sym mmerge]]
   [klor.multi roles typecheck emit-form]
   [klor.multi.types :refer [parse-type normalize-type render-type]]
   [klor.multi.specials :refer [at local copy pack unpack* chor* inst]]
   [klor.multi.util :refer [usym? unpack-binder? analysis-error]]
   [klor.util :refer [error]]))

(defn parse-at [[_ roles expr :as form] env]
  (when-not (= (count form) 3)
    (analysis-error "`at` needs exactly 2 arguments" form env))
  (when-not (and (vector? roles) (not-empty roles))
    (analysis-error ["`at` needs a non-empty vector of roles: " roles]
                    form env))
  {:op       :at
   :form     form
   :env      env
   :roles    roles
   :expr     (clj-analyzer/analyze-form expr (ctx env :ctx/expr))
   :children [:expr]})

(defn parse-local [[_ roles & body :as form] env]
  (when-not (>= (count form) 2)
    (analysis-error "`local` needs at least 1 argument" form env))
  (when-not (and (vector? roles) (not-empty roles))
    (analysis-error ["`local` needs a non-empty vector of roles: " roles]
                    form env))
  ;; NOTE: We use `:mask` because `:local` is used for references to locals.
  {:op       :mask
   :form     form
   :env      env
   :roles    roles
   :body     (clj-analyzer/analyze-body body env)
   :children [:body]})

(defn parse-copy [[_ roles expr :as form] env]
  (when-not (= (count form) 3)
    (analysis-error "`copy` needs exactly 2 arguments" form env))
  (when-not (and (vector? roles) (= (count roles) 2))
    (analysis-error ["`copy` needs a vector of exactly 2 roles: " roles]
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
    (analysis-error ["`pack` needs at least 1 argument"] form env))
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

(defn analyze-unpack-binder [form env binder init]
  (when-not (unpack-binder? binder)
    (analysis-error ["Invalid `unpack*` binder: " binder] form env))
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
    (analysis-error "`unpack*` needs at least 2 arguments" form env))
  (let [init (clj-analyzer/analyze-form init (ctx env :ctx/expr))
        bindings (analyze-unpack-binder form env binder init)
        locals (into {} (for [b bindings] [(:name b) (dissoc-env b)]))
        env' (mmerge env {:locals locals})]
    {:op       :unpack
     :form     form
     :env      env
     ;; NOTE: Preserve the binder but only for its shape and error reporting.
     ;; The bindings are given by `:bindings`.
     :binder   binder
     :bindings (vec bindings)
     :init     init
     :body     (clj-analyzer/analyze-body body env')
     :children [:bindings :init :body]}))

(defn analyze-chor-param [form env param]
  (when-not (usym? param)
    (analysis-error ["Invalid `chor*` param: " param] form env))
  {:op    :binding
   :form  param
   :env   env
   :local :chor
   :name  param})

(defn parse-chor* [[_ tspec params & body :as form] env]
  (when-not (>= (count form) 3)
    (analysis-error "`chor*` needs at least 2 arguments" form env))
  (let [{:keys [ctor aux] :as signature} (parse-type tspec)]
    (when-not signature
      (analysis-error ["Invalid `chor` signature: " tspec] form env))
    (when-not (= ctor :chor)
      (analysis-error ["`chor`'s signature must be a choreography type: " tspec]
                      form env))
    (when-not (empty? aux)
      (analysis-error ["`chor`'s signature must not specify auxiliary roles: "
                       tspec]
                      form env))
    (let [normalized (normalize-type signature)]
      (when-not (= signature normalized)
        (analysis-error ["`chor`'s signature must be normalized: " tspec
                         " vs. " (render-type normalized)]
                        form env)))
    (when-not (vector? params)
      (analysis-error ["`chor` needs a vector of parameters: " params]
                      form env))
    (let [bindings (mapv (partial analyze-chor-param form env) params)
          locals (into {} (for [b bindings] [(:name b) (dissoc-env b)]))
          env' (mmerge env {:locals locals})]
      {:op        :chor
       :form      form
       :env       env
       :signature signature
       :params    bindings
       :body      (clj-analyzer/analyze-body body env')
       :children  [:params :body]})))

(defn parse-role-expr [[role & body :as form] env]
  (assoc (parse-local `(~'local [~role] ~@body) env) :role? true))

(defn parse-inst [[_ name roles :as form] env]
  (when-not (= (count form) 3)
    (analysis-error "`inst` needs exactly 2 arguments" form env))
  (when-not (symbol? name)
    (analysis-error ["`inst` needs a symbol for the name: " name] form env))
  (when-not (and (vector? roles) (not-empty roles))
    (analysis-error ["`inst` needs a non-empty vector of roles: " roles]
                    form env))
  (let [{var' :var :keys [op] :as var} (clj-analyzer/analyze-form name env)]
    (when-not (= op :var)
      (analysis-error ["Unknown var: " name] form env))
    (when-not (contains? (meta var') :klor/chor)
      (analysis-error [name " does not name a Klor choreography"] form env))
    {:op       :inst
     :form     form
     :env      env
     :var      var
     :roles    roles
     :children [:var]}))

(defn special [var parser]
  {(:name (meta var)) parser
   var                parser})

(def specials
  (merge (special #'at #'parse-at)
         (special #'local #'parse-local)
         (special #'copy #'parse-copy)
         (special #'pack #'parse-pack)
         (special #'unpack* #'parse-unpack*)
         (special #'chor* #'parse-chor*)
         (special #'inst #'parse-inst)))

(defn get-special [form env]
  ;; We aim to be flexible and recognize Klor's special operators even when they
  ;; haven't been imported into the current namespace, for convenience. Assuming
  ;; that the operator position is a symbol, we first check if it names a Klor
  ;; special operator, unqualified.
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

(defn parse [form env]
  ((or (get-special form env)
       (if (contains? (:roles env) (first form))
         parse-role-expr
         jvm-analyzer/parse))
   form env))

(defn macroexpand-1 [form env]
  ;; Klor's special operators have accompanying dummy Clojure macros purely for
  ;; indentation and error catching purposes, so we override `macroexpand-1` to
  ;; ignore them.
  (if (get-special form env) form (jvm-analyzer/macroexpand-1 form env)))

(def default-passes
  #{;; Transfer the source info from the metadata to the local environment.
    #_#'clojure.tools.analyzer.passes.source-info/source-info

    ;; Elide metadata given by `clojure.tools.analyzer.passes.elide-meta/elides`
    ;; or `*compiler-options*`.
    #_#'clojure.tools.analyzer.passes.elide-meta/elide-meta

    ;; Propagate constness to vectors, maps and sets of constants.
    #_#'clojure.tools.analyzer.passes.constant-lifter/constant-lift

    ;; Rename all local bindings to fresh names. Requires
    ;; `:uniquify/uniquify-env` to be true within the pass options in order to
    ;; also apply the same changes to the local environments.
    #_#'clojure.tools.analyzer.passes.uniquify/uniquify-locals

    ;; Elide superfluous `:do`, `:let` and `:try` nodes when possible.
    #_#'clojure.tools.analyzer.passes.trim/trim

    ;; Refine `:host-field`, `:host-call` and `:host-interop` nodes to
    ;; `:instance-field`, `:instance-call`, `:static-field` and `:static-call`,
    ;; when possible. Refine `:var` and `:maybe-class` nodes to `:const`, when
    ;; possible.
    #_#'clojure.tools.analyzer.passes.jvm.analyze-host-expr/analyze-host-expr

    ;; Refine `:invoke` nodes to `:keyword-invoke`, `:prim-invoke`,
    ;; `:protocol-invoke` and `:instance?` nodes, when possible.
    #_#'clojure.tools.analyzer.passes.jvm.classify-invoke/classify-invoke

    ;; Throw if `:maybe-class` or `:maybe-host-form` nodes are encountered. Such
    ;; nodes are produced for non-namespaced and namespaced symbols that do not
    ;; resolve to a var, respectively.
    ;;
    ;; Note two things:
    ;;
    ;; (1) `clojure.tools.analyzer.jvm/macroexpand-1` macroexpands namespaced
    ;; symbols such as `<ns>/<name>` into `(. <ns> <name>)` when `<ns>` names a
    ;; class. If it doesn't, the symbol is analyzed as usual and might produce
    ;; `:maybe-host-form`, however, `clojure.tools.analyzer.jvm` has no
    ;; particular handling for that node in any of its passes.
    ;;
    ;; (2) This pass depends on
    ;; `clojure.tools.analyzer.passes.jvm.analyze-host-expr/analyze-host-expr`
    ;; which refines `:maybe-class` to `:const` (with a `:type` of `:class`)
    ;; when possible.
    #_#'clojure.tools.analyzer.passes.jvm.validate/validate

    ;; Throw on invalid role applications.
    #'klor.multi.roles/validate-roles

    ;; Propagate role masks.
    #'klor.multi.typecheck/propagate-masks

    ;; Typecheck.
    #'klor.multi.typecheck/typecheck
    #'klor.multi.typecheck/sanity-check

    ;; Emit form.
    #'klor.multi.emit-form/emit-form})

(def run-passes
  (schedule default-passes))

(defn analyze [form & {:as env}]
  (let [bindings {#'clj-analyzer/macroexpand-1 macroexpand-1
                  #'clj-analyzer/parse parse
                  #'jvm-analyzer/run-passes (schedule default-passes)}]
    (jvm-analyzer/analyze form (merge (jvm-analyzer/empty-env) env)
                          {:bindings bindings})))

(comment
  (defn analyze-plain
    ([form]
     (analyze-plain form (jvm-analyzer/empty-env)))
    ([form env]
     (binding [clj-analyzer/macroexpand-1 jvm-analyzer/macroexpand-1
               clj-analyzer/parse clj-analyzer/-parse
               clj-analyzer/create-var jvm-analyzer/create-var
               clj-analyzer/var? var?]
       (env/with-env (jvm-analyzer/global-env)
         (clj-analyzer/analyze form env)))))
  )
