# MATH EDGE — EMA 9 + VWAP (cTrader)

This is an **original interpretation** of the public Instagram description "EMA 9 + VWAP, automated." It does **not** reproduce the unpublished ROBOQUANT strategy.

- Timeframe: intended for M5; supports other chart timeframes.
- Buy: confirmed EMA9 cross above session VWAP, with candle close above VWAP.
- Sell: reverse of buy.
- Session VWAP: typical candle price weighted by **tick volume**, reset each UTC day.
- SL: 1.5 ATR(14), minimum 8 pips.
- TP: 1.5 × risk.
- Risk: target 0.5% equity per trade; max 6 entries/day; 2% bot daily loss cutoff. Slippage and gaps can exceed risk limits.
- Optional H1 EMA50 trend filter, off by default.
- **Order placement disabled by default.** Switch `Enable automatic orders` on only after demo/backtesting.

## Build
Download the compiled `.algo` from this branch's GitHub Actions artifact under the job **Build MATH EDGE EMA9 VWAP**.

Manual:
```bash
dotnet build MATH_EDGE_EMA9_VWAP/MATH_EDGE_EMA9_VWAP.csproj -c Release
```

The project uses the official `cTrader.Automate` NuGet package. No API key, AI service, or trading credentials are required by the bot. Trading has material capital-loss risk; compile success is not performance testing.
