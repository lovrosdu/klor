(ns klor.multi.types
  (:require [clojure.set :as set]
            [clojure.walk :refer [postwalk]]
            [klor.multi.util :refer [usym?]]
            [klor.util :refer [error]]))

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
;;;   is the set of auxiliary roles.
;;;
;;; The representation, in EBNF:
;;;
;;;   R ::= <unqualified-symbol>
;;;   T ::= {:ctor :agree :roles #{R*}}
;;;       | {:ctor :tuple :elems [T+]}
;;;       | {:ctor :chor :params [T*] :ret T :aux #{R*}}
;;;
;;; A type's surface syntax is called its "typespec", which is also given in
;;; terms of Clojure collections. In EBNF:
;;;
;;;   R ::= <unqualified-symbol>
;;;   T ::= R              ; shorthand for a singleton agreement type
;;;       | #{R+}          ; an agreement type
;;;       | [T+]           ; a tuple type
;;;       | (-> T* T)      ; a choreography type
;;;       | (-> T* T | R*) ; a choreography type with auxiliary roles

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
      (when (and pipe (empty? aux))
        (parse-error ["A choreography type's auxiliary part cannot be "
                      "empty: " tspec]
                     tspec))
      (when-not (every? usym? aux)
        (parse-error ["A choreography type's auxiliary part can only contain "
                      "roles: " tspec]
                     tspec))
      (when-not (or (empty? aux) (apply distinct? aux))
        (parse-error ["A choreography type's auxiliary roles must be "
                      "distinct: " tspec] tspec))
      {:ctor   :chor
       :params (mapv parse-type* params)
       :ret    (parse-type* ret)
       :aux    (set aux)})))

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

(defn type-roles [{:keys [ctor roles elems ret params aux] :as type}]
  (case ctor
    :agree roles
    :tuple (apply set/union (map type-roles elems))
    :chor (apply set/union aux (map type-roles (cons ret params)))))

(defn normalize-type [{:keys [ctor] :as type}]
  (case ctor
    :agree type
    :tuple (update type :elems #(mapv normalize-type %))
    :chor (let [type' (update type :params #(mapv normalize-type %))
                type'' (update type' :ret normalize-type)
                primary (type-roles (assoc type'' :aux nil))]
            (update type'' :aux #(set/difference % primary)))))

(defn render-type [{:keys [ctor roles elems ret params aux] :as type}]
  (case ctor
    :agree (if (= (count roles) 1) (first roles) roles)
    :tuple (mapv render-type elems)
    :chor `(~'-> ~@(map render-type params) ~(render-type ret)
            ~@(and (not-empty aux) `(~'| ~@aux)))))

(defn substitute-roles [type subs]
  (postwalk #(if (usym? %) (get subs % %) %) type))
