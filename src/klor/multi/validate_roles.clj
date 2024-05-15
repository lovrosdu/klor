(ns klor.multi.validate-roles
  (:require [clojure.set :as set]
            [clojure.tools.analyzer.utils :refer [-source-info]]
            [klor.multi.types :refer [type-roles]]
            [klor.multi.util :refer [usym? form-error]]))

(defn validate-error [msg form env & {:as kvs}]
  (form-error :klor/parse msg form env kvs))

(defn -validate-roles [form env roles]
  (when-not (every? usym? roles)
    (validate-error ["Roles must be unqualified symbols: " roles] form env))
  (when-not (apply distinct? roles)
    (validate-error (str "Duplicate roles: " roles) form env))
  (let [diff (set/difference (set roles) (set (:roles env)))]
    (when-not (empty? diff)
      (validate-error (str "Unknown roles: " diff) form env))))

(defmulti validate-roles
  {:pass-info {:walk :post}}
  :op)

(defmethod validate-roles :narrow [{:keys [form env roles] :as ast}]
  (-validate-roles form env roles)
  ast)

(defmethod validate-roles :lifting [{:keys [form env roles] :as ast}]
  (-validate-roles form env roles)
  ast)

(defmethod validate-roles :copy [{:keys [form env src dst] :as ast}]
  (-validate-roles form env [src dst])
  ast)

(defmethod validate-roles :chor [{:keys [form env signature] :as ast}]
  (-validate-roles form env (type-roles signature))
  ast)

(defmethod validate-roles :inst [{:keys [form env roles] :as ast}]
  (-validate-roles form env roles)
  ast)

(defmethod validate-roles :default [ast]
  ast)
