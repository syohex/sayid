(ns com.billpiel.sayid.nrepl-middleware
  (:require [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as t]
            [com.billpiel.sayid.core :as sd]
            [com.billpiel.sayid.query2 :as q]
            [com.billpiel.sayid.string-output :as so]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rts]
            [com.billpiel.sayid.util.other :as util]
            [clojure.tools.namespace.find :as ns-find]))

(defn send-status-done
  [transport msg]
  (t/send transport (response-for msg :status :done)))

(defn get-form-at-file-pos
  [file line source]
  (let [rdr (rts/source-logging-push-back-reader source
                                                 (count source)
                                                 file)]
    (loop [rdr-iter rdr]
      (let [frm (r/read rdr)
            m (meta frm)]
        (cond
          (nil? frm) nil
          (<= (:line m) line (:end-line m)) frm
          :else (recur rdr-iter))))))

(defn get-meta-at-file-pos
  [file line source]
  (meta (get-form-at-file-pos file line source)))

(defn parse-ns-name-from-source
  [source]
  (second (re-find #"\(\s*ns\s+([\w$.*-]+)"
                   source)))

(defn tree-contains-inner-trace?
  [tree]
  (->> tree
       (tree-seq map? :children)
       (filter #(contains? % :src-pos))
       first
       nil?
       not))

(defn inner-replay-matches!
  [[{:keys [name]} :as matches]]
  (sd/ws-add-deep-trace-fn!* name)
  (doseq [{:keys [name args]} matches]
    (apply (resolve name)
           args)))

(defn query-ws-by-file-line-range
  "Find the tree node that: is smallest or starts latest, contains line
  and starts at or after start-line."
  [file start-line line]
  (let [ws (sd/ws-deref!)
        ids (q/get-ids-from-file-line-range ws
                                            file
                                            start-line
                                            line)]
    (if-not (empty? ids)
      (sd/ws-query* [:id ids])
      [])))

(defn process-line-meta
  [line-meta]
  (mapv (fn [[n m]]
          [n
           (update-in m
                      [:path]
                      str)])
        line-meta))

;; ======================

(defn sayid-force-get-inner-trace
  [{:keys [transport source file line] :as msg}]
  (try (let [{start-line :line} (get-meta-at-file-pos file line source)
             matches (query-ws-by-file-line-range file start-line line)
             has-inner? (tree-contains-inner-trace? {:children matches})
             matches' (cond
                        has-inner?
                        matches
                        (empty? matches) nil
                        :else
                        (do (inner-replay-matches! matches)
                            (query-ws-by-file-line-range file start-line line)))

             out (if-not (nil? matches')
                   (-> matches'
                       util/wrap-kids
                       so/tree->string+meta)
                   {:string (str "No trace records found for function at line: " line)})]
         (t/send transport (response-for msg
                                         :value (:string out)
                                         :meta (-> out :meta process-line-meta))))
       (catch Exception e
         (t/send transport (response-for msg
                                         :value (with-out-str (clojure.stacktrace/print-stack-trace e)))))
       (finally
         (send-status-done transport msg))))

(defn sayid-query-form-at-point
  [{:keys [transport file line] :as msg}]
  (println "****** sayid-query-form-at-point")
  (println msg)
  (let [out (-> (sd/ws-query-by-file-pos file line)
                util/wrap-kids
                so/tree->string+meta)]
    (t/send transport (response-for msg
                                    :value (:string out)
                                    :meta (-> out :meta process-line-meta))))
  (t/send transport (response-for msg :status :done)))


(defn sayid-buf-query
  [q-vec mod-str]
  (let [[_ sk sn] (re-find #"(\w+)\s*(\d+)?" mod-str)
        k (keyword sk)
        n (util/->int sn)
        query (remove nil? [k n q-vec])]
    (-> (apply sd/ws-query* query)
        util/wrap-kids
        so/tree->string+meta)))

(defn sayid-buf-query-id-w-mod
  [{:keys [transport trace-id mod] :as msg}]
  (let [{v :string
         m :meta} (sayid-buf-query [:id (keyword trace-id)] mod)]
    (t/send transport (response-for msg
                                    :value v
                                    :meta (process-line-meta m))))
  (send-status-done transport msg))

(defn sayid-buf-query-fn-w-mod
  [{:keys [transport fn-name mod] :as msg}]
  (let [out (sayid-buf-query [(some-fn :parent-name :name)
                              (symbol fn-name)]
                             mod)]
    (t/send transport (response-for msg
                                    :value (:string out)
                                    :meta (-> out :meta process-line-meta))))
  (send-status-done transport msg))

(defn sayid-buf-def-at-point
  [{:keys [transport trace-id path] :as msg}]
  (println path)
  (let [path' (read-string path)]
    (util/def-ns-var '$s '* (-> [:id (keyword trace-id)]
                                sd/ws-query*
                                first
                                (get-in path'))))
  (t/send transport (response-for msg :value "Def'd as $s/*"))
  (send-status-done transport msg))

(defn sayid-clear-log
  [{:keys [transport] :as msg}]
  (sd/ws-clear-log!)
  (send-status-done transport msg))

(defn sayid-reset-workspace
  [{:keys [transport] :as msg}]
  (sd/ws-reset!)
  (send-status-done transport msg))

(defn sayid-trace-all-ns-in-dir
  [{:keys [transport dir] :as msg}]
  (doall (map sd/ws-add-trace-ns!* (ns-find/find-namespaces-in-dir (java.io.File. dir))))
  (send-status-done transport msg))

(defn sayid-remove-all-traces
  [{:keys [transport] :as msg}]
  (sd/ws-remove-all-traces!)
  (send-status-done transport msg))

(defn sayid-get-workspace
  [{:keys [transport] :as msg}]
  (let [out (so/tree->string+meta (sd/ws-deref!))]
    (t/send transport (response-for msg
                                    :value (:string out)
                                    :meta  (-> out :meta process-line-meta))))
  (t/send transport (response-for msg :status :done)))

(def sayid-nrepl-ops
  {"sayid-query-form-at-point" #'sayid-query-form-at-point
   "sayid-force-get-inner-trace" #'sayid-force-get-inner-trace
   "sayid-get-workspace" #'sayid-get-workspace
   "sayid-clear-log" #'sayid-clear-log
   "sayid-reset-workspace" #'sayid-reset-workspace
   "sayid-trace-all-ns-in-dir" #'sayid-trace-all-ns-in-dir
   "sayid-remove-all-traces" #'sayid-remove-all-traces
   "sayid-buf-query-id-w-mod" #'sayid-buf-query-id-w-mod
   "sayid-buf-query-fn-w-mod" #'sayid-buf-query-fn-w-mod
   "sayid-buf-def-at-point" #'sayid-buf-def-at-point})


(defn wrap-sayid
  [handler]
  (fn [{:keys [op] :as msg}]
    ((get sayid-nrepl-ops op handler) msg)))

(set-descriptor! #'wrap-sayid
                 {:handles (zipmap (keys sayid-nrepl-ops)
                                   (repeat {:doc "docs?"
                                            :returns {}
                                            :requires {}}))})
