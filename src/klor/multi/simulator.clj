(ns klor.multi.simulator
  (:require [clojure.core.async :as a]
            [klor.multi.runtime :refer [play-role]]
            [klor.multi.types :refer [type-roles render-type]]
            [klor.multi.util :refer [error do1 virtual-thread]])
  (:import java.io.CharArrayWriter))

;;; Logging

(def ^:dynamic *log*
  true)

(defn log* [& args]
  (when *log*
    (binding [*out* *log*]
      ;; NOTE: Lock `*out*` to prevent interleaved printing of the individual
      ;; arguments of multiple `print` calls. `Writer` objects are thread-safe,
      ;; but Clojure's `print` & co. write out each argument separately.
      (locking *out*
        (apply print args)))))

(defn log [& args]
  (apply log* (concat args ["\n"])))

(defn redirect [role]
  (proxy [CharArrayWriter] []
    (flush []
      (log* (str role ":") (.toString ^CharArrayWriter this))
      (.reset ^CharArrayWriter this))))

;;; `core.async` Transport

(defn ensure-channel [channels src dst]
  (if (get channels [src dst])
    channels
    (conj channels [[src dst] (a/chan)])))

(defn get-channel [channels src dst]
  (let [channels (swap! channels ensure-channel src dst)]
    (get channels [src dst])))

(defn channel-send [channels src]
  (fn [dst value]
    ;; NOTE: Wrap `value` in a vector so that we can communicate nils.
    (a/>!! (get-channel channels src dst) [value])
    value))

(defn channel-recv [channels dst]
  (fn [src]
    (let [[value] (a/<!! (get-channel channels src dst))]
      (log src "-->" (str dst ": " (pr-str value)))
      value)))

(defn wrap-channels [{:keys [role] :as config} roles channels]
  (merge config {:send (channel-send channels role)
                 :recv (channel-recv channels role)
                 :locators roles}))

;;; Simulator

(defn project-args [role args params]
  (keep (fn [[a p]] (when (contains? (type-roles p) role) a))
        (map vector args params)))

(defn spawn-role [{:keys [role] :as config} chor args]
  (let [log-writer (if (true? *log*) *out* *log*)
        redirect-writer (if *log* (redirect role) *out*)]
    (virtual-thread
      (binding [*log* log-writer]
        (log role "spawned")
        (try
          (do1 (binding [*out* redirect-writer]
                 (apply play-role config chor args))
            (log role "exited normally"))
          (catch Throwable t
            (log role "exited abruptly:" (.getMessage t))
            t))))))

(defn simulate-chor [chor & args]
  (let [channels (atom {})
        {:keys [roles signature]} (:klor/chor (meta chor))
        {:keys [params]} signature]
    (when (some #(not= (:ctor %) :agree) params)
      (error :klor ["Cannot invoke a choreography that has parameters of "
                    "non-agreement type: " (render-type signature)]))
    (let [c1 (count args)
          c2 (count params)]
      (when-not (= c1 c2)
        (error :klor ["Wrong number of arguments to the choreography: got " c1
                      ", expected " c2])))
    (->> roles
         (map #(spawn-role (wrap-channels {:role %} roles channels) chor
                           (project-args % args params)))
         ;; NOTE: Ensure that all roles have been spawned before waiting.
         doall
         (map deref)
         (zipmap roles)
         future)))
