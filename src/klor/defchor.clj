(ns klor.defchor
  (:require
   [clojure.set :as set]
   [klor.analyzer :refer [adjust-chor-signature]]
   [klor.driver :refer [analyze project]]
   [klor.emit-form :refer [emit-form]]
   [klor.types :refer [parse-type type-roles render-type replace-roles]]
   [klor.stdlib :refer [chor]]
   [klor.opts :refer [*opts*]]
   [klor.util :refer [usym? warn error]]))

(defn adjust-defchor-signature [roles type]
  (-> (update type :aux #(let [main (type-roles (assoc type :aux #{}))]
                           (set/difference (if (= % :none) (set roles) %)
                                           main)))
      ;; NOTE: Set unspecified aux sets of any choreography parameters to the
      ;; empty set. This is already done by the analyzer and ideally we would
      ;; just inherit the final signature from the type checker, but we need to
      ;; be able to install the definition's signature *before* any analysis is
      ;; done, due to possibility of recursion and self-reference.
      adjust-chor-signature))

(defn defchor-signature-changed? [roles' signature' roles signature]
  (and roles' roles
       (or (not= (count roles') (count roles))
           (not= (replace-roles signature' (zipmap roles' (range)))
                 (replace-roles signature (zipmap roles (range)))))))

(defn render-signature [roles signature]
  `(~'forall ~roles ~(render-type signature)))

(defn make-projs [roles signature [params & body]]
  (let [chor `(chor ~(render-type signature) ~params ~@body)
        ast (analyze chor {:env {:roles roles} :passes-opts *opts*})]
    [ast (map #(project ast {:role %}) roles)]))

(defn make-expansion [name meta roles signature def]
  (let [[ast projs] (when def (make-projs roles signature def))
        name (vary-meta name merge `{:klor/chor '~meta})]
    ;; NOTE: Reattach the metadata to the var (via the symbol) because `def`
    ;; clears it. Also attach the metadata to the vector for convenience.
    [ast (cond
           (get-in *opts* [:debug :expansion]) `'~(emit-form ast #{:sugar})
           def `(def ~name ~(with-meta (vec projs) `{:klor/chor '~meta}))
           :else `(declare ~name))]))

(defmacro defchor
  {:arglists '([name roles tspec] [name roles tspec & [params & body]])}
  [name roles tspec & def]
  (when-not (and (vector? roles) (not-empty roles) (every? usym? roles)
                 (apply distinct? roles))
    (error :klor ["`defchor`'s roles must be given as a vector of distinct "
                  "unqualified symbols: " roles]))
  (let [;; Create or fetch the var and remember its existing metadata
        exists? (ns-resolve *ns* name)
        var (intern *ns* name)
        m (meta var)
        {roles' :roles signature' :signature} (:klor/chor m)
        ;; Set the aux sets within the new signature, if unspecified
        signature (if-let [signature (parse-type tspec)]
                    (adjust-defchor-signature roles signature)
                    (error :klor ["Invalid `defchor` signature: " tspec]))
        ;; Prepare the new metadata
        m' {:roles roles :signature signature}]
    (try
      ;; Alter the metadata so that the analyzer can see it
      (alter-meta! var merge {:klor/chor m'})
      ;; Build, analyze and project the chor, and create the expansion
      (let [[ast expansion] (make-expansion name m' roles signature def)]
        (when-let [mentions (when def (:rmentions (:body ast)))]
          (when-let [diff (not-empty (set/difference (set roles) mentions))]
            (warn ["Some role parameters are never used: " diff])))
        (when (defchor-signature-changed? roles' signature' roles signature)
          (warn ["Signature of " var " changed:\n"
                 "  was " (render-signature roles' signature') ",\n"
                 "  is " (render-signature roles signature) ";\n"
                 "make sure to recompile dependents"]))
        expansion)
      (finally
        ;; NOTE: We don't want to create or modify the var during
        ;; macroexpansion, so we unconditionally roll back our changes and leave
        ;; the job to the evaluation of the expansion.
        (if exists?
          (alter-meta! var (constantly m))
          (ns-unmap *ns* name))))))
