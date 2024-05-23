(ns klor.multi.examples
  (:require [clojure.string :as str]
            [klor.multi.core :refer :all]
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
