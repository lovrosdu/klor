(ns klor.sockets
  (:require
   [klor.runtime :refer [play-role]]
   [taoensso.nippy :as nippy])
  (:import
   java.nio.ByteBuffer
   java.net.InetSocketAddress
   java.net.StandardSocketOptions
   java.nio.channels.ReadableByteChannel
   java.nio.channels.SocketChannel
   java.nio.channels.WritableByteChannel
   java.nio.channels.ServerSocketChannel))

(set! *warn-on-reflection* true)

(def ^:dynamic *log*
  false)

(defn read-bc! ^ByteBuffer [^ReadableByteChannel bc ^ByteBuffer bb]
  (while (not (zero? (.remaining bb)))
    (.read bc bb))
  bb)

(defn write-bc! ^ByteBuffer [^WritableByteChannel bc ^ByteBuffer bb]
  (while (not (zero? (.remaining bb)))
    (.write bc bb))
  bb)

(defn socket-send [^SocketChannel sc value]
  (let [bs (nippy/freeze value)]
    (write-bc! sc (doto (ByteBuffer/allocate Long/BYTES)
                    (.putLong (count bs))
                    (.flip)))
    (write-bc! sc (ByteBuffer/wrap bs)))
  (when *log* (println (str (.getRemoteAddress sc)) "<--" (pr-str value))))

(defn socket-recv [^SocketChannel sc]
  (let [bb1 (ByteBuffer/allocate Long/BYTES)
        n (.getLong (.flip (read-bc! sc bb1)))
        bb2 (ByteBuffer/allocate n)
        bs (.array (read-bc! sc bb2))
        value (nippy/thaw bs)]
    (when *log* (println (str (.getRemoteAddress sc)) "-->" (pr-str value)))
    value))

(defn wrap-sockets [config sockets & {:keys [log] :or {log :dynamic}}]
  (letfn [(wrap-log [f]
            (if (= log :dynamic)
              f
              (fn [& args]
                (binding [*log* log]
                  (apply f args)))))]
    (merge {:locators sockets
            :send (wrap-log socket-send)
            :recv (wrap-log socket-recv)}
           config)))

(defn with-server-socket [& {:keys [host port] :or {host "0.0.0.0"}}]
  (doto (ServerSocketChannel/open)
    (.configureBlocking true)
    (.setOption StandardSocketOptions/SO_REUSEADDR true)
    (.bind (InetSocketAddress. (str host) (long port)))))

(defn with-server* [expr [sym opts]]
  `(let [~sym (with-server-socket ~opts)]
     (try ~expr (finally (.close ~sym)))))

(defmacro with-server [specs & body]
  (reduce with-server* `(do ~@body) (reverse (partition-all 2 specs))))

(defn with-accept* [expr [ssc syms]]
  (let [ssc# (gensym)
        syms (if (symbol? syms) [syms] syms)]
    `(let [~ssc# ~ssc
           ~@(mapcat identity (for [sym syms] [sym `(.accept ~ssc#)]))]
       (try ~expr (finally ~@(for [sym syms] `(.close ~sym)))))))

(defmacro with-accept [specs & body]
  (reduce with-accept* `(do ~@body) (reverse (partition-all 2 specs))))

(defn with-client-socket [& {:keys [host port] :or {host "127.0.0.1"}}]
  (doto (SocketChannel/open
         (InetSocketAddress. (str host) (long port)))
    (.configureBlocking true)))

(defn with-client* [expr [sym opts]]
  `(let [~sym (with-client-socket ~opts)]
     (try ~expr (finally (.close ~sym)))))

(defmacro with-client [specs & body]
  (reduce with-client* `(do ~@body) (reverse (partition-all 2 specs))))
