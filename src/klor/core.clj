(ns klor.core
  (:require [clojure.set :refer [union]]
            [metabox.core :refer [box]]
            [puget.printer :as puget]))

;;; Util

(defn role-qualified-symbol
  "Return a vector if SYM is a role-qualified symbol. Otherwise, return nil.

  SYM is role-qualified if its namespace is any of the symbols in the set ROLES.

  The returned vector is of the form `[ns name]`, where `ns` and `name` are
  unqualified symbols whose names are the namespace and the name of SYM,
  respectively."
  [roles sym]
  (and (symbol? sym)
       (let [ns (some-> sym namespace symbol)]
         (or (and (contains? roles ns) [ns (symbol (name sym))]) nil))))

(defn merge-meta [x & {:as m}]
  "Return X with metadata that's the result of merging M into X's existing
  metadata (keys in M take precedence)."
  (vary-meta x #(merge % m)))

(defn metaify
  "If X doesn't implement `clojure.lang.IObj`, return a `MetaBox` containing X so
  that metadata can be attached. Otherwise, return X.

  If M is given, merge it into the metadata of X with `merge-meta`."
  ([x]
   (if (instance? clojure.lang.IObj x) x (box x)))
  ([x & {:as m}]
   (merge-meta (metaify x) m)))

(defn unmetaify
  "If X is a `MetaBox`, return the value contained inside. Otherwise, return X."
  [x]
  (if (instance? metabox.MetaBox x) @x x))

;;; Role Expansion
;;;
;;; The role expansion process expands role-qualified symbols into explicit role
;;; forms, i.e. `Role/x` into `(Role x)`. The expansion can be performed in
;;; positions that are not evaluated, e.g. `let` binders.

(defn role-expand-form-dispatch [ctx form]
  (cond
    ;; Atom
    (not (seq? form)) :atom
    ;; Role form
    (contains? (:roles ctx) (first form)) :role
    ;; Other compound form
    :else (first form)))

(defmulti role-expand-form #'role-expand-form-dispatch)

(defmethod role-expand-form :default [ctx form]
  (apply list (map (partial role-expand-form ctx) form)))

(defmethod role-expand-form :atom [ctx form]
  (if-let [[ns name] (role-qualified-symbol (:roles ctx) form)]
    `(~ns ~name)
    form))

(defmethod role-expand-form :role [ctx [role & body]]
  `(~role ~@(map (partial role-expand-form ctx) body)))

(defmethod role-expand-form 'do [ctx [_ & body]]
  `(~'do ~@(map (partial role-expand-form ctx) body)))

(defn role-expand-form-let-binding [ctx [binder form :as binding]]
  (let [form (role-expand-form ctx form)]
    (if-let [[ns name] (role-qualified-symbol (:roles ctx) binder)]
      `[(~ns ~name) ~form]
      `[~binder ~form])))

(defmethod role-expand-form 'let [ctx [_ bindings & body]]
  `(~'let [~@(mapcat (partial role-expand-form-let-binding ctx)
                     (partition 2 bindings))]
    ~@(map (partial role-expand-form ctx) body)))

(defmethod role-expand-form 'if [ctx [_ cond then else]]
  `(~'if ~(role-expand-form ctx cond)
    ~(role-expand-form ctx then)
    ~(role-expand-form ctx else)))

(defmethod role-expand-form 'select [ctx [_ & body]]
  `(~'select ~@(map (partial role-expand-form ctx) body)))

(defn role-expand
  "Return the result of role expanding FORM in the context of ROLES."
  [roles form]
  (role-expand-form {:role nil :roles roles} form))

;;; Role Analysis
;;;
;;; Role analysis determines for each expression:
;;;
;;; (1) The "involved roles": the roles that are involved in the evaluation of
;;;     the expression.
;;;
;;; (2) The "result role": the role that holds the value that is the result of
;;;     the expression.
;;;
;;; If an expression has no involved roles and no result role, then it
;;; is "unlocated", which might be an error depending on the expression.
;;;
;;; (1) and (2) are stored as metadata attached to each expression, under the
;;; keys `:roles` (a set of unqualified symbols) and `:role` (an unqualified
;;; symbol).
;;;
;;; Furthermore, role analysis transforms role forms:
;;;
;;; - `(Role x)` is transformed into just `x`.
;;;
;;; - `(Role x ...)` is transformed into `(do x ...)`.

(defn role-analyze-form-dispatch [ctx form]
  (cond
    ;; Atom
    (not (seq? form)) :atom
    ;; Role form
    (contains? (:roles ctx) (first form)) :role
    ;; Other compound form
    :else (first form)))

(defmulti role-analyze-form #'role-analyze-form-dispatch)

(defn role-union [& forms]
  (apply union (map #(:roles (meta %)) forms)))

(defmethod role-analyze-form :default [ctx [op & args]]
  (let [op (role-analyze-form ctx op)
        args (apply list (map (partial role-analyze-form ctx) args))]
    (merge-meta `(~op ~@args)
                {:role (:role ctx) :roles (apply role-union args)})))

(defmethod role-analyze-form :atom [ctx form]
  (let [role (:role ctx)]
    (metaify form {:role role :roles (if role #{role} #{})})))

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

(defn role-analyze-form-let-binding [ctx binding]
  (mapv (partial role-analyze-form ctx) binding))

(defmethod role-analyze-form 'let [ctx [_ bindings & body]]
  (let [body (map (partial role-analyze-form ctx) body)
        bindings (mapcat (partial role-analyze-form-let-binding ctx)
                         (partition 2 bindings))]
    (merge-meta `(~'let [~@bindings] ~@body)
                {:role (:role ctx)
                 :roles (apply role-union (concat bindings body))})))

(defmethod role-analyze-form 'if [ctx [_ cond then else :as form]]
  (let [[cond then else] (map (partial role-analyze-form ctx) [cond then else])]
    (merge-meta `(~'if ~cond ~then ~else)
                {:role (:role ctx) :roles (role-union cond then else)})))

(defmethod role-analyze-form 'select [ctx [_ & body]]
  (let [body (map (partial role-analyze-form ctx) body)]
    (merge-meta `(~'select ~@body)
                {:role ctx :roles (apply role-union body)})))

(defn role-analyze
  "Return the result of role analyzing FORM in the context of ROLES."
  [roles form]
  (role-analyze-form {:role nil :roles roles} form))

;;; Printing

(defrecord Colored [color value])

(def fg-colors
  ;; Taken from `puget.color.ansi/sgr-code`.
  [:red :cyan :yellow :magenta :blue :green :white :black])

(def bg-colors
  ;; Taken from `puget.color.ansi/sgr-code`.
  [:bg-red :bg-cyan :bg-yellow :bg-magenta
   :bg-blue :bg-green :bg-white :bg-black])

(defn make-color-scheme [color]
  ;; Taken from `puget.printer/*options*`.
  {:delimiter [:bold color]
   :tag [:bold color]
   :nil [:bold color]
   :boolean [:bold color]
   :number [:bold color]
   :string [:bold color]
   :character [:bold color]
   :keyword [:bold color]
   :symbol [:bold color]
   :function-symbol [:bold color]
   :class-delimiter [:bold color]
   :class-name [:bold color]})

(defn colored-handler [opts colored]
  (puget/cprint-str
   (:value colored)
   (merge opts {:color-scheme (make-color-scheme (:color colored))})))

(defn color-chor-form [roles form]
  (let [{:keys [role]} (meta form)]
    (->Colored (or (roles role) :white)
               (cond
                 (vector? form) (mapv (partial color-chor-form roles) form)
                 (seq? form) (map (partial color-chor-form roles) form)
                 :else (unmetaify form)))))

(defn print-chor-form
  "Print a role-analyzed FORM with syntax coloring, using one color for per role.

  COLORS maps each role to a color. By default, colors are taken from
  `fg-colors`."
  ([form]
   (print-chor-form form (zipmap (:roles (meta form)) fg-colors)))
  ([form colors]
   (-> (color-chor-form colors form)
       (puget/cprint {:print-handlers {klor.core.Colored colored-handler}
                      :width 500}))))

(defn role-visualize [roles form & colors]
  (let [analyzed (role-analyze roles (role-expand roles form))]
    (puget/pprint form {:width 500})
    (print "  => ")
    (apply print-chor-form analyzed colors)))

(defn -main []
  (role-visualize '#{Ana Bob} '(let [Ana/x Bob/x] (if Ana/x Bob/x Bob/x))
                  {'Ana :yellow 'Bob :cyan})
  (role-visualize '#{Ana Bob Cal Dan}
                  '(let [(Bob x) (Ana x)]
                     (let [(Cal x) (Bob x)]
                       (let [(Dan x) (Cal x)]
                         (Dan x)))))
  (role-visualize '#{Ana Bob Cal Dan}
                  '(let [(Bob x) (Ana x)]
                     (let [(Cal x) (Bob x)]
                       (let [(Dan x) (Cal x)]
                         (Dan x))))
                  {'Ana :yellow 'Bob :magenta 'Cal :red 'Dan :cyan}))
