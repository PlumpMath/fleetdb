(ns fleetdb.embedded
  (:use [fleetdb.util :only (def- ? spawn rassert raise)])
  (:require (fleetdb [types :as types] [lint :as lint] [core :as core]
                     [fair-lock :as fair-lock] [file :as file])
            (clj-json [core :as json]))
  (:import  (java.util ArrayList)
            (java.io FileReader BufferedReader FileWriter BufferedWriter
                     File RandomAccessFile)))

(defn- dba? [dba]
  (? (:write-lock (meta dba))))

(defn- replay-query [db q]
  (first (core/query* db q)))

(defn- check-log [read-path]
  (let [f (File. #^String read-path)
        raf (RandomAccessFile. f "rw")]
    (loop [l (.length f)]
      (if (= 0 l)
        (.setLength raf 0)
        (do
          (.seek raf (dec l))
          (if (= 10 (.read raf))
            (.setLength raf l)
            (recur (dec l))))))))

(defn- read-db [read-path]
  (check-log read-path)
  (let [reader  (BufferedReader. (FileReader. #^String read-path))
        queries (json/parsed-seq reader)
        empty   (core/init)]
    (reduce replay-query empty queries)))

(defn- new-writer [write-path]
  (let [appending (file/exist? write-path)]
    (BufferedWriter. (FileWriter. #^String write-path #^Boolean appending))))

(defn- close-writer [#^BufferedWriter writer]
  (.close writer))

(defn- write-query [#^BufferedWriter writer query & [flush]]
  (.write writer #^String (json/generate-string query))
  (.write writer "\r\n")
  (if flush (.flush writer)))

(defn- write-db [write-path db]
  (let [writer (new-writer write-path)]
    (doseq [coll (core/query* db ["list-collections"])]
      (doseq [chunk (partition-all 100 (core/query* db ["select" coll]))]
          (write-query writer ["insert" coll chunk]))
      (doseq [ispec (core/query* db ["list-indexes" coll])]
          (write-query writer ["create-index" coll ispec])))
    (close-writer writer)))

(defn- init* [db & [other-meta]]
  (atom db :meta (assoc other-meta :write-lock (fair-lock/init))))

(defn init-ephemeral []
  (init* (core/init)))

(defn load-ephemeral [read-path]
  (init* (read-db read-path)))

(defn init-persistent [write-path]
  (init* (core/init)
         {:writer (new-writer write-path)
          :write-path write-path}))

(defn load-persistent [read-write-path]
  (init* (read-db read-write-path)
         {:writer (new-writer read-write-path)
          :write-path read-write-path}))

(defn close [dba]
  (assert (fair-lock/join (:write-lock (meta dba)) 60))
  (if-let [writer (:writer (meta dba))]
    (close-writer writer))
  (assert (compare-and-set! dba @dba nil))
  true)

(defn persistent? [dba]
  (? (:writer (meta dba))))

(defn ephemeral? [dba]
  (not (persistent? dba)))

(defn fork [dba]
  (rassert (ephemeral? dba) "cannot fork persistent databases")
  (init* @dba))

(defn snapshot [dba snapshot-path]
  (rassert (ephemeral? dba) "cannot snapshot persistent databases")
  (let [tmp-path (str snapshot-path ".tmp")]
    (write-db tmp-path @dba)
    (file/mv tmp-path snapshot-path)
    true))

(defn write-path [dba]
  (rassert (persistent? dba) "only persistent databases have files")
  (:write-path (meta dba)))

(defn compacting? [dba]
  (? (:write-buf (meta dba))))

(defn compact [dba]
  (rassert (persistent? dba) "cannot compact ephemeral database")
  (fair-lock/fair-locking (:write-lock (meta dba))
    (if (compacting? dba)
      false
      (let [tmp-path (str (:write-path (meta dba)) ".tmp")
            db-at-start @dba]
        (alter-meta! dba assoc :write-buf (ArrayList.))
        (spawn
          (write-db tmp-path db-at-start)
          (fair-lock/fair-locking (:write-lock (meta dba))
            (let [writer (new-writer tmp-path)]
              (doseq [q (:write-buf (meta dba))]
                (write-query writer q))
              (file/mv tmp-path (:write-path (meta dba)))
              (close-writer (:writer (meta dba)))
              (alter-meta! dba dissoc :write-buf)
              (alter-meta! dba assoc  :writer writer))))
        true))))

(defn query* [dba q]
  (if (types/read-queries (first q))
    (core/query* @dba q)
    (fair-lock/fair-locking (:write-lock (meta dba))
      (let [old-db          @dba
            [new-db result] (core/query* old-db q)]
        (when-let [writer (:writer (meta dba))]
          (write-query writer q true)
          (when-let [write-buf (:write-buf (meta dba))]
            (.add #^ArrayList write-buf q)))
        (assert (compare-and-set! dba old-db new-db))
        result))))

(defn query [dba q]
  (rassert (dba? dba) "dba not recognized as a database")
  (lint/lint-query q)
  (query* dba q))
