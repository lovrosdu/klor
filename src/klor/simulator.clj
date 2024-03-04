(ns klor.simulator
  (:require [clojure.core.async :as a]
            [klor.roles :refer [role-of]]
            [klor.runtime :refer [play-role]]
            [klor.util :refer [virtual-thread]])
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

;;; core.async Transport

(defn ensure-channel [channels from to]
  (if (get channels [from to])
    channels
    (conj channels [[from to] (a/chan)])))

(defn get-channel [channels from to]
  (let [channels (swap! channels ensure-channel from to)]
    (get channels [from to])))

(defn channel-send [channels from]
  (fn [to value]
    ;; NOTE: Wrap `value` in a vector so that we can communicate nils.
    (a/>!! (get-channel channels from to) [value])
    value))

(defn channel-recv [channels to]
  (fn [from]
    (let [[value] (a/<!! (get-channel channels from to))]
      (log from "-->" (str to ": " (pr-str value)))
      value)))

(defn channel-offer [channels to]
  (fn [from]
    (let [[value] (a/<!! (get-channel channels from to))]
      (log from "-->" to (str "[" (pr-str value) "]"))
      value)))

(defn wrap-channels [{:keys [role] :as config} channels]
  (merge {:send (channel-send channels role)
          :recv (channel-recv channels role)
          :offer (channel-offer channels role)}
         config))

;;; Simulator

(defn project-args [role params args]
  (keep (fn [[param arg]] (if (= (role-of param) role) arg nil))
        (map vector params args)))

(defn spawn-role [role channels params chor args]
  (let [log-writer (if (true? *log*) *out* *log*)
        redirect-writer (if *log* (redirect role) *out*)]
    (virtual-thread
      (binding [*log* log-writer]
        (log role "spawned")
        (let [res (binding [*out* redirect-writer]
                    (apply play-role (wrap-channels {:role role} channels) chor
                           (project-args role params args)))]
          (log role "exited")
          res)))))

(defn promise-all [promises]
  (delay (mapv #(try (deref %) (catch Throwable t t)) promises)))

(defn simulate-chor [chor & args]
  (let [channels (atom {})
        {:keys [roles params]} (:klor/chor (meta chor))]
    (promise-all (mapv #(spawn-role % channels params chor args) roles))))
