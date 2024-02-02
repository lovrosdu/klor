(ns klor.projection
  (:require [clojure.set :as set]
            [klor.roles :refer [role-expand role-analyze role-of roles-of]]
            [klor.util :refer [unmetaify form-dispatch error warn]]))

;;; Reporting

(defn projection-error [message & {:as options}]
  (error :klor/projection-error message options))

(defn check-located
  ([form message]
   (check-located form nil message))
  ([form role message]
   (let [actual (role-of form)]
     (if (and actual (or (not role) (= role actual)))
       actual
       (projection-error message :form form)))))

(defn local? [form]
  (= (count (roles-of form)) 1))

(defn check-local [form message]
  (when-not (local? form)
    (projection-error message :form form)))

(defn warn-local [form message]
  (when-not (local? form)
    (warn message)))

;;; Projection (EndPoint Projection, EPP)
;;;
;;; The projection of a Klor form F at a role R is a Clojure form that contains
;;; only those actions in F relevant to R.
;;;
;;; If F's value is used by the enclosing then we call F a "com" (or in "com
;;; position") with respect to the enclosing form, because its value might have
;;; to be communicated depending on whether the roles of the two agree or not.
;;;
;;; If F contains no actions relevant to R, projection produces a special
;;; no-op ("noop") value. However, if F's value is used by the projection of the
;;; enclosing form, the noop is by default emitted as nil. This is controlled by
;;; `*emit-noop-as-nil*` (true by default) which when set to false will emit
;;; `:klor/noop` instead of nil, useful for debugging purposes to differentiate
;;; between user-written and projection-produced nils.
;;;
;;; During projection we try to "prettify" (optimize) the generated Clojure
;;; forms as much as possible by removing any unnecessary noops, unwrapping
;;; shallow `do` forms, etc. This is controlled by `*emit-pretty*` (true by
;;; default). Note that some more aggressive optimizations only kick in with
;;; `*emit-noop-as-nil*` set to true. Also, we make no distinction between
;;; user-written and projection-produced code when optimizing.
;;;
;;; Internally, we represent a noop as `:klor/noop*`, which we call an "internal
;;; noop". Internal noops will be optimized out (depending on the value of
;;; `*emit-pretty*`) except when their value is used by the projection of the
;;; enclosing form, in which case they have to be emitted (either as nil or as
;;; `:klor/noop` (an "external noop"), depending on the value of
;;; `*emit-noop-as-nil*`). This distinction allows us to not accidentally
;;; optimize out noops that the user requested to see with `*emit-noop-as-nil*`
;;; set to false.
;;;
;;; In the implementation below, the contract is that `project-form` methods
;;; might return internal noops, while `emit-*` functions cannot and must return
;;; external noops instead.

(def ^:const noop
  :klor/noop*)

(defn noop? [x]
  (= x noop))

(def ^:dynamic *emit-noop-as-nil*
  true)

(def ^:dynamic *emit-pretty*
  true)

(defn emit-noop []
  (if *emit-noop-as-nil* nil :klor/noop))

