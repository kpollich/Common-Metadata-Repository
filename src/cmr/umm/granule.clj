(ns cmr.umm.granule
  "Defines the UMM Granule record. TODO - add granule info to UMM Overview document.")

(defrecord CollectionRef
  [
   ;; maps to  Granule/Collection/DataSetId in echo granule schema
   entry-title

   ;; maps to Granule/Collection/ShortName
   short-name

   ;;  maps to Granule/Collection/VersionId
   version-id
   ])

(defrecord DataGranule
  [
   ;; maps to  Granule/DataGranule/ProducerGranuleID in echo granule schema
   producer-gran-id

   ;; maps to Granule/DataGranule/DayNight
   day-night

   ;; maps to Granule/DataGranule/ProductionDateTime
   production-date-time
   ])

(defrecord GranuleTemporal
  [
   range-date-time
   single-date-time
   ])

(defrecord OrbitCalculatedSpatialDomain
  [
   orbital-model-name
   orbit-number
   start-orbit-number
   stop-orbit-number
   equator-crossing-longitude
   equator-crossing-date-time

   ])

;; A reference to a product specific attribute in the parent collection. The attribute reference may
;; contain a granule specific value that will override the value in the parent collection for this
;; granule. An attribute with the same name must exist in the parent collection.
(defrecord ProductSpecificAttributeRef
  [
   name
   values
  ])

(defrecord UmmGranule
  [
   ;; maps to Granule/GranuleUR in echo granule schema
   granule-ur

   ;; granule parent
   collection-ref

   data-granule

   temporal

   orbit-calculated-spatial-domains

   ;; references to projects/campaigns
   project-refs

   ;; reference to PSAs in the parent collection
   product-specific-attributes

   ;; maps to Granule/CloudCover in echo granule schema
   ;; TODO - per Granule.xsd it not clear how a specific attrib is selected to set cloud-cover value
   cloud-cover
   ])

(defn collection-ref
  ([entry-title]
   (map->CollectionRef {:entry-title entry-title}))
  ([short-name version-id]
   (map->CollectionRef {:short-name short-name :version-id version-id})))