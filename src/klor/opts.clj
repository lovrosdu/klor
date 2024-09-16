(ns klor.opts
  (:require [clojure.tools.analyzer.jvm :refer [empty-env macroexpand-all]]))

(def ^:dynamic *opts*
  {:debug {:expansion false}
   :instrument {:agreement false :instantiation false}})

(defmacro with-opts [map & body]
  (binding [*opts* (eval map)]
    ;; NOTE: We use `clojure.tools.analyzer.jvm`'s `macroexpand-all` because
    ;; `clojure.walk`'s is very primitive and walks the form blindly without
    ;; actually understanding which parts are code, which are literals, etc.
    (macroexpand-all `(do ~@body) (empty-env) {:passes-opts {}})))

(defn alter-opts! [f & args]
  (apply alter-var-root #'*opts* f args))
