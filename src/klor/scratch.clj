(ns klor.scratch)

(defmacro defchor [name roles params & body]
  nil)

(defn com [& args]
  nil)

(defmacro comlet [vars & body]
  nil)

(defmacro select [label & body]
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
                (format "Arriving on %s" date))))
          (select [Seller ok@Buyer]
            (format "Nevermind"))))))

  ;; Roles as communication functions (plus some role inference)
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (if (>= (:budget order)
            (Buyer (price-of (Seller (:title order)) catalogue)))
      (select [Seller Buyer/ok]
        (->> (:address order)
             Seller
             ship!
             Buyer
             (format "Arriving on %s")))
      (select [Seller Buyer/ko]
        (format "Nevermind"@Seller))))

  ;; Roles as "blocks"/"local contexts" (multitier-like)
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (Buyer
     (if (>= (:budget order)
             (Seller (price-of (Buyer (:title order)) catalogue)))
       (select ok
         (as-> (:address order) v
           (Seller (ship! v))
           (Buyer (format "Arriving on %s" v))))
       (select ko
         (Seller (format "Nevermind")))))))
