(ns klor.multi.runtime
  (:refer-clojure :exclude [send])
  (:require [klor.util :refer [error]]))

(def ^:dynamic *config*
  {})

(defn noop [& _]
  noop)

(defn send [dst-idx val]
  (if-let [f (:send *config*)]
    (f (get-in *config* [:locators dst-idx]) val)
    (error :klor ["Send function unspecified: " (str *config*)]))
  ;; NOTE: `send` always returns the value sent.
  val)

(defn recv [src-idx]
  (if-let [f (:recv *config*)]
    (f (get-in *config* [:locators src-idx]))
    (error :klor ["Receive function unspecified: " (str *config*)])))

(defn config-fn [config f]
  ;; NOTE: Capture `config` and install it as the value of `*config*` once the
  ;; function is called. This is necessary because a choreography might be
  ;; passed outside of the context in which it was created.
  (fn [& args]
    (binding [*config* config]
      (apply f args))))

(defn make-chor
  ([f]
   (config-fn *config* f))
  ([chor role-idx locator-idxs]
   (let [locators (:locators *config*)]
     (config-fn (merge *config*
                       {:locators (mapv #(get locators %) locator-idxs)})
                (get chor role-idx)))))
