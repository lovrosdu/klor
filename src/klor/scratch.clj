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
  (defchor buy-book [Buyer Seller] [order@Buyer catalogue@Seller]
    (comlet [title@Seller (:title order)]
      (comlet [price@Buyer (price-of title catalogue)]
        (if [Buyer (>= (:budget order) price)]
          (select [Buyer ok@Seller]
            (comlet [address@Seller (:address order)]
              (comlet [date@Buyer (ship! address catalogue)]
                (format "Arriving on %s" date))))
          (select [Buyer ok@Seller]
            (format "Nevermind"))))))

  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (if (>= (:budget order) (Buyer (price-of (Seller (:title order)) catalogue)))
      (select [Buyer Seller/ok]
        (->> (:address order)
             Seller
             ship!
             Buyer
             (format "Arriving on %s")))
      (select [Buyer Seller/ko]
        (format "Nevermind")))))
