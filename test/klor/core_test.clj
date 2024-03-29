(ns klor.core-test
  (:require [clojure.pprint :as pp]
            [clojure.set :refer [union]]
            [clojure.test :refer [deftest is] :as t]
            [klor.util :refer [metaify unmetaify] :as u]
            [klor.roles :refer [role-expand role-analyze]]))

;;; Printing
;;;
;;; We use a custom pprint dispatch function during testing in order to hide
;;; printing of any `MetaBox`es in test reports as it just adds noise. By
;;; default, pprint prints `MetaBox` as any other `IDeref`: `#<...>`.

(defn make-pprint-metabox-dispatch []
  (let [prev pp/*print-pprint-dispatch*]
    (fn rec [x]
      (if (instance? metabox.MetaBox x)
        (do (#'pp/pprint-meta x)
            (rec (unmetaify x)))
        (prev x)))))

(t/use-fixtures :once #(pp/with-pprint-dispatch (make-pprint-metabox-dispatch)
                         (binding [*print-meta* true]
                           (%))))

;;; Role Expansion

(defmacro role-expands
  {:style/indent 1}
  [roles & pairs]
  `(do ~@(for [[from to] pairs]
           `(is (= (role-expand ~roles ~from) ~to)))))

(deftest role-expand-symbol
  (role-expands []
    ['x     'x]
    ['Ana/x 'Ana/x])

  (role-expands '[Ana]
    ['x     'x]
    ['Ana/x '(Ana x)]
    ['Bob/x 'Bob/x]))

(deftest role-expand-do
  (role-expands []
    ['(do x y)         '(do x y)]
    ['(do Ana/x Ana/y) '(do Ana/x Ana/y)])

  (role-expands '[Ana]
    ['(do x y)         '(do x y)]
    ['(do Ana/x Ana/y) '(do (Ana x) (Ana y))]
    ['(do Bob/x Bob/y) '(do Bob/x Bob/y)]))

(deftest role-expand-let
  (role-expands []
    ['(let [x y] z)             '(let [x y] z)]
    ['(let [Ana/x Ana/y] Ana/z) '(let [Ana/x Ana/y] Ana/z)])

  (role-expands '[Ana]
    ['(let [x y] z)             '(let [x y] z)]
    ['(let [Ana/x Ana/y] Ana/z) '(let [(Ana x) (Ana y)] (Ana z))]
    ['(let [Bob/x Bob/y] Bob/z) '(let [Bob/x Bob/y] Bob/z)]))

(deftest role-expand-if
  (role-expands []
    ['(if x y z)             '(if x y z)]
    ['(if Ana/x Ana/y Ana/z) '(if Ana/x Ana/y Ana/z)])

  (role-expands '[Ana]
    ['(if x y z)             '(if x y z)]
    ['(if Ana/x Ana/y Ana/z) '(if (Ana x) (Ana y) (Ana z))]
    ['(if Bob/x Bob/y Bob/z) '(if Bob/x Bob/y Bob/z)]))

(deftest role-expand-select
  (role-expands []
    ['(select [x] y)         '(select [x] y)]
    ['(select [Ana/x] Ana/y) '(select [Ana/x] Ana/y)])

  (role-expands '[Ana]
    ['(select [x] y)         '(select [x] y)]
    ['(select [Ana/x] Ana/y) '(select [(Ana x)] (Ana y))]
    ['(select [Bob/x] Bob/y) '(select [Bob/x] Bob/y)]))

;;; Role Analysis

(defn role-from-tag
  "Return a tree equal in structure to X, except with the metadata of its nodes
  and leaves altered in the following way:

  - If `:role` or `:roles` exist, don't adjust the respective key.
  - Otherwise, set `:role` and `:roles` to `:any`.
  - Additionally, if `:tag` is present, set `:role` to its value and remove
  `:tag`.

  Primitive values that cannot hold metadata are boxed into a `MetaBox` using
  `metaify`."
  [x]
  (let [{:keys [tag] :as m} (meta x)
        x (cond (vector? x) (mapv role-from-tag x)
                (seq? x) (apply list (map role-from-tag x))
                :else x)]
    (as-> m m
      (merge {:role :any :roles :any} (and tag {:role tag}) m)
      (dissoc m :tag)
      ;; NOTE: Coerce an empty map returned by `dissoc` to nil. Unlike nil,
      ;; empty maps are printed when printing metadata.
      (if (seq m) m nil)
      (metaify x m))))

(defn role-meta=
  "Compare the `:role` and `:roles` metadata of X and Y for equality,
  respectively. If a key has the special value `:any`, it is treated as equal to
  any other value (including the absence of the key altogether)."
  [x y]
  (let [{role-x :role roles-x :roles} (meta x)
        {role-y :role roles-y :roles} (meta y)]
    (and (or (= role-x :any) (= role-y :any) (= role-x role-y))
         (or (= roles-x :any) (= roles-y :any) (= roles-x roles-y)))))

(defn listlike?
  "Return whether X is a seq but not a vector."
  [x]
  (and (not (vector? x)) (seq? x)))

(defn coll=
  "Return whether all values in ARGS are collections of the same kind, i.e.
  whether are all vectors, maps, sets or listlike."
  [& args]
  (or (some #(every? % args) [vector? map? set? listlike?]) false))

(defn roled=
  "Return whether X and Y are equal as if by `=`, with three differences:

  - X and Y must have equal role metadata, compared using `role-meta=`.
  - X and Y must be collections of the same kind, compared using `coll=`.
  - If X and Y are both a `MetaBox`, compare their respective values."
  [x y]
  (and (role-meta= x y)
       (if (coll= x y)
         (every? identity (map roled= x y))
         (= (unmetaify x) (unmetaify y)))))

(defn dissoc-entries [m & entries]
  (let [entries (set entries)]
    (into {} (remove #(contains? entries %) m))))

(defn role-meta-only [x & entries]
  "Return a tree equal in structure to X, except with the metadata of its nodes
  and leaves altered so that only the `:role` and `:roles` keys are present, if
  any.

  For further filtering, ENTRIES is a vector of pairs [K V] and specifies
  particular key-value combinations that should be removed
  completely (naturally, K should be one of `:role` or `:roles`)."
  (as-> (meta x) m
    (select-keys m [:role :roles])
    (apply dissoc-entries m entries)
    ;; NOTE: Coerce an empty map returned by `select-keys` to nil. Unlike nil,
    ;; empty maps are printed when printing metadata.
    (if (seq m) m nil)
    ;; NOTE: Override `x`'s metadata completely.
    (with-meta (metaify x) m)))

(defn role-meta-expected [x]
  (u/postwalk #(role-meta-only % [:role :any] [:roles :any]) x))

(defn role-meta-actual [x]
  (u/postwalk #(role-meta-only % [:role nil] [:roles #{}]) x))

;;; We use `assert-expr` to register a custom `is` predicate called
;;; `role-analyze=` for nicer test reports.

(defmethod t/assert-expr 'role-analyze= [msg [_ expected actual]]
  `(let [expected# (role-from-tag ~expected)
         actual# ~actual
         result# (roled= expected# actual#)]
     (t/do-report {:type (if result# :pass :fail)
                   :message ~msg
                   :expected (role-meta-expected expected#)
                   :actual (role-meta-actual actual#)})
     result#))

(defmacro role-analyzes
  {:style/indent 1}
  [roles & pairs]
  `(do ~@(for [[from to] pairs]
           `(is (~'role-analyze= ~to (role-analyze ~roles ~from))))))

(deftest role-analyze-primitive
  (role-analyzes []
    ['(Ana 123) '(Ana 123)])

  (role-analyzes '[Ana]
    ['(Ana 123) (metaify 123 {:role 'Ana :roles :any})]
    ['(Bob 123) '(Bob 123)]))

(deftest role-analyze-symbol
  (role-analyzes []
    ['(Ana x) '(Ana x)])

  (role-analyzes '[Ana]
    ['(Ana x) '^Ana x]
    ['(Bob x) '(Bob x)]))

(deftest role-analyze-do
  (role-analyzes []
    ['(do (Ana x)) '(do (Ana x))])

  (role-analyzes '[Ana]
    ['(do (Ana x)) '(do ^Ana x)]
    ['(do (Ana x)) '^{:role nil} (do x)]
    ['(Ana (do x)) '(do ^Ana x)]
    ['(Ana (do x)) '^Ana (do x)]
    ['(do (Bob x)) '(do (Bob x))])

  (role-analyzes '[Ana Bob]
    ['(do (Ana x) (Bob y))       '(do ^Ana x ^Bob y)]
    ['(Ana (do x (Bob y)))       '(do ^Ana x ^Bob y)]
    ['(do (Ana x) (Bob y))       '^{:role nil} (do x y)]
    ['(Ana (do (Ana x) (Bob y))) '^Ana (do x y)]))

(deftest role-analyze-let
  (role-analyzes []
    ['(let [(Ana x) (Ana y)] (Ana z)) '(let [(Ana x) (Ana y)] (Ana z))])

  (role-analyzes '[Ana]
    ['(let [(Ana x) (Ana y)])         '(let [^Ana x ^Ana y])]
    ['(let [(Ana [x [y z]]) (Bob w)]) '(let [^Ana [x [y z]] (Bob w)])]
    ['(let [] (Ana x))                '(let [] ^Ana x)]
    ['(let [] (Ana x))                '^{:role nil} (let [] x)]
    ['(Ana (let [x y] z))             '(let [^Ana x ^Ana y] ^Ana z)]
    ['(let [(Bob x) (Bob y)] (Bob z)) '(let [(Bob x) (Bob y)] (Bob z))])

  (role-analyzes '[Ana Bob]
    ['(let [(Ana x) (Bob y)])  '(let [^Ana x ^Bob y])]
    ['(Ana (let [x (Bob y)]))  '(let [^Ana x ^Bob y])]
    ['(let [] (Ana x) (Bob y)) '^{:role nil} (let [] x y)]
    ['(Ana (let [] (Bob x)))   '^Ana (let [] x)]))

(deftest role-analyze-if
  (role-analyzes []
    ['(if (Ana x) (Ana z) (Ana y)) '(if (Ana x) (Ana z) (Ana y))])

  (role-analyzes '[Ana]
    ['(if (Ana x) (Ana z) (Ana y)) '(if ^Ana x ^Ana z ^Ana y)]
    ['(if (Ana x) (Ana z) (Ana y)) '^{:role nil} (if x z y)]
    ['(Ana (if x z y))             '(if ^Ana x ^Ana z ^Ana y)]
    ['(if (Bob x) (Bob z) (Bob y)) '(if (Bob x) (Bob z) (Bob y))])

  (role-analyzes '[Ana Bob]
    ['(if (Ana x) (Bob z) (Bob y)) '(if ^Ana x ^Bob z ^Bob y)]
    ['(Ana (if x (Bob z) (Bob y))) '(if ^Ana x ^Bob z ^Bob y)]
    ['(if (Ana x) (Bob z) (Bob y)) '^{:role nil} (if x z y)]
    ['(Ana (if x (Bob z) (Bob y))) '^Ana (if x z y)]))

(deftest role-analyze-select
  (role-analyzes []
    ['(select (Ana x)) '(select (Ana x))])

  (role-analyzes '[Ana]
    ['(select [(Ana x)]) '(select [^Ana x])]
    ['(select [(Ana x)]) '^{:role nil} (select [x])]
    ['(Ana (select [x])) '(select [^Ana x])]
    ['(select [(Bob x)]) '(select [(Bob x)])])

  (role-analyzes '[Ana Bob]
    ['(select [(Ana x)] (Bob y)) '(select [^Ana x] ^Bob y)]
    ['(Ana (select [x] (Bob y))) '(select [^Ana x] ^Bob y)]
    ['(select [(Ana x)] (Bob y)) '^{:role nil} (select [x] y)]
    ['(Ana (select [(Bob x)]))   '^Ana (select [x])]))

(deftest role-analyze-sample-1
  (let [sample '(let [(Ana x) (Ana y)]
                  (let [(Bob y) (Cal b)]
                    (let [(Cal z) (Dan c)]
                      (Dan w))))]
    (role-analyzes '[Ana Bob Cal Dan]
      [sample '(let [^Ana x ^Ana y]
                 (let [^Bob y ^Cal b]
                   (let [^Cal z ^Dan c]
                     ^Dan w)))]
      [sample '^{:role nil} (let [x y]
                              ^{:role nil} (let [y b]
                                             ^{:role nil} (let [z c]
                                                            w)))])))

(deftest role-analyze-sample-2
  (let [sample '(let [(Ana x) (Ana y)]
                  (let [(Bob y) (Cal b)]
                    (let [(Cal z) (Dan c)]
                      (Dan a b (Ana w)))))]
    (role-analyzes '[Ana Bob Cal Dan]
      [sample '(let [^Ana x ^Ana y]
                 (let [^Bob y ^Cal b]
                   (let [^Cal z ^Dan c]
                     (do ^Dan a ^Dan b ^Ana w))))]
      [sample '^{:role nil} (let [x y]
                              ^{:role nil} (let [y b]
                                             ^{:role nil} (let [z c]
                                                            (do a b w))))])))