(defn fills-noop? [form]
  (and *emit-noop-as-nil*
       (or (nil? form)
           (and (seq? form)
                (let [[op & _] form]
                  (contains? '#{send choose} op))))))

(defn emit-body [body]
  (let [body-ret (last body)]
    (if *emit-pretty*
      (let [clean (remove noop? body)
            clean-ret (last clean)]
        (cond
          ;; Body is all noops
          (empty? clean) nil
          ;; Body has a noop in tail position, but it can be filled
          (and (noop? body-ret) (fills-noop? clean-ret)) clean
          ;; Body has a noop in tail position
          (noop? body-ret) (concat clean [(emit-noop)])
          ;; Body has a non-noop in tail position
          :else clean))
      (if (noop? body-ret)
        ;; Body has a noop in tail position
        (concat (butlast body) [(emit-noop)])
        ;; Body has a non-noop in tail position
        body))))

(defn emit-do [body]
  (let [body (emit-body body)]
    (if *emit-pretty*
      (cond
        (empty? body) (emit-noop)
        (= (count body) 1) (first body)
        :else `(~'do ~@body))
      `(~'do ~@body))))

(defn classify-role [ctx form]
  (cond
    (= (role-of form) (:role ctx)) :me
    (contains? (roles-of form) (:role ctx)) :in
    :else :none))

(defmulti project-form #'form-dispatch)

(defn project-as-receiver [ctx form]
  (case (classify-role ctx form)
    :me (project-form ctx form)
    :in `(~'do ~(project-form ctx form) (~'recv '~(role-of form)))
    :none `(~'recv '~(role-of form))))

(defn project-as-sender [ctx form receiver]
  (case (classify-role ctx form)
    ;; NOTE: Only send if we have someone to send to.
    :me (let [p (project-form ctx form)]
           (if receiver `(~'send '~receiver ~p) p))
    :in (project-form ctx form)
    :none noop))

(defn project-coms [ctx form coms]
  (case (classify-role ctx form)
    :me (map (partial project-as-receiver ctx) coms)
    :in (map #(project-as-sender ctx % (role-of form)) coms)
    :none nil))

(defn project-simple [ctx form coms f]
  (let [coms (project-coms ctx form coms)]
    (case (classify-role ctx form)
      :me (f coms)
      :in (emit-do coms)
      :none noop)))

(defn project-body [ctx form body]
  (concat (map (partial project-form ctx) (butlast body))
          (project-coms ctx form (take-last 1 body))))

(defn project-maybe
  ([ctx form f]
   (project-maybe ctx form noop f))
  ([ctx form default f]
   (case (classify-role ctx form)
     :none default
     (f))))

(defmethod project-form :default [ctx [op & args :as form]]
  ;; Invoked for any non-special operator OP.
  (let [role (role-of form)]
    (check-located form ["Unlocated operator invocation: " form])
    (check-located op role ["Operator position should be located at the same "
                            "role as the invocation: expected " role ", is "
                            (role-of op)]))
  (project-simple ctx form args (fn [coms] `(~(project-form ctx op) ~@coms))))

(defmethod project-form :atom [ctx form]
  (check-located form ["Unlocated atomic form: " form])
  (project-maybe ctx form #(unmetaify form)))

(defmethod project-form :vector [ctx form]
  (check-located form ["Unlocated vector: " form])
  (project-simple ctx form form vec))

(defmethod project-form :map [ctx form]
  (check-located form ["Unlocated map: " form])
  (warn-local ["Order of communications within a map is non-deterministic, use "
               "an explicit `let` instead: " form])
  (project-simple ctx form form (fn [coms] `(~'hash-map ~@coms))))

(defmethod project-form :set [ctx form]
  (check-located form ["Unlocated set: " form])
  (warn-local ["Order of communications within a set is non-deterministic, use"
               "an explicit `let` instead: " form])
  (project-simple ctx form form (fn [coms] `(~'hash-set ~@coms))))

(defmethod project-form :role [ctx form]
  (projection-error ["Role expression found during projection: " form]))

(defmethod project-form 'do [ctx [_ & body :as form]]
  (project-maybe ctx form #(emit-do (project-body ctx form body))))

(defn project-let-binding [ctx [binder initform]]
  (check-located binder ["Unlocated `let` binder: " binder])
  (check-local binder ["Multiple roles in `let` binder: " binder])
  (check-located initform ["Unlocated `let` initform: " initform])
  (case (classify-role ctx binder)
    :me [binder (project-as-receiver ctx initform)]
    :none (case (classify-role ctx initform)
            (:me :in) ['_ (project-as-sender ctx initform (role-of binder))]
            :none [])))

(defmethod project-form 'let [ctx [_ bindings & body :as form]]
  (project-maybe ctx form
                 #(let [bindings (mapcat (partial project-let-binding ctx)
                                         (partition 2 bindings))
                        body (project-body ctx form body)]
                    (if (empty? bindings)
                      (emit-do body)
                      `(~'let [~@bindings] ~@(emit-body body))))))

(defn offer? [form]
  (and (seq? form)
       (let [[op & _] form]
         (= op 'offer))))

(defn merge-branches [left right]
  (cond
    (and (noop? left) (noop? right))
    noop
    (and (offer? left) (offer? right))
    (let [[_ sender-l & options-l] left
          [_ sender-r & options-r] right
          labels-l (set (keys (apply hash-map options-l)))
          labels-r (set (keys (apply hash-map options-r)))]
      (cond
        (not= sender-l sender-r)
        (projection-error ["Cannot merge; different senders: " sender-l
                           " and " sender-r] :left left :right right)
        (seq (set/intersection labels-l labels-r))
        (projection-error ["Cannot merge; overlapping labels: " labels-l
                           " and " labels-r] :left left :right right)
        :else
        `(~'offer ~sender-l ~@options-l ~@options-r)))
    :else
    (projection-error ["Cannot merge; only noops and offers are supported: "
                       left " and " right] :left left :right right)))

(defmethod project-form 'if [ctx [_ cond then else :as form]]
  (check-located form ["Unlocated `if`: " form])
  ;; NOTE: Clojure's `cond` macro unfortunately does not try to optimize its
  ;; expansion by using the least number of `if`s possible. In particular, the
  ;; `:else` case expands into an `if` with dead code: `(if :else <then> nil)`.
  ;; Note that the symbol `:else` is not any more special to `cond` than any
  ;; other truthy literal. The dead nil branch makes projection fail for a role
  ;; that requires merging of the two branches, as it doesn't contain an
  ;; appropriate selection. We detect this case here and implicitly elide the
  ;; `if`, projecting only `<then>`. We believe this solution is less surprising
  ;; than having to e.g. override Clojure's `cond`.
  (if (and (= (unmetaify cond) :else) (nil? (unmetaify else)))
    (project-form ctx then)
    (project-maybe ctx form
                   #(let [[c t e] (project-coms ctx form [cond then else])]
                      (case (classify-role ctx form)
                        :me `(if ~c ~t ~e)
                        :in (let [merged (merge-branches t e)]
                              (case (classify-role ctx cond)
                                (:me :in) (emit-do [c merged])
                                :none merged)))))))

(defmethod project-form 'select [ctx [_ [label & roles] & body :as form]]
  (check-located label ["Unlocated `select` label: " label])
  (project-maybe ctx form
                 #(let [body (project-body ctx form body)]
                    (case (classify-role ctx label)
                      :me (let [choices (for [r roles] `(~'choose '~r '~label))]
                            (emit-do (concat choices body)))
                      :none (let [m {:role nil :roles (set roles)}
                                  roles (with-meta roles m)]
                              (case (classify-role ctx roles)
                                :in `(~'offer ~(role-of label)
                                      ~label ~(emit-do body))
                                :none (emit-do body)))))))

(defn project [role form]
  "Project FORM into Clojure code for a role.

  ROLE is an unqualified symbol naming the role."
  (emit-do [(project-form {:role role} form)]))
