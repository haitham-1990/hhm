# الصقر — SAQR Multi (experimental M1 multi-market cBot)

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
- 120 second cooldown, trading session UTC 07:00–17:00.
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
