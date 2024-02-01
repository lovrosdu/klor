(ns klor.scratch
  (:require [klor.macros :refer [defchor select]]
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

(comment
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (let [Seller/title (Buyer (:title order))
          Buyer/price (Seller (price-of title catalogue))]
      (Buyer
       (if (>= (:budget order) price)
         (select [Buyer/ok Seller]
           (let [Seller/address (Buyer (:address order))
                 Seller/date (Seller (ship! address))]
             (println "I'll get the book on" Seller/date)))
         (select [Buyer/ko Seller]
           (do (Seller (println "Buyer changed his mind"))
               nil))))))

  (defchor buy-book [Buyer Seller]
    [(Buyer {:keys [title budget address]}) Seller/catalogue]
    (Buyer
     ;; NOTE: This is why it's important for the condition to be multi-role!
     (if (>= (:budget order) (Seller (price-of Buyer/title catalogue)))
       (select [Buyer/ok Seller]
         (println "I'll get the book on" (Seller (ship! Buyer/address))))
       (select [Buyer/ko Seller]
         (do (Seller (println "Buyer changed his mind"))
             nil)))))
  )
