(ns klor.roles
  (:require [clojure.set :refer [union]]
            [klor.util :refer [merge-meta metaify]]))

(defn role-qualified-symbol
  "Return a vector if SYM is a role-qualified symbol (or keyword). Otherwise,
  return nil.

  SYM is role-qualified if its namespace part matches any of the symbols in the
  set ROLES.

  The returned vector is of the form `[ns name]`, where `ns` is an unqualified
  symbol naming the namespace of SYM and `name` is an unqualified symbol (or
  keyword) naming SYM."
  [roles sym]
  (and (or (symbol? sym) (keyword? sym))
       (let [ns (some-> sym namespace symbol)]
         (or (and (contains? roles ns)
                  [ns ((if (symbol? sym) symbol keyword) (name sym))])
             nil))))

;;; Role Expansion
;;;
;;; The role expansion process expands role-qualified symbols into explicit role
;;; forms, i.e. `Role/x` into `(Role x)`. The expansion can be performed in
;;; positions that are not evaluated, e.g. `let` binders.

(defn form-dispatch [ctx form]
  (cond
    ;; Non-list compound form
    (vector? form) :vector
    (map? form) :map
    (set? form) :set
    ;; Role form
    (and (seq? form) (contains? (:roles ctx) (first form))) :role
    ;; Other list compound form
    (seq? form) (first form)
    ;; Atom
    :else :atom))

(defmulti role-expand-form #'form-dispatch)

(defmethod role-expand-form :default [ctx form]
  ;; Invoked for any non-special operator OP.
  (apply list (map (partial role-expand-form ctx) form)))

(defmethod role-expand-form :atom [ctx form]
  (if-let [[ns name] (role-qualified-symbol (:roles ctx) form)]
    `(~ns ~name)
    form))

(defmethod role-expand-form :vector [ctx form]
  (mapv (partial role-expand-form ctx) form))

(defmethod role-expand-form :map [ctx form]
  (into {} (map #(mapv (partial role-expand-form ctx) %) form)))

(defmethod role-expand-form :set [ctx form]
  (into #{} (map (partial role-expand-form ctx) form)))

(defmethod role-expand-form :role [ctx [role & body]]
  `(~role ~@(map (partial role-expand-form ctx) body)))

(defmethod role-expand-form 'do [ctx [_ & body]]
  `(~'do ~@(map (partial role-expand-form ctx) body)))

(defn role-expand-let-binding [ctx [binder form :as binding]]
  (let [form (role-expand-form ctx form)]
    (if-let [[ns name] (role-qualified-symbol (:roles ctx) binder)]
      `[(~ns ~name) ~form]
      `[~binder ~form])))

(defmethod role-expand-form 'let [ctx [_ bindings & body]]
  `(~'let [~@(mapcat (partial role-expand-let-binding ctx)
                     (partition 2 bindings))]
    ~@(map (partial role-expand-form ctx) body)))

(defmethod role-expand-form 'if [ctx [_ cond then else]]
  `(~'if ~(role-expand-form ctx cond)
    ~(role-expand-form ctx then)
    ~(role-expand-form ctx else)))

(defmethod role-expand-form 'select [ctx [_ [label & roles] & body]]
  `(~'select [~(role-expand-form ctx label) ~@roles]
    ~@(map (partial role-expand-form ctx) body)))

(defn role-expand
  "Expand all role-qualified symbols in FORM into their role form equivalents.

  ROLES is a set of unqualified symbols that name the recognized roles."
  [roles form]
  (role-expand-form {:role nil :roles (set roles)} form))

