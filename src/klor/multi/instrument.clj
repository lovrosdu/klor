(ns klor.multi.instrument
  (:require [clojure.tools.analyzer.env :as env]
            [clojure.tools.analyzer.passes :refer [schedule]]
            [klor.multi.analyzer :refer [analyze*]]
            [klor.multi.emit-form :refer [emit-form]]
            [klor.multi.specials :refer [narrow lifting inst]]
            [klor.multi.stdlib :refer [bcast gather]]
            [klor.multi.types :refer [render-type replace-roles]]
            [klor.multi.typecheck :refer [typecheck sanity-check]]
            [klor.multi.util :refer [usym? error warn]]))

;;; NOTE: For ease of development and convenience, agreement and signature
;;; verification are implemented at the choreographic level via macros, which
;;; are then injected into the code. However, macros work at the lower
;;; S-expression level while the instrumentation pass has to work with an AST.
;;; For that reason, we construct the macro form on the fly and invoke the
;;; analyzer (again) to produce an AST.
;;;
;;; The AST also has to be type checked again because the macro can expand to
;;; arbitrary code. This goes not just for the AST produced on the fly but for
;;; the whole AST, because both the type and/or the mentions can change and have
;;; to be propagated upward. This is especially important for the verification
;;; of agreement parameters, since the checks injected into the body can
;;; technically widen the set of its mentioned roles if not all roles are
;;; mentioned (although generally it is silly for a top-level choreography's
;;; body not to already mention all of its roles).

;;; Agreement Verification

(defn agree? [vals]
  (if (every? fn? vals)
    (do (warn ["Cannot check for agreement when values are functions"])
        true)
    (apply = vals)))

(defn agreement-error [expr vals]
  (error :klor ["Values of an agreement differ: " expr ", " vals]))

(defmacro verify-agreement-centralized [[role & others :as roles] expr]
  (let [sym (gensym "expr")
        make-narrow (fn [role] `(narrow [~role] ~sym))]
    `(let [~sym ~expr
           vals# (~role (apply vector ~sym
                               (gather [~@roles] ~@(map make-narrow others))))]
       (lifting [~@roles]
         (when-not (bcast [~@roles] (~role (agree? vals#)))
           (agreement-error '~expr (bcast [~@roles] vals#))))
       ~sym)))

(defmacro verify-agreement-decentralized [roles expr]
  (let [sym (gensym "expr")
        make-bcast (fn [role] `(bcast [~role ~@(remove #{role} roles)]
                                      (narrow [~role] ~sym)))]
    `(let [~sym ~expr]
       (lifting [~@roles]
         (let [vals# ~(mapv make-bcast roles)]
           (when-not (agree? vals#)
             (agreement-error '~expr vals#))))
       ~sym)))

;;; Signature Verification

(defn defchor-signature-changed? [roles signature roles' signature']
  (and roles roles'
       (or (not= (count roles) (count roles'))
           (not= (replace-roles signature (zipmap roles (range)))
                 (replace-roles signature' (zipmap roles' (range)))))))

(defn render-signature [roles signature]
  `(~'forall ~roles ~(render-type signature)))

(defn signature-error [var roles signature roles' signature']
  (error :klor ["Signature of " var " differs from the recorded one:\n"
                "  was " (render-signature roles signature) ",\n"
                "  is " (render-signature roles' signature') ";\n"
                "make sure to recompile"]))

(defmacro verify-inst [name inst-roles roles signature]
  `(do
     (lifting [~@inst-roles]
       (let [{roles'# :roles signature'# :signature} (:klor/chor (meta #'~name))]
         (when (defchor-signature-changed? '~roles '~signature roles'# signature'#)
           (signature-error #'~name '~roles '~signature roles'# signature'#))))
     (inst ~name [~@inst-roles])))

;;; Instrumentation

(defmulti -instrument (fn [{:keys [op] :as ast} opts] op))

(defn instrument
  {:pass-info {:walk :post :depends #{#'typecheck} :after #{#'sanity-check}}}
  ([ast]
   (instrument ast (get-in (env/deref-env) [:passes-opts :instrument])))
  ([{:keys [env] :as ast} {:keys [agreement] :as opts}]
   (assert (or (contains? #{true false nil} agreement) (usym? agreement))
           "Invalid `:agreement` value")
   (assert (or (not (usym? agreement)) (some #{agreement} (:roles env)))
           (str "Role " agreement " is not part of the choreography"))
   (if opts (-instrument ast opts) ast)))

(defn verify-agreement [env agreement {:keys [rtype] :as ast}]
  (let [{:keys [ctor roles]} rtype
        form (emit-form ast #{})]
    (assert (= ctor :agree) "Expected an agreement type")
    (analyze* (if (true? agreement)
                `(verify-agreement-decentralized [~@roles] ~form)
                `(verify-agreement-centralized
                  [~agreement ~@(remove #{agreement} roles)] ~form))
              :env env :run-passes identity)))

(defmethod -instrument :chor
  [{:keys [top-level params body] :as ast} {:keys [agreement] :as opts}]
  (if (and top-level agreement)
    (let [params' (filter #(let [{:keys [ctor roles]} (:rtype %)]
                             (and (= ctor :agree) (>= (count roles) 2)))
                          params)
          ;; NOTE: Use `doall` to force `verify-agreement` to run within the
          ;; context of the currently bound analyzer environment, since it
          ;; invokes the analyzer to produce an AST.
          exprs (doall (map #(verify-agreement (:env body) agreement %)
                            params'))]
      (assert (= (:op body) :do) "Expected a do node")
      (assoc ast :body (update body :statements #(vec (concat exprs %)))))
    ast))

(defmethod -instrument :agree
  [{:keys [env] :as ast} {:keys [agreement] :as opts}]
  (if agreement (verify-agreement env agreement ast) ast))

(defmethod -instrument :inst
  [{inst-roles :roles :keys [env name var] :as ast}
   {:keys [instantiation] :as opts}]
  (if instantiation
    (let [{:keys [roles signature]} (:klor/chor (meta var))]
      (analyze* `(verify-inst ~name ~inst-roles ~roles ~signature)
                :env env :run-passes identity))
    ast))

(defmethod -instrument :default [ast opts]
  ast)

;;; Type Check Again

(def typecheck-again*
  ;; NOTE: Schedule a completely new instance of type checking.
  (schedule #{#'typecheck}))

(defn typecheck-again
  {:pass-info {:walk :none :after #{#'instrument}}}
  [ast]
  (if (get-in (env/deref-env) [:passes-opts :instrument])
    (typecheck-again* ast)
    ast))
