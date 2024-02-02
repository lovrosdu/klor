(ns klor.macros
  (:require [klor.roles :refer [role-of roles-of role-expand role-analyze]]
            [klor.projection :refer [check-local project]]
            [klor.util :refer [error warn]]))

(def ^:dynamic *defchor-role*
  nil)

(defn analyze [roles form]
  (->> form (role-expand roles) (role-analyze roles)))

(defn analyze-param [roles param]
  (let [param (analyze roles param)]
    (check-local param "A `defchor` parameter cannot involve multiple roles")
    param))

(defn project-params [role params]
  (filterv #(= (role-of %) role) params))

(defmacro defchor [name roles params & body]
  (when (nil? *defchor-role*)
    (error :klor "`*defchor-role*` hasn't been set" :form &form))
  (let [params (map (partial analyze-param roles) params)
        form (analyze roles `(~'do ~@body))]
    (when-not (contains? (roles-of form) *defchor-role*)
      (warn ["Role " *defchor-role* " does not appear in the body of " name]))
    `(defn ~name ~(project-params *defchor-role* params)
       ~(project *defchor-role* form))))

(defmacro select
  {:style/indent 1}
  [& _]
  (warn "`select` used outside of a choreographic context")
  &form)
