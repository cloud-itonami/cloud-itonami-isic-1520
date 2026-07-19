(ns footwearops.render-html
  "Build-time HTML renderer for docs/samples/operator-console.html.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300).
  Drives the REAL actor stack (footwearops.operation -> footwearops.governor -> footwearops.store).
  No invented numbers, no timestamps, byte-identical across reruns.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [footwearops.store :as store]
            [footwearops.operation :as op]
            [footwearops.phase :as phase]
            [langgraph.graph :as g]))

;; ----------------------------- harness -----------------------------

(def ^:private operator {:actor-id "op-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(def ^:private always-escalate-ops
  "The one op whose own `footwearops.advisor` unconditionally sets
  `:stake :coordination/quality-concern` (see
  `footwearops.advisor/flag-quality-concern` -- never conditional on
  any patch/value field). `footwearops.governor/high-stakes` then
  always escalates that stake regardless of confidence, even when
  governor-clean. Read off the real source, not invented."
  #{:flag-quality-concern})

(defn run-demo!
  "Drives the real FootwearOperationActor StateGraph through a
  scenario built directly from `footwearops.store/sample-data!`'s real
  seeded batches/equipment and `footwearops.governor`'s actual rules
  (this repo's own `footwearops.sim` was run first via `clojure
  -M:dev:run` and checked trustworthy -- every id/op it drives matches
  the real seed data, and every HARD-hold it produces really is HARD
  per governor.cljc; this mirrors the same scenario rather than
  reusing `sim.cljc`'s `-main` directly, to keep this namespace's demo
  self-contained):

    1. `:log-production-batch` batch-001 (clean patch) -- phase-3
       auto-commit (`footwearops.phase`'s only auto-eligible op).
    2. `:flag-quality-concern` concern-1 on equip-001 -- ALWAYS
       escalates (see `always-escalate-ops` above) -> human plant
       supervisor approval -> commit.
    3. `:schedule-maintenance` mnt-1 on equip-001 (verified,
       registered) -- `:schedule-maintenance` is never auto-eligible
       at any phase, a permanent invariant documented in
       `footwearops.phase` -- escalates -> human approval -> commit
       (drafts a real maintenance-schedule record via
       `footwearops.registry`).
    4. `:coordinate-shipment` ship-1 on batch-001 (verified,
       registered, within volume) -- escalates -> human approval ->
       commit (drafts a real shipment-coordination record, and bumps
       batch-001's own `:shipped-volume-pairs`).
    5. `:schedule-maintenance` mnt-2 on equip-002 -- equip-002 is
       seeded UNVERIFIED/unregistered -- HARD hold, rule
       `:equipment-not-verified`.
    6. `:coordinate-shipment` ship-2 on batch-003 -- batch-003 is
       seeded UNVERIFIED/unregistered -- HARD hold, rule
       `:batch-not-verified`.
    7. `:coordinate-shipment` ship-3 on batch-002 (1000 pairs) --
       batch-002 is seeded with `:volume-pairs` 8000.0 and
       `:shipped-volume-pairs` 7500.0, so this claim would exceed it
       -- HARD hold, rule `:shipment-volume-exceeded`.
    8. `:schedule-maintenance` mnt-3 on equip-001 with
       `:direct-operate? true` -- HARD hold, rule
       `:line-operate-blocked` (PERMANENT -- no phase or human
       approval can ever override this, per two independent layers:
       `footwearops.governor`'s unconditional check and
       `footwearops.phase` never listing this op in any `:auto` set).

  Returns the seeded `db` (a `footwearops.store/MemStore`) after the
  run, so `render` can read every value straight off it."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (exec! actor "t1" {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:quality-grade :grade-a :last-assessed "2026-07-19"}})

    (exec! actor "t2" {:op :flag-quality-concern :effect :propose :subject "concern-1"
                        :value {:equipment-id "equip-001" :concern-type :materials-defect
                                :severity :moderate
                                :description "cutting-line upper material irregular tear observed"}})
    (approve! actor "t2")

    (exec! actor "t3" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "equip-001" :maintenance-type :blade-change
                                :scheduled-date "2026-08-01" :direct-operate? false}})
    (approve! actor "t3")

    (exec! actor "t4" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                        :value {:batch-id "batch-001" :volume-pairs 5000.0
                                :destination "buyer-warehouse-north"}})
    (approve! actor "t4")

    (exec! actor "t5" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                        :value {:equipment-id "equip-002" :maintenance-type :mold-calibration
                                :scheduled-date "2026-08-01" :direct-operate? false}})

    (exec! actor "t6" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                        :value {:batch-id "batch-003" :volume-pairs 1000.0
                                :destination "buyer-warehouse-south"}})

    (exec! actor "t7" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                        :value {:batch-id "batch-002" :volume-pairs 1000.0
                                :destination "buyer-warehouse-east"}})

    (exec! actor "t8" {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                        :value {:equipment-id "equip-001" :maintenance-type :emergency-run
                                :scheduled-date "2026-09-01" :direct-operate? true}})

    db))

;; ----------------------------- render helpers -----------------------------

(defn- esc
  "Minimal HTML-escape -- every rendered string passes through this."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- last-fact-for
  "The most recent ledger fact for `subject-id`, off the real
  subject-key field this repo's `commit-fact`/`hold-fact` records use:
  `:subject` (see `footwearops.operation/commit-fact` and
  `footwearops.governor/hold-fact`)."
  [ledger subject-id]
  (last (filter #(= subject-id (:subject %)) ledger)))

(defn- status-cell
  "[css-class label] for the last known ledger fact of a subject."
  [fact]
  (cond
    (nil? fact)                          ["muted" "no activity"]
    (= :committed (:t fact))             ["ok" "committed"]
    (= :approval-granted (:t fact))      ["ok" "approved & committed"]
    (= :governor-hold (:t fact))         ["critical" (str "HARD hold: " (str/join "," (map name (:basis fact))))]
    (= :approval-rejected (:t fact))     ["err" "approval-rejected"]
    (= :approval-requested (:t fact))    ["warn" "awaiting approval"]
    :else                                ["muted" "in progress"]))

(defn- batches-table [db]
  (let [ledger (store/ledger db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>id</th><th>style</th><th>grade</th><th>volume (pairs)</th><th>defect rate</th>\n"
     "<th>verified?</th><th>registered?</th><th>shipped (pairs)</th><th>status</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [b (store/all-batches db)
            :let [fact (last-fact-for ledger (:id b))
                  [cls label] (status-cell fact)]]
        (str "<tr>"
             "<td><code>" (esc (:id b)) "</code></td>"
             "<td>" (esc (:style b)) "</td>"
             "<td><code>" (esc (:quality-grade b)) "</code></td>"
             "<td>" (esc (:volume-pairs b)) "</td>"
             "<td>" (esc (:defect-rate-percent b)) "%</td>"
             "<td>" (if (:verified? b) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (if (:registered? b) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (esc (:shipped-volume-pairs b)) "</td>"
             "<td class=\"" cls "\">" (esc label) "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- equipment-table [db]
  (let [ledger (store/ledger db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>id</th><th>kind</th><th>verified?</th><th>registered?</th><th>last maintenance</th><th>status</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [eq (store/all-equipment db)
            :let [fact (last-fact-for ledger (:id eq))
                  [cls label] (status-cell fact)]]
        (str "<tr>"
             "<td><code>" (esc (:id eq)) "</code></td>"
             "<td><code>" (esc (:kind eq)) "</code></td>"
             "<td>" (if (:verified? eq) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (if (:registered? eq) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (if (:last-maintenance-date eq) (esc (:last-maintenance-date eq)) "&mdash;") "</td>"
             "<td class=\"" cls "\">" (esc label) "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- drafts-table [db]
  (str
   "<table>\n<thead><tr>\n"
   "<th>record_id</th><th>kind</th><th>subject_id</th><th>equipment/batch</th>\n"
   "</tr></thead>\n<tbody>\n"
   (str/join
    "\n"
    (concat
     (for [r (store/maintenance-history db)]
       (str "<tr>"
            "<td><code>" (esc (get r "record_id")) "</code></td>"
            "<td>" (esc (get r "kind")) "</td>"
            "<td><code>" (esc (get r "maintenance_id")) "</code></td>"
            "<td><code>" (esc (get r "equipment_id")) "</code></td>"
            "</tr>"))
     (for [r (store/shipment-history db)]
       (str "<tr>"
            "<td><code>" (esc (get r "record_id")) "</code></td>"
            "<td>" (esc (get r "kind")) "</td>"
            "<td><code>" (esc (get r "shipment_id")) "</code></td>"
            "<td>&mdash;</td>"
            "</tr>"))))
   "\n</tbody></table>"))

(defn- action-gate-table
  "Static op-contract description, sourced from the real
  `footwearops.phase/phases` (phase 3, this actor's `default-phase`)
  and `always-escalate-ops` (grounded in `footwearops.advisor` /
  `footwearops.governor/high-stakes`, see above) -- not invented, just
  rendered."
  []
  (let [ph (get phase/phases phase/default-phase)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>op</th><th>phase-" phase/default-phase " write allowed?</th><th>auto-eligible?</th><th>always escalates (high-stakes)?</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [op (sort phase/write-ops)]
        (str "<tr>"
             "<td><code>" (esc op) "</code></td>"
             "<td>" (if (contains? (:writes ph) op) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (if (contains? (:auto ph) op) "<span class=\"ok\">yes</span>" "no") "</td>"
             "<td>" (if (contains? always-escalate-ops op) "<span class=\"critical\">yes</span>" "no") "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- audit-ledger-table [db]
  (str
   "<table>\n<thead><tr>\n"
   "<th>t</th><th>op</th><th>subject</th><th>disposition</th><th>basis / rule</th>\n"
   "</tr></thead>\n<tbody>\n"
   (str/join
    "\n"
    (for [f (store/ledger db)]
      (str "<tr>"
           "<td>" (esc (:t f)) "</td>"
           "<td><code>" (esc (:op f)) "</code></td>"
           "<td><code>" (esc (:subject f)) "</code></td>"
           "<td class=\""
           (case (:disposition f) :commit "ok" :hold "err" "muted")
           "\">" (esc (:disposition f)) "</td>"
           "<td>" (if (seq (:basis f))
                    (str/join ", " (map (comp esc name) (:basis f)))
                    "&mdash;")
           "</td>"
           "</tr>")))
   "\n</tbody></table>"))

(def ^:private css
  "table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ok { color: #137a3f; }
body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }
header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
h2 { margin-top: 0; font-size: 15px; }
.warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }
main { max-width: 980px; margin: 24px auto; padding: 0 20px; }
header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }
.muted { color: #888; font-size: 13px; }
.critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }
.card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }
th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }
code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }")

(defn render [db]
  (str
   "<!doctype html>\n"
   "<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
   "<title>footwearops.render-html -- Footwear Plant Operations Governor operator console</title>\n"
   "<style>\n" css "\n</style>\n"
   "</head>\n<body>\n"
   "<header class=\"bar\"><h1>Footwear Plant Operations Governor -- Operator Console</h1>"
   "<span class=\"badge\">ISIC 1520 &middot; phase " phase/default-phase " (" (:label (get phase/phases phase/default-phase)) ")</span>"
   "</header>\n"
   "<main>\n"
   "<div class=\"card\">\n<h2>Production batches</h2>\n" (batches-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Equipment</h2>\n" (equipment-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Draft records (maintenance-schedule / shipment-coordination)</h2>\n" (drafts-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Action gate (footwearops.phase &middot; always-escalate-ops)</h2>\n" (action-gate-table) "\n</div>\n"
   "<div class=\"card\">\n<h2>Audit ledger</h2>\n" (audit-ledger-table db) "\n</div>\n"
   "</main>\n"
   "</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out)))
