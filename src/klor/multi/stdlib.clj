(ns klor.multi.stdlib
  (:require [klor.util :refer [error]]
            [klor.multi.specials :refer [at copy unpack* chor*]]
            [klor.multi.util :refer [usym? unpack-binder?]]))

(defmacro move [[src dst] expr]
  `(at [~dst] (copy [~src ~dst] ~expr)))

(defmacro unpack
  {:style/indent 1
   :arglists '([[(binder init) *] & body])}
  [bindings & body]
  (when-not (and (vector? bindings) (even? (count bindings)))
    (error :klor ["`unpack` needs a vector with an even number of bindings: "
                  bindings]))
  (if (empty? bindings)
    `(do ~@body)
    (first (reduce (fn [body [binder init]]
                     `((unpack* ~binder ~init ~@body)))
                   body (reverse (partition 2 bindings))))))

(defn process-chor-param [param]
  (cond
    (usym? param) [nil param]
    (unpack-binder? param) [param (gensym "p")]
    :else (error :klor ["Invalid `chor` param: " param])))

(defmacro chor
  {:style/indent :defn
   :arglists '([tspec [params*] & body] [name tspec [params*] & body])}
  [& [name & _ :as args]]
  (let [[name tspec params & body] (if (symbol? name) args (cons nil args))]
    (when-not (vector? params)
      (error :klor ["`chor` needs a vector of parameters: " params]))
    (let [params (map process-chor-param params)
          unpacks (filter first params)
          names (mapv second params)]
      `(chor* ~@(and name `(~name)) ~tspec ~names
         ~@(if (empty? unpacks)
             body
             `((unpack ~(into [] (apply concat unpacks))
                 ~@body)))))))
