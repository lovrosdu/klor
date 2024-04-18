(ns klor.multi.specials
  (:require [klor.util :refer [warn]]))

(defmacro at [[& roles] expr]
  (warn "`at` used outside of a choreographic context")
  &form)

(defmacro local [[& roles] expr]
  (warn "`local` used outside of a choreographic context")
  &form)

(defmacro copy [[from to] expr]
  (warn "`copy` used outside of a choreographic context")
  &form)

(defmacro pack [& exprs]
  (warn "`pack` used outside of a choreographic context")
  &form)

(defmacro unpack* [binder init & body]
  {:style/indent 2}
  (warn "`unpack*` used outside of a choreographic context")
  &form)

(defmacro chor* [signature [& params] & body]
  {:style/indent 2}
  (warn "`chor*` used outside of a choreographic context")
  &form)

(defmacro inst [name [& roles]]
  (warn "`inst` used outside of a choreographic context")
  &form)
