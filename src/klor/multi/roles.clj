(ns klor.multi.roles
  (:require [clojure.set :as set]
            [clojure.tools.analyzer.utils :refer [-source-info]]
            [klor.multi.types :refer [type-roles]]
            [klor.multi.util :refer [usym? analysis-error]]))

;;; Validate Roles

(defn -validate-roles [form env roles]
  (when-not (every? usym? roles)
    (analysis-error ["Roles must be unqualified symbols: " roles] form env))
  (when-not (apply distinct? roles)
    (analysis-error (str "Duplicate roles: " roles) form env))
  (let [diff (set/difference (set roles) (:roles env))]
    (when-not (empty? diff)
      (analysis-error (str "Unknown roles: " diff) form env))))

(defmulti validate-roles
  {:pass-info {:walk :post}}
  :op)

(defmethod validate-roles :at [{:keys [form env roles] :as ast}]
  (-validate-roles form env roles)
  ast)

(defmethod validate-roles :mask [{:keys [form env roles] :as ast}]
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
