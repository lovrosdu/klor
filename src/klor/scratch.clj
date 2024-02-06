(ns klor.scratch
  (:require [clojure.core.async :as a]
            [klor.macros :refer [defchor select]]
            [klor.roles :refer [role-expand role-analyze roles-of]]
            [klor.projection :refer [project]]
            [klor.util :refer [warn]]))

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

(doit '(Ana (+ (Bob 1) (Cal (- (Ana 5) (Bob 3))))))

;;; Do

(doit '(do (Ana (println "hello"))
           (Bob (println "world"))))

(doit '(Ana (do (Ana (println "hello"))
                (Bob (println "world")))))

(doit '(Ana (println "hello")
            (Bob (println "world"))))

(doit '(Ana (println "hello")
            (Bob (println "world"))
            nil))

;;; Let

(doit '(let [Ana/x Bob/x
             Cal/x (Bob (Ana 123))
             Eli/y (Eli 123)]
         (Ana 666)
         (Bob 123)
         (Cal z)
         (Dan 3)
         (Ana x)))

;;; Select

(doit '(Cal (select [Ana/ok Bob Cal] (Bob 123))))

;;; Conditionals

;;; Normal conditional at Ana

(doit '(Ana (if cond
              (select [ok Bob Cal] (do Bob/x nil))
              (select [ko Bob Cal] (do Cal/y nil)))))

;;; Conditional at Ana, with help from Bob

(doit '(Ana (if Bob/cond
              (select [ok Bob Cal] (do Bob/x nil))
              (select [ko Bob Cal] (do Cal/y nil)))))

;;; Conditional at Ana, but selections from Dan (unmergeable at Dan)

(doit '(Ana (if cond
              (select [Dan/ok Bob Cal] (do Bob/x nil))
              (select [Dan/ko Bob Cal] (do Cal/y nil)))))

;;; Nested conditionals at Ana

(doit '(Ana (if cond1
              (select [a Bob Cal] Bob/x)
              (if cond2
                (select [b Bob Cal] Cal/y)
                (select [c Bob Cal] Cal/z)))))

;;; Nested conditionals at Ana, with help from Bob (unmergeable at Bob)

(doit '(Ana (if Bob/cond1
              (select [a Bob Cal] Bob/x)
              (if Bob/cond2
                (select [b Bob Cal] Cal/y)
                (select [c Bob Cal] Cal/z)))))

;;; Nested conditionals at Ana via `cond`

(doit `(~'Ana ~(clojure.walk/macroexpand-all
                '(cond
                   cond1 (select [a Bob Cal] (Bob x))
                   cond2 (select [b Bob Cal] (Cal y))
                   :else (select [c Bob Cal] (Bob x) (Cal y))))))

;;; Optional else branch

(doit '(Ana (if cond 123)))

;;; Optional else branch, with Bob involved (unmergeable at Bob)

(doit '(Ana (if cond (select [ok Bob] (Bob 123)))))

;;; Buyer--Seller

(comment
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (let [Seller/title (Buyer (:title order))
          Buyer/price (Seller (get catalogue title :none))]
      (Buyer
       (if (and (int? price) (>= (:budget order) price))
         (select [Buyer/ok Seller]
           (let [Seller/address (Buyer (:address order))
                 Seller/date (Seller (ship! address))]
             (println "I'll get the book on" Seller/date)))
         (select [Buyer/ko Seller]
           (do (Seller (println "Buyer changed his mind"))
               nil))))))

  ;; XXX: Get rid of MetaBoxes when emitting.
  (defchor buy-book [Buyer Seller]
    [(Buyer {:keys [title budget address]}) Seller/catalogue]
    (Buyer
     (let [price (Seller (get catalogue title :none))]
       (if (and (int? price) (>= (:budget order) price))
         (select [Buyer/ok Seller]
           (println "I'll get the book on" (Seller (ship! Buyer/address))))
         (select [Buyer/ko Seller]
           (do (Seller (println "Buyer changed his mind"))
               nil))))))
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
