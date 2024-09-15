(ns klor.multi.examples
  (:require
   [clojure.core.match :refer [match]]
   [clojure.string :as str]
   [klor.multi.core :refer :all]
   [klor.multi.runtime :refer [play-role]]
   [klor.multi.simulator :refer [simulate-chor]])
  (:import java.time.LocalDate))

;;; Simple

(defchor simple-1 [A B] (-> A #{A B}) [x]
  (A=>B x))

(defchor simple-2 [A B] (-> A B) [x]
  (A->B x))

(comment
  @(simulate-chor simple-1 123)
  @(simulate-chor simple-2 123)
  )

;;; Share

(defchor share [A B] (-> A B) [x]
  (if (A=>B (A (even? x)))
    (B (println "It's even!"))
    (B (println "It's odd!"))))

;;; Remote

(defchor remote-invoke [A B] (-> B A A) [f x]
  (B->A (f (A->B x))))

(defchor remote-apply [A B] (-> B A A) [f xs]
  (B->A (B (apply f (A->B xs)))))

(defchor remote-map [A B] (-> B A A) [f xs]
  (if (A=>B (A (empty? xs)))
    (A nil)
    (A (cons (remote-invoke [A B] f (first xs))
             (remote-map [A B] f (next xs))))))

;;; Ping-Pong

(defchor ping-pong-1 [A B] (-> A [A B]) [n]
  (if (A=>B (A (<= n 0)))
    ;; NOTE: Some inference could be useful here!
    (pack (A :done) (B nil))
    (unpack [[x y] (ping-pong-1 [B A] (B (dec (A->B n))))]
      (pack y x))))

(defchor ping-pong-2 [A B] (-> A [A B]) [n]
  (let [n (A=>B n)]
    (if (<= n 0)
      (pack (A :done) (B nil))
      (unpack [[x y] (ping-pong-2 [B A] (B (dec n)))]
        (pack y x)))))

(comment
  @(simulate-chor ping-pong-1 5)
  @(simulate-chor ping-pong-2 5)
  )

;;; Mutual Recursion

(defchor mutrec-2 [A B] (-> A [A B]))

(defchor mutrec-1 [A B] (-> A [A B]) [n]
  (A (println 'mutrec-1 n))
  (if (A=>B (A (<= n 0)))
    (pack (A :done) (B nil))
    (mutrec-2 [A B] (A (dec n)))))

(defchor mutrec-2 [A B] (-> A [A B]) [n]
  (A (println 'mutrec-2 n))
  (if (A=>B (A (<= n 0)))
    (pack (A :done) (B nil))
    (mutrec-1 [A B] (A (dec n)))))

(comment
  @(simulate-chor mutrec-1 5)
  )

;;; Higher-order

(defchor chain [A B C] (-> (-> B C) (-> A B) A C) [g f x]
  (g (f x)))

(defchor chain-test-1 [A B C] (-> C) []
  (chain [A B C]
         (chor (-> B C) [x] (B->C (B (+ x 10))))
         (chor (-> A B) [x] (A->B (A (* x 10))))
         (A 41)))

(defchor mul [A B] (-> A B) [x]
  (A->B (A (* x 10))))

(defchor add [A B] (-> A B) [x]
  (A->B (A (+ x 10))))

(defchor chain-test-2 [A B C] (-> C) []
  (chain [A B C] (inst add [B C]) (inst mul [A B]) (A 41)))

(defchor compose [A B C] (-> (-> B C) (-> A B) (-> A C | B)) [g f]
  (chor (-> A C | B) [x] (g (f x))))

(defchor compose-test [A B C] (-> C) []
  (let [h (compose [A B C] (inst add [B C]) (inst mul [A B]))]
    (C (+ (h (A 40)) (h (A 0))))))

;;; Buyer--Seller

(defn ship! [address]
  (println "Shipping to" address)
  (str (java.time.LocalDate/now)))

(defchor buy-book-1 [B S] (-> B S B) [order catalog]
  (let [price (S->B (S (get catalog (B->S (B (:title order))) :none)))]
    (if (B=>S (B (when (int? price) (>= (:budget order) price))))
      (let [date (S->B (S (ship! (B->S (B (:address order))))))]
        (B (println "I'll get the book on" date))
        date)
      (do (S (println "Buyer changed his mind"))
          (B nil)))))

(comment
  @(simulate-chor buy-book-1
                  {:title "To Mock A Mockingbird"
                   :budget 50
                   :address "Some Address 123"}
                  {"To Mock A Mockingbird" 50})
  )

;;; Two-Buyer

(defchor buy-book-2 [B1 B2 S] (-> B1 S (-> B1 B1 | B2) B1) [order catalog decide]
  (let [price (S->B1 (S (get catalog (B1->S (B1 (:title order))) :none)))]
    (if (B1=>S (B1 (when (B1=>B2 (int? price)) (decide price))))
      (let [date (S->B1 (S (ship! (B1->S (B1 (:address order))))))]
        (B1 (println "I'll get the book on" date))
        date)
      (do (S (println "Buyer changed his mind"))
          (B1 nil)))))

(defchor buy-book-2-main [B1 B2 S] (-> B1 S B1) [order catalog]
  (buy-book-2 [B1 B2 S] order catalog
              (chor (-> B1 B1) [price]
                (let [contrib (B2 (if (rand-nth [true false])
                                    (do (println "I guess I can help") 42)
                                    (do (println "Sorry, I'm broke") 0)))]
                  (B1 (>= (:budget order) (- price (B2->B1 contrib))))))))

(comment
  @(simulate-chor buy-book-2-main
                  {:title "To Mock A Mockingbird"
                   :budget 8
                   :address "Some Address 123"}
                  {"To Mock A Mockingbird" 50})
  )

;;; Auth

(defn read-creds [prompt]
  (print prompt)
  {:password (str/trim (read-line))})

(defchor auth [C A] (-> C #{C A}) [get-creds]
  (or (A=>C (A (= (:password (C->A (get-creds))) "secret")))
      (and (C=>A (C (rand-nth [true false])))
           (auth [C A] get-creds))))

(defchor get-token [C S A] (-> C C) [get-creds]
  (if (A=>S (auth [C A] get-creds))
    (S->C (S (random-uuid)))
    (C :error)))

(comment
  @(simulate-chor get-token (constantly {:password "secret"}))
  @(simulate-chor get-token (constantly {:password "wrong"}))
  @(simulate-chor get-token #(hash-map :password (rand-nth ["wrong" "secret"])))
  @(simulate-chor get-token #(read-creds "Password: "))
  )

;;; Diffie--Hellman

(defn modpow [base exp mod]
  (.modPow (biginteger base) (biginteger exp) (biginteger mod)))

(defchor exchange-key-1 [A B] (-> #{A B} #{A B} A B [A B]) [g p sa sb]
  (pack (A (modpow (B->A (B (modpow g sb p))) sa p))
        (B (modpow (A->B (A (modpow g sa p))) sb p))))

(defchor exchange-key-2 [A B] (-> #{A B} #{A B} A B #{A B}) [g p sa sb]
  (agree! (A (modpow (B->A (B (modpow g sb p))) sa p))
          (B (modpow (A->B (A (modpow g sa p))) sb p))))

(comment
  ;; Example from <https://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange#Cryptographic_explanation>.
  @(simulate-chor exchange-key-1 5 23 4 3)
  @(simulate-chor exchange-key-2 5 23 4 3)
  )

(defchor secure-1 [A B] (-> A B) [x]
  (unpack [[k1 k2] (exchange-key-1 [A B] 5 23 (A 4) (B 3))]
    (B (.xor k2 (A->B (A (.xor k1 (biginteger x))))))))

(defchor secure-2 [A B] (-> A B) [x]
  (let [k (exchange-key-2 [A B] 5 23 (A 4) (B 3))]
    (B (.xor k (A->B (A (.xor k (biginteger x))))))))

(comment
  @(simulate-chor secure-1 42)
  @(simulate-chor secure-2 42)
  )

;;; Game

(defn make-game []
  {:moves 0 :black 0 :white 0})

(defn make-move [player]
  {player (inc (rand-int 10))})

(defn apply-move [game move]
  (merge-with + game move {:moves 1}))

(defn final? [{:keys [moves black white] :as game}]
  (and (>= moves 5) (not= black white)))

(defn winner [game]
  (key (apply max-key val game)))

(defchor play-game [A B] (-> #{A B} A B #{A B}) [game p1 p2]
  (let [game (apply-move game (A=>B (A (make-move p1))))]
    (if (final? game)
      (winner game)
      (play-game [B A] game p2 p1))))

(comment
  @(simulate-chor play-game (make-game) :black :white)
  )

;;; Key-value Store

(defn handle-req! [req store]
  (match req
    [:put k v] (do (swap! store assoc k v) v)
    [:get k] (get @store k nil)))

(defchor kvs [C S] (-> C S #{C S}) [req store]
  (let [r (S=>C (S (handle-req! (C->S req) store)))]
    (agree! (narrow [C] r) (narrow [S] r))))

(comment
  (let [store (atom {})]
    @(simulate-chor kvs [:put :secret 42] store)
    @(simulate-chor kvs [:get :secret] store))
  )

;;; Replicated Key-value Store

(defchor kvs-replicated [C S B] (-> C S B #{C S}) [req primary backup]
  (let [req (S=>B (C->S req))]
    (B (handle-req! req backup))
    (B->S (B :ack))
    (S=>C (S (handle-req! req primary)))))

(comment
  (let [primary (atom {})
        backup (atom {})]
    @(simulate-chor kvs-replicated [:put :secret 42] primary backup)
    @(simulate-chor kvs-replicated [:get :secret] primary backup))
  )

;;; Higher-Order Key-value Store

(defchor kvs-custom [C S B1 B2] (-> C S B1 B2 (-> S B1 B2 S) #{C S})
  [req primary backup1 backup2 backup-chor]
  (let [req (C->S req)]
    (backup-chor req backup1 backup2)
    (S=>C (S (handle-req! req primary)))))

(defchor kvs-custom-null [C S B1 B2] (-> C S #{C S} | B1 B2) [req primary]
  (kvs-custom [C S B1 B2] req primary (B1 nil) (B2 nil)
              (chor (-> S B1 B2 S) [_ _ _] (S nil))))

(comment
  (let [primary (atom {})]
    @(simulate-chor kvs-custom-null [:put :secret 42] primary)
    @(simulate-chor kvs-custom-null [:get :secret] primary))
  )

(defchor kvs-custom-single [C S B1 B2] (-> C S B1 #{C S}) [req primary backup1]
  (kvs-custom [C S B1 B2] req primary backup1 (B2 nil)
              (chor (-> S B1 B2 S) [req backup1 _]
                (let [req (S->B1 req)]
                  (B1 (handle-req! req backup1))
                  (B1->S (B1 :ack))))))

(comment
  (let [primary (atom {})
        backup (atom {})]
    @(simulate-chor kvs-custom-single [:put :secret 42] primary backup)
    @(simulate-chor kvs-custom-single [:get :secret] primary backup))
  )

(defchor kvs-custom-double [C S B1 B2] (-> C S B1 B2 #{C S})
  [req primary backup1 backup2]
  (kvs-custom [C S B1 B2] req primary backup1 backup2
              (chor (-> S B1 B2 S) [req backup1 backup2]
                (let [req (S=>B2 (S=>B1 req))]
                  (B1 (handle-req! req backup1))
                  (B2 (handle-req! req backup2))
                  (B1->S (B1 :ack))
                  (B2->S (B2 :ack))))))

(comment
  (let [primary (atom {})
        backup1 (atom {})
        backup2 (atom {})]
    @(simulate-chor kvs-custom-double [:put :secret 42] primary backup1 backup2)
    @(simulate-chor kvs-custom-double [:get :secret] primary backup1 backup2))
  )

;;; Mergesort

(defchor ms-merge [A B C] (-> B C A) [l r]
  (if (B=>C (B=>A (B (not-empty l))))
    (if (C=>B (C=>A (C (not-empty r))))
      (B (let [[head & tail] l]
           (if (B=>C (B=>A (B (< head (C->B (C (first r)))))))
             (A (cons (B->A head) (ms-merge [A B C] tail r)))
             (A (cons (C->A (C (first r))) (ms-merge [A B C] l (C (next r))))))))
      (B->A l))
    (C->A r)))

(defchor mergesort [A B C] (-> A A) [seq]
  (A (if (A=>C (A=>B (= (count seq) 1)))
       seq
       (let [[l r] (split-at (quot (count seq) 2) seq)]
         (ms-merge [A B C]
                   (mergesort [B C A] (A->B l))
                   (mergesort [C A B] (A->C r)))))))

(comment
  @(simulate-chor mergesort [7 3 4 5 1 0 9 8 6 2])
  )
