(ns klor.runtime
  (:refer-clojure :exclude [send])
  (:require [klor.util :refer [error]]))

(def ^:dynamic *config*
  {})

(defn config-invoke [keys message & args]
  (if-let [f (some #(get *config* %) (if (coll? keys) keys [keys]))]
    (apply f args)
    (error :klor [message ": " (str *config*)])))

(defn locator [role]
  (get-in *config* [:locators role]))

(defn send [to value]
  (config-invoke :send "Send function undefined" (locator to) value)
  ;; NOTE: The projection assumes that `send` always returns nil.
  nil)

(defn recv [from]
  (config-invoke :recv "Receive function undefined" (locator from)))

(defn choose [to label]
  (config-invoke [:choose :send] "Choose function undefined" (locator to) label)
  ;; NOTE: The projection assumes that `choose` always returns nil.
  nil)

(defn offer* [from]
  (config-invoke [:offer :recv] "Offer function undefined" (locator from)))

(defmacro offer [from & options]
  `(let [label# (offer* '~from)]
     (condp = label#
       ~@(mapcat identity (for [[label body] (partition 2 options)]
                            `['~label ~body]))
       (error :klor ["Received unrecognized label: " label#]))))

(defn play-role [{:keys [role locators] :as config} chor & args]
  (let [roles (keys chor)
        locators (merge (zipmap roles roles) locators)]
    (binding [*config* (merge config {:locators locators})]
      (apply (get chor role) args))))
