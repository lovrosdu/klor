(ns klor.sockets
  (:require [klor.runtime :refer [play-role]]
            [taoensso.nippy :as nippy])
  (:import java.nio.ByteBuffer
           (java.net InetSocketAddress
                     StandardSocketOptions)
           (java.nio.channels ReadableByteChannel
                              SocketChannel
                              WritableByteChannel
                              ServerSocketChannel)))

(set! *warn-on-reflection* true)

(def ^:dynamic *log*
  false)

(defn read-bc! ^ByteBuffer [^ReadableByteChannel bc ^ByteBuffer bb]
  (while (not (zero? (. bb (remaining))))
    (. bc (read bb)))
  bb)

(defn write-bc! ^ByteBuffer [^WritableByteChannel bc ^ByteBuffer bb]
  (while (not (zero? (. bb (remaining))))
    (. bc (write bb)))
  bb)

(defn socket-send [^SocketChannel sc value]
  (let [bs (nippy/freeze value)]
    (write-bc! sc (doto (ByteBuffer/allocate Long/BYTES)
                    (.putLong (count bs))
                    (.flip)))
    (write-bc! sc (ByteBuffer/wrap bs)))
  (when *log* (println (str (. sc (getRemoteAddress))) "<--" (pr-str value))))

(defn socket-recv [^SocketChannel sc]
  (let [bb1 (ByteBuffer/allocate Long/BYTES)
        n (.. (read-bc! sc bb1) (flip) (getLong))
        bb2 (ByteBuffer/allocate n)
        bs (. (read-bc! sc bb2) (array))
        value (nippy/thaw bs)]
    (when *log* (println (str (. sc (getRemoteAddress))) "-->" (pr-str value)))
    value))

(defn wrap-sockets [state sockets & {:keys [log] :or {log :dynamic}}]
  (letfn [(wrap-log [f]
            (if (= log :dynamic)
              f
              (fn [& args]
                (binding [*log* log]
                  (apply f args)))))]
    (merge {:locators sockets
            :send (wrap-log socket-send)
            :recv (wrap-log socket-recv)}
           state)))

(defmacro with-server [[sym & {:keys [host port] :or {host "0.0.0.0"}}] & body]
  `(let [~sym (doto (ServerSocketChannel/open)
                (.configureBlocking true)
                (.setOption StandardSocketOptions/SO_REUSEADDR true)
                (.bind (InetSocketAddress. (str ~host) (long ~port))))]
     (try ~@body (finally (. ~sym (close))))))

(defmacro with-accept [[ssc & syms] & body]
  (let [ssc# (gensym)]
    `(let [~ssc# ~ssc
           ~@(mapcat identity (for [sym syms] [sym `(. ~ssc# (accept))]))]
       (try ~@body (finally ~@(for [sym syms] `(. ~sym (close))))))))

(defmacro with-client [[sym & {:keys [host port] :or {host "127.0.0.1"}}]
                       & body]
  `(let [~sym (doto (SocketChannel/open
                     (InetSocketAddress. (str ~host) (long ~port)))
                (.configureBlocking true))]
     (try ~@body (finally (. ~sym (close))))))
