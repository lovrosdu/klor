(ns klor.multi.macros
  (:require [clojure.set :as set]
            [klor.multi.analyzer :refer [analyze]]
            [klor.multi.types :refer [parse-type type-roles render-type
                                      substitute-roles]]
            [klor.multi.stdlib :refer [chor]]
            [klor.multi.util :refer [usym?]]
            [klor.util :refer [warn error]]))

(defn adjust-defchor-signature [roles type]
  (update type :aux #(let [main (type-roles (assoc type :aux #{}))]
                       (set/difference (if (= % :none) (set roles) %) main))))

(defn signature-changed? [roles' signature' roles signature]
  (and roles' roles
       (or (not= (count roles') (count roles))
           (not= (substitute-roles signature' (zipmap roles' (range)))
                 (substitute-roles signature (zipmap roles (range)))))))

(defmacro defchor [name roles tspec & [params & body :as def]]
  (when-not (and (vector? roles) (not-empty roles) (every? usym? roles)
                 (apply distinct? roles))
    (error :klor ["`defchor`'s roles must be given as a vector of distinct "
                  "unqualified symbols:" roles]))
  (let [;; Create or fetch the var and remember its existing metadata
        exists? (ns-resolve *ns* name)
        var (intern *ns* name)
        m (meta var)
        {roles' :roles signature' :signature} (:klor/chor m)
        ;; Fill in the aux set for the new signature, if unspecified
        signature (adjust-defchor-signature roles (parse-type tspec))]
    (try
      (let [;; Alter the metadata so that the analyzer can see it
            m' {:roles roles :signature signature}
            _ (alter-meta! var merge {:klor/chor m'})]
        (when (not (empty? def))
          (let [;; Build and analyze the chor
                chor `(chor ~(render-type signature) ~params ~@body)
                {mentions :rmentions :as ast} (analyze chor {:roles (set roles)})]
            (when-let [diff (not-empty (set/difference (set roles) mentions))]
              (warn ["Some role parameters are never used: " diff]))))
        (when (signature-changed? roles' signature' roles signature)
          (warn ["Signature of " var " changed: was " (render-type signature')
                 ", is " (render-type signature) "; make sure to recompile "
                 "dependencies"]))
        ;; NOTE: Reattach the metadata to the var (via the symbol) because `def`
        ;; clears it. Also attach the metadata to the map for convenience.
        `(def ~(vary-meta name #(merge % `{:klor/chor '~m'}))
           ~(with-meta {:projections :todo} `{:klor/chor '~m'})))
      (catch Exception e
        ;; Roll back the metadata or remove the var if the analysis failed
        (if exists?
          (alter-meta! var (constantly m))
          (ns-unmap *ns* name))
        (throw e)))))
