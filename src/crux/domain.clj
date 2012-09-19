(ns crux.domain
  (:require [crux.internal.keys :refer :all])
  (:require [slingshot.slingshot :refer (throw+)])
  (:require [crux.util
             :refer (eval-with-meta
                     unquoted?
                     defrecord-dynamically
                     defrecord-keep-meta)]))

(defn check [{:keys [entity event user]}
             validation-value error-msg]
  (when (not validation-value)
    error-msg))

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
  (when-not (and (vector? (FIELDS entity-spec))
                 (every? symbol? (FIELDS entity-spec)))
    (throw+ {:type :crux/invalid-entity-fields
             :msg (str entity-spec)}))
  (doseq [[event-symbol event-map] (EVENTS entity-spec)]
    (when-not (and (vector? (FIELDS event-map))
                   (every? symbol? (FIELDS event-map)))
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
  (doseq [[entity-symbol entity-spec] (ENTITIES domain-spec)]
    (-validate-spec entity-spec))
  domain-spec)

(defrecord DomainSpec
    [^{:type Symbol} name
     ^{:type CruxDefaults} defaults
     ^{:type map.of.Symbol->EntitySpec} entities
     ;; event-records: crux.reify/blah
     ;; event-store: crux.reify.savant
     ^{:type '{Symbol Symbol}} reifications]

  ICruxSpec
  (-validate-spec [this] (-validate-domain-spec this)))

;;;;
;;; Crux DDL: minor fns/macros

(defn -ensure-is-crux-domain-spec [m]
  (if (instance? DomainSpec m)
    m
    (map->DomainSpec m)))

(defn -seed-domain-with-crux-defaults [domain-spec]
  (if-let [provided-defaults (DEFAULTS domain-spec)]
    (if-not (instance? CruxDefaults provided-defaults)
      (assoc domain-spec DEFAULTS (map->CruxDefaults provided-defaults))
      domain-spec)
    (assoc domain-spec DEFAULTS (map->CruxDefaults {}))))

(defmacro create-domain [initial-spec & body]
  `(do
     (assert (:name ~initial-spec))
     (-> ~initial-spec
         -ensure-is-crux-domain-spec
         -seed-domain-with-crux-defaults
         ~@body
         -validate-domain-spec)))

(defmacro defdomain [domain-name & body]
  `(def ~domain-name
     (create-domain {:name ~domain-name}
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
    `(let [entity-spec# (map->EntitySpec {DEFAULTS (DEFAULTS ~domain-spec)
                                          :name ~(name entity-name)
                                          FIELDS ~fields})]
       (-> ~domain-spec
           (update-in [ENTITIES] assoc '~entity-name
                      (-> entity-spec# ~@body -validate-spec))))))

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


(defn gen-single-command-validator [entity-spec event-spec form]
  (let [property-symbols (keys (PROPERTIES entity-spec))
        event-fields (FIELDS event-spec)
        validator-fn-symbol (symbol (format "%s-%s-validate"
                                            (:name event-spec)
                                            (:name entity-spec)))
        fn-form `(fn ~validator-fn-symbol
                   [validator-args#]
                   (let [~(symbol "check") crux.domain/check
                         {:keys [~(symbol "event")
                                 ~(symbol "entity")
                                 ~(symbol "user")]} validator-args#
                                 entity-properties# (CRUX-PROPERTIES
                                                     ~(symbol "entity"))
                                 {:keys [~@property-symbols]}
                                 (into {} (for [[k# v#] entity-properties#]
                                            [(keyword k#) v#]))
                                 {:keys [~@event-fields]} ~(symbol "event")]
                     (-> validator-args#
                         ~form)))]

    (eval-with-meta fn-form
      {:doc (format
             "Command Validator generated by crux for command `%s`: `%s`"
             (:name event-spec)
             form)
       :crux/generated-from 'crux.domain/gen-single-command-validator})))

(defn gen-single-constraint-checker [entity-spec form]
  (let [property-symbols (keys (PROPERTIES entity-spec))
        fn-form `(fn constraint-checker [entity#]
                   (let [~(symbol "entity") entity#
                         entity-properties# (CRUX-PROPERTIES entity#)
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

    (when (get-in entity-spec [EVENTS event-symbol])
      (throw-aready-declared-error
       'event event-symbol 'entity entity-symbol))

    (when-not reduce-forms
      (throw-reduce-forms-required-error event-symbol))

    (let [command-constraints (CONSTRAINTS additional-event-attrs)
          command-validations (VALIDATIONS additional-event-attrs)
          reducer (gen-event-reducer entity-symbol event-symbol
                                     event-fields reduce-forms)
          
          event-spec (map->EventSpec
                      {DEFAULTS (DEFAULTS entity-spec)
                       :name event-symbol
                       EVENT-SYMBOL event-symbol
                       COMMAND-SYMBOL command-symbol
                       FIELDS event-fields
                       COMMAND-CONSTRAINT-FORMS command-constraints
                       COMMAND-CONSTRAINTS
                       (into (array-map)
                             (for [form command-constraints]
                               [form
                                (gen-single-constraint-checker entity-spec
                                                               form)]))
                       COMMAND-VALIDATION-FORMS command-validations
                       ADDITIONAL-EVENT-ATTRS additional-event-attrs
                       REDUCER reducer
                       REDUCE-FORMS reduce-forms})
          event-spec (assoc event-spec COMMAND-VALIDATIONS
                           (into (array-map)
                             (for [form command-validations]
                               [form
                                (gen-single-command-validator entity-spec
                                                              event-spec
                                                               form)])))]
      (-> entity-spec
          (update-in [EVENTS] assoc event-symbol event-spec)))))

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

    `{EVENT-SYMBOL '~event-symbol
      COMMAND-SYMBOL '~command-symbol
      :event-fields ~event-fields
      ADDITIONAL-EVENT-ATTRS '~additional-event-attrs
      REDUCE-FORMS '~reduce-forms}))

(defmacro events [entity-spec & event-specs]
  (let [event-specs (into [] (map quote-event-spec-form event-specs))]
    `(doall (reduce event* ~entity-spec ~event-specs))))

;;;;;;;;;
;;; Crux DDL: minor fns/macros

(defn field-types* [m field-types-map]
  (update-in m [DEFAULTS :field-types] merge field-types-map))

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
   entity-spec [DEFAULTS :type-inference-rules]
   conj [regex abstract-type]))

;; (get-prop entity)

(defn gen-entity-property-fn
  [entity-symbol property-symbol entity-properties form]
  ;; NOTE: Allow the entity# record to have a CRUX-PROPERTIES and that
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
         (update-in [PROPERTY-FORMS] merge properties-map#)
         (update-in [PROPERTIES] merge
                    (into {}
                          (for [[sym# form#] properties-map#]
                            [sym# (gen-entity-property-fn
                                   (:name ~entity-spec)
                                   sym# properties-map# form#)]))))))

(defn id-field* [entity-spec id-field-name & [id-type]]
  (assoc entity-spec ID-FIELD {:name id-field-name
                                :type (or id-type :guid)}))
(defmacro id-field [entity-spec id-field-name & [id-type]]
  `(id-field* ~entity-spec '~id-field-name ~id-type))

(defn first-unmet-constraint [entity constraint-forms+preds]
  (some (fn -or* [[form pred]] (if-not (pred entity) form))
        constraint-forms+preds))

(defn unmet-constraints [entity constraint-forms+preds]
  (for [[form pred] constraint-forms+preds
        :when (not (pred entity))]
    form))

(defn unmet-validations [validation-args validation-forms+vfns]
  (for [[form vfn] validation-forms+vfns
        [error-message] [[(vfn validation-args)]]
        :when (not (nil? error-message))]
    {:error-message error-message
     :validation-form form}))

(defn first-unmet-validation [validation-args validation-forms+vfns]
  (first (unmet-validations validation-args validation-forms+vfns)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

