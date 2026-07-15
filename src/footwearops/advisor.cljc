(ns footwearops.advisor
  "FootwearAdvisor -- the *contained intelligence node* for the
  footwear plant-operations coordination actor.

  It normalizes production-batch patches (quality-grade/volume/
  defect-rate), drafts a cutting/sewing/molding-equipment maintenance
  scheduling proposal against a piece of equipment, drafts a
  materials-defect/labor-safety/labeling-compliance quality-concern
  flag, and drafts an outbound-footwear-shipment coordination proposal
  against a production batch. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record and NEVER a real cutting/lasting/
  assembly-line-equipment actuation or freight dispatch. Every output
  is censored downstream by `footwearops.governor` before anything
  touches the SSoT -- see README `What this actor does NOT do`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `footwearops.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:batch/upsert :maintenance/schedule
                                 ; :quality-concern/flag
                                 ; :shipment/propose} propose-shaped
                                 ; effects, NEVER a direct cutting/
                                 ; lasting/assembly-line-equipment-
                                 ; control effect
     :stake      kw|nil         ; :coordination/quality-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) --
  `footwearops.governor` HARD-holds any request that doesn't, so a
  mis-wired caller can never reach a commit path even if this advisor
  were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [footwearops.registry :as registry]
            [footwearops.store :as store]
            [langchain.model :as model]))

(defn- log-production-batch
  "Production-batch intake upsert -- the advisor only normalizes/
  validates the patch; it does not invent the batch's quality-grade,
  volume, defect-rate or verification status. High confidence, low
  stakes -- administrative logging, not an operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "生産バッチ記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :batch/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- schedule-maintenance
  "Draft a cutting/sewing/molding-equipment maintenance-window
  scheduling proposal against a piece of equipment. The advisor
  reports what it can see (equipment verified?/registered?) in its
  rationale, but `footwearops.governor` NEVER trusts this report -- it
  independently re-derives verified?/registered? from the equipment's
  own stored fields before any commit is possible."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        eq (store/equipment-unit db equipment-id)
        ready? (and eq (registry/equipment-ready? eq))]
    {:summary    (str subject " 向け保守作業予定提案 (" (:maintenance-type value) ")"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (if eq
                   (str "equipment-verified?=" (registry/equipment-verified? eq)
                        " equipment-registered?=" (registry/equipment-registered? eq)
                        " direct-operate?=" (boolean (:direct-operate? value)))
                   (str equipment-id " が見つかりません"))
     :cites      (if eq [equipment-id] [])
     :effect     :maintenance/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? (not (:direct-operate? value))) 0.9 0.3)}))

(defn- flag-quality-concern
  "Draft a materials-defect/labor-safety/labeling-compliance
  quality-concern flag. ALWAYS `:stake :coordination/quality-concern`
  -- a quality concern is NEVER a proposal the advisor may quietly
  downgrade to low-stakes, and it is never gated on the referenced
  equipment/batch being verified (a concern can be raised about ANY
  equipment or batch, verified or not -- see README `What this actor
  does NOT do` re: never blocking safety/compliance-relevant reporting
  on an administrative technicality). See `footwearops.phase`: no
  phase ever adds this op to a phase's `:auto` set;
  `footwearops.governor` also always escalates on
  `:coordination/quality-concern`. Two independent layers agree,
  deliberately."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        eq (and equipment-id (store/equipment-unit db equipment-id))]
    {:summary    (str subject " 向け品質懸念報告 (" (:concern-type value) "/" (:severity value) ")"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (str "concern-type=" (:concern-type value)
                      " severity=" (:severity value)
                      " description=" (:description value))
     :cites      (if eq [equipment-id] [])
     :effect     :quality-concern/flag
     :value      value
     :stake      :coordination/quality-concern
     :confidence 0.9}))

(defn- coordinate-shipment
  "Draft an outbound-footwear-shipment coordination proposal against a
  production batch. The advisor passes through the caller's own
  claimed volume -- it does NOT invent one, and `footwearops.governor`
  NEVER trusts it: it independently recomputes whether the batch's own
  cumulative-shipped volume plus this claim would exceed the batch's
  own recorded volume before any commit is possible."
  [db {:keys [subject value]}]
  (let [batch-id (:batch-id value)
        b (store/batch db batch-id)
        ready? (and b (registry/batch-ready? b))
        over-volume? (and b (registry/shipment-volume-exceeded?
                             b (:volume-pairs value)))]
    {:summary    (str subject " 向け出荷調整提案 ("
                      (:volume-pairs value) " 足)"
                      (when b (str " batch=" batch-id)))
     :rationale  (if b
                   (str "batch-verified?=" (registry/batch-verified? b)
                        " batch-registered?=" (registry/batch-registered? b)
                        " over-volume?=" over-volume?)
                   (str batch-id " が見つかりません"))
     :cites      (if b [batch-id] [])
     :effect     :shipment/propose
     :value      value
     :stake      nil
     :confidence (if (and ready? (not over-volume?)) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-production-batch      (log-production-batch db request)
    :schedule-maintenance      (schedule-maintenance db request)
    :flag-quality-concern      (flag-quality-concern db request)
    :coordinate-shipment       (coordinate-shipment db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは製靴工場プラント運用コーディネーターの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:batch/upsert|:maintenance/schedule|"
       ":quality-concern/flag|:shipment/propose) "
       ":stake(:coordination/quality-concern か nil) :confidence(0..1)。\n"
       "重要: 未検証または未登録の設備・バッチに対する作業を提案してはいけません。"
       "裁断・成形・組立ライン設備の直接操作を絶対に提案してはいけません"
       "(この actor は提案のみを行い、実行は一切行いません)。"
       "出荷量を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-production-batch       {:batch (store/batch st subject)}
    :schedule-maintenance       {:equipment (store/equipment-unit st (:equipment-id value))}
    :flag-quality-concern        {:equipment (and (:equipment-id value)
                                                   (store/equipment-unit st (:equipment-id value)))}
    :coordinate-shipment        {:batch (store/batch st (:batch-id value))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `footwearops.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule maintenance,
  auto-flag a concern, or auto-coordinate a shipment."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :footwear-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
