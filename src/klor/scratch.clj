(ns klor.scratch)

(defmacro defchor [name roles params & body]
  nil)

(defn com [& args]
  nil)

(defmacro comlet
  {:style/indent 1}
  [vars & body]
  nil)

(defmacro select
  {:style/indent 1}
  [label & body]
  nil)

(comment
  ;; `comlet` for communication (plus some role inference)
  (defchor buy-book [Buyer Seller] [order@Buyer catalogue@Seller]
    (comlet [title@Seller (:title order)]
      (comlet [price@Buyer (price-of title catalogue)]
        (if [Buyer (>= (:budget order) price)]
          (select [Seller ok@Buyer]
            (comlet [address@Seller (:address order)]
              (comlet [date@Buyer (ship! address catalogue)]
                (println "I'll get the book on" date))))
          (select [Seller ok@Buyer]
            (println "Buyer changed his mind"))))))

  ;; Roles as communication functions (plus some role inference)
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (if (>= (:budget order)
            (Buyer (price-of (Seller (:title order)) catalogue)))
      (select [Seller Buyer/ok]
        (->> (:address order)
             Seller
             ship!
             Buyer
             (println "I'll get the book on %s")))
      (select [Seller Buyer/ko]
        (println "Buyer changed his mind"@Seller))))

  ;; Roles as "blocks"/"local contexts" (multitier-like)
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (Buyer
     (if (>= (:budget order)
             (Seller (price-of (Buyer (:title order)) catalogue)))
       (select ok
         (as-> (Buyer (:address order)) v
           (Seller (ship! v))
           (println "I'll get the book on" v)))
       (select ko
         (Seller (println "Buyer changed his mind")))))))
