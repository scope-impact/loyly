(ns replay.fressian
  "A Fressian reader in Babashka, enough for the event bodies a Grain SQLite store writes
   (grain's fressian-util: the Clojure handlers plus the grain/instant and grain/odt tags).
   Babashka cannot load org.fressian; this follows org.fressian.FressianReader code for code.
   An unknown tag comes back as {:fressian/tag t :fressian/value [components]}."
  (:import [java.nio ByteBuffer]))

(defn hex->bytes [^String h]
  (let [n (quot (count h) 2) b (byte-array n)]
    (dotimes [i n] (aset b i (unchecked-byte (Integer/parseInt (subs h (* 2 i) (+ 2 (* 2 i))) 16))))
    b))

(defn- u8 [st] (let [{:keys [^bytes b pos]} @st] (swap! st update :pos inc) (bit-and 0xff (aget b pos))))
(defn- raw [st n] (loop [i 0 v 0] (if (= i n) v (recur (inc i) (+ (* v 256) (u8 st))))))
(defn- signed [v bits] (if (bit-test v (dec bits)) (- v (bit-shift-left 1 bits)) v))
(defn- raw-bytes [st n] (let [{:keys [^bytes b pos]} @st] (swap! st update :pos + n) (java.util.Arrays/copyOfRange b (int pos) (int (+ pos n)))))

(declare read-obj)
(defn- read-int [st]
  (let [c (u8 st)]
    (cond (<= c 0x3f) c
          (= c 0xff) -1
          (<= 0x40 c 0x5f) (+ (bit-shift-left (- c 0x50) 8) (raw st 1))
          (<= 0x60 c 0x6f) (+ (bit-shift-left (- c 0x68) 16) (raw st 2))
          (<= 0x70 c 0x73) (+ (bit-shift-left (- c 0x72) 24) (raw st 3))
          (<= 0x74 c 0x77) (+ (bit-shift-left (- c 0x76) 32) (raw st 4))
          (<= 0x78 c 0x7b) (+ (bit-shift-left (- c 0x7a) 40) (raw st 5))
          (<= 0x7c c 0x7f) (+ (bit-shift-left (- c 0x7e) 48) (raw st 6))
          (= c 0xf8) (signed (raw st 8) 64)
          :else (throw (ex-info (str "fressian: expected an int, got code " c) {})))))

(defn- utf8ish
  "Fressian strings: each UTF-16 unit as one to three bytes (modified UTF-8)."
  [^bytes bs]
  (let [sb (StringBuilder.) n (alength bs)]
    (loop [i 0]
      (if (>= i n) (str sb)
          (let [c (bit-and 0xff (aget bs i))]
            (case (bit-shift-right c 4)
              (0 1 2 3 4 5 6 7) (do (.append sb (char c)) (recur (inc i)))
              (12 13) (do (.append sb (char (bit-or (bit-shift-left (bit-and c 0x1f) 6) (bit-and 0x3f (aget bs (inc i))))))
                          (recur (+ i 2)))
              14 (do (.append sb (char (bit-or (bit-shift-left (bit-and c 0x0f) 12)
                                               (bit-shift-left (bit-and 0x3f (aget bs (inc i))) 6)
                                               (bit-and 0x3f (aget bs (+ i 2))))))
                     (recur (+ i 3)))
              (throw (ex-info (str "fressian: bad string byte " c) {}))))))))

(defn- read-n [st n] (vec (repeatedly n #(read-obj st))))
(defn- read-until-end [st]
  (loop [acc []]
    (if (or (>= (:pos @st) (alength ^bytes (:b @st)))
            (= 0xfd (bit-and 0xff (aget ^bytes (:b @st) (:pos @st)))))
      (do (when (< (:pos @st) (alength ^bytes (:b @st))) (u8 st)) acc)
      (recur (conj acc (read-obj st))))))

(defn- tagged [tag comps]
  (case tag
    ("grain/instant" "grain/odt") (first comps)
    "key" (keyword (first comps) (second comps))
    "sym" (symbol (first comps) (second comps))
    "set" (set (first comps))
    "map" (apply hash-map (first comps))
    "char" (str (char (first comps)))
    ("bigint" "bigdec" "uri" "regex") (str (first comps))
    "inst" (str (java.time.Instant/ofEpochMilli (first comps)))
    "uuid" (let [bb (ByteBuffer/wrap (first comps))] (str (java.util.UUID. (.getLong bb) (.getLong bb))))
    "ratio" (str (first comps) "/" (second comps))
    {:fressian/tag tag :fressian/value comps}))

(defn- read-struct [st [tag n]] (tagged tag (read-n st n)))

(defn- read-string-chunks [st first-bytes]
  (loop [acc [first-bytes]]
    (let [c (bit-and 0xff (aget ^bytes (:b @st) (:pos @st)))]
      (cond (<= 0xda c 0xe1) (do (u8 st) (conj acc (raw-bytes st (- c 0xda))))
            (= c 0xe3) (do (u8 st) (conj acc (raw-bytes st (read-int st))))
            (= c 0xe2) (do (u8 st) (recur (conj acc (raw-bytes st (read-int st)))))
            :else (throw (ex-info "fressian: string chunk not followed by a string" {}))))))

(defn read-obj [st]
  (let [c (bit-and 0xff (aget ^bytes (:b @st) (:pos @st)))]
    (cond
      (or (<= c 0x7f) (= c 0xff) (= c 0xf8)) (read-int st)
      :else
      (do (u8 st)
          (cond
            (<= 0x80 c 0x9f) (get-in @st [:pcache (- c 0x80)])
            (<= 0xa0 c 0xaf) (read-struct st (get-in @st [:scache (- c 0xa0)]))
            (<= 0xd0 c 0xd7) (raw-bytes st (- c 0xd0))
            (<= 0xda c 0xe1) (utf8ish (raw-bytes st (- c 0xda)))
            (<= 0xe4 c 0xeb) (read-n st (- c 0xe4))
            :else
            (case (int c)
              0xc0 (let [kvs (read-obj st)] (into {} (map vec (partition 2 kvs))))
              0xc1 (set (read-obj st))
              0xc3 (tagged "uuid" [(read-obj st)])
              0xc4 (str (read-obj st))
              0xc5 (str (read-obj st))
              0xc6 (str (BigInteger. ^bytes (read-obj st)))
              0xc7 (let [b (read-obj st) s (read-int st)] (str (java.math.BigDecimal. (BigInteger. ^bytes b) (int s))))
              0xc8 (tagged "inst" [(read-int st)])
              0xc9 (let [ns (read-obj st) n (read-obj st)] (symbol ns n))
              0xca (let [ns (read-obj st) n (read-obj st)] (keyword ns n))
              0xcc (get-in @st [:pcache (read-int st)])
              0xcd (let [o (read-obj st)] (swap! st update :pcache conj o) o)
              0xce (do (swap! st update :pcache conj (read-obj st)) (read-obj st))
              0xd8 (let [chunks (loop [acc [(raw-bytes st (read-int st))]]
                                  (let [c2 (u8 st)]
                                    (cond (<= 0xd0 c2 0xd7) (conj acc (raw-bytes st (- c2 0xd0)))
                                          (= c2 0xd9) (conj acc (raw-bytes st (read-int st)))
                                          (= c2 0xd8) (recur (conj acc (raw-bytes st (read-int st))))
                                          :else (throw (ex-info "fressian: bytes chunk" {})))))]
                     (byte-array (mapcat seq chunks)))
              0xd9 (raw-bytes st (read-int st))
              0xe2 (apply str (map utf8ish (read-string-chunks st (raw-bytes st (read-int st)))))
              0xe3 (utf8ish (raw-bytes st (read-int st)))
              0xec (read-n st (read-int st))
              0xed (read-until-end st)
              0xee (read-until-end st)
              0xef (let [tag (read-obj st) n (read-int st)]
                     (swap! st update :scache conj [tag n]) (read-struct st [tag n]))
              0xf0 (read-struct st (get-in @st [:scache (read-int st)]))
              0xf1 (do (read-obj st) (read-obj st))
              0xf5 true 0xf6 false 0xf7 nil
              0xf9 (double (Float/intBitsToFloat (unchecked-int (raw st 4))))
              0xfa (Double/longBitsToDouble (unchecked-long (raw st 8)))
              0xfb 0.0 0xfc 1.0
              0xb0 (vec (repeatedly (read-int st) #(read-int st)))
              (0xb1 0xb2 0xb3 0xb4 0xb5) (vec (repeatedly (read-int st) #(read-obj st)))
              0xfe (do (swap! st assoc :pcache [] :scache []) (read-obj st))
              (throw (ex-info (str "fressian: unknown code " c) {:code c}))))))))

(defn decode
  "Decode one Fressian-encoded value from bytes (or a hex string)."
  [b]
  (let [b (if (string? b) (hex->bytes b) b)]
    (read-obj (atom {:b b :pos 0 :pcache [] :scache []}))))
