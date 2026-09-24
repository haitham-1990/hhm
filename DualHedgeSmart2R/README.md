# Dual Hedge Smart 2R

Experimental DEMO-first cTrader paired-hedge strategy.

## What V2 changes
- Scans EURUSD, GBPUSD, USDJPY and XAUUSD from one cBot instance.
- GOLD is enabled by default. Set `Gold-only test=true` to test only XAUUSD.
- It does NOT open immediately after becoming flat.
- It waits for a specific entry location:
  1. prior M1 true-range compression;
  2. a fresh completed M1 breakout candle;
  3. meaningful breakout body relative to ATR;
  4. breakout not too far from the compressed range (anti-chase);
  5. low transaction spread relative to both ATR and intended stop.
- It chooses the lowest **normalized** spread among qualified markets.
  Raw FX pips and gold dollar spreads are not directly comparable.
- Primary ranking: spread/stop; secondary: spread/ATR; tertiary: setup quality.
- Opens BUY + SELL sequentially on the selected symbol.
- Every leg gets SL = dynamically calculated stop and TP = exactly 2 × SL.
- FX stop: max(1.35 ATR, 4×spread, 4 pips), capped at 15 pips by default.
- GOLD stop: max(1.35 ATR, 4×spread, $0.60), capped at $8 by default.
- Default pair-risk budget: FX 0.20% total; GOLD 0.30% total, split equally by leg.
- Cash risk is checked with `Symbol.AmountRisked`; minimum broker volume is never forced above budget.
- If second hedge leg fails, the bot attempts to close the first immediately.
- Requires a hedging account. Opposite orders are sequential, not atomic.
- Opening both directions does not create a guaranteed edge. Whipsaws can stop both legs and both legs pay spread/fees.
