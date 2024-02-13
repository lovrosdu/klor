(ns klor.macros
  (:require [klor.roles :refer [role-of roles-of role-expand role-analyze]]
            [klor.projection :refer [check-located check-local project]]
            [klor.util :refer [error warn fully-qualify]]))

(defn analyze [roles form]
  (->> form (role-expand roles) (role-analyze roles)))

(defn analyze-param [roles param]
  (let [param (analyze roles param)]
    (check-located param ["Unlocated parameter: " param])
    (check-local param ["Parameter cannot involve multiple roles: " param])
    param))

(defn project-params [role params]
  (filterv #(= (role-of %) role) params))

(defn project-name [role name]
  (symbol (namespace name) (str (clojure.core/name name) "-" role)))

(defn project-chor [role name roles params form]
  (let [proj-name (project-name role name)
        ns (clojure.core/name (ns-name *ns*))]
    [`(defn ~proj-name ~(project-params role params)
        ~(project role form
                  ;; Remember the current namespace for resolution purposes.
                  :ns ns
                  ;; Remember the role parameters currently in scope.
                  :roles roles
                  ;; Add `name` to the environment to allow for recursion.
                  :env {(fully-qualify ns name) {:roles roles :params params}}))
     proj-name]))

(defmacro defchor [name roles params & body]
  (when-not (apply distinct? roles)
    (error :klor ["Duplicate roles: " roles]))
  (let [params (map (partial analyze-param roles) params)
        form (analyze roles `(~'do ~@body))
        projs (map #(project-chor % name roles params form) roles)
        meta {:klor/chor `'{:roles ~roles :params ~params}}]
    `(do
       (declare ~name)
       ~@(map first projs)
       ;; NOTE: Attach the metadata both to the var and the map, for
       ;; convenience.
       (def ~(vary-meta name #(merge % meta))
         ~(with-meta (zipmap (for [r roles] `'~r) (map second projs)) meta)))))

(defmacro select
  {:style/indent 1}
  [& _]
  (warn "`select` used outside of a choreographic context")
  &form)
