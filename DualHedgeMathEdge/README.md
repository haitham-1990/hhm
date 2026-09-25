# Dual Hedge Math Edge V6.2

DEMO-first research build based on the pre-crypto V6.1 bot.

## Objective
Measure and improve pair-level expectancy over a 100-pair sample. One BUY+SELL launch is treated as one pair/trial, not two independent trades.

## Markets
- EURUSD
- GBPUSD
- USDJPY
- XAUUSD
- No crypto.

## Entry score
Existing M1/M5 EMA, RSI, tick volume, ATR, compression, breakout and normalized spread are retained.
V6.2 adds Kaufman Efficiency Ratio (ER), ADX-style trend strength, ATR percentile, Bollinger-width expansion, normalized M1/M5 EMA slope, and an M5 return-correlation filter.
Default minimum Math Edge score: 3.75.

## Pair-level exit math
After one hedge leg hits SL:
1. Survivor gets an immediate but looser safety lock, default up to +0.45R. No old early 50% profit-taking.
2. Modeled pair costs are estimated as 2 * spread/stop + 0.05R.
3. Survivor must reach about 1R + modeled costs + 0.10R before the stop is raised to a modeled pair break-even floor.
4. Optional late partial: 25% only after +1.50R.
5. Dynamic trailing starts at +1.65R and never loosens below the modeled pair break-even floor.
6. TP remains 2R.
7. Time-based exit remains OFF by default.

## 100-pair telemetry
For every completed pair the bot logs PairR, pair MFE/MAE, entry score, ER, ADX, ATR percentile and Bollinger expansion.
It also logs cumulative expectancy, pair win/loss count, pair Profit Factor, per-market expectancy/PF, and a special summary at the configured 100-pair target.

This does not guarantee profitability. The purpose is to collect a clean sample and evaluate whether observed expectancy stays positive after spread and execution costs.
