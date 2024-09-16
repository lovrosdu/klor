(ns klor.fokkink-plain
  (:require
   [clojure.set :as set]
   [clojure.core.async :as a]
   [clojure.core.match :refer [match]]
   [klor.events :as events]
   [klor.util :refer [do1]]))

(def ^:dynamic *debug*
  false)

(defn debug [& args]
  (when *debug*
    (locking *out*
      (apply println args))))

;;; Util

(defn max* [& args]
  (apply max (remove nil? args)))

(defn rand-elem [coll]
  (rand-nth (seq coll)))

;;; Transport

(defn ensure-channel [channels src dst]
  (if (get channels [src dst])
    channels
    (conj channels [[src dst] (a/chan 100)])))

(defn get-channel [channels src dst]
  (let [channels (swap! channels ensure-channel src dst)]
    (get channels [src dst])))

(defn send! [s dst val]
  (let [{:keys [chans me]} s]
    ;; NOTE: Wrap `val` in a vector so that we can communicate nils.
    (do1 val
      (a/>!! (get-channel chans me dst) [val]))))

(defn recv! [s src]
  (let [{:keys [chans me]} s
        [val] (a/<!! (get-channel chans src me))]
    (do1 val
      (debug src "-->" (str me ": " (pr-str val))))))

(defn send-first! [s val]
  (send! s (first (:outs s)) val))

(defn send-all! [s val]
  (doseq [o (:outs s)]
    (send! s o val)))

