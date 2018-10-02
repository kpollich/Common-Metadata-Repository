(ns cmr.umm-spec.umm-g.project
  "Contains functions for parsing UMM-G JSON projects into umm-lib granule modelProjectRefs
  and generating UMM-G JSON projects from umm-lib granule model ProjectRefs."
  (:require
   [cmr.umm-spec.umm-g.instrument :as instrument]
   [cmr.umm-spec.util :as util]
   [cmr.umm.umm-granule :as g])
  (:import cmr.umm.umm_granule.UmmGranule))

(defn umm-g-projects->ProjectRefs
  "Returns the umm-lib granule model ProjectRefs from the given UMM-G Projects."
  [projects]
  (seq (distinct (mapcat :Campaigns projects))))

(defn ProjectRefs->umm-g-projects
  "Returns the UMM-G Projects from the given umm-lib granule model ProjectRefs."
  [project-refs]
  (when (seq project-refs)
    [{:ShortName util/not-provided
      :Campaigns project-refs}]))
