# MATH EDGE EMA9 + VWAP VIDEO V2

**Based on the user's uploaded 82-second strategy reel, not a claim of proprietary strategy equivalence.**

## What the reel actually displays
- Main timeframe: **3 minutes**.
- Indicators: VWAP and EMA9.
- Entry: "right before or when the VWAP + EMA9 cross".
- Technical exit: when a candle **closes through EMA9 after the cross**.
- Alternative "-25% to -30%" loss: the reel does not specify its denominator; this may be an **options-premium** threshold, not acceptable as account risk. It has **NOT** been implemented for spot/CFDs.
- No unambiguous fixed profit target shown.
- Backtest / financial results in video are promotional, **not verified**.

## Implementation and differences
- By default, entries wait for a **closed candle confirming the EMA9 / VWAP cross**. No future data.
- `Allow near-cross entry (experimental)` is **off by default**. If enabled, it uses an **original approximation**: approaching line difference, small ATR-relative gap, price confirmation. The original video's timing specification is not precise enough to replicate exactly.
- Exits when a fully closed candle crosses EMA9 against the position (buy: close BELOW EMA; sell: close ABOVE EMA).
- Fixed take profit is **off** by default, allowing EMA9 exit to follow trend; optional R:R TP available if preferred.
- Initial protective ATR-based SL is ALWAYS set, daily bot loss control, spread/margin checks, trade frequency limit.
- Position risk defaults to **0.5% of equity**; this is a target, not a guarantee under slippage/gaps.
- VWAP is **tick-volume** weighted in cTrader, not exchange true volume, and resets at configurable UTC hour (default 00:00). Choose session hour appropriate for instrument, e.g. US equities.
- For the video timeframe, open a **3-minute cTrader chart**. Code runs on whatever chart timeframe is selected and warns if not M3.
- **Orders disabled by default**: set `Enable automatic orders=true` on a demo account to test.

## GitHub Actions
Workflow `Build MATH EDGE EMA9 VWAP` builds a .NET 6 cTrader `.algo` and publishes the artifact named `MATH_EDGE_EMA9_VWAP_VIDEO_V2_algo`.

**Safety:** This compiles as a trading algorithm; it is not a verified profitable system. Backtest and forward-test on a demo account before real capital.
