(ns crux.util
  )

(defn map-over-values [modifier-fn m]
  (into {} (for [[k v] m]
             [k (modifier-fn v)])))

(defn map-over-keys [modifier-fn m]
  (into {} (for [[k v] m]
             [(modifier-fn k) v])))

(defn addmethod-to-multi
  [multifn dispatch-val fn]
  (. multifn addMethod dispatch-val fn))

(defn eval-with-meta [form meta-info]
  (with-meta (eval form)
    (merge {:crux/generated-code form}
           meta-info)))

(defn unquoted?
  "Check if the form is syntax-unquoted: like ~form"
  [form]
  (and (seq? form)
       (= (first form) 'clojure.core/unquote)))

(defn defrecord-dynamically [record-symbol fields]
  (let [cls (eval `(defrecord ~record-symbol ~fields))
        ctor-symbol (symbol (str "map->" record-symbol))]
    {:record-class cls
     :record-symbol record-symbol
     :record-ctor (eval `~ctor-symbol)
     :record-ctor-symbol ctor-symbol}))

(defmacro defrecord-keep-meta [name [& fields-with-meta] & body]
  `(do
     (def ~(symbol (format "-%s-fields-with-meta" name)) '~fields-with-meta)
     (defrecord ~name [~@fields-with-meta]
       ~@body)))