;;; Role Analysis
;;;
;;; Role analysis determines for each expression:
;;;
;;; (1) The "owning role": the role that holds the value that is the result of
;;;     the expression.
;;;
;;; (2) The "involved roles": the roles that are involved in the evaluation of
;;;     the expression.
;;;
;;; If an expression has no owning role and no involved roles, then it
;;; is "unlocated", which might be an error depending on the expression.
;;;
;;; (1) and (2) are stored as metadata attached to each expression, under the
;;; keys `:role` (an unqualified symbol) and `:roles` (a set of unqualified
;;; symbols).
;;;
;;; Furthermore, role analysis transforms role forms:
;;;
;;; - `(Role x)` is transformed into just `x`.
;;;
;;; - `(Role x ...)` is transformed into `(do x ...)`.

(defmulti role-analyze-form #'form-dispatch)

(defn role-union [& forms]
  (apply union (map #(:roles (meta %)) forms)))

(defmethod role-analyze-form :default [ctx [op & args]]
  ;; Invoked for any non-special operator OP.
  (let [op (role-analyze-form ctx op)
        args (apply list (map (partial role-analyze-form ctx) args))]
    (merge-meta `(~op ~@args)
                {:role (:role ctx) :roles (apply role-union args)})))

(defmethod role-analyze-form :atom [ctx form]
  (let [role (:role ctx)]
    (metaify form {:role role :roles (if role #{role} #{})})))

(defmethod role-analyze-form :vector [ctx form]
  (let [form (mapv (partial role-analyze-form ctx) form)]
    (merge-meta form {:role (:role ctx) :roles (apply role-union form)})))

(defmethod role-analyze-form :map [ctx form]
  (let [form (into {} (map #(mapv (partial role-analyze-form ctx) %) form))]
    (merge-meta form {:role (:role ctx)
                      :roles (apply role-union (mapcat identity form))})))

(defmethod role-analyze-form :set [ctx form]
  (let [form (into #{} (map (partial role-analyze-form ctx) form))]
    (merge-meta form {:role (:role ctx) :roles (apply role-union form)})))

(defmethod role-analyze-form :role [ctx [role & body]]
  (let [body (map (partial role-analyze-form (assoc ctx :role role)) body)]
    (merge-meta (if (and (= (count body) 1)
                         (= (:role (meta (first body))) role))
                  ;; Simplify the resulting expression if possible.
                  (first body)
                  `(do ~@body))
                {:role role :roles (conj (apply role-union body) role)})))

(defmethod role-analyze-form 'do [ctx [_ & body]]
  (let [body (map (partial role-analyze-form ctx) body)]
    (merge-meta `(~'do ~@body)
                {:role (:role ctx) :roles (apply role-union body)})))

(defn role-analyze-let-binding [ctx binding]
  (map (partial role-analyze-form ctx) binding))

(defmethod role-analyze-form 'let [ctx [_ bindings & body]]
  (let [body (map (partial role-analyze-form ctx) body)
        bindings (mapcat (partial role-analyze-let-binding ctx)
                         (partition 2 bindings))]
    (merge-meta `(~'let [~@bindings] ~@body)
                {:role (:role ctx)
                 :roles (apply role-union (concat bindings body))})))

(defmethod role-analyze-form 'if [ctx [_ cond then else :as form]]
  (let [[cond then else] (map (partial role-analyze-form ctx) [cond then else])]
    (merge-meta `(~'if ~cond ~then ~else)
                {:role (:role ctx) :roles (role-union cond then else)})))

(defmethod role-analyze-form 'select [ctx [_ [label & roles] & body]]
  (let [label (role-analyze-form ctx label)
        body (map (partial role-analyze-form ctx) body)]
    (merge-meta `(~'select [~label ~@roles] ~@body)
                {:role (:role ctx)
                 :roles (union (apply role-union label body) (set roles))})))

(defn role-analyze
  "Analyze and annotate FORM and its subforms with metadata describing its owning
  role and the involved roles.

  Role forms are removed and replaced with `do` forms if the role form has more
  than one subform or if it is a direct subform of another role form.

  ROLES is a set of unqualified symbols that name the recognized roles."
  [roles form]
  (role-analyze-form {:role nil :roles (set roles)} form))
