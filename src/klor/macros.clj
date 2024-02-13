(ns klor.macros
  (:require [klor.roles :refer [role-of roles-of role-expand role-analyze]]
            [klor.projection :refer [check-local project]]
            [klor.util :refer [warn]]))

(defn analyze [roles form]
  (->> form (role-expand roles) (role-analyze roles)))

(defn analyze-param [roles param]
  (let [param (analyze roles param)]
    (check-local param "A `defchor` parameter cannot involve multiple roles")
    param))

(defn project-params [role params]
  (filterv #(= (role-of %) role) params))

(defn project-name [role name]
  (symbol (namespace name) (str (clojure.core/name name) "-" role)))

(defn project-chor [role name params form]
  (let [name (project-name role name)]
    [`(defn ~name ~(project-params role params)
        ~(project role form))
     name]))

(defmacro defchor [name roles params & body]
  (let [params (map (partial analyze-param roles) params)
        form (analyze roles `(~'do ~@body))
        projs (map #(project-chor % name roles params form) roles)
        meta {:klor/chor `'{:roles ~roles :params ~params}}]
    `(do
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
