(ns org.rathore.amit.capjure
  (:refer-clojure :exclude [flatten])
  (:use org.rathore.amit.capjure-utils)
  (:require
   [clojure.string :as string])
  (:import (java.util Set Map)
           (org.apache.hadoop.hbase HBaseConfiguration HColumnDescriptor HTableDescriptor KeyValue HColumnDescriptor)
           (org.apache.hadoop.hbase.client Delete Get HBaseAdmin HTable Put Scan ResultScanner Result HTable$ClientScanner HConnectionManager)
           (org.apache.hadoop.hbase.util Bytes VersionInfo)
           (org.apache.hadoop.hbase.filter Filter InclusiveStopFilter)
           (org.apache.hadoop.hbase.io.hfile Compression$Algorithm)))

(def *hbase-master*)
(def *single-column-family?*)
(def *hbase-single-column-family*)
(def *primary-keys-config*)

(def HAS-MANY-STRINGS "1c8fd7d")

(def version
  (let [parse-int (fn [s] (Integer/parseInt s))
        [major minor micro] (map parse-int (string/split (. VersionInfo getVersion) #"\."))]
    {:major major
     :minor minor
     :micro micro}))

;; Hack to support hbase 0.20 and hbase 0.90
(def is-hbase-02 (and (=  (:major version) 0)
                      (>= (:minor version) 20)
                      (<  (:minor version) 90)))


(defmacro with-hbase-table [[table hbase-table-name] & exprs]
  `(let [~table ^HTable (hbase-table ~hbase-table-name)
         ret# (do ~@exprs)]
     (.close ~table)
     ret#))

(defmacro with-hbase-admin [admin & exprs]
  `(let [~admin ^HBaseAdmin (hbase-admin)
         ret# (do ~@exprs)]

     ;; If we are on 0.90.2 or newer we must cleanup the connection used by HBaseAdmin
     ;; Versions of HBaseAdmin prior to 0.90.2 reused existing connections and therefore
     ;; didn't leak.
     ;; Hopefully this will be addressed in later versions (0.92+)
     (when (and (>= (:minor version) 90)
                (>= (:micro version) 2))
       (let [config# (.getConfiguration ~admin)]
         (HConnectionManager/deleteConnection config# true)))
     ret#))

;; (defmacro with-scanner [[scanner] & exprs]
;;   `(let [ret# (do ~@exprs)]
;;      (.close ~scanner)
;;      ret#))

(defn COLUMN-NAME-DELIMITER []
  (if *single-column-family?* "__" ":"))

(defn single-column-prefix []
  (str *hbase-single-column-family* ":"))

(declare symbol-name)

(defmemoized symbolize [a-string]
  (keyword a-string))

(defn encoding-keys []
  (*primary-keys-config* :encode))

(defn decoding-keys []
  (*primary-keys-config* :decode))

(defmemoized qualifier-for [key-name]
  (((encoding-keys) (keyword key-name)) :qualifier))

(defmemoized encoding-functor-for [key-name]
  (((encoding-keys) (keyword key-name)) :functor))

(defmemoized all-primary-keys []
  (map #(symbol-name %) (keys (encoding-keys))))

(defmemoized primary-key [column-family]
  (first (filter #(.startsWith ^String column-family ^String (str %)) (all-primary-keys))))

(defmemoized decoding-functor-for [key-name]
  (((decoding-keys) (keyword key-name)) :functor))

(defmemoized decode-with-key [key-name value]
  ((decoding-functor-for key-name) value))

(declare flatten add-to-insert-batch capjure-insert hbase-table read-row read-cell)



(defn create-put-hbase02
  "Create an hbase 0.20 Put object with given bytes and optional
   timestamp."
  [^bytes b version-timestamp]
  (if version-timestamp
    (.setTimeStamp (Put. ^bytes b) (long version-timestamp))
    (Put. ^bytes b)))

(defn create-put-hbase09
  "Create an hbase 0.90 Put object. Fall back to 0.20 version if the
   0.90 API isn't available, and update `is-hbase02` to true."
  [^bytes b version-timestamp]
  (if version-timestamp
      (Put. ^bytes b (long version-timestamp))
      (Put. ^bytes b)))

(defn create-put
  "Create an hbase Put object. Dispatches to 0.20 or 0.90 based on the
  value of `is-hbase02`."
  [row-id version-timestamp]
  (let [b (if (string? row-id)
            (Bytes/toBytes ^String row-id)
            (Bytes/toBytes (long row-id)))]
    (if is-hbase-02
      (create-put-hbase02 b version-timestamp)
      (create-put-hbase09 b version-timestamp))))

(defn insert-with-table-and-put [object-to-save ^HTable table ^Put put]
  (let [flattened (flatten object-to-save)]
    (add-to-insert-batch put flattened)
    (.put table put)))

(defn insert-with-put [object-to-save hbase-table-name ^Put put]
  (with-hbase-table [table hbase-table-name]
    (insert-with-table-and-put object-to-save table put)))

(defn capjure-insert-with-table
  ([object-to-save ^HTable hbase-table row-id]
     (let [put (create-put row-id nil)]
       (insert-with-table-and-put object-to-save hbase-table put)))
  ([object-to-save ^HTable hbase-table row-id version-timestamp]
     (let [put (create-put row-id version-timestamp)]
       (insert-with-put object-to-save hbase-table put))))

(defn capjure-insert
  ([object-to-save hbase-table-name row-id]
     (let [put (create-put row-id nil)]
       (insert-with-put object-to-save hbase-table-name put)))
  ([object-to-save hbase-table-name row-id version-timestamp]
     (let [put (create-put row-id version-timestamp)]
       (insert-with-put object-to-save hbase-table-name put))))

(defn to-bytes [value]
  (Bytes/toBytes ^String (str value)))

(defn add-to-insert-batch [put flattened-list]
  (doseq [[column value] flattened-list]
    (let [[family qualifier] (.split ^String column ":")
          safe-qualifier (or qualifier "")]
      (.add ^Put put ^bytes (Bytes/toBytes ^String family) ^bytes (Bytes/toBytes ^String safe-qualifier) ^bytes (to-bytes value)))))

(defmemoized symbol-name [prefix]
  (if (keyword? prefix)
    (name prefix)
    (str prefix)))

(defn new-key [part1 separator part2]
  (str (symbol-name part1) separator (symbol-name part2)))

(defn prepend-to-keys [prefix separator hash-map]
  (reduce (fn [ret key]
            (assoc ret
              (new-key prefix separator key)
              (hash-map key)))
          {} (keys hash-map)))

(defn postpend-to-keys [postfix separator hash-map]
  (reduce (fn [ret key]
            (assoc ret
              (new-key key separator postfix)
              (hash-map key)))
          {} (keys hash-map)))

(declare process-multiple process-maps process-map process-strings)
(defn process-key-value [key value]
  (cond
   (map? value) (prepend-to-keys key (COLUMN-NAME-DELIMITER) value)
   (vector? value) (process-multiple key value)
   :else {(new-key key (COLUMN-NAME-DELIMITER) "") value}))

(defn process-multiple [key values]
  (if (map? (first values))
    (process-maps key values)
    (process-strings key values)))

(defn process-maps [key maps]
  (let [qualifier (qualifier-for key)
        encoding-functor (encoding-functor-for key)]
    (apply merge (map
                  (fn [single-map]
                    (process-map (symbol-name key) (encoding-functor single-map) (dissoc single-map qualifier)))
                  maps))))

(defn process-map [initial-prefix final-prefix single-map]
  (let [all-keys (to-array (keys single-map))]
    (areduce all-keys idx ret {}
             (assoc ret
               (str initial-prefix "_" (symbol-name (aget all-keys idx)) (COLUMN-NAME-DELIMITER) final-prefix)
               (single-map (aget all-keys idx))))))

(defn process-strings [key strings]
  (reduce (fn [ret the-string]
            (assoc ret (new-key key (COLUMN-NAME-DELIMITER) the-string) HAS-MANY-STRINGS))
          {} strings))

(defn prepend-keys-for-single-column-family [flattened]
  (if-not *single-column-family?*
    flattened
    (let [prefix (single-column-prefix)
          key-prepender (fn [[key value]]
                          {(str prefix key) value})]
      (apply merge (map key-prepender flattened)))))

(defn flatten [bloated-object]
  (let [f (apply merge (map
                        (fn [[k v]]
                          (process-key-value k v))
                        bloated-object))]
    (prepend-keys-for-single-column-family f)))

(declare read-as-hash cell-value-as-string hydrate-pair has-many-strings-hydration has-many-objects-hydration has-one-string-hydration has-one-object-hydration collapse-for-hydration)

(defmemoized is-from-primary-keys? [key-name]
  (let [key-name-str (symbol-name key-name)]
    (some #(.startsWith ^String key-name-str ^String %) (all-primary-keys))))

(defmemoized column-name-empty? [key-name]
  (= 1 (count (.split ^String key-name ^String (COLUMN-NAME-DELIMITER)))))

(defn strip-prefixes [flattened-and-prepended]
  (if-not *single-column-family?*
    flattened-and-prepended
    (let [prefix-length (count (single-column-prefix))
          prefix-stripper (fn [[key value]]
                            {(.substring ^String key prefix-length) value})]
      (apply merge (map prefix-stripper flattened-and-prepended)))))

(defn tokenize-column-name [full-column-name]
  (seq (.split ^String full-column-name ^String (COLUMN-NAME-DELIMITER))))

(defn collapse-for-hydration [mostly-hydrated]
  (reduce (fn [ret key]
            (let [primary-key (symbol-name key)
                  inner-map (ret primary-key)
                  inner-values (apply vector (vals inner-map))]
              (assoc ret primary-key inner-values)))
          mostly-hydrated (filter is-from-primary-keys? (keys mostly-hydrated))))

(defn hydrate [flattened-and-prepended]
  (let [flattened-object (strip-prefixes flattened-and-prepended)
        flat-keys (keys flattened-object)
        mostly-hydrated (reduce (fn [ret key]
                                  (hydrate-pair key flattened-object ret))
                                {} flat-keys)
        pair-symbolizer (fn [[key value]]
                          {(symbolize key) value})]
    (apply merge (map pair-symbolizer (collapse-for-hydration mostly-hydrated)))))

(defn hydrate-pair [#^String key-name flattened hydrated]
  (let [#^String value (.trim (str (flattened key-name)))
        [#^String column-family #^String column-name] (tokenize-column-name key-name)]
    (cond
     (= HAS-MANY-STRINGS value) (has-many-strings-hydration hydrated column-family column-name)
     (is-from-primary-keys? column-family) (has-many-objects-hydration hydrated column-family column-name value)
     (column-name-empty? key-name) (has-one-string-hydration hydrated column-family value)
     :else (has-one-object-hydration hydrated column-family column-name value))))

(defn has-one-string-hydration [hydrated #^String column-family #^String value]
  (assoc hydrated (symbolize column-family) value))

(defn has-one-object-hydration [hydrated #^String column-family #^String column-name #^String value]
  (let [value-map (or (hydrated column-family) {})]
    (assoc-in hydrated [column-family (symbolize column-name)] value)))

(defn has-many-strings-hydration [hydrated #^String column-family #^String value]
  (let [old-value (hydrated (symbolize column-family))]
    (if (nil? old-value)
      (assoc hydrated (symbolize column-family) [value])
      (assoc hydrated (symbolize column-family) (conj old-value value)))))

(defn has-many-objects-hydration [hydrated #^String column-family #^String column-name #^String value]
  (let [#^String outer-key (primary-key column-family)
        #^String inner-key (.substring column-family (+ 1 (count outer-key)) (count column-family))
        primary-key-name (qualifier-for outer-key)
        inner-map (or (hydrated outer-key) {})
        inner-object (or (inner-map column-name)
                         {(symbolize (symbol-name primary-key-name)) (decode-with-key outer-key column-name)})]
    (assoc hydrated outer-key
           (assoc inner-map column-name
                  (assoc inner-object (symbolize inner-key) value)))))

(defn columns-from-hbase-row-result [^Map hbase-row-result]
  (let [^Set<byte[]> key-set (.keySet hbase-row-result)]
    (map (fn [^bytes k] (String. k)) (seq key-set))))

(defn hbase-object-as-hash [^Result hbase-result]
  (let [extractor (fn [^KeyValue kv]
                    {(str (String. (.getFamily kv)) ":" (String. (.getQualifier kv)))
                     (String. (.getValue kv))})
        key-values-objects (.list hbase-result)]
    (apply merge (map extractor key-values-objects))))

(defn hydrate-hbase-row [hbase-row]
  (hydrate (hbase-object-as-hash hbase-row)))

(defn to-strings [array-of-byte-arrays]
  (map #(String. ^bytes %) array-of-byte-arrays))

(defn column-name-from [column-family-colon-column-name]
  (last (tokenize-column-name column-family-colon-column-name)))

(defn read-as-hash [hbase-table-name row-id]
  (hbase-object-as-hash (read-row hbase-table-name row-id)))

(defn read-as-hydrated [hbase-table-name row-id]
  (hydrate (read-as-hash hbase-table-name row-id)))

(defn row-exists? [hbase-table-name row-id-string]
  (with-hbase-table [table hbase-table-name]
    (.exists table ^bytes (.getBytes ^String row-id-string))))

(defn cell-value-as-string [^Result row ^String column-name]
  (let [[family qualifier] (.split column-name ":")
        value (.getValue row ^bytes
                         (.getBytes (or family ""))
                         (.getBytes (or qualifier "")))]
    (if-not value "" (String. value))))

(defn create-get
  ([row-id]
     (Get. (.getBytes ^String row-id)))
  ([row-id number-of-versions]
     (let [the-get ^Get (create-get row-id)]
       (.setMaxVersions the-get number-of-versions)
       the-get))
  ([row-id columns number-of-versions]
     (let [the-get ^Get (create-get row-id number-of-versions)]
       (.addColumns the-get (into-array (map #(.getBytes ^String %) columns)))
       the-get)))

(defn get-result-for [hbase-table-name #^String row-id]
  (with-hbase-table [table hbase-table-name]
    (let [hbase-get-row-id (create-get row-id)]
      (.get table hbase-get-row-id))))

(defn read-row [hbase-table-name row-id]
  (get-result-for hbase-table-name row-id))

(defn read-rows [hbase-table-name row-id-list]
  (map #(get-result-for hbase-table-name %) row-id-list))

(declare table-scanner table-scanner-including-stop)
(defn read-rows-between
  "Returns rows from start to stop IDs provided.  Does NOT include stop row.
   Use read-rows-including if you'd like to include the stop row."
  [hbase-table-name columns start-id stop-id]
  (let [#^ResultScanner scanner (table-scanner hbase-table-name columns
                                         start-id stop-id)]
    (iterator-seq (.iterator scanner))))

(defn read-rows-including
  "Returns rows from start to stop IDs provided.  Includes stop row."
  [hbase-table-name columns start-id stop-id]
  (let [#^ResultScanner scanner (table-scanner-including-stop hbase-table-name columns start-id stop-id)]
    (iterator-seq (.iterator scanner))))

(defn row-id-of-row [^Result hbase-row]
  (String. (.getRow hbase-row)))

(defn first-row-id [hbase-table-name column-name]
  (let [#^ResultScanner first-row-scanner (table-scanner hbase-table-name [column-name])]
    (row-id-of-row (first (iterator-seq (.iterator first-row-scanner))))))

(defn remove-single-column-family [all-versions-map]
  (let [smaller-map (fn [[row-id v]]
                      {row-id (v *hbase-single-column-family*)})]
    (doall
     (apply merge (map smaller-map all-versions-map)))))

(defn collect-by-split-key [compound-keys-map]
  (reduce (fn [bucket [compound-key vals-map]]
            (let [split-keys (seq (.split ^String compound-key ^String (COLUMN-NAME-DELIMITER)))
                  key1 (first split-keys)
                  key2 (second split-keys)]
              (assoc-in bucket [key1 key2] vals-map))) {} compound-keys-map))

(defn collect-compound-keys-by-row [bucket [row-id vals-map]]
  (assoc bucket row-id (collect-by-split-key vals-map)))

(defn expand-single-col-versions [all-versions-map]
  (let [smaller-map (remove-single-column-family all-versions-map)]
    (reduce collect-compound-keys-by-row {} smaller-map)))

(defn read-all-versions
  ([hbase-table-name row-id-string number-of-versions]
     (with-hbase-table [table hbase-table-name]
       (stringify-nav-map (.getMap (.get table (create-get row-id-string number-of-versions))))))
  ([hbase-table-name row-id-string column-family-as-string number-of-versions]
     (with-hbase-table [table hbase-table-name]
       (stringify-nav-map (.getMap (.get table (create-get row-id-string [column-family-as-string] number-of-versions)))))))

(defn read-all-multi-col-versions
  [read-rows-fn hbase-table-name column-family-as-string start-id stop-id]
  (let [rows (read-rows-fn hbase-table-name [column-family-as-string]
                           start-id stop-id)
        row-ids (map row-id-of-row rows)
        make-row-map (fn [row-id]
                       {row-id
                        (read-all-versions hbase-table-name row-id
                                           column-family-as-string 100000)})]
    (apply merge (map make-row-map row-ids))))

(defn read-all-multi-col-versions-between
  [hbase-table-name column-family-as-string start-id stop-id]
  (read-all-multi-col-versions read-rows-between hbase-table-name
                               column-family-as-string start-id stop-id))

(defn read-all-multi-col-versions-inclusive
  [hbase-table-name column-family-as-string start-id stop-id]
  (read-all-multi-col-versions read-rows-including hbase-table-name
                               column-family-as-string start-id stop-id))

(defn read-all-versions-between
  ([hbase-table-name column-family-as-string start-id stop-id]
     (read-all-multi-col-versions-between hbase-table-name column-family-as-string start-id stop-id))
  ([hbase-table-name start-id stop-id]
     (expand-single-col-versions
      (read-all-versions-between hbase-table-name *hbase-single-column-family* start-id stop-id))))

(defn read-all-versions-inclusive
  ([hbase-table-name column-family-as-string start-id stop-id]
     (read-all-multi-col-versions-inclusive hbase-table-name column-family-as-string start-id stop-id))
  ([hbase-table-name start-id stop-id]
     (expand-single-col-versions
      (read-all-versions-inclusive hbase-table-name *hbase-single-column-family* start-id stop-id))))

(defn read-cell [hbase-table-name row-id column-name]
  (let [row (read-row hbase-table-name row-id)]
    (cell-value-as-string row column-name)))

(defn table-iterator
  ([^String hbase-table-name columns]
     (iterator-seq (.iterator ^ResultScanner (table-scanner hbase-table-name columns))))
  ([^String hbase-table-name columns start-id]
     (iterator-seq (.iterator ^ResultScanner (table-scanner hbase-table-name columns start-id))))
  ([^String hbase-table-name columns start-id stop-id]
     (iterator-seq (.iterator ^ResultScanner (table-scanner hbase-table-name columns start-id stop-id)))))

(defn add-columns-to-scan [#^Scan scan columns]
  (doseq [#^String col columns]
    (.addColumn scan (.getBytes col))))

(defn scan-for-all [columns]
  (let [scan (Scan.)]
    (add-columns-to-scan scan columns)
    scan))

(defn scan-for-start [columns #^String start-id]
  (let [scan (Scan. (.getBytes start-id))]
    (add-columns-to-scan scan columns)
    scan))

(defn scan-for-start-to-stop
  [columns #^String start-id #^String stop-id]
  (let [scan (Scan. (.getBytes start-id) (.getBytes stop-id))]
    (add-columns-to-scan scan columns)
    scan))

(defn scan-for-start-and-filter
  [columns #^String start-row-string #^Filter filter]
  (let [scan (Scan. (.getBytes start-row-string) filter)]
    (add-columns-to-scan scan columns)
    scan))

(defn scan-for-start-including-stop
  [columns #^String start-id #^String stop-id]
  (let [stop-filter (InclusiveStopFilter. (.getBytes stop-id))]
    (scan-for-start-and-filter columns start-id stop-filter)))

(defn table-scanner
  "Does not include stop row, if given.
   Use table-scanner-including-stop, if stop row required."
  ([^String hbase-table-name columns]
     (let [table (hbase-table hbase-table-name)]
       (.getScanner ^HTable table ^Scan (scan-for-all columns))))
  ([^String hbase-table-name columns ^String start-id]
     (let [table (hbase-table hbase-table-name)]
       (.getScanner ^HTable table ^Scan (scan-for-start columns start-id))))
  ([^String hbase-table-name columns ^String start-id ^String stop-id]
     (let [table (hbase-table hbase-table-name)]
       (.getScanner ^HTable table ^Scan (scan-for-start-to-stop columns start-id stop-id)))))

(defn table-scanner-including-stop
  [#^String hbase-table-name columns #^String start-id #^String stop-id]
  (let [^HTable table (hbase-table hbase-table-name)
        ^Scan scan (scan-for-start-including-stop columns start-id stop-id)]
    (.getScanner table scan)))

(defn hbase-row-seq [^ResultScanner scanner]
  (let [first-row (.next scanner)]
    (if-not first-row
      nil
      (lazy-seq
       (cons first-row (hbase-row-seq scanner))))))

(defn next-row-id [^String hbase-table-name column-to-use row-id]
  (let [scanner ^HTable$ClientScanner (table-scanner hbase-table-name [column-to-use] row-id)
        _ (.next scanner)
        next-result (.next scanner)]
    (if next-result
      (row-id-of-row next-result))))

(defn rowcount [#^String hbase-table-name & columns]
  (count (table-iterator hbase-table-name columns)))

(defn delete-row-col-at [hbase-table-name ^String row-id ^String family ^String qualifier timestamp]
  (with-hbase-table [table hbase-table-name]
    (let [delete (Delete. (.getBytes row-id))]
      (if timestamp
        (.deleteColumn delete (.getBytes family) (.getBytes qualifier) timestamp)
        (.deleteColumn delete (.getBytes family) (.getBytes qualifier)))
      (.delete table delete))))

(defn delete-row-col-latest [hbase-table-name row-id family qualifier]
  (delete-row-col-at hbase-table-name row-id family qualifier nil))

(defn delete-all-versions-for [table-name row-id]
  (let [all-versions (read-all-versions table-name row-id 10000)
        families (keys all-versions)
        del-column (fn [family qualifier]
                     (let [timestamps (keys (get-in all-versions [family qualifier]))
                           num-timestamps (count timestamps)]
                       (dotimes [n num-timestamps]
                         (delete-row-col-latest table-name row-id family qualifier)
                         )))
        del-family (fn [family]
                     (let [qualifiers (keys (all-versions family))]
                       (dorun (map #(del-column family %) qualifiers))))]
    (if all-versions
      (do
        (dorun (map del-family families))
        ;; Versions could be higher than max-versions, but we don't
        ;; know that from all-versions (unfortunately)... hence the
        ;; recur step.
        (recur table-name row-id))
      )
    )
  )

(defn delete-all-rows-versions [table-name row-ids]
  (dorun
   (map #(delete-all-versions-for table-name %) row-ids))
  ;; Thread sleep because hbase deletes can mask puts in the same ms even if they happen afterwards:
  ;; http://search-hadoop.com/m/rNnhN15Xecu
  (Thread/sleep 1))

(defn delete-all [#^String hbase-table-name & row-ids-as-strings]
  (delete-all-rows-versions hbase-table-name row-ids-as-strings))

(defmemoized column-families-for [hbase-table-name]
  (with-hbase-table [table hbase-table-name]
    (let [table-descriptor (.getTableDescriptor ^HTable table)]
      (map #(String. (.getNameAsString ^HColumnDescriptor %)) (.getFamilies table-descriptor)))))

(defn simple-delete-row [hbase-table-name ^String row-id]
  (with-hbase-table [table hbase-table-name]
    (let [delete (Delete. (.getBytes row-id))]
      (.delete table delete))))

(defn simple-delete-rows-with-sleep [hbase-table-name rows]
  (doseq [row rows]
    (simple-delete-row hbase-table-name row))
  ;; Thread sleep because hbase deletes can mask puts in the same ms even if they happen afterwards:
  ;; http://search-hadoop.com/m/rNnhN15Xecu
  (Thread/sleep 1))

(defn column-names-as-strings [^Map result-row]
  (map #(String. ^bytes %) (.keySet result-row)))

(defmemoized hbase-config []
  (HBaseConfiguration.))

(defn hbase-admin []
  (HBaseAdmin. (hbase-config)))

(defn hbase-column-descriptor
  "Create an HColumnDescriptor with given `col-name`, and initialize
  with common options, such as compression type."
  [^String col-name]
  (let [hcdesc (HColumnDescriptor. ^String col-name)]
    (when-not is-hbase-02
      (.setCompressionType hcdesc Compression$Algorithm/LZO)
      (.setCompactionCompressionType hcdesc Compression$Algorithm/LZO))
    hcdesc))

(defn create-hbase-table-multiple-versions
  "Create an HBase table with the given table-name and
  column-family-name - version tuples. LZO compression is turned on
  for column families."
  [#^String table-name &
  column-families-and-versions]
  (let [desc (HTableDescriptor. table-name)
        col-desc (fn [[col-family-name max-versions]]
                   (let [hcdesc (hbase-column-descriptor col-family-name)]
                     (.setMaxVersions hcdesc max-versions)
                     (.addFamily desc hcdesc)))]
    (doseq [family-entry column-families-and-versions]
      (col-desc family-entry))
    (with-hbase-admin admin
      (.createTable admin desc))))

(defn create-hbase-table [#^String table-name max-versions & column-families]
  (let [desc (HTableDescriptor. table-name)
        col-desc (fn [col-family-name]
                   (let [hcdesc (hbase-column-descriptor col-family-name)]
                     (.setMaxVersions hcdesc max-versions)
                     (.addFamily desc hcdesc)))]
    (doall (map col-desc column-families))
    (with-hbase-admin admin
      (.createTable admin desc))))

(defn add-hbase-columns [^String table-name column-family-names versions]
  (if-not (empty? column-family-names)
    (with-hbase-admin admin
      (let [col-desc (fn [^String col-name]
                       (let [desc (hbase-column-descriptor col-name)]
                         (.setMaxVersions desc versions)
                         desc))]
        (.disableTable admin (.getBytes table-name))
        (doall (map #(.addColumn admin table-name ^HColumnDescriptor (col-desc %)) column-family-names))
        (.enableTable admin (.getBytes table-name))))))

(defn clone-table [#^String new-hbase-table-name #^String from-hbase-table-name max-versions]
  (apply create-hbase-table new-hbase-table-name max-versions (column-families-for from-hbase-table-name)))

(defn disable-table [^String table-name]
  (with-hbase-admin admin
    (.disableTable admin (.getBytes table-name))))

(defn enable-table [#^String table-name]
  (with-hbase-admin admin
    (.enableTable admin (.getBytes table-name))))

(defn drop-hbase-table [#^String hbase-table-name]
  (with-hbase-admin admin
    (.deleteTable admin hbase-table-name)))

(defn truncate-hbase-table [#^String hbase-table-name]
  (with-hbase-table [table hbase-table-name]
    (let [table-descriptor (.getTableDescriptor table)]
      (try
        (disable-table hbase-table-name)
        (drop-hbase-table hbase-table-name)
        (catch Exception e))
      (with-hbase-admin admin
        (.createTable admin table-descriptor)))))

(defn hbase-table [^String hbase-table-name]
  (let [table (HTable. ^HBaseConfiguration (hbase-config) hbase-table-name)]
    (.setScannerCaching table 1000)
    table))

(defn table-exists?
  ([table-name]
     (with-hbase-admin admin
       (table-exists? table-name admin)))
  ([^String table-name ^HBaseAdmin hadmin]
     (.tableExists hadmin table-name)))

(defn get-table-name-from-desc [^HTableDescriptor table-desc]
  (apply str (map char (.getName table-desc))))

(defn list-tables []
  (with-hbase-admin admin
    (let [tables (.listTables admin)]
      (map get-table-name-from-desc tables))))
