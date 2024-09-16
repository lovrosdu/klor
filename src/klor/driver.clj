(ns klor.driver
  (:require
   [clojure.tools.analyzer.jvm :as jvm-analyzer]
   [clojure.tools.analyzer.passes :refer [schedule]]
   [clojure.tools.analyzer.passes.constant-lifter]
   [clojure.tools.analyzer.passes.jvm.emit-form]
   [klor.analyzer :refer [analyze*]]
   [klor.emit-form :refer [emit-form]]
   [klor.instrument]
   [klor.typecheck]
   [klor.projection :as proj]))

;;; Main

(def analyze-passes
  #{;; Transfer the source info from the metadata to the local environment.
    #_#'clojure.tools.analyzer.passes.source-info/source-info

    ;; Elide metadata given by `clojure.tools.analyzer.passes.elide-meta/elides`
    ;; or `*compiler-options*`. `clojure.tools.analyzer.jvm/analyze` provides a
    ;; default set of elides for `fn` and `reify` forms.
    #_#'clojure.tools.analyzer.passes.elide-meta/elide-meta

    ;; Propagate constness to vectors, maps and sets of constants.
    #'clojure.tools.analyzer.passes.constant-lifter/constant-lift

    ;; Rename the `:name` field of all all `:binding` and `:local` nodes to
    ;; fresh names.
    ;;
    ;; Requires `:uniquify/uniquify-env` to be true within the pass options in
    ;; order to also apply the same changes to the `:binding` nodes in local
    ;; environments (under `[:env :locals]`). However, keys of the local
    ;; environment are never touched and remain the original names (stored under
    ;; the `:form` field).
    #_#'clojure.tools.analyzer.passes.uniquify/uniquify-locals

    ;; Elide superfluous `:do`, `:let` and `:try` nodes when possible.
    #_#'clojure.tools.analyzer.passes.trim/trim

    ;; Refine `:host-field`, `:host-call` and `:host-interop` nodes to
    ;; `:instance-field`, `:instance-call`, `:static-field` and `:static-call`
    ;; when possible. This happens when the class can be determined statically.
    ;; In that case, the named field or method must exist otherwise an error is
    ;; thrown. If the class cannot be determined statically, the node is
    ;; kept as or converted to a `:host-interop` node.
    ;;
    ;; Also refine `:var` and `:maybe-class` nodes to `:const` when possible.
    #_#'clojure.tools.analyzer.passes.jvm.analyze-host-expr/analyze-host-expr

    ;; Refine `:invoke` nodes to `:keyword-invoke`, `:prim-invoke`,
    ;; `:protocol-invoke` and `:instance?` nodes when possible.
    #_#'clojure.tools.analyzer.passes.jvm.classify-invoke/classify-invoke

    ;; Validate a number of JVM-specific things. Most importantly, throw on
    ;; encountering `:maybe-class` or `:maybe-host-form` nodes. Such nodes are
    ;; produced for non-namespaced and namespaced symbols (respectively) that do
    ;; not resolve to a var or a class.
    ;;
    ;; This pass depends on
    ;; `clojure.tools.analyzer.passes.jvm.analyze-host-expr/analyze-host-expr`
    ;; which first performs a number of refinements when possible. Other than
    ;; that, `clojure.tools.analyzer.jvm` has no substantial handling for the
    ;; above two nodes in any of its passes and always considers them an error.
    #_#'clojure.tools.analyzer.passes.jvm.validate/validate

    ;; Throw on invalid role applications.
    #_#'klor.validate-roles/validate-roles

    ;; Propagate lifting masks.
    #_#'klor.typecheck/propagate-masks

    ;; Type check.
    #'klor.typecheck/typecheck

    ;; Assert invariants after type checking.
    #'klor.typecheck/sanity-check

    ;; Potentially instrument the code with dynamic checks.
    #'klor.instrument/instrument

    ;; Type check again after instrumenting.
    #'klor.instrument/typecheck-again

    ;; Emit form.
    #_#'klor.emit-form/emit-form})

(def analyze-passes*
  (schedule analyze-passes))

(defn analyze [form & {:as opts}]
  (analyze* form :run-passes analyze-passes* opts))

(def project-passes
  #{#'klor.projection/cleanup
    #'clojure.tools.analyzer.passes.jvm.emit-form/emit-form})

(def project-passes*
  (schedule project-passes))

(defn project [ast & {:keys [cleanup] :as opts}]
  (let [cleanup' {:style (or cleanup :aggressive)}
        opts' {:bindings {#'jvm-analyzer/run-passes project-passes*}
               :passes-opts {:cleanup cleanup'}}]
    (jvm-analyzer/analyze (proj/project ast opts) (jvm-analyzer/empty-env)
                          (merge-with merge opts' opts))))

;;; Utility

(defn analyze+emit [form & {:keys [emit] :as opts}]
  (let [emit (cond
               (nil? emit) #{:sugar :type-meta}
               (set? emit) emit
               :else #{emit})
        ast (analyze form opts)]
    [ast (emit-form ast emit)]))

(defn analyze+project [form & {:keys [env] :as opts}]
  (let [{:keys [roles]} env
        ast (analyze form opts)]
    [ast (zipmap roles (map #(project ast (merge opts {:role %})) roles))]))
