(ns klor.fokkink
  (:require
   [clojure.set :as set]
   [clojure.core.match :refer [match]]
   [klor.core :refer :all]
   [klor.events :refer
    [events with-events count-of out-of in-of data-of swap-data! enq!
     start! stop! with-reacts]]
   [klor.simulator :refer [simulate-chor]]
   [klor.util :refer [usym? error do1]]))

(def ^:dynamic *debug*
  false)

(defn debug [& args]
  (when *debug*
    (apply println args)))

;;; Util

(defn max* [& args]
  (apply max (remove nil? args)))

(defn rand-elem [coll]
  (rand-nth (seq coll)))

;;; Fokkink (2013): Election: Chang--Roberts (Non-faithful)

(defchor chang-roberts-hop [A B] (-> A A B B) [m da db]
  (B (let [m (A->B m)
           {:keys [id leader passive?]} db]
       (match m
         [:ignore]
         [m db]

         [:propose {:id id'}]
         (cond
           (or passive? (> id' id))
           (let [leader' (max* leader id')]
             [m (assoc db :passive? true :leader leader')])

           (< id' id)
           [[:ignore] db]

           (= id' id)
           [nil (assoc db :leader id)])))))

(defchor chang-roberts-round [A B C] (-> A B C [A B C]) [da db dc]
  (A (let [m (if (:passive? da) [:ignore] [:propose {:id (:id da)}])]
       (B (let [[m db] (chang-roberts-hop [A B] m da db)]
            (C (let [[m dc] (chang-roberts-hop [B C] m db dc)]
                 (A (let [[m da] (chang-roberts-hop [C A] m dc da)]
                      (pack da db dc))))))))))

(defchor chang-roberts [A B C] (-> A B C [A B C]) [da db dc]
  (unpack [[r1a r1b r1c] (chang-roberts-round [A B C] da db dc)
           [r2b r2c r2a] (chang-roberts-round [B C A] r1b r1c r1a)
           [r3c r3a r3b] (chang-roberts-round [C A B] r2c r2a r2b)]
    (pack r3a r3b r3c)))

(comment
  @(simulate-chor chang-roberts {:id 7} {:id 3} {:id 9})
  @(apply simulate-chor chang-roberts
          (for [id (take 3 (shuffle (range 10)))] {:id id}))
  )

;;; Fokkink (2013): Election: Chang--Roberts

(defn chang-roberts-enq! [s msg]
  (enq! s (first (out-of s)) msg))

(defn chang-roberts-swap [{:keys [id leader passive? leader?] :as data} s _ msg]
  (match msg
    [:propose {:id id'}]
    (cond
      ;; Purge the message if we have already become the leader previously.
      (or leader?)
      data

      ;; Update the last observed leader if we're passive or our ID is smaller
      ;; than the received one. Also pass on the message.
      (or passive? (> id' id))
      (let [leader' (max* leader id')]
        (debug "New leader:" leader')
        (chang-roberts-enq! s msg)
        (assoc data :passive? true :leader leader'))

      ;; Purge the message if our ID is higher than the received one.
      (< id' id)
      data

      ;; Become the leader if our ID matches the received one, as that means the
      ;; message came from us and made a full round trip. Also send an exit
      ;; message.
      :else
      (do (debug "I am the leader:" id)
          (chang-roberts-enq! s [:exit {:id id}])
          (assoc data :leader id :leader? true)))

    ;; Pass on the `:exit` message and then exit.
    [:exit {:id id'}]
    (do (debug "Exiting")
        (when (not= id id')
          (chang-roberts-enq! s msg))
        (stop! s)
        data)))

(defmacro chang-roberts [[head & _ :as roles] & args]
  (when-not (and (every? usym? roles) (apply distinct? roles))
    (error :klor ["Invalid `chang-roberts` roles: " roles]))
  (when-not (>= (count roles) 2)
    (error :klor ["`chang-roberts` needs at least 2 roles: " roles]))
  (let [ring (interpose '-> (concat roles [head]))
        s (gensym "s")]
    `(with-reacts [~s {:layout [(~@ring)]
                       :init ~(zipmap roles args)
                       :swap chang-roberts-swap}]
       (let [{id# :id passive?# :passive} (data-of ~s)]
         (when (not passive?#)
           (chang-roberts-enq! ~s [:propose {:id id#}])))
       (start! ~s)
       (pack ~@(for [r roles] `(~r (data-of ~s)))))))

(defchor chang-roberts-test [A B C] (-> A B C [A B C]) [da db dc]
  (chang-roberts [A B C] da db dc))

(comment
  @(simulate-chor chang-roberts-test {:id 7} {:id 3} {:id 9})
  @(apply simulate-chor chang-roberts-test
          (for [id (take 3 (shuffle (range 10)))] {:id id}))
  )

;;; Fokkink (2013): Waves: Itai--Rodeh

(defn itai-rodeh-enq! [s msg]
  (enq! s (first (out-of s)) msg))

(defn itai-rodeh-swap [{:keys [id round leader passive? leader?] :as data} s _ msg]
  (let [n (count-of s)]
    (match msg
      [:propose {:id id' :round round' :hops hops :dup? dup?}]
      (cond
        (or leader?)
        data

        (or passive? (> round' round) (and (= round' round) (> id' id)))
        (let [leader' (max* leader id')]
          (debug "New leader:" leader')
          (itai-rodeh-enq! s (update-in msg [1 :hops] inc))
          (assoc data :passive? true :leader leader'))

        (or (< round' round) (and (= round' round) (< id' id)))
        data

        (and (= round' round) (= id' id) (< hops n))
        (do (itai-rodeh-enq! s [:propose {:id id :round round
                                          :hops (inc hops) :dup? true}])
            data)

        (and (= round' round) (= id' id) (= hops n) dup?)
        (let [id'' (rand-int n)
              round' (inc round)]
          (debug "New ID:" id'')
          (itai-rodeh-enq! s [:propose {:id id'' :round round'
                                        :hops 1 :dup? false}])
          (assoc data :id id'' :round round'))

        (and (= round' round) (= id' id) (= hops n) (not dup?))
        (do (debug "I am the leader:" id)
            (itai-rodeh-enq! s [:exit {:hops 1}])
            (assoc data :leader id :leader? true)))

      ;; Pass on the `:exit` message and then exit.
      [:exit {:hops hops}]
      (do (debug "Exiting")
          (when (not= hops n)
            (itai-rodeh-enq! s (update-in msg [1 :hops] inc)))
          (stop! s)
          data))))

(defmacro itai-rodeh [[head & _ :as roles] & args]
  (when-not (and (every? usym? roles) (apply distinct? roles))
    (error :klor ["Invalid `itai-rodeh` roles: " roles]))
  (when-not (>= (count roles) 2)
    (error :klor ["`itai-rodeh` needs at least 2 roles: " roles]))
  (let [ring (interpose '-> (concat roles [head]))
        s (gensym "s")]
    `(with-reacts [~s {:layout [(~@ring)]
                       :init ~(zipmap roles args)
                       :swap itai-rodeh-swap}]
       (swap-data! ~s #(merge %2 %1) {:round 0 :id (rand-int (count-of ~s))})
       (let [{id# :id round# :round passive?# :passive} (data-of ~s)]
         (when (not passive?#)
           (itai-rodeh-enq! ~s [:propose {:id id# :round round#
                                          :hops 1 :dup? false}])))
       (start! ~s)
       (pack ~@(for [r roles] `(~r (data-of ~s)))))))

(defchor itai-rodeh-test [A B C] (-> A B C [A B C]) [da db dc]
  (itai-rodeh [A B C] da db dc))

(comment
  @(simulate-chor itai-rodeh-test {:id 1} {:id 0 :passive? true} {:id 1})
  @(simulate-chor itai-rodeh-test {} {} {})
  )

;;; Fokkink (2013): Waves: Tarry's Algorithm

(defn tarry-swap [{:keys [parent done] :as data} s src msg]
  (match msg
    [:token {:hops hops}]
    (let [;; Set the parent if necessary
          parent' (or parent src)
          ;; Compute the set of unvisited neighbors
          todo (set/difference (disj (out-of s) parent') done)
          ;; Choose a random unvisited neighbor to pass the token to
          next (or (rand-elem todo) parent')
          ;; Update the done set
          done' (if (= next :root) done (conj done next))]
      ;; Only pass the token if we're not the initiator
      (when (not= next :root)
        (enq! s next [:token {:hops (inc hops)}]))
      ;; Stop if we sent to our parent
      (when (= next parent')
        (stop! s))
      (assoc data :parent parent' :done done'))))

(defmacro tarry [[initiator & _ :as roles] layout & args]
  (let [s (gensym "s")]
    `(with-reacts [~s {:layout ~layout
                       :swap tarry-swap}]
       (swap-data! ~s (constantly {:done #{}}))
       (~initiator
        (let [next# (rand-elem (out-of ~s))]
          (swap-data! ~s (constantly {:parent :root :done #{next#}}))
          (enq! ~s next# [:token {:hops 1}])))
       (start! ~s)
       (pack ~@(for [r roles] `(~r (data-of ~s)))))))

(defchor tarry-test [A B C D E] (-> [A B C D E]) []
  (tarry [A B C D E] [(C -- B -- A -- D -- E -- B) (A -- E)]))

(comment
  @(simulate-chor tarry-test)
  )

;;; Fokkink (2013): Waves: Depth-first Search

(defn dfs-swap [{:keys [parent done] :as data} s src msg]
  (match msg
    [:token {:hops hops}]
    (let [;; Set the parent if necessary
          parent' (or parent src)
          ;; Compute the set of unvisited neighbors
          todo (set/difference (disj (out-of s) parent') done)
          ;; Choose a random unvisited neighbor to pass the token to, but
          ;; prioritize the source if possible
          next (if (contains? todo src) src (or (rand-elem todo) parent'))
          ;; Update the done set
          done' (if (= next :root) done (conj done next))]
      ;; Only pass the token if we're not the initiator
      (when (not= next :root)
        (enq! s next [:token {:hops (inc hops)}]))
      ;; Stop if we sent to our parent
      (when (= next parent')
        (debug "Stopping!")
        (stop! s))
      (assoc data :parent parent' :done done'))))

(defmacro dfs [[initiator & _ :as roles] layout]
  (let [s (gensym "s")]
    `(with-reacts [~s {:layout ~layout
                       :swap dfs-swap}]
       (swap-data! ~s (constantly {:done #{}}))
       (~initiator
        (let [next# (rand-elem (out-of ~s))]
          (swap-data! ~s (constantly {:parent :root :done #{next#}}))
          (enq! ~s next# [:token {:hops 1}])))
       (start! ~s)
       (pack ~@(for [r roles] `(~r (data-of ~s)))))))

(defchor dfs-test [A B C D E] (-> [A B C D E]) []
  (dfs [A B C D E] [(C -- B -- A -- D -- E -- B) (A -- E)]))

(comment
  @(simulate-chor dfs-test)
  )

;;; Fokkink (2013): Waves: Echo

(defn echo-swap [{:keys [parent todo] :as data} s src msg]
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
                  (disj (in-of s) src))
                ;; Send to the parent once we have received all replies
                (when (and (empty? todo') (not= parent :root))
                  #{parent'}))]
      (doseq [n next]
        (enq! s n [:token {:hops (inc hops)}]))
      ;; Stop if we've received all replies
      (when (empty? todo')
        (stop! s))
      (assoc data :parent parent' :todo todo'))))

(defn echo! [s msg]
  (doseq [dst (out-of s)]
    (enq! s dst msg)))

(defmacro echo [[initiator & _ :as roles] layout]
  (let [s (gensym "s")]
    `(with-reacts [~s {:layout ~layout
                       :swap echo-swap}]
       (swap-data! ~s (constantly {:todo (in-of ~s)}))
       (~initiator (swap-data! ~s merge {:parent :root})
        (echo! ~s [:token {:hops 1}]))
       (start! ~s)
       (pack ~@(for [r roles] `(~r (data-of ~s)))))))

(defchor echo-test [A B C D E] (-> [A B C D E]) []
  (echo [A B C D E] [(C -- B -- A -- D -- E -- B -- D) (A -- E)]))

(comment
  @(simulate-chor echo-test)
  )

;;; Fokkink (2013): Waves: Echo with Extinction

(defn echoex-reset [{:keys [itodo] :as data} & kvs]
  (apply assoc data :parent nil :todo itodo kvs))

(defn echoex-wave [{:keys [id wave parent todo exit] :as data} s src msg]
  (let [;; Set the parent if necessary
        parent' (or parent src)
        ;; Update the todo set
        todo' (disj todo src)]
    (do1 (if (and (not= msg [:exit]) (empty? todo') (= wave id))
           (do (echo! s [:exit])
               (echoex-reset data :parent :root :exit true))
           (let [next (set/union
                       ;; Send to non-parent neighbors on the first receive
                       (when (not parent)
                         (disj (in-of s) src))
                       ;; Send to the parent once we have received all replies
                       (when (and (empty? todo') (not= parent :root) (not= wave id))
                         #{parent'}))]
             (doseq [n next]
               (enq! s n msg))
             (assoc data :parent parent' :todo todo')))
      ;; Stop if we've received all `:exit` messages
      (when (and (empty? todo') exit)
        (stop! s)))))

(defn echoex-swap [{:keys [wave itodo exit] :as data} s src msg]
  (match msg
    [:exit]
    (echoex-wave (if exit data (echoex-reset data :exit true)) s src msg)

    [:token {:id id}]
    (cond
      (or exit (and wave (< id wave)))
      data

      (or (not wave) (> id wave))
      (echoex-wave (echoex-reset data :wave id) s src msg)

      :else
      (echoex-wave data s src msg))))

(defmacro echoex [roles layout & args]
  (let [s (gensym "s")]
    `(with-reacts [~s {:layout ~layout
                       :init ~(zipmap roles args)
                       :swap echoex-swap}]
       (swap-data! ~s merge {:itodo (in-of ~s) :parent :root})
       (echo! ~s [:token {:id (:id (data-of ~s))}])
       (start! ~s)
       (pack ~@(for [r roles] `(~r (data-of ~s)))))))

(defchor echoex-test [A B C D E] (-> A B C D E [A B C D E]) [da db dc dd de]
  (echoex [A B C D E] [(C -- B -- A -- D -- E -- B -- D) (A -- E)]
    da db dc dd de))

(comment
  @(simulate-chor echoex-test {:id 3} {:id 11} {:id 1} {:id 7} {:id 15})
  @(apply simulate-chor echoex-test
          (for [id (take 5 (shuffle (range 20)))] {:id id}))
  )
