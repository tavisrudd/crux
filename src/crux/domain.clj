(ns crux.domain
  (:require [slingshot.slingshot :refer (throw+)])
  (:require [crux.util
             :refer (eval-with-meta
                     unquoted?
                     defrecord-dynamically
                     defrecord-keep-meta)]))

(defprotocol ICruxSpec
  (-validate-spec [this]))

(defrecord-keep-meta CruxDefaults
    [^{:type map.of.Symbol->AbstractFieldType} field-types
     ^{:type list.of.Fn->AbstractFieldType} type-inference-rules])

(defrecord-keep-meta EventReductionSpec
    [^{:type :Keyword} name
     ^{:type :variable} args])

(defrecord-keep-meta EventSpec
    [^{:type Symbol} name
     ^{:type list.of.Symbol} fields
     ^{:type list.of.EventReductionSpec} reduce-forms
     ^{:type Fn } reducer
     ^{:type CruxDefaults} defaults
     ])

(defrecord-keep-meta CommandSpec
    [^{:type Symbol} name
     ^{:type list.of.Symbol} fields
     ^{:type CruxDefaults} defaults
     ])

(defn -validate-entity-spec [entity-spec]
  (when-not (and (vector? (:fields entity-spec))
                 (every? symbol? (:fields entity-spec)))
    (throw+ {:type :crux/invalid-entity-fields
             :msg (str entity-spec)}))
  (doseq [[event-symbol event-map] (:events entity-spec)]
    (when-not (and (vector? (:fields event-map))
                   (every? symbol? (:fields event-map)))
      (throw+ {:type :crux/invalid-event-fields
               :msg (str event-map)})))
  entity-spec)

(defrecord-keep-meta EntitySpec
    [^{:type Symbol} name
     ^{:type Symbol} plural-name
     ^{:type CruxDefaults} defaults
     ^{:type Map} id-field
     ^{:type list.of.Symbol} fields
     ^{:type list.of.EntityProperty} properties
     ^{:type map.of.Symbol->EventSpec} events
     ^{:type map.of.Symbol->CommandSpec} commands]

  ICruxSpec
  (-validate-spec [this] (-validate-entity-spec this)))

(defn -validate-domain-spec [domain-spec]
  ;; it's better to do this in each of the macros so the error
  ;; reporting is closer to the original source of the error
  (doseq [[entity-symbol entity-spec] (:entities domain-spec)]
    (-validate-spec entity-spec))
  domain-spec)

(defrecord-keep-meta DomainSpec
    [^{:type Symbol} name
     ^{:type CruxDefaults} defaults
     ^{:type map.of.Symbol->EntitySpec} entities]

  ICruxSpec
  (-validate-spec [this] (-validate-domain-spec this)))

;;;;
;;; Crux DDL: minor fns/macros

(defmacro create-domain [initial-spec & body]
  `(do
     (assert (instance? DomainSpec ~initial-spec))
     (-> ~initial-spec ~@body -validate-domain-spec)))

(defmacro defdomain [domain-name & body]
  `(def ~domain-name
     (create-domain (map->DomainSpec {:name '~domain-name
                                      :defaults (map->CruxDefaults {})})
       ~@body)))

