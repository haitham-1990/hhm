# Dual Hedge 2R

Experimental cTrader cBot that launches a matched BUY + SELL cycle on the chart symbol.

- Demo-only by default.
- Requires a hedging account/broker. On a netting account, opposite positions may offset instead of coexisting.
- BUY and SELL market orders are sequential, not truly atomic.
- If the second leg fails, the bot immediately attempts to close the first leg.
- Dynamic stop uses the larger of the minimum stop and ATR(14) × 1.5.
- Take-profit is always stop × reward/risk; default RR is 2.0:1.
- Default total nominal pair risk is 0.20% of equity: 0.10% per leg.
- Actual normalized-volume cash risk is checked with Symbol.AmountRisked before launch.
- After both legs close, the bot waits 60 seconds and may start another pair.
- Default max 20 cycles per UTC day.
- Spread must be no more than 20% of stop distance.
- Because both directions pay spread/fees, opening both sides does not itself create an edge. A whipsaw can stop both legs; a trend can stop one leg and later hit the other leg's 2R target.
- Use demo/backtesting before any live use.
