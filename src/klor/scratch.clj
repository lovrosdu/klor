(ns klor.scratch
  (:require [klor.macros :refer [defchor select]]
            [klor.roles :refer [role-expand role-analyze roles-of]]
            [klor.projection :refer [project]]
            [klor.runtime :refer [play-role]]
            [klor.simulator :refer [simulate-chor]]
            [klor.sockets :refer [with-server with-accept with-client
                                  wrap-sockets]]
            [klor.util :refer [warn virtual-thread]]))

;;; Debug

(def ^:dynamic *dbg* [])

(defn dbg [x]
  (alter-var-root #'*dbg* conj x)
  x)

;;; Projection

(def ^:dynamic *roles*
  '[Ana Bob Cal Dan Eli])

(defn doit1 [form role]
  (try
    (project role form)
    (catch Exception e
      (warn ["Unmergeable at " role])
      ['unmergable (ex-message e)])))

(defn doit [form & roles]
  (let [roles (if (seq roles) roles *roles*)
        form (role-analyze roles (role-expand roles form))]
    (into {} (map #(vector % (doit1 form %)) (roles-of form)))))

;;; Functions

(doit '(Ana (println (Bob (inc (Ana x))))))

(doit '(Cal (+ (Ana x) (Bob y))))

(doit '(Ana (+ (Bob 1) (Cal (- (Ana 5) (Bob 3))))))

;;; Do

(do
  (doit '(do (Ana (println "hello"))
             (Bob (println "world"))))

  (doit '(Ana (do (Ana (println "hello"))
                  (Bob (println "world")))))

  (doit '(Ana (println "hello")
              (Bob (println "world"))))

  (doit '(Ana (println "hello")
              (Bob (println "world"))
              nil)))

;;; Let

(doit '(let [Ana/x (Bob 'foo)
             Ana/x (Ana (inc x))
             Cal/x (Bob (Ana 123))
             Eli/y (Eli 456)]
         (Bob 789)
         (Cal x)
         (Dan 'bar)
         (Ana x)))

;;; Select

(doit '(Cal (select [Ana/ok Bob Cal] (Bob 123))))

;;; Conditionals

(do
  ;; Normal conditional at Ana
  (doit '(Ana (if cond
                (select [ok Bob Cal] (do Bob/x nil))
                (select [ko Bob Cal] (do Cal/y nil)))))

  ;; Conditional at Ana, with help from Bob
  (doit '(Ana (if Bob/cond
                (select [ok Bob Cal] (do Bob/x nil))
                (select [ko Bob Cal] (do Cal/y nil)))))

  ;; Conditional at Ana, but selections from Dan (unmergeable at Dan)
  (doit '(Ana (if cond
                (select [Dan/ok Bob Cal] (do Bob/x nil))
                (select [Dan/ko Bob Cal] (do Cal/y nil)))))

  ;; Nested conditionals at Ana
  (doit '(Ana (if cond1
                (select [a Bob Cal] Bob/x)
                (if cond2
                  (select [b Bob Cal] Cal/y)
                  (select [c Bob Cal] Cal/z)))))

  ;; Nested conditionals at Ana, with help from Bob (unmergeable at Bob)
  (doit '(Ana (if Bob/cond1
                (select [a Bob Cal] Bob/x)
                (if Bob/cond2
                  (select [b Bob Cal] Cal/y)
                  (select [c Bob Cal] Cal/z)))))

  ;; Nested conditionals at Ana via `cond`
  (doit `(~'Ana ~(clojure.walk/macroexpand-all
                  '(cond
                     cond1 (select [a Bob Cal] (Bob x))
                     cond2 (select [b Bob Cal] (Cal y))
                     :else (select [c Bob Cal] (Bob x) (Cal y))))))

  ;; Optional else branch
  (doit '(Ana (if cond 123)))

  ;; Optional else branch, with Bob involved (unmergeable at Bob)
  (doit '(Ana (if cond (select [ok Bob] (Bob 123))))))

;;; Increment

(defchor increment-chor [Ana Bob] [Ana/x]
  (Ana (Bob (inc Ana/x))))

@(simulate-chor increment-chor 5)

;;; Buyer--Seller

(defn ship! [address]
  (str (java.time.LocalDate/now)))

(defchor buy-book [Buyer Seller] [Buyer/order Seller/catalog]
  (let [Seller/title (Buyer (:title order))
        Buyer/price (Seller (get catalog title :none))]
    (Buyer
     (if (and (int? price) (>= (:budget order) price))
       (select [Buyer/ok Seller]
         (let [Seller/address (Buyer (:address order))
               Buyer/date (Seller (ship! address))]
           (println "I'll get the book on" date)
           date))
       (select [Buyer/ko Seller]
         (Seller (println "Buyer changed his mind"))
         nil)))))

(let [order {:title "To Mock A Mockingbird"
             :budget 50
             :address "Some Address 123"}
      catalog {"To Mock A Mockingbird" 50}]
  @(simulate-chor buy-book order catalog))

;; TODO: Get rid of MetaBoxes when emitting.
(comment
  (defchor buy-book [Buyer Seller]
    [(Buyer {:keys [title budget address]}) Seller/catalog]
    (Buyer
     (let [price (Seller (get catalog title :none))]
       (if (and (int? price) (>= (:budget order) price))
         (select [Buyer/ok Seller]
           (println "I'll get the book on" (Seller (ship! Buyer/address))))
         (select [Buyer/ko Seller]
           (Seller (println "Buyer changed his mind"))
           nil))))))

;;; Dance

(defchor f1 [X Y] [X/x Y/y]
  (X (println x))
  (Y (println y))
  (X 123))

(defchor g1 [A B] [A/a B/b]
  (dance f1 [B A] A/a B/b))

@(simulate-chor g1 123 456)

(defchor f2 [X Y] [X/x]
  X/x)

(defchor g2 [A B] []
  (dance f2 [B A] (A 123)))

@(simulate-chor g2)

;;; Ping-Pong

(defchor ping-pong-1 [A B] [A/n]
  (A (if (<= n 0)
       (select [ok B] :done)
       (select [ko B] (A (dance ping-pong-1 [B A] (dec n)))))))

@(simulate-chor ping-pong-1 5)

(defchor ping-pong-2 [A B] [A/n]
  (A (if (<= n 0)
       (select [ok B] :done)
       (select [ko B] (B (dance ping-pong-2 [B A] (A (dec n))))))))

@(simulate-chor ping-pong-2 5)

;;; Mutual Recursion

(declare ^{:klor/chor {:params [(with-meta 'n {:role 'A})]
                       :roles '[A B]}}
         mutrec-2)

(defchor mutrec-1 [A B] [A/n]
  (A (println 'mutrec-1 n)
     (if (<= n 0)
       (select [ok B] :done)
       (select [ko B] (dance mutrec-2 [A B] (dec n))))))

(defchor mutrec-2 [A B] [A/n]
  (A (println 'mutrec-2 n)
     (if (<= n 0)
       (select [ok B] :done)
       (select [ko B] (dance mutrec-1 [A B] (dec n))))))

@(simulate-chor mutrec-1 5)

;;; Sockets

(def port 1337)

(defn run-seller [catalog & {:keys [host port forever log] :or
                               {host "0.0.0.0" port port
                                forever false log :dynamic}}]
  (let [catalog (or catalog {"To Mock A Mockingbird" 50})]
    (with-server [ssc :host host :port port]
      (loop []
        (println "Listening on" (str (. ssc (getLocalAddress))))
        (with-accept [ssc sc]
          (println "Got client" (str (. sc (getRemoteAddress))))
          (play-role (wrap-sockets {:role 'Seller} {'Buyer sc} :log log)
                     buy-book catalog))
        (when forever (recur))))))

(defn run-buyer [order & {:keys [host port log]
                          :or {host "127.0.0.1" port port log :dynamic}}]
  (let [order (merge {:title "To Mock A Mockingbird"
                      :budget 50
                      :address "Some Address 123"}
                     order)]
    (with-client [sc :host host :port port]
      (println "Connected to" (str (. sc (getRemoteAddress))))
      (play-role (wrap-sockets {:role 'Buyer} {'Seller sc} :log log)
                 buy-book order))))

(comment
  (virtual-thread (run-seller nil :port port :forever true :log true))
  (virtual-thread (run-buyer nil :port port))
  (virtual-thread (run-buyer {:budget 49} :port port))
  (virtual-thread (run-buyer {:title "Weird"} :port port))
  )

;;; Auth

(defn get-creds []
  {:password (if (rand-nth [true false]) "secret" "wrong")})

(defchor auth-1 [C S A] [A/n C/creds]
  (A (println "Attempt" n))
  (let [A/creds C/creds
        C/out (C (volatile! nil))]
    (A (if (= (:password creds) "secret")
         (select [token C S]
           (C (vreset! out (S (random-uuid))))
           nil)
         (select [invalid C]
           (C (if (rand-nth [true false])
                (select [retry A]
                  (select [A/retry S]
                    (vreset! out (dance auth-1 [C S A]
                                        (A (inc n)) (get-creds)))))
                (select [error A]
                  (select [A/error S]
                    (vreset! out :error)))))
           ;; TODO: The `nil` causes merging to fail at S. Without the nil
           ;; however, we're forced to communicate to A.
           #_nil)))
    (C @out)))

@(simulate-chor auth-1 1 (get-creds))

(comment
  ;; XXX: An attempt to extract the communication between C and A won't work
  ;; since the agreement between them established in `auth-2-helper` is not
  ;; visible to `auth-2`.
  (defchor auth-2-helper [C A] [C/creds]
    (let [A/creds C/creds]
      (A (if (= (:password creds) "secret")
           (select [token C] true)
           (select [invalid C]
             (C (if (rand-nth [true false])
                  (select [retry A] (dance auth-2-helper [C A] creds))
                  (select [error A] false))))))))

  (defchor auth-2 [C S A] [C/creds]
    ;; XXX: This breaks here because it's not visible to the compiler that C and
    ;; A actually agree on the result of `auth-2-helper`. A way to fix it would
    ;; be to also include C in the selection, but that would be redundant.
    (A (if (dance auth-2-helper [C A] C/creds)
         (select [A/token S] (C (vreset! out (S (random-uuid)))))
         (select [A/error S] (C (vreset! out :error))))))

  ;; XXX; If we had agreement types, we could extract the communication between
  ;; C and A and preserve the agreement across choreographies.
  (defchor auth-3-helper [C A] [creds C]
    (or (A=>C (= (:password (C->A creds)) "secret"))
        (and (C=>A (rand-nth [true false]))
             (dance auth-3-helper [C A] creds))))

  (defchor auth-3 [C S A] [creds C]
    (if (A=>S (dance auth-3-helper [C A] creds))
      (S->C (random-uuid))
      (C :error)))
  )

;;; Laziness

(comment
  ;; XXX: This should fail to compile. Under the hood `lazy-seq` uses an `fn`
  ;; that should be interpreted as a choreography (a multi-role value) because
  ;; it contains both roles. This choreography cannot be used with local
  ;; functions or variables but only within "choreographic contexts".
  ;;
  ;; There is most likely no way to write a choreographic `lazy-seq` that would
  ;; return a lazy sequence at the receiver but turn into a loop at the sender.
  ;; This is because the receive would have to be within the thunk of the lazy
  ;; sequence, while the send would have to be "outside", within the sender's
  ;; loop. There's no way to achieve this when the position of the two cannot be
  ;; controlled independently (since it's projected from a single com).
  ;;
  ;; XXX: Additionally, even if `lazy-seq` worked here, it would end up
  ;; communicating the lazy sequence to Ana with the way this choreography is
  ;; written.
  (defchor lazy-chor [Ana Bob] [Ana/n]
    (Ana (if (zero? n)
           (select [done Bob]
             (Bob nil))
           (select [more Bob]
             (Bob (lazy-seq (cons Ana/n (lazy-chor (dec n)))))))))

  ;; XXX: Disregarding for a moment that `lazy-seq` cannot be used like this in
  ;; a choreographic context, this is the sort of hack we have to use when we
  ;; want to return a result at Bob, but are stuck within a context at Ana (due
  ;; to the conditional in this case).
  (defchor lazy-chor [Ana Bob] [Ana/n]
    (let [Bob/hack (Bob (volatile! nil))]
      (Ana (if (zero? n)
             (select [done Bob]
               (Bob (vreset! hack nil))
               nil)
             (select [more Bob]
               (Bob (vreset! hack (lazy-seq (cons Ana/n (lazy-chor (dec n))))))
               nil)))
      (Bob @hack)))

  ;; XXX: An alterantive to the previous implementation with volatile is to
  ;; locate the conditional at Bob, but this then requires performing a
  ;; redundant selection for Ana.
  (defchor lazy-chor [Ana Bob] [Ana/n]
    (Bob (if (Ana (zero? n))
           (select [Ana done]
             nil)
           (select [Ana more]
             (lazy-seq (cons Ana/n (lazy-chor (dec n))))))))

  ;; A way to achieve "laziness" is through channels.
  (defchor lazy-chor [Ana Bob] [Ana/s Bob/c]
    (Ana (if (seq s)
           (select [more Bob]
             (Bob (a/>!! c (Ana (first s))))
             ;; NOTE: This invocation has to be in a non-com position.
             (lazy-chor (rest s) c)
             nil)
           (select [done Bob]
             (Bob (a/close! c))
             nil))))
  )
