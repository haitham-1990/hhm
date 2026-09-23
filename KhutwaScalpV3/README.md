# Khutwa Scalp V3 (EXPERIMENTAL / DEMO-ONLY by default)

## Setup
- cTrader, account Demo, EURUSD, timeframe M1.
- **STOP** V2/V2.1/V2.2 before installing V3; do not let versions trade together.
- Install the .algo from GitHub Actions' build artifact and choose cTrader.
- Enable cloud bot on Demo only. By default, V3 refuses a live account.

## What changed
- Minute (M1) entry indicators: EMA 9/21, RSI 7, ATR14, tick volume, breakout or impulse.
- Confirm direction using **fully closed M5** EMA 20/50 and M5 fast EMA slope.
- EURUSD: live bid-ask spread plus a configurable **estimated** non-spread round-trip cost.
- Adaptive stop: max(4 pips, 1.3*ATR, 3*spread), capped at 10 pips; target 1.5R.
- Sizing is based on current equity and a conservative reserve for estimated cost.
- Limit 25 new trades/day, stop after 3 losing trades, 2.5% reference daily loss,
  60-second cooldown, max 10-minute hold, protective breakeven after +0.9R.
- Diagnostic OPEN/CLOSED event logs include quote spread, slippage and broker-reported commission.
- Daily counts and loss streak are reconstructed from the broker history on restart.
- No automatic news filter; do not assume news volatility is screened.

## Why these settings?
Heuristic starting settings, NOT proven superior or profit-making.
Test using historical real ticks + actual Fiper Demo trading conditions, then
out-of-sample testing and forward testing before drawing conclusions.
More trades can increase commission/slippage and losses.

## Important risk assumptions
The extra commission field is only an estimate (default 0.05 pip).
Verify against your actual account: a history row with $0 commission for one
trade doesn't prove all trading is free. Spread is variable.
The bot blocks *new* orders at risk limits; existing positions retain stop-loss.
The daily account-equity reference may be affected by other manual positions
and is reconstructed approximately after restart.
All risk controls are best effort: slippage, gaps and minimum lot sizes may
cause losses greater than intended. Small balances may not be tradable at all.
