(ns klor.multi.stdlib
  (:require [klor.util :refer [error]]
            [klor.multi.specials :refer [unpack* chor*]]
            [klor.multi.util :refer [usym? unpack-binder?]]))

(defmacro unpack [bindings & body]
  {:style/indent 1}
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

(defmacro chor [signature params & body]
  {:style/indent 2}
  (when-not (vector? params)
    (error :klor ["`chor` needs a vector of parameters: " params]))
  (let [params (map process-chor-param params)
        unpacks (filter first params)
        names (mapv second params)]
    `(chor* ~signature ~names
       ~@(if (empty? unpacks)
           body
           `((unpack ~(into [] (apply concat unpacks))
               ~@body))))))
