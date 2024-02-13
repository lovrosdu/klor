(ns klor.runtime
  (:refer-clojure :exclude [send])
  (:require [klor.util :refer [error]]))

(def ^:dynamic *state*
  {})

(defn state-invoke [keys message & args]
  (if-let [f (some #(get *state* %) (if (coll? keys) keys [keys]))]
    (apply f args)
    (error :klor [message ": " (str *state*)])))

(defn locator [role]
  (get-in *state* [:locators role]))

(defn send [to value]
  (state-invoke :send "Send function undefined" (locator to) value)
  ;; NOTE: `send` returns nil.
  nil)

(defn recv [from]
  (state-invoke :recv "Receive function undefined" (locator from)))

(defn choose [to label]
  (state-invoke [:choose :send] "Choose function undefined" (locator to) label)
  ;; NOTE: `choose` returns nil.
  nil)

(defn offer* [from]
  (state-invoke [:offer :recv] "Offer function undefined" (locator from)))

(defmacro offer [from & options]
  `(let [label# (offer* '~from)]
     (condp = label#
       ~@(mapcat identity (for [[label body] (partition 2 options)]
                            `['~label ~body]))
       (error :klor ["Received unrecognized label: " label#]))))

(defn play-role [{:keys [role locators] :as state} chor & args]
  (let [roles (keys chor)
        locators (merge (zipmap roles roles) locators)]
    (binding [*state* (merge state {:locators locators})]
      (apply (get chor role) args))))
