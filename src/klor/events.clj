(ns klor.events
  (:require [clojure.set :as set]
            [klor.core :refer :all]
            [klor.util :refer [usym? error do1]])
  (:import java.util.concurrent.LinkedBlockingQueue))

;;; Events

(def ^:dynamic *debug*
  false)

(defn debug [& args]
  (when *debug*
    (apply println args)))

(def stop (Object.))

(defn put! [^LinkedBlockingQueue q e]
  ;; NOTE: Wrap `e` in a vector so that we can put nils.
  (.put q [e]))

(defn take! [^LinkedBlockingQueue q]
  (first (.take q)))

(defn send-loop [^LinkedBlockingQueue q send-fn]
  (try
    (loop []
      (let [e (take! q)]
        (when-not (= e stop)
          (send-fn e)
          (recur))))
    (catch Throwable t
      (debug "Exception:" (.getMessage t)))
    (finally
      (debug "Send loop exited"))))

(defn recv-loop [^LinkedBlockingQueue recv-fn]
  (try
    (while true (recv-fn))
    (catch InterruptedException e
      (debug "Interrupted"))
    (catch Throwable t
      (debug "Exception:" (.getMessage t)))
    (finally
      (debug "Recv loop exited"))))

(defchor -events [A B] (-> (-> A B) [A A B]) [handler]
  (pack (A 'send-queue) (A 'send-loop-thread) (B 'recv-loop-thread)))

(def events
  (with-meta [(fn [send-fn]
                (let [q (LinkedBlockingQueue.)]
                  [q (Thread. (bound-fn [] (send-loop q send-fn)))]))
              (fn [recv-fn]
                [(Thread. (bound-fn [] (recv-loop recv-fn)))])]
    (meta -events)))

(alter-meta! #'events merge (select-keys (meta #'-events) [:klor/chor]))

;;; `with-events`
;;;
;;; <layout>    ::= [<chain>*]
;;; <chain>     ::= (<link-head> <link-tail>+)
;;; <link-head> ::= <role>
;;; <link-tail> ::= -> <role> | <- <role> | -- <role>

(defn link-edges [[l kind r :as link]]
  (when-not (and (= (count link) 3) (every? usym? [l r]) (not= l r))
    (error :klor ["Invalid `with-events` link: " link]))
  (case kind
    -> [[l r]]
    <- [[r l]]
    -- [[l r] [r l]]
    (error :klor ["Invalid `with-events` link kind: " kind])))

(defn links-graph [links]
  (reduce (fn [acc [src dst]]
            (-> (update-in acc [:out src] (fnil conj #{}) dst)
                (update-in [:in dst] (fnil conj #{}) src)))
          {} (mapcat link-edges links)))

(defn layout-graph [layout]
  (links-graph (mapcat #(partition 3 2 %) layout)))

(defn graph-roles [{:keys [out in] :as graph}]
  (set/union (set (keys out)) (set (keys in))))

(defn count-of [s]
  (get-in @s [:klor/events :count]))

(defn role-of [s]
  (get-in @s [:klor/events :role]))

(defn out-of [s]
  (set (keys (get-in @s [:klor/events :out]))))

(defn in-of [s]
  (set (keys (get-in @s [:klor/events :in]))))

(defn threads-of [s]
  (concat (map :send (vals (get-in @s [:klor/events :out])))
          (map :recv (vals (get-in @s [:klor/events :in])))))

(defn enq! [s dst val]
  (if-let [q (get-in @s [:klor/events :out dst :queue])]
    (put! q val)
    (throw (ex-info (format "No queue for %s" dst) {}))))

(defn interrupt! [s src]
  (if-let [t (get-in @s [:klor/events :in src :recv])]
    (.interrupt t)
    (throw (ex-info (format "No receiving thread for %s" src) {}))))

(defn start! [s]
  (let [ts (threads-of s)]
    (doseq [t ts]
      (.start t))
    (doseq [t ts]
      (.join t))))

(defn stop! [s]
  (doseq [dst (out-of s)]
    (enq! s dst stop))
  (doseq [src (in-of s)]
    (interrupt! s src)))

(defn data-of [s]
  (get @s :data))

(defn swap-data! [s f & args]
  (locking s
    (vswap! s (fn [val] (apply update val :data f args)))))

(defchor link! [A B] (-> #{A B} B A (-> [A B] [B A A] B) #{A B})
  [s src dst handler]
  (let [sa (narrow [A] s)
        sb (narrow [B] s)
        handler (chor (-> A B) [msg] (handler (pack sa sb) (pack src dst msg)))]
    (unpack [[q ts tr] (events [A B] handler)]
      (A (vswap! sa assoc-in [:klor/events :out dst] {:queue q :send ts}))
      (B (vswap! sb assoc-in [:klor/events :in src] {:recv tr}))
      s)))

(defmacro with-events [[s {:keys [layout init handler]}] & body]
  (let [{:keys [out] :as graph} (layout-graph layout)
        roles (graph-roles graph)]
    `(lifting [~@roles]
       (let [~s (volatile! {:klor/events {:count ~(count roles)}})]
         ~@(for [r roles]
             `(~r (vswap! ~s assoc-in [:klor/events :role] '~r)))
         ~@(for [[src dsts] out
                 dst dsts]
             `(link! [~src ~dst]
                     (narrow [~src ~dst] ~s) (~dst '~src) (~src '~dst)
                     (inst ~handler [~src ~dst])))
         ~@(for [r roles
                 :let [e (get init r)]]
             `(~r (swap-data! ~s (constantly ~e))))
         ~@body))))

;;; `with-reacts`

(defchor event-handler [A B] (-> [A B] [B A A] B) [[_ sb] [src _ msg]]
  (B (swap-data! sb (get-in @sb [:klor/actors :swap]) sb src (A->B msg))))

(defmacro with-reacts [[s {:keys [swap] :as opts}] & body]
  `(with-events [~s ~(dissoc (merge opts {:handler `event-handler}) :swap)]
     (vswap! ~s assoc-in [:klor/actors :swap] ~swap)
     ~@body))
