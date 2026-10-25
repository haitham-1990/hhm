using System;
using System.Collections.Generic;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    public enum MarketPreset
    {
        Auto, ForexMajor, ForexOther, Gold, Silver, Indices, Crypto, Energy, Equities, ConservativeOther
    }

    // EMA 9 + anchored tick-volume VWAP, inspired by the supplied educational video.
    // One instance manages ONE symbol. Multiple instances with the same label
    // share account-wide bot limits, with no claim of atomic cross-instance locks.
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class MathEdgeAllMarketsV3 : Robot
    {
        [Parameter("Enable real orders", DefaultValue = false, Group = "01 General")]
        public bool EnableTrading { get; set; }

        [Parameter("Shared portfolio label", DefaultValue = "MATH_EDGE_ALL_V3", Group = "01 General")]
        public string BotLabel { get; set; }

        [Parameter("Asset preset", DefaultValue = MarketPreset.Auto, Group = "01 General")]
        public MarketPreset Preset { get; set; }

        [Parameter("EMA length", DefaultValue = 9, MinValue = 2, Group = "02 Signals")]
        public int EmaPeriod { get; set; }

        [Parameter("ATR length", DefaultValue = 14, MinValue = 2, Group = "02 Signals")]
        public int AtrPeriod { get; set; }

        [Parameter("VWAP reset UTC hour", DefaultValue = 0, MinValue = 0, MaxValue = 23, Group = "02 Signals")]
        public int VwapResetUtcHour { get; set; }

        [Parameter("Minimum session candles", DefaultValue = 12, MinValue = 2, MaxValue = 200, Group = "02 Signals")]
        public int MinimumSessionBars { get; set; }

        [Parameter("H1 EMA50 filter", DefaultValue = false, Group = "02 Signals")]
        public bool H1TrendFilter { get; set; }

        [Parameter("Near-cross entry (experimental)", DefaultValue = false, Group = "02 Signals")]
        public bool EarlyEntry { get; set; }

        [Parameter("Near-cross distance in ATR", DefaultValue = 0.1, MinValue = 0.01, MaxValue = 1, Group = "02 Signals")]
        public double EarlyDistanceAtr { get; set; }

        [Parameter("Equity risk cap per trade %", DefaultValue = 0.25, MinValue = 0.01, MaxValue = 1, Group = "03 Risk")]
        public double MaxRiskPercent { get; set; }

        [Parameter("Portfolio loss stop/day %", DefaultValue = 1.5, MinValue = 0.2, MaxValue = 5, Group = "03 Risk")]
        public double DailyLossPercent { get; set; }

        [Parameter("Entries per symbol/day", DefaultValue = 4, MinValue = 1, MaxValue = 20, Group = "03 Risk")]
        public int MaxSymbolEntries { get; set; }

        [Parameter("Portfolio entries/day", DefaultValue = 8, MinValue = 1, MaxValue = 60, Group = "03 Risk")]
        public int MaxPortfolioEntries { get; set; }

        [Parameter("Portfolio open positions", DefaultValue = 2, MinValue = 1, MaxValue = 10, Group = "03 Risk")]
        public int MaxOpenPositions { get; set; }

        [Parameter("Max spread/ATR (0=Auto)", DefaultValue = 0, MinValue = 0, MaxValue = 3, Group = "03 Risk")]
        public double CustomSpreadToAtr { get; set; }

        [Parameter("Max spread bps (0=Auto)", DefaultValue = 0, MinValue = 0, MaxValue = 200, Group = "03 Risk")]
        public double CustomSpreadBps { get; set; }

        [Parameter("ATR stop x (0=Auto)", DefaultValue = 0, MinValue = 0, MaxValue = 8, Group = "04 Exits")]
        public double CustomAtrStopMultiple { get; set; }

        [Parameter("Extra min stop pips", DefaultValue = 0, MinValue = 0, Group = "04 Exits")]
        public double MinimumStopPips { get; set; }

        [Parameter("Optional fixed take profit", DefaultValue = false, Group = "04 Exits")]
        public bool FixedTakeProfit { get; set; }

        [Parameter("Optional reward/risk", DefaultValue = 1.5, MinValue = 0.5, MaxValue = 10, Group = "04 Exits")]
        public double RewardRisk { get; set; }

        [Parameter("Min bars between entries", DefaultValue = 3, MinValue = 0, MaxValue = 200, Group = "04 Exits")]
        public int CooldownBars { get; set; }

        private ExponentialMovingAverage _ema;
        private AverageTrueRange _atr;
        private Bars _h1;
        private ExponentialMovingAverage _h1Ema;
        private MarketPreset _group;
        private double _assetRisk, _atrStopMultiple, _maxSpreadToAtr, _maxSpreadBps;
        private DateTime _today;
        private int _lastEntryBar = -100000;
        private bool _dailyBlocked;

        protected override void OnStart()
        {
            if (string.IsNullOrWhiteSpace(BotLabel))
            {
                Print("ERROR: shared portfolio label cannot be empty");
                Stop();
                return;
            }
            _group = Preset == MarketPreset.Auto ? DetectPreset(SymbolName) : Preset;
            ConfigurePreset(_group);
            _ema = Indicators.ExponentialMovingAverage(Bars.ClosePrices, EmaPeriod);
            _atr = Indicators.AverageTrueRange(AtrPeriod, MovingAverageType.Exponential);
            if (H1TrendFilter)
            {
                _h1 = MarketData.GetBars(TimeFrame.Hour, SymbolName);
                _h1Ema = Indicators.ExponentialMovingAverage(_h1.ClosePrices, 50);
            }
            Positions.Closed += OnPositionClosed;
            RefreshDay();
            Print("MATH EDGE ALL V3 | {0}, type={1}, timeframe={2}", SymbolName, _group, TimeFrame);
            Print("Risk per trade <= {0:F2}% equity; spread <= {1:F2} ATR and {2:F2} bps; ATR SL x{3:F2}; active={4}",
                Math.Min(MaxRiskPercent, _assetRisk), _maxSpreadToAtr, _maxSpreadBps, _atrStopMultiple, EnableTrading);
            Print("Attach to a separate chart per symbol. Use same label on each instance for portfolio guardrails.");
            if (TimeFrame != TimeFrame.Minute3)
                Print("NOTE: the reel example used M3, while this chart is {0}.", TimeFrame);
            if (!EnableTrading)
                Print("SAFE DEFAULT: trading disabled until Enable real orders is turned on.");
        }

        protected override void OnStop()
        {
            Positions.Closed -= OnPositionClosed;
        }

        protected override void OnTick()
        {
            RefreshDay();
            if (!EnableTrading)
                return;
            if (PortfolioLossLimitHit())
            {
                if (!_dailyBlocked)
                    Print("Portfolio loss guard triggered: no new entries until the next UTC day.");
                _dailyBlocked = true;
                // The current instance closes only its own symbol. The same-label instances
                // on other symbols must also be running for their positions to be closed.
                CloseThisSymbolPositions();
            }
        }

        protected override void OnBarClosed()
        {
            if (!EnableTrading)
                return;
            RefreshDay();
            int current = Bars.Count - 1;
            int previous = current - 1;
            if (previous < Math.Max(50, Math.Max(EmaPeriod, AtrPeriod)) + 2)
                return;

            double close = Bars.ClosePrices.Last(0);
            double ema = _ema.Result.Last(0);
            double prevEma = _ema.Result.Last(1);
            double atr = _atr.Result.Last(0);
            if (!Valid(close) || !Valid(ema) || !Valid(prevEma) || !Valid(atr) || atr <= 0)
                return;

            // Exit rule in the user-supplied video: a FULL candle closes through EMA9.
            // Always evaluate exits before any new-entry filter.
            bool exited = false;
            foreach (var position in Positions.FindAll(BotLabel, SymbolName))
            {
                bool shouldExit = position.TradeType == TradeType.Buy ? close < ema : close > ema;
                if (!shouldExit)
                    continue;
                TradeResult result = ClosePosition(position);
                if (!result.IsSuccessful)
                {
                    Print("EMA close exit rejected: {0}", result.Error);
                    return;
                }
                exited = true;
                Print("EMA exit: {0} on {1}", position.TradeType, SymbolName);
            }
            if (exited)
                return;
            if (_dailyBlocked || PortfolioLossLimitHit())
                return;
            if (!Symbol.IsTradingEnabled || !Symbol.MarketHours.IsOpened())
                return;
            if (SessionBarCount(current) < MinimumSessionBars)
                return;
            if (SessionDate(Bars.OpenTimes[current]) != SessionDate(Bars.OpenTimes[previous]))
                return;

            double vwap = SessionVwap(current);
            double prevVwap = SessionVwap(previous);
            if (!Valid(vwap) || !Valid(prevVwap))
                return;

            bool buyCross = prevEma <= prevVwap && ema > vwap && close > ema;
            bool sellCross = prevEma >= prevVwap && ema < vwap && close < ema;

            // Video says "before or at the cross", but offers no numeric near-cross rule.
            // This is an original optional approximation, disabled by default.
            double gap = ema - vwap, previousGap = prevEma - prevVwap;
            bool earlyBuy = EarlyEntry && previousGap < 0 && gap < 0 && gap > previousGap &&
                Math.Abs(gap) <= atr * EarlyDistanceAtr && close > ema && close > Bars.ClosePrices.Last(1);
            bool earlySell = EarlyEntry && previousGap > 0 && gap > 0 && gap < previousGap &&
                Math.Abs(gap) <= atr * EarlyDistanceAtr && close < ema && close < Bars.ClosePrices.Last(1);

            bool buy = buyCross || earlyBuy, sell = sellCross || earlySell;
            if (buy == sell)
                return;
            TradeType direction = buy ? TradeType.Buy : TradeType.Sell;
            if (!PassesH1(direction))
                return;
            if (Positions.FindAll(BotLabel, SymbolName).Length > 0)
                return;
            if (Positions.FindAll(BotLabel).Length >= MaxOpenPositions)
                return;
            if (EntriesToday(SymbolName) >= MaxSymbolEntries || EntriesToday(null) >= MaxPortfolioEntries)
                return;
            if (current - _lastEntryBar < CooldownBars)
                return;

            double pip = Symbol.PipSize;
            double midPrice = (Symbol.Ask + Symbol.Bid) * 0.5;
            double spread = Symbol.Ask - Symbol.Bid;
            if (!Valid(midPrice) || !Valid(spread) || midPrice <= 0 || pip <= 0 || spread <= 0)
                return;
            double spreadAtr = spread / atr;
            double spreadBps = spread / midPrice * 10000.0;
            if (spreadAtr > _maxSpreadToAtr || spreadBps > _maxSpreadBps)
            {
                Print("Blocked {0}: spread/ATR {1:F2}/{2:F2}, spread bps {3:F2}/{4:F2}",
                    SymbolName, spreadAtr, _maxSpreadToAtr, spreadBps, _maxSpreadBps);
                return;
            }

            double brokerMinimum = Symbol.MinDistanceType == SymbolMinDistanceType.Pips
                ? Symbol.MinStopLossDistance
                : (midPrice * Symbol.MinStopLossDistance / 100.0) / pip;
            double stopPips = Math.Max(Math.Max(MinimumStopPips, 1.10 * brokerMinimum),
                Math.Max(2.5 * spread / pip, _atrStopMultiple * atr / pip));
            if (!Valid(stopPips) || stopPips <= 0)
                return;
            double? takeProfitPips = FixedTakeProfit ? (double?)(stopPips * RewardRisk) : null;
            double tradeRiskPercent = Math.Min(MaxRiskPercent, _assetRisk);
            double rawVolume = Symbol.VolumeForProportionalRisk(
                ProportionalAmountType.Equity, tradeRiskPercent, stopPips, RoundingMode.Down);
            if (!Valid(rawVolume) || rawVolume <= 0)
                return;
            double volume = Symbol.NormalizeVolumeInUnits(
                Math.Min(rawVolume, Symbol.VolumeInUnitsMax), RoundingMode.Down);
            if (!Valid(volume) || volume < Symbol.VolumeInUnitsMin)
            {
                Print("Order skipped: broker minimum volume would exceed risk budget.");
                return;
            }
            double margin = Symbol.GetEstimatedMargin(direction, volume);
            if (!Valid(margin) || margin > Account.FreeMargin)
            {
                Print("Order skipped: insufficient free margin.");
                return;
            }

            TradeResult order = ExecuteMarketOrder(direction, SymbolName, volume, BotLabel, stopPips, takeProfitPips);
            if (order.IsSuccessful)
            {
                _lastEntryBar = current;
                Print("Entry: {0} {1} volume {2} SL {3:F1} pips; risk {4:F2}%; fixed TP {5}",
                    direction, SymbolName, volume, stopPips, tradeRiskPercent, FixedTakeProfit);
            }
            else
                Print("Order rejected: {0}", order.Error);
        }

        private bool Valid(double x) { return !double.IsNaN(x) && !double.IsInfinity(x); }

        private bool PassesH1(TradeType direction)
        {
            if (!H1TrendFilter) return true;
            if (_h1 == null || _h1.Count < 53) return false;
            double h1Price = _h1.ClosePrices.Last(1);
            double h1Ema = _h1Ema.Result.Last(1);
            return direction == TradeType.Buy ? h1Price > h1Ema : h1Price < h1Ema;
        }

        private DateTime SessionDate(DateTime openUtc) { return openUtc.AddHours(-VwapResetUtcHour).Date; }

        private int SessionBarCount(int index)
        {
            DateTime day = SessionDate(Bars.OpenTimes[index]);
            int count = 0;
            for (int i = index; i >= 0 && SessionDate(Bars.OpenTimes[i]) == day; i--)
                count++;
            return count;
        }

        private double SessionVwap(int index)
        {
            DateTime day = SessionDate(Bars.OpenTimes[index]);
            double pv = 0, vol = 0;
            int i = index;
            for (; i >= 0 && SessionDate(Bars.OpenTimes[i]) == day; i--)
            {
                double ticks = Bars.TickVolumes[i];
                if (ticks <= 0) continue;
                double typical = (Bars.HighPrices[i] + Bars.LowPrices[i] + Bars.ClosePrices[i]) / 3.0;
                pv += typical * ticks;
                vol += ticks;
            }
            // Fail closed if the loaded chart begins in the middle of this session.
            return i < 0 || vol <= 0 ? double.NaN : pv / vol;
        }

        private void RefreshDay()
        {
            DateTime day = Server.Time.Date;
            if (_today == day) return;
            _today = day;
            _dailyBlocked = false;
        }

        private double PortfolioDailyNet()
        {
            double net = 0;
            foreach (var trade in History.FindAll(BotLabel))
                if (trade.ClosingTime.Date == _today)
                    net += trade.NetProfit;
            foreach (var position in Positions.FindAll(BotLabel))
                net += position.NetProfit;
            return net;
        }

        private bool PortfolioLossLimitHit()
        {
            if (_dailyBlocked) return true;
            double limit = Math.Max(Account.Balance, 1.0) * DailyLossPercent / 100.0;
            return PortfolioDailyNet() <= -limit;
        }

        private int EntriesToday(string symbolOrNull)
        {
            var positionIds = new HashSet<int>();
            foreach (var trade in History.FindAll(BotLabel))
                if (trade.EntryTime.Date == _today &&
                    (symbolOrNull == null || trade.SymbolName == symbolOrNull))
                    positionIds.Add(trade.PositionId);
            foreach (var pos in Positions.FindAll(BotLabel))
                if (pos.EntryTime.Date == _today &&
                    (symbolOrNull == null || pos.SymbolName == symbolOrNull))
                    positionIds.Add(pos.Id);
            return positionIds.Count;
        }

        private void CloseThisSymbolPositions()
        {
            foreach (var position in Positions.FindAll(BotLabel, SymbolName))
            {
                var close = ClosePosition(position);
                if (!close.IsSuccessful)
                    Print("Portfolio risk close failed: {0}", close.Error);
            }
        }

        private void OnPositionClosed(PositionClosedEventArgs e)
        {
            if (e.Position.Label != BotLabel) return;
            if (PortfolioLossLimitHit()) _dailyBlocked = true;
        }

        private void ConfigurePreset(MarketPreset p)
        {
            // Risk %, spread/ATR ceiling, maximum bid/ask spread in basis points,
            // protective ATR multiple. Unrecognised instruments use strict defaults.
            switch (p)
            {
                case MarketPreset.ForexMajor: _assetRisk=.25; _maxSpreadToAtr=.38; _maxSpreadBps=2.5; _atrStopMultiple=1.8; break;
                case MarketPreset.ForexOther: _assetRisk=.20; _maxSpreadToAtr=.32; _maxSpreadBps=5; _atrStopMultiple=2.0; break;
                case MarketPreset.Gold: _assetRisk=.20; _maxSpreadToAtr=.35; _maxSpreadBps=12; _atrStopMultiple=2.2; break;
                case MarketPreset.Silver: _assetRisk=.15; _maxSpreadToAtr=.35; _maxSpreadBps=15; _atrStopMultiple=2.4; break;
                case MarketPreset.Indices: _assetRisk=.15; _maxSpreadToAtr=.40; _maxSpreadBps=12; _atrStopMultiple=2.2; break;
                case MarketPreset.Crypto: _assetRisk=.10; _maxSpreadToAtr=.30; _maxSpreadBps=20; _atrStopMultiple=2.8; break;
                case MarketPreset.Energy: _assetRisk=.15; _maxSpreadToAtr=.40; _maxSpreadBps=15; _atrStopMultiple=2.5; break;
                case MarketPreset.Equities: _assetRisk=.15; _maxSpreadToAtr=.35; _maxSpreadBps=20; _atrStopMultiple=2.2; break;
                default: _assetRisk=.10; _maxSpreadToAtr=.25; _maxSpreadBps=5; _atrStopMultiple=2.5; break;
            }
            if (CustomSpreadToAtr > 0) _maxSpreadToAtr = CustomSpreadToAtr;
            if (CustomSpreadBps > 0) _maxSpreadBps = CustomSpreadBps;
            if (CustomAtrStopMultiple > 0) _atrStopMultiple = CustomAtrStopMultiple;
        }

        private MarketPreset DetectPreset(string symbolName)
        {
            string s = (symbolName ?? "").ToUpperInvariant();
            if (s.StartsWith("XAU") || s.StartsWith("GOLD")) return MarketPreset.Gold;
            if (s.StartsWith("XAG") || s.StartsWith("SILVER")) return MarketPreset.Silver;

            string[] cryptocurrencies = { "BTC","ETH","SOL","XRP","DOGE","ADA","BNB","LTC","BCH","DOT","AVAX" };
            foreach (var a in cryptocurrencies) if (s.StartsWith(a)) return MarketPreset.Crypto;
            string[] energies = { "USOIL","UKOIL","WTI","BRENT","XTI","XBR","NATGAS","NGAS","CRUDE" };
            foreach (var a in energies) if (s.StartsWith(a)) return MarketPreset.Energy;
            string[] indices = { "US30","US100","US500","NAS100","NAS","SPX","SP500","GER40","DE40","DAX","UK100","JP225","AUS200","HK50","USTEC","DJ30","DJI","FRA40" };
            foreach (var a in indices) if (s.StartsWith(a)) return MarketPreset.Indices;
            string[] fxMajors = { "EURUSD","GBPUSD","USDJPY","USDCHF","AUDUSD","USDCAD","NZDUSD" };
            foreach (var a in fxMajors) if (s.StartsWith(a)) return MarketPreset.ForexMajor;
            string[] ccy = { "USD","EUR","GBP","JPY","CHF","AUD","CAD","NZD","SEK","NOK","DKK","ZAR","TRY","MXN","SGD","HKD","PLN","CNH" };
            if (s.Length >= 6)
            {
                string x=s.Substring(0,3), y=s.Substring(3,3);
                if (x != y && Array.IndexOf(ccy,x)>=0 && Array.IndexOf(ccy,y)>=0)
                    return MarketPreset.ForexOther;
            }
            string[] stocks = { "SLV","GLD","SPY","QQQ","AAPL","TSLA","MSFT","NVDA","AMZN","META","GOOG","GOOGL","NFLX","AMD","IBM","TSM","VTI" };
            foreach (var a in stocks)
                if (s == a || s.StartsWith(a+".") || s.StartsWith(a+"_"))
                    return MarketPreset.Equities;
            return MarketPreset.ConservativeOther;
        }

        protected override void OnError(Error error)
        {
            Print("Trading error: {0}", error.Code);
        }
    }
}
