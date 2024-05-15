(ns klor.multi.stdlib
  (:require [klor.multi.specials :refer [narrow copy unpack* chor*]]
            [klor.multi.util :refer [usym? unpack-binder? error]]))

(defmacro move [roles expr]
  (when-not (and (vector? roles) (= (count roles) 2))
    (error :klor ["`move` needs a vector of exactly 2 roles: " roles]))
  (let [[src dst] roles]
    `(narrow [~dst] (copy [~src ~dst] ~expr))))

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
      `(chor* ~@(when name [name]) ~tspec ~names
         ~@(if (empty? unpacks)
             body
             `((unpack ~(into [] (apply concat unpacks))
                 ~@body)))))))

(defn make-copy [src dst]
  (symbol (str src '=> dst)))

(defn make-move [src dst]
  (symbol (str src '-> dst)))

(defmacro scatter [[src & dsts] expr]
  (reduce (fn [res dst] `(~(make-copy src dst) ~res)) expr dsts))

(defmacro gather [[dst & srcs] & exprs]
  (let [c1 (count srcs)
        c2 (count exprs)]
    (when-not (= (count srcs) (count exprs))
      (error :klor ["`gather` needs an equal number of sources and "
                    "expressions: " c1 " vs. " c2])))
  `(~dst ~(mapv (fn [src expr] `(~(make-move src dst) ~expr)) srcs exprs)))
