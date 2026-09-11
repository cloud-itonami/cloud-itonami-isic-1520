(ns footwearops.store
  "SSoT for the footwear plant-operations coordination actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every `cloud-itonami-isic-*` actor in this fleet uses.

  Scope note: unlike some sibling actors, this build ships a single
  `MemStore` backend only (atom of EDN) -- the deterministic default
  for dev/tests/demo, no deps. This vertical is self-contained (no
  external footwear capability library, no jurisdiction-scoped Datomic
  parity requirement driving a second backend); a `langchain.db`-backed
  store can be added later behind the same protocol without changing
  any caller.

  Four kinds of entity live here:
    - `batches`          -- the central entity. A production batch's
                             cutting/lasting/assembly output: quality-
                             grade/volume/defect-rate record.
                             `:verified?` marks whether the batch's own
                             claims have actually been QC-inspected
                             (never inferred from a routine intake
                             patch); `:registered?` marks whether it is
                             on file in the plant's production ledger;
                             `:shipped-volume-pairs` tracks the batch's
                             own cumulative-shipped ground truth.
    - `equipment`         -- a cutting/sewing/molding unit's own
                             record. `:verified?`/`:registered?` track
                             whether it has actually been
                             inspected/commissioned and is on file --
                             the same ground-truth discipline as
                             `batches`.
    - `maintenance`       -- a scheduled cutting/sewing/molding-
                             equipment maintenance-window DRAFT against
                             a piece of equipment
                             (`footwearops.registry`'s
                             `register-maintenance`). Dedicated
                             `:scheduled?` double-schedule guard (never
                             a `:status` value).
    - `shipments`         -- a proposed outbound-footwear-shipment
                             DRAFT (`footwearops.registry`'s
                             `register-shipment`).

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which batch was logged, which
  maintenance was scheduled against a verified/registered equipment
  unit, which shipment was coordinated and at what
  independently-recomputed volume, approved by whom, which quality
  concern was flagged' is always a query over an immutable log -- the
  audit trail a factory owner or footwear buyer trusting this
  coordinator needs."
  (:require [footwearops.registry :as registry]))

