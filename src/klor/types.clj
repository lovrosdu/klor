(ns klor.types
  (:require
   [clojure.set :as set]
   [klor.util :refer [usym? error]]))

;;; Types
;;;
;;; Roles are represented as unqualified symbols. Types are represented as
;;; Clojure maps.
;;;
;;; The only map key common to all types is `:ctor`, which is a keyword
;;; describing the type constructor that determines the rest of the structure.
;;; The constructors are:
;;;
;;; - `:agree`
;;;
;;;   An agreement type. Its `:roles` key is a set of roles.
;;;
;;; - `:tuple`
;;;
;;;   A tuple type. Its `:elems` key is a vector of types of the elements.
;;;
;;; - `:chor`
;;;
;;;   A choreography type. Its `:params` key is a vector of types of the input
;;;   parameters. Its `:ret` key is the type of the return value. Its `:aux` key
;;;   is the (possibly empty) set of auxiliary roles, or `:none` if it has been
;;;   omitted.
;;;
;;; The representation, in EBNF (parentheses are used for grouping):
;;;
;;;   R ::= <unqualified-symbol>
;;;   T ::= {:ctor :agree :roles #{R*}}
;;;       | {:ctor :tuple :elems [T+]}
;;;       | {:ctor :chor :params [T*] :ret T :aux (:none | #{R*})}
;;;
;;; A type's surface syntax is called its "typespec", which is also given in
;;; terms of Clojure collections. In EBNF (parentheses are used for lists):
;;;
;;;   R ::= <unqualified-symbol>
;;;   T ::= R              ; shorthand for a singleton agreement type
;;;       | #{R+}          ; an agreement type
;;;       | [T+]           ; a tuple type
;;;       | (-> T* T)      ; a choreography type with omitted auxiliary roles
;;;       | (-> T* T | 0)  ; a choreography type with empty auxiliary roles
;;;       | (-> T* T | R+) ; a choreography type with explicit auxiliary roles

(defn parse-error [msg tspec]
  (error :klor msg :tspec tspec))

(declare parse-type*)

(defn parse-type-agreement [tspec]
  (when-not (set? tspec)
    (parse-error ["An agreement type must be a set: " tspec] tspec))
  (when (empty? tspec)
    (parse-error ["An agreement type cannot be empty: " tspec] tspec))
  (when-not (every? usym? tspec)
    (parse-error ["An agreement type can only contain roles: " tspec] tspec))
  {:ctor :agree :roles tspec})

(defn parse-type-tuple [tspec]
  (when-not (vector? tspec)
    (parse-error ["A tuple type must be a vector: " tspec] tspec))
  (when (empty? tspec)
    (parse-error ["A tuple type cannot be empty: " tspec] tspec))
  {:ctor :tuple :elems (mapv parse-type* tspec)})

(defn parse-type-chor [tspec]
  (when-not (seq? tspec)
    (parse-error ["A choreography type must be a seq: " tspec] tspec))
  (let [[op & tspecs] tspec]
    (when-not (= op '->)
      (parse-error ["A choreography type must start with `->`: " tspec] tspec))
    (let [pipe (and tspecs (.indexOf tspecs '|))
          pipe (and (not= pipe -1) pipe)
          pos (or pipe (inc (count tspecs)))
          main (take pos tspecs)
          aux (drop (inc pos) tspecs)
          params (butlast main)
          ret (last main)]
      (when (nil? ret)
        (parse-error ["A choreography type must have an output: " tspec] tspec))
      (when pipe
        (when (empty? aux)
          (parse-error ["A choreography type's auxiliary part cannot be "
                        "empty: " tspec]
                       tspec))
        (when-not (every? (some-fn usym? #{0}) aux)
          (parse-error ["A choreography type's auxiliary part can only contain "
                        "roles or 0: " tspec]
                       tspec))
        (when (and (some #{0} aux) (not (= (count aux) 1)))
          (parse-error ["A choreography type's auxiliary part cannot contain "
                        "roles when it contains 0: " tspec]
                       tspec))
        (when-not (apply distinct? aux)
          (parse-error ["A choreography type's auxiliary roles must be "
                        "distinct: " tspec] tspec)))
      (merge {:ctor   :chor
              :params (mapv parse-type* params)
              :ret    (parse-type* ret)
              :aux    (if pipe (disj (set aux) 0) :none)}))))

(defn parse-type [tspec]
  (cond
    (usym? tspec) (parse-type-agreement #{tspec})
    (set? tspec) (parse-type-agreement tspec)
    (vector? tspec) (parse-type-tuple tspec)
    (and (seq? tspec) (= (first tspec) '->)) (parse-type-chor tspec)
    :else nil))

(defn parse-type* [tspec]
  (or (parse-type tspec)
      (parse-error ["Unrecognized type: " tspec] tspec)))

(defn postwalk-type [f {:keys [ctor] :as type}]
  (case ctor
    :agree (f type)
    :tuple (f (update type :elems #(mapv (partial postwalk-type f) %)))
    :chor (-> (update type :params #(mapv (partial postwalk-type f) %))
              (update :ret (partial postwalk-type f))
              f)))

(defn type-roles [type]
  (-> (fn [{:keys [ctor] :as type}]
        (case ctor
          :agree (:roles type)
          :tuple (apply set/union (:elems type))
          :chor (let [{:keys [params ret aux]} type]
                  (apply set/union (if (= aux :none) #{} aux) ret params))))
      (postwalk-type type)))

(defn normalize-type [type]
  (-> (fn [{:keys [ctor] :as type}]
        (case ctor
          (:agree :tuple) type
          :chor (if (not= (:aux type) :none)
                  (let [main (type-roles (assoc type :aux #{}))]
                    (update type :aux #(set/difference % main)))
                  type)))
      (postwalk-type type)))

(defn render-type [type]
  (-> (fn [{:keys [ctor] :as type}]
        (case ctor
          :agree (let [{:keys [roles]} type]
                   (if (= (count roles) 1) (first roles) roles))
          :tuple (:elems type)
          :chor (let [{:keys [params ret aux]} type]
                  `(~'-> ~@params ~ret
                    ~@(cond (= aux :none) nil
                            (empty? aux) `(~'| 0)
                            :else `(~'| ~@aux))))))
      (postwalk-type type)))

(defn replace-roles [type subs]
  (let [sub #(get subs % %)]
    (-> (fn [{:keys [ctor] :as type}]
          (case ctor
            :agree (update type :roles #(set (map sub %)))
            :tuple type
            :chor (if (not= (:aux type) :none)
                    (update type :aux #(set (replace subs %)))
                    type)))
        (postwalk-type type))))
