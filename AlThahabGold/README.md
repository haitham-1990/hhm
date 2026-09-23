# الذهب — cTrader Gold M1 Demo Bot

This is an unvalidated research version for the Fiper XAUUSD (or GOLD) symbol,
on cTrader M1 (entry) and fully closed M5 (confirmation). It is a DIFFERENT
instrument from EURUSD; settings are NOT copied blindly from the FX bot.

## Defaults for initial DEMO testing
- Demo-only safety switch enabled.
- Equity risk 0.5% per order, before unforeseen slippage.
- At most 15 new orders per UTC day; stop after 3 consecutive losses.
- Approximate reference day loss cap: 2% (blocks NEW positions).
- 120 sec cooldown, 12 min time exit, breakeven after 1R.
- M1 EMA9/EMA21 + RSI7 + tick volume + impulse/breakout.
- Fully closed M5 EMA20/EMA50 direction and slope confirmation.
- Live gold bid/ask spread filter. Max spread: 0.70 gold quote USD.
- ATR14 volatility bounds and oversized M1 candle spike filter.
- Dynamic stop: max(2.0 USD, 1.8 * ATR price, 4 * live gold spread), capped at 12 USD.
- Profit target: 1.4 * dynamic stop.
- Extra roundtrip cost default 0.10 USD gold QUOTE price: estimate only,
  NOT a broker fee claim. Check the broker commissions in the CLOSED log.

## Setup
1. Import the .algo in cTrader.
2. Create DEMO instance on your Fiper XAUUSD (or GOLD) symbol, M1.
3. Stop the old bots if you prefer independent equity-based test results.
4. Keep default risk 0.5%. Check the trade log for spread and rejected orders.
5. For controlled comparison, test separately over sufficient periods on the
   same underlying real-tick market data, with actual commission assumptions.

Note: If 0.5% risk gives a volume smaller than broker minimum, the bot SKIPS
the trade; it does NOT increase lot to force an entry.

This bot does not include a genuine news calendar. The candle-spike filter is
not a news filter and cannot prevent gaps, extreme moves, or stop loss overruns.
The daily equity reference can be affected by other positions in the same
trading account and is reconstructed approximately after bot restarts.
No profit target is promised; the strategy is experimental.
