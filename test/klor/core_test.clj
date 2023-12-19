(ns klor.core-test
  (:require [clojure.set :refer [union]]
            [clojure.test :refer :all]
            [klor.core :refer :all]))

;;; Role Expansion

(defmacro role-expands
  {:style/indent 1}
  [roles & pairs]
  (let [sym (gensym "roles")]
    `(let [~sym (set ~roles)]
       (do ~@(for [[from to] pairs]
               `(is (= (role-expand ~sym ~from) ~to)))))))

(deftest role-expand-symbol
  (role-expands []
    ['x 'x]
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
    ['(select x y)         '(select x y)]
    ['(select Ana/x Ana/y) '(select Ana/x Ana/y)])

  (role-expands '[Ana]
    ['(select x y)         '(select x y)]
    ['(select Ana/x Ana/y) '(select (Ana x) (Ana y))]
    ['(select Bob/x Bob/y) '(select Bob/x Bob/y)]))

;;; Role Analysis

(defn role-tags
  "Return a tree equal in structure to X, except with all `:tag` metadata keys
  renamed to `:role`, and `:roles` set to `:any`. If `:tag` doesn't exist,
  `:role` is set to `:any`."
  [x]
  (let [{:keys [tag] :as m} (meta x)
        x (cond (vector? x) (mapv role-tags x)
                (seq? x) (apply list (map role-tags x))
                :else x)]
    (with-meta (metaify x)
      (-> (merge m {:role :any :roles :any} (and tag {:role tag}))
          (dissoc :tag)))))

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

(defmacro role-analyzes
  {:style/indent 1}
  [roles & pairs]
  (let [sym (gensym "roles")]
    `(let [~sym (set ~roles)]
       (do ~@(for [[from to] pairs]
               `(is (roled= (role-analyze ~sym ~from) (role-tags ~to))))))))

(deftest role-analyze-primitive
  (role-analyzes []
    ;; [123        :klor/unlocated-form]
    ['(Ana 123) '(Ana 123)])

  (role-analyzes '[Ana]
    ['(Ana 123) ^Ana (metaify 123)]
    ['(Bob 123) '(Bob 123)]))

(deftest role-analyze-symbol
  (role-analyzes []
    ;; ['x       :klor/unlocated-form]
    ['(Ana x) '(Ana x)])

  (role-analyzes '[Ana]
    ;; ['x :klor/unlocated-form]
    ['(Ana x) '^Ana x]
    ['(Bob x) '(Bob x)]))

(deftest role-analyze-do
  (role-analyzes []
    ['(do (Ana x)) '(do (Ana x))])

  (role-analyzes '[Ana]
    ['(do (Ana x)) '(do ^Ana x)]
    ['(do (Ana x)) '^Ana (do x)]
    ['(Ana (do x)) '(do ^Ana x)]
    ['(Ana (do x)) '^Ana (do x)]
    ['(do (Bob x)) '(do (Bob x))])

  (role-analyzes '[Ana Bob]
    ['(do (Ana x) (Bob y))       '(do ^Ana x ^Bob y)]
    ['(do (Ana x) (Bob y))       '^Bob (do x y)]
    ['(Ana (do (Ana x) (Bob y))) '^Bob (do x y)]))

(deftest role-analyze-let
  (role-analyzes []
    ['(let [(Ana x) (Ana y)] (Ana z)) '(let [(Ana x) (Ana y)] (Ana z))])

  (role-analyzes '[Ana]
    ['(let [(Ana x) (Ana y)])         '(let [^Ana x ^Ana y])]
    ['(let [(Ana [x [y z]]) (Bob w)]) '(let [^Ana [x [y z] ^Bob w]])]
    ['(let [] (Ana x))                '(let [] ^Ana x)]
    ['(let [] (Ana x))                '^Ana (let [] x)]
    ['(Ana (let [x y] z))             '(let [^Ana x ^Ana y] ^Ana z)]
    ['(let [(Bob x) (Bob y)] (Bob z)) '(let [(Bob x) (Bob y)] (Bob z))])

  (role-analyzes '[Ana Bob]
    ['(let [(Ana x) (Bob y)])  '(let [^Ana x ^Bob y])]
    ['(let [] (Ana x) (Bob y)) '^Bob (let [] x y)]
    ['(Ana (let [] (Bob x)))   '^Bob (let [] x)]))

(deftest role-analyze-if
  (role-analyzes []
    ['(if (Ana x) (Ana z) (Ana y)) '(if (Ana x) (Ana z) (Ana y))])

  (role-analyzes '[Ana]
    ['(if (Ana x) (Ana z) (Ana y)) '(if ^Ana x ^Ana z ^Ana y)]
    ['(if (Ana x) (Ana z) (Ana y)) '^Ana (if x z y)]
    ['(Ana (if x z y))             '(if ^Ana x ^Ana z ^Ana y)]
    ['(if (Bob x) (Bob z) (Bob y)) '(if (Bob x) (Bob z) (Bob y))])

  (role-analyzes '[Ana Bob]
    ['(if (Ana x) (Bob z) (Bob y)) '(if ^Ana x ^Bob z ^Bob y)]
    ['(if (Ana x) (Bob z) (Bob y)) '^Bob (if x z y)]
    ['(Ana (if x (Bob z) (Bob y))) '^Bob (if x z y)]
    ;; ['(if (Ana x) (Bob z) (Cal y)) :klor/differing-result-roles]
    ))

(deftest role-analyze-select
  (role-analyzes []
    ['(select (Ana x)) '(select (Ana x))])

  (role-analyzes '[Ana]
    ['(select (Ana x)) '(select ^Ana x)]
    ['(select (Ana x)) '^Ana (select x)]
    ['(Ana (select x)) '(select ^Ana x)]
    ['(select (Bob x)) '(select (Bob x))])

  (role-analyzes '[Ana Bob]
    ['(select (Ana x) (Bob y)) '(select ^Ana x ^Bob y)]
    ['(select (Ana x) (Bob y)) '^Bob (select x y)]
    ['(Ana (select (Bob x)))   '^Bob (select x)]))

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
      [sample '^Dan (let [x y]
                      ^Dan (let [y b]
                             ^Dan (let [z c]
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
      [sample '^Ana (let [x y]
                      ^Ana (let [y b]
                             ^Ana (let [z c]
                                    (do a b w))))])))