(defprotocol Store
  (batch [s id])
  (all-batches [s])
  (equipment-unit [s id])
  (all-equipment [s])
  (maintenance [s id])
  (all-maintenance [s])
  (shipment [s id])
  (quality-concerns [s] "the append-only quality-concern log")
  (ledger [s])
  (maintenance-history [s] "the append-only maintenance-schedule history (footwearops.registry drafts)")
  (shipment-history [s] "the append-only shipment-coordination history (footwearops.registry drafts)")
  (next-maintenance-sequence [s] "next maintenance-number sequence")
  (next-shipment-sequence [s] "next shipment-number sequence")
  (maintenance-already-scheduled? [s maintenance-id] "has this maintenance window already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-batches [s batches] "replace/seed the batch directory (map id->batch)")
  (with-equipment [s equipment] "replace/seed the equipment directory (map id->equipment)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-batches []
  {"batch-001" {:id "batch-001" :quality-grade :grade-a :style "running-shoe-v3"
                :volume-pairs 50000.0 :defect-rate-percent 2.0
                :verified? true :registered? true
                :shipped-volume-pairs 10000.0
                :last-assessed "2026-06-01"}
   "batch-002" {:id "batch-002" :quality-grade :grade-b :style "canvas-sneaker-classic"
                :volume-pairs 8000.0 :defect-rate-percent 5.0
                :verified? true :registered? true
                :shipped-volume-pairs 7500.0
                :last-assessed "2026-06-01"}
   "batch-003" {:id "batch-003" :quality-grade :irregular :style "leather-boot-heritage"
                :volume-pairs 60000.0 :defect-rate-percent 12.0
                :verified? false :registered? false
                :shipped-volume-pairs 0.0
                :last-assessed "2026-05-15"}})

(defn- sample-equipment []
  {"equip-001" {:id "equip-001" :kind :cutting-press
                :verified? true :registered? true
                :last-maintenance-date "2026-05-01"}
   "equip-002" {:id "equip-002" :kind :injection-molder
                :verified? false :registered? false
                :last-maintenance-date nil}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-maintenance!
  "Backend-agnostic `:maintenance/schedule` -- drafts the
  maintenance-schedule record via `footwearops.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s maintenance-id equipment-id]
  (let [seq-n (next-maintenance-sequence s)
        result (registry/register-maintenance maintenance-id equipment-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :maintenance-number (get result "maintenance_number")}}))

(defn- propose-shipment!
  "Backend-agnostic `:shipment/propose` -- drafts the
  shipment-coordination record via `footwearops.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s shipment-id]
  (let [seq-n (next-shipment-sequence s)
        result (registry/register-shipment shipment-id seq-n)]
    {:result result
     :patch {:shipment-number (get result "shipment_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (batch [_ id] (get-in @a [:batches id]))
  (all-batches [_] (sort-by :id (vals (:batches @a))))
  (equipment-unit [_ id] (get-in @a [:equipment id]))
  (all-equipment [_] (sort-by :id (vals (:equipment @a))))
  (maintenance [_ id] (get-in @a [:maintenance id]))
  (all-maintenance [_] (sort-by :id (vals (:maintenance @a))))
  (shipment [_ id] (get-in @a [:shipments id]))
  (quality-concerns [_] (:quality-concerns @a))
  (ledger [_] (:ledger @a))
  (maintenance-history [_] (:maintenance-history @a))
  (shipment-history [_] (:shipment-history @a))
  (next-maintenance-sequence [_] (:maintenance-sequence @a 0))
  (next-shipment-sequence [_] (:shipment-sequence @a 0))
  (maintenance-already-scheduled? [_ maintenance-id]
    (boolean (get-in @a [:maintenance maintenance-id :scheduled?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :batch/upsert)
      (swap! a update-in [:batches (first path)] merge (assoc value :id (first path)))

      (= effect :maintenance/schedule)
      (let [maintenance-id (first path)
            equipment-id (:equipment-id value)
            {:keys [result patch]} (schedule-maintenance! s maintenance-id equipment-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :maintenance-sequence (fnil inc 0))
                       (update-in [:maintenance maintenance-id] merge (assoc value :id maintenance-id) patch)
                       (update :maintenance-history registry/append result)
                       (update-in [:equipment equipment-id :last-scheduled-maintenance-date]
                                  (fn [_prev] (:scheduled-date value))))))
        result)

      (= effect :quality-concern/flag)
      (let [concern-id (first path)
            concern (assoc value :id concern-id)]
        (swap! a update :quality-concerns conj concern)
        concern)

      (= effect :shipment/propose)
      (let [shipment-id (first path)
            batch-id (:batch-id value)
            {:keys [result patch]} (propose-shipment! s shipment-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :shipment-sequence (fnil inc 0))
                       (update-in [:shipments shipment-id] merge (assoc value :id shipment-id) patch)
                       (update :shipment-history registry/append result)
                       (update-in [:batches batch-id :shipped-volume-pairs]
                                  (fn [prev]
                                    (+ (double (or prev 0.0))
                                       (double (or (:volume-pairs value) 0.0))))))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above (also what `sawmilling`-style siblings expose as their
      ;; own low-level commit path).
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-batches [s batches] (when (seq batches) (swap! a assoc :batches batches)) s)
  (with-equipment [s equipment] (when (seq equipment) (swap! a assoc :equipment equipment)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:batches {} :equipment {} :maintenance {} :shipments {}
                      :records {} :quality-concerns []
                      :ledger [] :maintenance-sequence 0 :maintenance-history []
                      :shipment-sequence 0 :shipment-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained batch + equipment
  set -- one verified+registered batch with shipping headroom
  (schedulable), one verified+registered batch that is nearly fully
  shipped (a small new shipment blows through its own logged volume --
  HARD hold), one UNVERIFIED/unregistered batch (blocks any shipment
  coordinated against it); one verified+registered cutting-press unit
  (schedulable for maintenance), one UNVERIFIED/unregistered injection-
  molder unit (blocks any maintenance scheduling against it) -- so the
  actor + demo + tests run offline. Returns `s` (thread-friendly with
  `->`)."
  [s]
  (with-batches s (sample-batches))
  (with-equipment s (sample-equipment))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
