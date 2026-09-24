# الصقر — SAQR Quant V2 (continuous M1 multi-market DEMO research bot)

This cBot monitors **EURUSD, GBPUSD, USDJPY and XAUUSD** (symbol names are editable
to match broker suffixes) using fully closed **M1** bars for entry and fully
closed **M5** EMA 20/50 bars for direction. It compares qualifying markets
after market-specific execution-cost and ATR filters and places a trade on just
one market at a time. This cross-symbol selection score is a **heuristic**, not
a trained model or a proven expected-return estimate.

## Initial default demo profile
- Demo-only switch is ON. A detected live account causes the bot to stop.
- 0.5% calculated risk per trade, adjusted conservatively for quoted spread
  and **estimated** extra round-turn cost. Actual slippage, spread, fees and gaps
  may cause realised risk to be higher.
- **ONE total open SAQR position** across all symbols, never one per symbol.
- Up to 15 trades per UTC day. Stop new entries after 3 consecutive losses.
- 2% **approximate** daily equity loss threshold. Blocks NEW entries only:
  existing positions keep broker SL/TP and time management; this is not
  a guaranteed liquidation limit, particularly with restarts/other trades.
- 120 second cooldown, scanner operates UTC 00:00–24:00 (all hours while each broker's market is open). A symbol may be skipped if closed, too expensive, too volatile or signal is absent.
- FX dynamic stop (pips): max(4, 1.4*ATR14, 3*spread), capped at 14.
- GOLD dynamic stop (quoted USD/oz): max(2, 1.8*ATR14, 4*spread), capped at 12.
- M1 candle spike, ATR, spread/ATR and transaction cost/stop filters.
- FX target 1.45R; GOLD target 1.40R; breakeven after 1.0R; timed exits.
- Print OPEN/CLOSE logs with instrument, spread, estimated fees, broker-reported
  commission, fill slippage, reason for exit and realised PnL.
- Rebuild daily counters/history when the cBot restarts. Equity baseline is
  reconstructed approximately from account equity and bot trading history.

## Installation and safeguards
1. Download the .algo from this repository's successful GitHub Actions artifact.
2. Install in cTrader on **DEMO**, start **ONE SAQR instance**.
3. STOP other scalp/gold bots first for clean comparison, and do not leave
   overlapping manual positions on tracked assets.
4. SAQR can be attached to any chart: it fetches M1/M5 for all configured
   tracked instruments. Check exact broker symbols (e.g. an XAUUSD suffix).
5. Check Logs for "SAQR tracking" to verify every asset is actually detected.
6. Check your actual Fiper commission. Default cost reserves are **PLACEHOLDERS**,
   not validated broker charges, and can change whether a trade qualifies.
7. If broker minimum tradable units imply >0.5% nominal risk, SAQR skips the order;
   it never forces minimum lot at larger risk.

## Research limitations
A successful compilation does not establish correct trading behaviour or
profitability. Before deploying beyond DEMO, perform real-tick backtesting
with broker-realistic spread/fees, then out-of-sample and forward tests for
at least several market regimes. There is NO economic-news event calendar,
so the candle spike filter cannot protect against sudden data releases.
Concurrent separate bots/accounts do not share these limits.

## SAQR Quant V2 modifications (separate version label)
- `SAQR-Quant-V2` label for fresh forward-test stats. **Stop the prior SAQR bot** before
  installing V2; only ONE bot should trade this group of instruments on the account.
- Scan 24 hours *while the cTrader cloud instance is running AND each broker
  instrument is open*. Market hours, weekends, outages and safety filters can
  prevent trades. The scanner remains running after winning trades.
- Entry volume/risk and cost geometry are recalculated immediately before orders.
- New ATR-normalized mathematical gates: M1 EMA distance / M1 ATR >= 0.10;
  M5 EMA distance / M5 ATR >= 0.12; candle body / M1 ATR >= 0.12.
  Each is based on the latest completed bar. They are heuristics, **not**
  statistically verified win probabilities.
- Expected TARGET after *estimated* round-trip cost divided by stop plus
  estimated cost: `netRR = (rewardRisk - estimatedCost/stop) /
  (1 + estimatedCost/stop)`. Default minimum is 1.02.
- Simplified modeled breakeven hit rate (NOT observed hit rate):
  `pBE = (1 + estimatedCost/stop) / (1 + rewardRisk)`, default maximum 52%.
  These are mechanical payoff ratios, NOT promises of execution quality or profit.
- Dimensionless cross-asset candidate scoring combines signal score, relative
  M1 and M5 trend strength, candle body/ATR, normalized tick volume and cost/stop.
- Every 5 minutes, a HEARTBEAT prints counters, running state and block status.
  When signals are rejected, diagnostics display an available reason per market.
- Cooldown default 60 sec, max daily orders default 30; both adjustable.
  Daily **2% reference loss limit and 3-loss streak circuit breakers remain**.
  They may block NEW orders until the next UTC day, but scanning/heartbeat continue.
  This protects against infinite trading; it is not a guaranteed max-loss limit.
- No externally sourced economic calendar/news guard is installed.
- Never infer validated profitability from a mathematical formula; perform
  broker-condition backtesting and out-of-sample DEMO forward testing.

## SAQR Quant V3 10X — low-risk concurrent portfolio
- New label: `SAQR-Quant-V3-10X`; stop prior SAQR instances before forward testing.
- Default nominal risk per new trade is **0.10% of equity**, not 0.50%.
- Up to **10 SAQR positions open simultaneously** across the tracked instruments.
- Up to **3 open SAQR positions per symbol**.
- Conservative nominal portfolio cap: **1.00%** (= at most 10 x 0.10% configured risk).
- Same-symbol entries are spaced by at least **60 seconds**.
- Multiple independent qualifying markets can be entered during the same scan.
- If broker minimum volume would require more risk than the calculated low-risk
  position, the order is skipped; the cBot does not force a minimum lot.
- Gold can therefore be skipped frequently on a small account if one XAUUSD unit
  is too large for the 0.10% risk budget.
- Daily loss and losing-streak circuit breakers remain enabled. They stop NEW
  entries but do not stop the scanner/timer or remove existing SL/TP protection.
- Configured risk is only an estimate: slippage, gaps, spread, commissions,
  broker minimum volumes and execution can produce a different realised loss.

## SAQR Quant V4 Adaptive — reduced-risk mode + GOLD-SAFE
- New label: `SAQR-Quant-V4-Adaptive`.
- At **2% estimated daily loss**, new-trade risk is multiplied by **0.50**
  instead of stopping immediately. Example: base 0.10% becomes 0.05%.
- At **4% estimated daily loss**, the emergency circuit breaker stops NEW entries
  for the rest of the UTC day. Position management/heartbeat continue.
- The existing max-trades and consecutive-loss circuit breakers still apply.
- GOLD gets an additional **0.50 risk multiplier**, so normal gold risk defaults
  to 0.05% and reduced-mode gold risk defaults to 0.025%.
- GOLD requires a higher default signal score (4.25), max 1 open gold position,
  300s gold cooldown, and pauses gold for the UTC day after 2 consecutive gold losses.
- Every order now verifies approximate **cash risk at the actual normalized volume**
  using cTrader `Symbol.AmountRisked(volume, stopLossPips)`. If broker minimum volume
  makes the stop-risk exceed the configured cash budget by more than 10%, the trade
  is skipped. This is particularly important for XAUUSD on small accounts.
- GOLD reward/risk default is 1.60. This is an experimental forward-test setting,
  not evidence of profitability.
