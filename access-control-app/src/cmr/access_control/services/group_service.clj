(ns cmr.access-control.services.group-service
    "Provides functions for creating, updating, deleting, retrieving, and finding groups."
    (:require [cmr.transmit.metadata-db2 :as mdb]
              [cmr.transmit.echo.tokens :as tokens]
              [cmr.common.concepts :as concepts]
              [cmr.common.services.errors :as errors]
              [cmr.common.mime-types :as mt]
              [cmr.common.validations.core :as v]
              [cmr.transmit.urs :as urs]
              [cmr.access-control.services.group-service-messages :as msg]
              [clojure.edn :as edn]))

(defn- context->user-id
  "Returns user id of the token in the context. Throws an error if no token is provided"
  [context]
  (if-let [token (:token context)]
    (tokens/get-user-id context (:token context))
    (errors/throw-service-error :unauthorized msg/token-required-for-group-modification)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Metadata DB Concept Map Manipulation

(defn- group->mdb-provider-id
  "Returns the provider id to use in metadata db for the group"
  [group]
  (get group :provider-id "CMR"))

(defn- group->new-concept
  "Converts a group into a new concept that can be persisted in metadata db."
  [context group]
  {:concept-type :access-group
   :native-id (:name group)
   ;; Provider id is optional in group. If it is a system level group then it's owned by the CMR.
   :provider-id (group->mdb-provider-id group)
   :metadata (pr-str group)
   :user-id (context->user-id context)
   ;; The first version of a group should always be revision id 1. We always specify a revision id
   ;; when saving groups to help avoid conflicts
   :revision-id 1
   :format mt/edn})

(defn- fetch-group-concept
  "Fetches the latest version of a group concept by concept id"
  [context concept-id]
  (let [{:keys [concept-type provider-id]} (concepts/parse-concept-id concept-id)]
    (when (not= :access-group concept-type)
      (errors/throw-service-error :bad-request (msg/bad-group-concept-id concept-id))))

  (if-let [concept (mdb/get-latest-concept context concept-id false)]
    (if (:deleted concept)
      (errors/throw-service-error :not-found (msg/group-deleted concept-id))
      concept)
    (errors/throw-service-error :not-found (msg/group-does-not-exist concept-id))))

(defn- save-updated-group-concept
  "Saves an updated group concept"
  [context existing-concept updated-group]
  (mdb/save-concept
   context
   (-> existing-concept
       (assoc :metadata (pr-str updated-group)
              :deleted false
              :user-id (context->user-id context))
       (dissoc :revision-date)
       (update :revision-id inc))))

(defn- save-deleted-group-concept
  "Saves an existing group concept as a tombstone"
  [context existing-concept]
  (mdb/save-concept
    context
    (-> existing-concept
        ;; Remove fields not allowed when creating a tombstone.
        (dissoc :metadata :format :provider-id :native-id)
        (assoc :deleted true
               :user-id (context->user-id context))
        (dissoc :revision-date)
        (update :revision-id inc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validations

(defn validate-provider-exists
  "Validates that the groups provider exists."
  [context fieldpath provider-id]
  (when (and provider-id
             (not (some #{provider-id} (map :provider-id (mdb/get-providers context)))))
    {fieldpath [(msg/provider-does-not-exist provider-id)]}))

(defn- validate-members-exist
  "Validation that the given usernames exist"
  [context fieldpath usernames]
  (when-let [non-existant-users (seq (remove #(urs/user-exists? context %) usernames))]
    {fieldpath [(msg/users-do-not-exist non-existant-users)]}))

(defn- create-group-validations
  "Service level validations when creating a group."
  [context]
  {:provider-id #(validate-provider-exists context %1 %2)})

(defn- validate-create-group
 "Validates a group create."
 [context group]
 (v/validate! (create-group-validations context) group))

(defn- update-group-validations
  "Service level validations when updating a group."
  [context]
  [(v/field-cannot-be-changed :name)
   (v/field-cannot-be-changed :provider-id)
   (v/field-cannot-be-changed :legacy-guid)])

(defn- validate-update-group
  "Validates a group update."
  [context existing-group updated-group]
  (v/validate! (update-group-validations context) (assoc updated-group :existing existing-group)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Service level functions

(defn create-group
  "Creates the group by saving it to Metadata DB. Returns a map of the concept id and revision id of
  the created group."
  [context group]
  (validate-create-group context group)
  ;; Check if the group already exists
  (if-let [concept-id (mdb/get-concept-id context
                                          :access-group
                                          (group->mdb-provider-id group)
                                          (:name group))]

    ;; The group exists. Check if its latest revision is a tombstone
    (let [concept (mdb/get-latest-concept context concept-id)]
      (if (:deleted concept)
        ;; The group exists but was previously deleted.
        (save-updated-group-concept context concept group)

        ;; The group exists and was not deleted. Reject this.
        (errors/throw-service-error :conflict (msg/group-already-exists group concept-id))))

    ;; The group doesn't exist
    (mdb/save-concept context (group->new-concept context group))))

(defn get-group
  "Retrieves a group with the given concept id."
  [context concept-id]
  (edn/read-string (:metadata (fetch-group-concept context concept-id))))

(defn delete-group
  "Deletes a group with the given concept id"
  [context concept-id]
  (save-deleted-group-concept context (fetch-group-concept context concept-id)))

(defn update-group
  "Updates an existing group with the given concept id"
  [context concept-id updated-group]
  (let [existing-concept (fetch-group-concept context concept-id)
        existing-group (edn/read-string (:metadata existing-concept))]
    (validate-update-group context existing-group updated-group)
    (save-updated-group-concept context existing-concept updated-group)))

(defn- add-members-to-group
  "Adds the new members to the group handling duplicates."
  [group members]
  (update group
          :members
          (fn [existing-members]
            (vec (distinct (concat existing-members members))))))

(defn add-members
  "Adds members to the group"
  [context concept-id members]
  (let [existing-concept (fetch-group-concept context concept-id)
        existing-group (edn/read-string (:metadata existing-concept))
        updated-group (add-members-to-group existing-group members)]
    (save-updated-group-concept context existing-concept updated-group)))

(defn- remove-members-from-group
  "Removes the members from the group."
  [group members]
  (update group
          :members
          (fn [existing-members]
            (vec (remove (set members) existing-members)))))

(defn remove-members
  "Removes members to the group"
  [context concept-id members]
  (let [existing-concept (fetch-group-concept context concept-id)
        existing-group (edn/read-string (:metadata existing-concept))
        updated-group (remove-members-from-group existing-group members)]
    (save-updated-group-concept context existing-concept updated-group)))

(defn get-members
  "Gets the members in the group."
  [context concept-id]
  (:members (fetch-group-concept context concept-id)))
