(ns klor.multi.specials
  (:require [klor.util :refer [warn]]))

(defmacro at [[role+] expr]
  (warn "`at` used outside of a choreographic context")
  &form)

(defmacro local [[role+] & body]
  {:style/indent 1}
  (warn "`local` used outside of a choreographic context")
  &form)

(defmacro copy [[src dst] expr]
  (warn "`copy` used outside of a choreographic context")
  &form)

(defmacro pack [expr+]
  (warn "`pack` used outside of a choreographic context")
  &form)

(defmacro unpack*
  {:style/indent 2}
  [[binder+] init & body]
  (warn "`unpack*` used outside of a choreographic context")
  &form)

(defmacro chor*
  {:style/indent :defn
   :arglists '([signature [params*] & body] [name signature [params*] & body])}
  [& _]
  (warn "`chor*` used outside of a choreographic context")
  &form)

(defmacro inst [name [role+]]
  (warn "`inst` used outside of a choreographic context")
  &form)
