(ns klor.multi.defchor
  (:require [clojure.set :as set]
            [klor.multi.analyzer :refer [analyze adjust-chor-signature project]]
            [klor.multi.types :refer [parse-type type-roles render-type
                                      substitute-roles]]
            [klor.multi.stdlib :refer [chor]]
            [klor.multi.util :refer [usym?]]
            [klor.util :refer [warn error]]))

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
           (not= (substitute-roles signature' (zipmap roles' (range)))
                 (substitute-roles signature (zipmap roles (range)))))))

(defn render-signature [roles signature]
  `(~'forall ~roles ~(render-type signature)))

(defmacro defchor
  {:arglists '([name roles tspec] [name roles tspec & params & body])}
  [name roles tspec & [params & body :as def]]
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
                    (error :klor ["Invalid `defchor` signature: " tspec]))]
    (try
      (let [;; Alter the metadata so that the analyzer can see it
            m' {:roles roles :signature signature}
            _ (alter-meta! var merge {:klor/chor m'})]
        (when (not (empty? def))
          (let [;; Build, analyze and project the chor
                chor `(chor ~(render-type signature) ~params ~@body)
                ast (analyze chor {:env {:roles roles}})
                mentions (:rmentions (:body ast))
                projs (map #(project ast {:role % :defchor? true}) roles)]
            (when-let [diff (not-empty (set/difference (set roles) mentions))]
              (warn ["Some role parameters are never used: " diff]))
            (when (defchor-signature-changed? roles' signature' roles signature)
              (warn ["Signature of " var " changed:\n"
                     "  was " (render-signature roles' signature') ",\n"
                     "  is " (render-signature roles signature) ";\n"
                     "make sure to recompile dependencies"]))
            ;; NOTE: Reattach the metadata to the var (via the symbol) because
            ;; `def` clears it. Also attach the metadata to the vector for
            ;; convenience.
            `(def ~(vary-meta name #(merge % `{:klor/chor '~m'}))
               ~(with-meta (vec projs) `{:klor/chor '~m'})))))
      (finally
        ;; NOTE: We don't want to create or modify the var during
        ;; macroexpansion, so we unconditionally roll back our changes and leave
        ;; the job to the evaluation of the `def` form.
        (if exists?
          (alter-meta! var (constantly m))
          (ns-unmap *ns* name))))))