(defn recv-any! [s]
  (let [{:keys [chans me ins]} s
        [[val] chan] (a/alts!! (map #(get-channel chans % me) ins))
        src (some (fn [[[src _] chan']] (when (= chan chan') src)) @chans)]
    (do1 [val src]
      (debug src "-->" (str me ": " (pr-str val))))))

;;; Fokkink (2013): Election: Chang--Roberts

(defn chang-roberts-loop [s]
  (loop [{:keys [id leader passive? leader?] :as data} (:data s)]
    (let [[msg _] (recv-any! s)]
      (match msg
        [:propose {:id id'}]
        (cond
          ;; Purge the message if we have already become the leader previously.
          (or leader?)
          (recur data)

          ;; Update the last observed leader if we're passive or our ID is
          ;; smaller than the received one. Also pass on the message.
          (or passive? (> id' id))
          (let [leader' (max* leader id')]
            (debug "New leader:" leader')
            (send-first! s msg)
            (recur (assoc data :passive? true :leader leader')))

          ;; Purge the message if our ID is higher than the received one.
          (< id' id)
          (recur data)

          ;; Become the leader if our ID matches the received one, as that means
          ;; the message came from us and made a full round trip. Also send an
          ;; exit message.
          :else
          (do (debug "I am the leader:" id)
              (send-first! s [:exit {:id id}])
              (recur (assoc data :leader id :leader? true))))

        ;; Pass on the `:exit` message and then exit.
        [:exit {:id id'}]
        (do (debug "Exiting")
            (when (not= id id')
              (send-first! s msg))
            data)))))

(defn chang-roberts-1 [chans me ins outs data]
  (let [s {:chans chans :me me :ins ins :outs outs :data data}
        {:keys [id passive?]} data]
    (when (not passive?)
      (send-first! s [:propose {:id id}]))
    (chang-roberts-loop s)))

(defn chang-roberts [[head & _ :as roles] & args]
  (let [ring (interpose '-> (concat roles [head]))
        {:keys [in out] :as graph} (events/layout-graph [ring])
        chans (atom {})
        ps (for [[role arg] (map vector roles args)]
             (future
               (chang-roberts-1 chans role (in role) (out role) arg)))]
    (mapv deref (doall ps))))

(comment
  (chang-roberts '[A B C] {:id 3} {:id 7} {:id 5})
  )

;;; Fokkink (2013): Waves: Itai--Rodeh

(defn itai-rodeh-loop [s]
  (loop [{:keys [n id round leader passive? leader?] :as data} (:data s)]
    (let [[msg _] (recv-any! s)]
      (match msg
        [:propose {:id id' :round round' :hops hops :dup? dup?}]
        (cond
          (or leader?)
          (recur data)

          (or passive? (> round' round) (and (= round' round) (> id' id)))
          (let [leader' (max* leader id')]
            (debug "New leader:" leader')
            (send-first! s (update-in msg [1 :hops] inc))
            (recur (assoc data :passive? true :leader leader')))

          (or (< round' round) (and (= round' round) (< id' id)))
          (recur data)

          (and (= round' round) (= id' id) (< hops n))
          (do (send-first! s [:propose {:id id :round round
                                        :hops (inc hops) :dup? true}])
              (recur data))

          (and (= round' round) (= id' id) (= hops n) dup?)
          (let [id'' (rand-int n)
                round' (inc round)]
            (debug "New ID:" id'')
            (send-first! s [:propose {:id id'' :round round'
                                      :hops 1 :dup? false}])
            (recur (assoc data :id id'' :round round')))

          (and (= round' round) (= id' id) (= hops n) (not dup?))
          (do (debug "I am the leader:" id)
              (send-first! s [:exit {:hops 1}])
              (recur (assoc data :leader id :leader? true))))

        ;; Pass on the `:exit` message and then exit.
        [:exit {:hops hops}]
        (do (debug "Exiting")
            (when (not= hops n)
              (send-first! s (update-in msg [1 :hops] inc)))
            data)))))

(defn itai-rodeh-1 [chans me ins outs {:keys [n] :as data}]
  (let [{:keys [data] :as s} {:chans chans :me me :ins ins :outs outs
                              :data (merge data {:round 0 :id (rand-int n)})}
        {:keys [id round passive?]} data]
    (when (not passive?)
      (send-first! s [:propose {:id id :round round :hops 1 :dup? false}]))
    (itai-rodeh-loop s)))

(defn itai-rodeh [[head & _ :as roles] & args]
  (let [ring (interpose '-> (concat roles [head]))
        {:keys [in out] :as graph} (events/layout-graph [ring])
        chans (atom {})
        ps (for [[role arg] (map vector roles args)]
             (future
               (itai-rodeh-1 chans role (in role) (out role) arg)))]
    (mapv deref (doall ps))))

(comment
  (itai-rodeh '[A B C] {:n 3} {:n 3} {:n 3})
  )

;;; Fokkink (2013): Waves: Tarry's Algorithm

(defn tarry-loop [{:keys [outs] :as s}]
  (loop [{:keys [parent done] :as data} (:data s)]
    (let [[msg src] (recv-any! s)]
      (match msg
        [:token {:hops hops}]
        (let [ ;; Set the parent if necessary
              parent' (or parent src)
              ;; Compute the set of unvisited neighbors
              todo (set/difference (disj outs parent') done)
              ;; Choose a random unvisited neighbor to pass the token to
              next (or (rand-elem todo) parent')
              ;; Update the done set
              done' (if (= next :root) done (conj done next))]
          ;; Only pass the token if we're not the initiator
          (when (not= next :root)
            (send! s next [:token {:hops (inc hops)}]))
          ;; Stop if we sent to our parent
          (let [data' (assoc data :parent parent' :done done')]
            (if (= next parent') data' (recur data'))))))))

(defn tarry-1 [chans me ins outs {:keys [init?] :as data}]
  (let [s {:chans chans :me me :ins ins :outs outs
           :data (merge data {:done #{}})}
        s (if init?
            (let [next (rand-elem outs)]
              (send! s next [:token {:hops 1}])
              (update s :data merge {:parent :root :done #{next}}))
            s)]
    (tarry-loop s)))

(defn tarry [roles layout]
  (let [{:keys [in out] :as graph} (events/layout-graph layout)
        chans (atom {})
        ps (for [[role arg] (map vector roles (cons {:init? true} (repeat {})))]
             (future (tarry-1 chans role (in role) (out role) arg)))]
    (mapv deref (doall ps))))

(comment
  (tarry '[A B C D E] '[(C -- B -- A -- D -- E -- B) (A -- E)])
  )

;;; Fokkink (2013): Waves: Depth-first Search

(defn dfs-loop [{:keys [outs] :as s}]
  (loop [{:keys [parent done] :as data} (:data s)]
    (let [[msg src] (recv-any! s)]
      (match msg
        [:token {:hops hops}]
        (let [;; Set the parent if necessary
              parent' (or parent src)
              ;; Compute the set of unvisited neighbors
              todo (set/difference (disj outs parent') done)
              ;; Choose a random unvisited neighbor to pass the token to, but
              ;; prioritize the source if possible
              next (if (contains? todo src) src (or (rand-elem todo) parent'))
              ;; Update the done set
              done' (if (= next :root) done (conj done next))]
          ;; Only pass the token if we're not the initiator
          (when (not= next :root)
            (send! s next [:token {:hops (inc hops)}]))
          ;; Stop if we sent to our parent
          (let [data' (assoc data :parent parent' :done done')]
            (if (= next parent') data' (recur data'))))))))

(defn dfs-1 [chans me ins outs {:keys [init?] :as data}]
  (let [s {:chans chans :me me :ins ins :outs outs
           :data (merge data {:done #{}})}
        s (if init?
            (let [next (rand-elem outs)]
              (send! s next [:token {:hops 1}])
              (update s :data merge {:parent :root :done #{next}}))
            s)]
    (dfs-loop s)))

(defn dfs [roles layout]
  (let [{:keys [in out] :as graph} (events/layout-graph layout)
        chans (atom {})
        ps (for [[role arg] (map vector roles (cons {:init? true} (repeat {})))]
             (future (dfs-1 chans role (in role) (out role) arg)))]
    (mapv deref (doall ps))))

(comment
  (dfs '[A B C D E] '[(C -- B -- A -- D -- E -- B) (A -- E)])
  )

;;; Fokkink (2013): Waves: Echo

(defn echo-loop [{:keys [ins] :as s}]
  (loop [{:keys [parent todo] :as data} (:data s)]
    (let [[msg src] (recv-any! s)]
      (match msg
        [:token {:hops hops}]
        (let [;; Set the parent if necessary
              parent' (or parent src)
              ;; Update the todo set
              todo' (disj todo src)
              ;; Compute who to send to
              next (set/union
                    ;; Send to non-parent neighbors on the first receive
                    (when (not parent)
                      (disj ins src))
                    ;; Send to the parent once we have received all replies
                    (when (and (empty? todo') (not= parent :root))
                      #{parent'}))]
          (doseq [n next]
            (send! s n [:token {:hops (inc hops)}]))
          ;; Stop if we've received all replies
          (let [data' (assoc data :parent parent' :todo todo')]
            (if (empty? todo') data' (recur data'))))))))

(defn echo-1 [chans me ins outs {:keys [init?] :as data}]
  (let [s {:chans chans :me me :ins ins :outs outs
           :data (merge data {:todo ins})}
        s (if init?
            (do
              (send-all! s [:token {:hops 1}])
              (update s :data merge {:parent :root}))
            s)]
    (echo-loop s)))

(defn echo [roles layout]
  (let [{:keys [in out] :as graph} (events/layout-graph layout)
        chans (atom {})
        ps (for [[role arg] (map vector roles (cons {:init? true} (repeat {})))]
             (future (echo-1 chans role (in role) (out role) arg)))]
    (mapv deref (doall ps))))

(comment
  (echo '[A B C D E] '[(C -- B -- A -- D -- E -- B -- D) (A -- E)])
  )

;;; Fokkink (2013): Waves: Echo with Extinction

(defn echoex-reset [{:keys [itodo] :as data} & kvs]
  (apply assoc data :parent nil :todo itodo kvs))

(defn echoex-wave [{:keys [id wave parent todo exit] :as data}
                   {:keys [ins] :as s} src msg]
  (let [;; Set the parent if necessary
        parent' (or parent src)
        ;; Update the todo set
        todo' (disj todo src)]
    (if (and (not= msg [:exit]) (empty? todo') (= wave id))
      (do (send-all! s [:exit])
          [true (echoex-reset data :parent :root :exit true)])
      (let [next (set/union
                  ;; Send to non-parent neighbors on the first receive
                  (when (not parent)
                    (disj ins src))
                  ;; Send to the parent once we have received all replies
                  (when (and (empty? todo') (not= parent :root) (not= wave id))
                    #{parent'}))]
        (doseq [n next]
          (send! s n msg))
        [(not (and (empty? todo') exit))
         (assoc data :parent parent' :todo todo')]))))

(defn echoex-loop [s]
  (loop [{:keys [wave itodo exit] :as data} (:data s)]
    (let [[msg src] (recv-any! s)]
      (match msg
        [:exit]
        (let [[c d] (echoex-wave (if exit data (echoex-reset data :exit true)) s
                                 src msg)]
          (if c (recur d) d))

        [:token {:id id}]
        (cond
          (or exit (and wave (< id wave)))
          (recur data)

          (or (not wave) (> id wave))
          (let [[c d] (echoex-wave (echoex-reset data :wave id) s src msg)]
            (if c (recur d) d))

          :else
          (let [[c d] (echoex-wave data s src msg)]
            (if c (recur d) d)))))))

(defn echoex-1 [chans me ins outs {:keys [id] :as data}]
  (let [s {:chans chans :me me :ins ins :outs outs
           :data (merge data {:itodo ins :parent :root})}]
    (send-all! s [:token {:id id}])
    (echoex-loop s)))

(defn echoex [roles layout & args]
  (let [{:keys [in out] :as graph} (events/layout-graph layout)
        chans (atom {})
        ps (for [[role arg] (map vector roles args)]
             (future (echoex-1 chans role (in role) (out role) arg)))]
    (mapv deref (doall ps))))

(comment
  (echoex '[A B C D E] '[(C -- B -- A -- D -- E -- B -- D) (A -- E)]
          {:id 3} {:id 11} {:id 1} {:id 7} {:id 15})
  )
