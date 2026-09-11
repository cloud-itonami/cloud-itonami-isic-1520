(ns footwearops.operation-test
  "Smoke tests for the compiled FootwearOperationActor graph itself
  (build + one happy path per op). The governor's full rule contract
  (HARD holds, escalation, phase gating) is exercised in
  `footwearops.governor-contract-test`; the Store contract in
  `footwearops.store-contract-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [footwearops.operation :as op]
            [footwearops.store :as store]))

(def coordinator {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest test-actor-builds
  (testing "FootwearOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-production-batch-logging-proposal
  (testing "Proposing a production-batch log auto-commits when clean (phase 3, no physical/financial risk)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          initial-ledger-size (count (store/get-ledger s))
          result (exec-op actor "t1"
                          {:op :log-production-batch :effect :propose :subject "batch-001"
                           :patch {:quality-grade :grade-a}}
                          coordinator)
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (= :commit (get-in result [:state :disposition]))))))

(deftest test-maintenance-scheduling
  (testing "Maintenance scheduling always escalates for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t2"
                          {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                           :value {:equipment-id "equip-001" :maintenance-type :blade-change
                                   :scheduled-date "2026-08-01" :direct-operate? false}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t2") [:state :disposition]))))))

(deftest test-quality-concern-escalation
  (testing "Quality concerns always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t3"
                          {:op :flag-quality-concern :effect :propose :subject "concern-1"
                           :value {:equipment-id "equip-001" :concern-type :materials-defect
                                   :severity :moderate :description "dust near cutting press"}}
                          coordinator)]
      (is (= :interrupted (:status result))))))

(deftest test-shipment-coordination-proposal
  (testing "Shipment coordination proposal is submitted and (when within volume) escalates for approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t4"
                          {:op :coordinate-shipment :effect :propose :subject "ship-1"
                           :value {:batch-id "batch-001" :volume-pairs 5000.0
                                   :destination "buyer-warehouse-north"}}
                          coordinator)]
      (is (some? result))
      (is (= :interrupted (:status result))))))

(deftest test-ledger-is-append-only
  (testing "Audit ledger is append-only"
    (let [s (store/mem-store)
          initial-count (count (store/get-ledger s))]
      (store/append-ledger! s {:t :test-entry})
      (is (= (inc initial-count) (count (store/get-ledger s)))))))

(deftest test-records-are-committed
  (testing "The domain-agnostic commit-record! path stores a raw record by :id"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))