(defn -quote-or-unquote-fields-form [fields owner]
  (cond
    (vector? fields) `'[~@fields]
    (unquoted? fields) (second fields)
    :else (throw+
           (java.lang.IllegalArgumentException.
            (format "Crux: Illegal fields def '%s' for %s"
                    fields owner)))))

(defmacro entity [domain-spec
                  entity-name entity-fields & body]
  (let [fields (-quote-or-unquote-fields-form entity-fields entity-name)]
    `(let [entity-spec# (map->EntitySpec {:defaults (:defaults ~domain-spec)
                                          :name ~(name entity-name)
                                          :fields ~fields})]
       (update-in ~domain-spec [:entities] assoc '~entity-name
                  (-> entity-spec# ~@body -validate-spec)))))

(defn- throw-aready-declared-error [declaration-type sym owner-type owner]
  (throw+
   (java.lang.IllegalArgumentException.
    (format "Crux: %s '%s' already declared for %s '%s'"
            declaration-type sym owner-type owner))))

(defn- throw-reduce-forms-required-error [event-symbol]
  (throw+
   (java.lang.IllegalArgumentException.
    (format "Crux: %s '%s' requires a reduce form"
            event-symbol))))

(defn gen-event-reducer [entity-symbol event-symbol event-fields forms]
  (let [event-reducer-symbol (symbol (format "%s-%s-reduce"
                                             entity-symbol
                                             event-symbol))]
    (eval-with-meta
      `(fn ~event-reducer-symbol [entity#
                                  {:keys [~@event-fields] :as event#}]
         (let [~(symbol "entity") entity#
               ~(symbol "event") event#
               {~(symbol "entity-id") :oid} entity#
               {~(symbol "event-id") :oid} event#]
           (-> entity#
               ~@forms)))
      {:crux/generated-from 'crux.domain/gen-event-reducer
       :doc (format
             "Event reducer generated by crux for `%s%s`"
             entity-symbol
             event-symbol)})))



(defn gen-single-constraint-checker [entity-spec form]
  (let [property-symbols (keys (:properties entity-spec))
        fn-form `(fn constraint-checker [entity#]
                   (let [~(symbol "entity") entity#
                         entity-properties# (:crux/properties entity#)
                         {:keys [~@property-symbols]}
                         (into {} (for [[k# v#] entity-properties#]
                                    [(keyword k#) v#]))]
                     (-> entity#
                         ~form)))]
    
    (eval-with-meta fn-form
      {:doc (format
             "Function generated by crux for entity `%s`: constraint `%s`"
             (:name entity-spec)
             form)
       :crux/generated-from 'crux.domain/gen-single-constraint-checker})))

(defn event* [entity-spec
              {:keys [event-symbol command-symbol event-fields
                      additional-event-attrs reduce-forms]
               :as event-spec}]

  (let [entity-symbol (:name entity-spec)]
    
    (when (get-in entity-spec [:events event-symbol])
      (throw-aready-declared-error
       'event event-symbol 'entity entity-symbol))

    (when-not reduce-forms
      (throw-reduce-forms-required-error event-symbol))

    (when (get-in entity-spec [:commands command-symbol])
      (throw-aready-declared-error
       'command command-symbol 'entity entity-symbol))

    (let [command-constraints (:constraints additional-event-attrs)
          event-spec (map->EventSpec
                      {:defaults (:defaults entity-spec)
                       :name event-symbol
                       :fields event-fields
                       :command-constraints command-constraints
                       :additional-event-attrs additional-event-attrs
                       :reduce-forms reduce-forms})
          reducer (gen-event-reducer entity-symbol event-symbol
                                     event-fields reduce-forms)
          command-spec (assoc (map->CommandSpec event-spec)
                         :name command-symbol
                         :event event-symbol)
          event-spec (assoc event-spec :constraints
                            (into (array-map)
                                  (for [form command-constraints]
                                    [form
                                     (gen-single-constraint-checker entity-spec
                                                                    form)])))
          event-spec (assoc event-spec :reducer reducer)]
      (-> entity-spec
          (update-in [:events] assoc event-symbol event-spec)
          (update-in [:commands] assoc command-symbol command-spec)))))

(defn- quote-event-spec-form
  [[event-symbol command-symbol event-fields & rem]]
  (let [event-fields (-quote-or-unquote-fields-form
                      event-fields event-symbol)
        [additional-event-attrs
         reduce-forms] (if (map? (first rem))
                         [(first rem) (rest rem)]
                         [nil rem])]
    (when-not reduce-forms
      (throw-reduce-forms-required-error event-symbol))

    `{:event-symbol '~event-symbol
      :command-symbol '~command-symbol
      :event-fields ~event-fields
      :additional-event-attrs '~additional-event-attrs
      :reduce-forms '~reduce-forms})) 

(defmacro events [entity-spec & event-specs]
  (let [event-specs (into [] (map quote-event-spec-form event-specs))]
    `(doall (reduce event* ~entity-spec ~event-specs))))

;;;;;;;;;
;;; Crux DDL: minor fns/macros

(defn field-types* [m field-types-map]
  (update-in m [:defaults :field-types] merge field-types-map))

(defmacro field-types
  [m field-types-map]
  (cond
    (unquoted? field-types-map) `(field-types* ~m ~(second field-types-map))
    (map? field-types-map) `(field-types* ~m '~field-types-map)
    :else (throw+
           (java.lang.IllegalArgumentException.
            (format "Crux: Illegal field-types '%s'"
                    field-types-map)))))

(defn plural* [entity-spec plural-symbol]
  (assoc entity-spec :plural plural-symbol))

(defmacro plural [entity-spec plural-symbol]
  `(plural* ~entity-spec '~plural-symbol))

(defn type-infer-rule [entity-spec regex abstract-type]
  (update-in
   entity-spec [:defaults :type-inference-rules]
   conj [regex abstract-type]))

;; (get-prop entity)

(defn gen-entity-property-fn
  [entity-symbol property-symbol entity-properties form]
  ;; NOTE: Allow the entity# record to have a :crux/properties and that
  ;; would be a map of {Sym Fn}
  (eval-with-meta
    `(fn ~property-symbol [entity#]
       (let [~(symbol "entity")  entity#]
         ~form))
    {:doc (format "Property %s generated by crux for %s"
                  property-symbol entity-symbol)
     :crux/generated-by 'crux.domain/gen-entity-property-fn}))

(defmacro properties [entity-spec & property-pairs]
  ;;TODO: normalize property-pairs
  `(let [properties-map# (into {} (map #(into [] %)
                                       (partition 2 '~property-pairs)))]
     (-> ~entity-spec
         (update-in [:property-forms] merge properties-map#)
         (update-in [:properties] merge
                    (into {}
                          (for [[sym# form#] properties-map#]
                            [sym# (gen-entity-property-fn
                                   (:name ~entity-spec)
                                   sym# properties-map# form#)]))))))

(defn id-field* [entity-spec id-field-name & [id-type]]
  (assoc entity-spec :id-field {:name id-field-name
                                :type (or id-type :guid)}))
(defmacro id-field [entity-spec id-field-name & [id-type]]
  `(id-field* ~entity-spec '~id-field-name ~id-type))

(defmacro command-validators [entity-spec & actions] entity-spec)

(defn first-unmet-constraint [entity constraint-forms+preds]
  (some (fn -or* [[form pred]] (if-not (pred entity) form))
        constraint-forms+preds))

(defn unmet-constraints [entity constraint-forms+preds]
  (for [[form pred] constraint-forms+preds
        :when (not (pred entity))]
    form))
