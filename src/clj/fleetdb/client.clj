(ns fleetdb.client
  (:require (fleetdb [io :as io]))
  (:import (java.net Socket)
           (java.io DataInputStream  BufferedInputStream
                    DataOutputStream BufferedOutputStream)))

(defn connect [#^String host #^Integer port]
  (let [socket (Socket. host port)]
    {:socket socket
     :dis    (DataInputStream.  (BufferedInputStream.  (.getInputStream  socket)))
     :dos    (DataOutputStream. (BufferedOutputStream. (.getOutputStream socket)))}))

(defn query [client q]
  (io/dos-write (:dos client) q)
  (let [result (io/dis-read (:dis client) io/eof)]
    (assert (not= result io/eof))
    result))

(defn close [client]
  (io/dis-close (:dis client))
  (io/dos-close (:dos client))
  (.close #^Socket (:socket client)))
