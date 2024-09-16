(ns klor.validate-roles
  (:require
   [clojure.set :as set]
   [clojure.tools.analyzer.utils :refer [-source-info]]
   [klor.types :refer [type-roles]]
   [klor.util :refer [usym? ast-error]]))

(defn validate-error [msg ast & {:as kvs}]
  (ast-error :klor/parse msg ast kvs))

(defn -validate-roles [{:keys [env] :as ast} roles]
  (when-not (every? usym? roles)
    (validate-error ["Roles must be unqualified symbols: " roles] ast))
  (when-not (apply distinct? roles)
    (validate-error (str "Duplicate roles: " roles) ast))
  (let [diff (set/difference (set roles) (set (:roles env)))]
    (when-not (empty? diff)
      (validate-error (str "Unknown roles: " diff) ast))))

(defmulti validate-roles
  {:pass-info {:walk :post}}
  :op)

(defmethod validate-roles :narrow [{:keys [roles] :as ast}]
  (-validate-roles ast roles)
  ast)

(defmethod validate-roles :lifting [{:keys [roles] :as ast}]
  (-validate-roles ast roles)
  ast)

(defmethod validate-roles :copy [{:keys [src dst] :as ast}]
  (-validate-roles ast [src dst])
  ast)

(defmethod validate-roles :chor [{:keys [signature] :as ast}]
  (-validate-roles ast (type-roles signature))
  ast)

(defmethod validate-roles :inst [{:keys [roles] :as ast}]
  (-validate-roles ast roles)
  ast)

(defmethod validate-roles :default [ast]
  ast)
