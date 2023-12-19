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
  ;; `comlet` for communication (plus some role inference). We abuse the parser
  ;; and vectors for role suffixes, because [x@y] is parsed as [x @y], but x@y
  ;; is a syntax error when outside of a vector.
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

  ;; `com` as a communication function.
  (defchor buy-book [Buyer Seller] [order@Buyer catalogue@Seller]
    (if (>= (:budget order)
            (com Buyer (price-of (com Seller (:title order)) catalogue)))
      (select [Seller Buyer/ok]
        (->> (:address order)
             (com Seller)
             ship!
             (com Buyer)
             (println "I'll get the book on %s")))
      (select [Seller ko@Buyer]
        (println "Buyer changed his mind"@Seller))))

  ;; Roles as communication functions (plus some role inference). Namespaces as
  ;; role prefixes.
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

  ;; Roles as "blocks"/"local contexts" (multitier-like, but expression- instead
  ;; of statement-oriented). Selections are automatically sent to the union of
  ;; the roles involved in the branches.
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (Buyer
     (if (>= (:budget order)
             (Seller (price-of (Buyer (:title order)) catalogue)))
       (select ok
         (as-> (Buyer (:address order)) v
           (Seller (ship! v))
           (println "I'll get the book on" v)))
       (select ko
         (Seller (println "Buyer changed his mind"))))))

  ;; Single-branch `if` and operators like `when`, etc. should automatically add
  ;; a selection in the else branch. It should use some default label, or maybe
  ;; even a freshly-generated one.
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (Buyer
     (when (>= (:budget order)
               (Seller (price-of (Buyer (:title order)) catalogue)))
       (select ok
         (as-> (Buyer (:address order)) v
           (Seller (ship! v))
           (println "I'll get the book on" v))))))

  ;; A `let`'s bindings, initialization forms and body are by default at the
  ;; currently active context.
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (Seller
     (let [title (Buyer (:title order))]
       (Buyer
        (let [price (Seller (price-of title))]
          (when (>= (:budget order) price)
            (select ok
              (Seller
               (let [address (Buyer (:address order))]
                 (as-> (Buyer (:address order)) v
                   (Seller (ship! v))
                   (println "I'll get the book on" v)))))))))))

  ;; A multi-role/choreographic `let` can make use of role-prefixed variables as
  ;; syntax sugar for the explicit context switches.
  ;;
  ;; If there's no active context, then (1) non-role-prefixed variables probably
  ;; shouldn't be allowed, and (2) contexts must be opened explicitly at each of
  ;; the 3 mentioned points.
  ;;
  ;; All variables are context-specific, i.e. `(Seller x)` and `(Buyer x)` are
  ;; expressions referring to 2 different `x`s. Implicit variable capture is
  ;; therefore not allowed and context transitions are directly visible.
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (let [Seller/title (Buyer (:title order))
          Buyer/price (Seller (price-of title))]
      (Buyer
       (when (>= (:budget order) price)
         (select ok
           (let [Seller/address (:address order)
                 Seller/date (Seller (ship! address))]
             (println "I'll get the book on" (Seller date))))))))

  ;; More generally, an expression such as `r/x` could just be a syntactical
  ;; shorthand for `(r x)`.
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (let [(Seller title) (Buyer (:title order))
          (Buyer price) (Seller (price-of title))]
      (Buyer
       (when (>= (:budget order) price)
         (select ok
           (let [Seller/address (:address order)
                 Seller/date (Seller (ship! address))]
             (println "I'll get the book on" Seller/date)))))))

  ;; `let` should be able to destructure its arguments as usual.
  (defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
    (let [(Buyer {:keys [title budget address]}) Buyer/order
          Buyer/price (Seller (price-of Buyer/title))]
      (Buyer
       (when (>= budget price)
         (select ok
           (let [Seller/address address
                 Seller/date (Seller (ship! address))]
             (println "I'll get the book on" Seller/date))))))))
