(ns klor.multi.runtime
  (:refer-clojure :exclude [send])
  (:require [klor.multi.types :refer [type-roles render-type]]
            [klor.multi.util :refer [error]]))

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

(defn make-proj
  ([f]
   (config-fn *config* f))
  ([chor role-idx locator-idxs]
   (let [locators (:locators *config*)]
     (config-fn (merge *config*
                       {:locators (mapv #(get locators %) locator-idxs)})
                (get chor role-idx)))))

(defn play-role [{:keys [role locators] :as config} chor & args]
  (let [{:keys [roles signature]} (:klor/chor (meta chor))
        role-idx (.indexOf roles role)
        locators (update-keys
                  locators
                  #(let [idx (.indexOf roles %)]
                     (if (= idx -1)
                       (error :klor ["Role " role " is not part of "
                                     "the choreography"])
                       idx)))
        {:keys [params]} signature]
    (when (= role-idx -1)
      (error :klor ["Role " role " is not part of the choreography"]))
    (when (some #(not= (:ctor %) :agree) params)
      (error :klor ["Cannot invoke the projection of a choreography that has "
                    "parameters of non-agreement type: "
                    (render-type signature)]))
    (let [params' (keep #(when (contains? (type-roles %) role) %) params)
          c1 (count args)
          c2 (count params')]
      (when-not (= c1 c2)
        (error :klor ["Wrong number of arguments to the projection for " role
                      ": got " c1 ", expected " c2])))
    (binding [*config* (assoc config :locators locators)]
      (apply (get chor role-idx) args))))
