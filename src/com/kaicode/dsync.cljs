(ns com.kaicode.dsync
  (:require-macros [cljs.core.async.macros :refer [go-loop go]])
  (:require [com.kaicode.wocket.client :as ws :refer [process-msg]]
            [cljs.core.async :refer [<! >! put! chan]]
            [com.kaicode.dsync.db :as db]
            [com.kaicode.mercury :as m]
            [com.kaicode.tily :as tily]))

(def query->channel (atom {}))

(defmethod process-msg :remote-transact-error [[tx error]]
  (m/broadcast [tx error]))

(defmethod process-msg :remote-q-result [[_ [query+params remote-result]]]
  (let [channel (@query->channel query+params)]
    (put! channel remote-result)))

(defn remote-transact [tx]
  (db/transact tx)
  (ws/send! [:remote-transact tx]))

(defmethod process-msg :schema [[_ schema-from-datomic]]
  (m/broadcast [:schema/avaiable schema-from-datomic]))

(defmethod process-msg :transact [[_ tx]]
  (db/transact tx))

(defn remote-q-channel [query & params]
  (let [query+params+as-vec (into [query] params)
        channel (or (@query->channel query+params+as-vec) (chan 10))
        cmd (into [:remote-q query] params)]
    (swap! query->channel update-in [query+params+as-vec] (constantly channel))
    (ws/send! cmd)
    channel))

(defn datomic->datascript [query & params]
  (go (let [in-result-channel (remote-q-channel query params)
            result (<! in-result-channel)
            tx-report (db/transact result)]
        tx-report)))

(defn default-save-fn [content row column-kw]
  (let [old-content (column-kw @row)
        content (clojure.string/trim content)]
    (when-not (= content old-content)
      (let [sys-id (:system/id @row)
            new-datom {column-kw content}
            tx (if sys-id
                 (merge new-datom {:system/id sys-id})
                 (let [sys-id (db/system-id)]
                   (merge new-datom {:system/id sys-id
                                     :system/time-created (js/Date.)})))]
        (try
          (remote-transact [tx])
          (catch js/Error e (do
                              (tily/set-atom! row [column-kw] " ")
                              (throw e))))))))

(defn delete-rows-fn [rows]
  (println "delete ros")
  (let [transactions (vec (for [r rows]
                            [:db.fn/retractEntity [:system/id (:system/id r)]]))]
    (remote-transact transactions)))