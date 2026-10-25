using System;
using System.Collections.Generic;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    // Video-informed version (M3, EMA9/VWAP cross, candle-close EMA9 exit).
    // Not the proprietary ROBOQUANT implementation. VWAP uses available tick volume.
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class MathEdgeEmaVwap : Robot
    {
        [Parameter("Enable automatic orders", DefaultValue = false, Group = "01 - Trading")]
        public bool EnableTrading { get; set; }

        [Parameter("Bot label", DefaultValue = "MATH_EDGE_EMA9_VWAP", Group = "01 - Trading")]
        public string BotLabel { get; set; }

        [Parameter("EMA period", DefaultValue = 9, MinValue = 2, Group = "02 - Signal")]
        public int EmaPeriod { get; set; }

        [Parameter("Require H1 EMA 50 trend", DefaultValue = false, Group = "02 - Signal")]
        public bool UseH1TrendFilter { get; set; }

        [Parameter("H1 trend EMA period", DefaultValue = 50, MinValue = 2, Group = "02 - Signal")]
        public int H1TrendPeriod { get; set; }

        [Parameter("VWAP reset UTC hour", DefaultValue = 0, MinValue = 0, MaxValue = 23, Group = "02 - Signal")]
        public int VwapResetUtcHour { get; set; }

        [Parameter("Allow near-cross entry (experimental)", DefaultValue = false, Group = "02 - Signal")]
        public bool EnableEarlyApproachEntry { get; set; }

        [Parameter("Pre-cross max distance (ATR x)", DefaultValue = 0.10, MinValue = 0.01, MaxValue = 1.0, Group = "02 - Signal")]
        public double EarlyCrossMaxAtrFraction { get; set; }

        [Parameter("Exit on reverse signal", DefaultValue = true, Group = "02 - Signal")]
        public bool ExitOnReverse { get; set; }

        [Parameter("Risk % of equity / trade", DefaultValue = 0.5, MinValue = 0.01, MaxValue = 3, Group = "03 - Risk")]
        public double RiskPercent { get; set; }

        [Parameter("Daily bot loss stop (%)", DefaultValue = 2.0, MinValue = 0.1, MaxValue = 10, Group = "03 - Risk")]
        public double DailyLossLimitPercent { get; set; }

        [Parameter("Max open bot positions (all markets)", DefaultValue = 3, MinValue = 1, MaxValue = 20, Group = "03 - Risk")]
        public int MaxTotalBotPositions { get; set; }

        [Parameter("Portfolio daily bot loss stop (%)", DefaultValue = 1.5, MinValue = 0.2, MaxValue = 10, Group = "03 - Risk")]
        public double PortfolioDailyLossPercent { get; set; }

        [Parameter("Max portfolio entries / UTC day", DefaultValue = 12, MinValue = 1, MaxValue = 100, Group = "03 - Risk")]
        public int MaxTotalEntriesPerDay { get; set; }

        [Parameter("Maximum entries / UTC day", DefaultValue = 6, MinValue = 1, MaxValue = 30, Group = "03 - Risk")]
        public int MaxEntriesPerDay { get; set; }

        [Parameter("Absolute spread cap pips (0=auto)", DefaultValue = 0.0, MinValue = 0.0, Group = "03 - Risk")]
        public double MaxSpreadPips { get; set; }

        [Parameter("ATR period", DefaultValue = 14, MinValue = 2, Group = "04 - Exits")]
        public int AtrPeriod { get; set; }

        [Parameter("Stop = ATR x", DefaultValue = 1.5, MinValue = 0.5, MaxValue = 8, Group = "04 - Exits")]
        public double AtrStopMultiplier { get; set; }

        [Parameter("Minimum stop (pips)", DefaultValue = 8.0, MinValue = 0.1, Group = "04 - Exits")]
        public double MinStopPips { get; set; }

        [Parameter("Enable fixed take profit", DefaultValue = false, Group = "04 - Exits")]
        public bool UseFixedTakeProfit { get; set; }

        [Parameter("Fixed TP reward / risk", DefaultValue = 1.5, MinValue = 0.5, MaxValue = 10, Group = "04 - Exits")]
        public double RewardRiskRatio { get; set; }

        [Parameter("Minimum bars between entries", DefaultValue = 2, MinValue = 0, MaxValue = 100, Group = "04 - Exits")]
        public int CooldownBars { get; set; }

        [Parameter("Auto market profile", DefaultValue = true, Group = "05 - Multi-market")]
        public bool AutoMarketProfile { get; set; }

        [Parameter("Spread / ATR maximum", DefaultValue = 0.30, MinValue = 0.01, MaxValue = 0.5, Group = "05 - Multi-market")]
        public double MaxSpreadAtrFraction { get; set; }

        [Parameter("Maximum ATR stop multiplier", DefaultValue = 3.0, MinValue = 1, MaxValue = 10, Group = "05 - Multi-market")]
        public double MaxAtrStopMultiplier { get; set; }

        private double _profileSpreadPips;
        private double _profileRiskPercent;
        private double _profileMinStopPips;
        private double _profileDailyLossLimit;
        private ExponentialMovingAverage _ema;
        private AverageTrueRange _atr;
        private Bars _h1Bars;
        private ExponentialMovingAverage _h1TrendEma;
        private DateTime _day;
        private double _realizedToday;
        private double _portfolioRealizedToday;
        private bool _dayBlocked;
        private int _lastEntryBar = -1000000;

        protected override void OnStart()
        {
            if (string.IsNullOrWhiteSpace(BotLabel))
            {
                Print("Bot label must not be empty.");
                Stop();
                return;
            }

            ConfigureMarketProfile();
            _ema = Indicators.ExponentialMovingAverage(Bars.ClosePrices, EmaPeriod);
            _atr = Indicators.AverageTrueRange(AtrPeriod, MovingAverageType.Exponential);

            if (UseH1TrendFilter)
            {
                _h1Bars = MarketData.GetBars(TimeFrame.Hour, SymbolName);
                _h1TrendEma = Indicators.ExponentialMovingAverage(_h1Bars.ClosePrices, H1TrendPeriod);
            }

            Positions.Closed += OnBotPositionClosed;
            ResetDayIfNeeded();
            Print("MATH EDGE EMA/VWAP: {0}, timeframe={1}, enabled={2}", SymbolName, TimeFrame, EnableTrading);
            Print("VWAP uses tick volume and resets at {0}:00 UTC.", VwapResetUtcHour);
            Print("Portfolio risk cap shared by label {0}: max {1} simultaneous positions, {2}% UTC-day bot drawdown.", BotLabel, MaxTotalBotPositions, PortfolioDailyLossPercent);
            Print("Exit: confirmed candle close across EMA {0}; ATR stop always protects trades.", EmaPeriod);
            if (TimeFrame != TimeFrame.Minute3)
                Print("NOTICE: video uses M3; current chart timeframe is {0}.", TimeFrame);
            if (!EnableTrading)
                Print("SAFETY MODE: automatic orders are disabled.");
        }

        protected override void OnStop()
        {
            Positions.Closed -= OnBotPositionClosed;
        }

        protected override void OnTick()
        {
            ResetDayIfNeeded();
            if (LossLimitReached())
            {
                if (!_dayBlocked)
                    Print("Daily bot loss limit reached. Blocking entries until next UTC day.");
                _dayBlocked = true;
                // Do not modify trades in safety mode.
                if (EnableTrading)
                    CloseOwnPositions();
            }
        }

        protected override void OnBarClosed()
        {
            ResetDayIfNeeded();
            int current = Bars.Count - 1;
            int previous = current - 1;
            if (previous < Math.Max(EmaPeriod, AtrPeriod) + 2)
                return;

            double emaCurrent = _ema.Result.Last(0);
            double emaPrevious = _ema.Result.Last(1);
            double close = Bars.ClosePrices.Last(0);

            if (double.IsNaN(emaCurrent) || double.IsNaN(emaPrevious))
                return;

            // The video calls for exiting when a FULL candle closes through EMA9,
            // even if EMA and VWAP have not crossed in the opposite direction.
            // OnBarClosed does not use any incomplete / future candle.
            bool closedOnEma = false;
            if (EnableTrading)
            {
                foreach (var position in Positions.FindAll(BotLabel, SymbolName))
                {
                    bool exitByEma = MathEdgeRiskMath.ShouldExitOnEmaClose(position.TradeType == TradeType.Buy, close, emaCurrent);
                    if (!exitByEma)
                        continue;

                    var result = ClosePosition(position);
                    if (!result.IsSuccessful)
                    {
                        Print("EMA candle-close exit failed: {0}", result.Error);
                        return;
                    }
                    closedOnEma = true;
                    Print("Exited on closed candle across EMA {0}: {1}", EmaPeriod, position.TradeType);
                }
            }

            // Never enter again on the very candle that forced the EMA exit.
            if (closedOnEma)
                return;

            // Only compare two VWAP values from the same anchored session.
            DateTime currentSession = MathEdgeRiskMath.VwapSessionDate(Bars.OpenTimes[current], VwapResetUtcHour);
            DateTime previousSession = MathEdgeRiskMath.VwapSessionDate(Bars.OpenTimes[previous], VwapResetUtcHour);
            if (currentSession != previousSession)
                return;

            double vwapCurrent = CalculateSessionVwap(current);
            double vwapPrevious = CalculateSessionVwap(previous);
            if (double.IsNaN(vwapCurrent) || double.IsNaN(vwapPrevious))
                return;

            double atrPrice = _atr.Result.Last(0);
            if (double.IsNaN(atrPrice) || double.IsInfinity(atrPrice) || atrPrice <= 0)
                return;

            // Video-confirmed signal: the EMA9 and VWAP cross.
            bool buyCross = MathEdgeRiskMath.ConfirmedBuyCross(emaPrevious, vwapPrevious, emaCurrent, vwapCurrent, close);
            bool sellCross = MathEdgeRiskMath.ConfirmedSellCross(emaPrevious, vwapPrevious, emaCurrent, vwapCurrent, close);

            // "Right before the cross" is not defined precisely in the reel.
            // This optional approach is an EXPLICIT original approximation, OFF by default.
            double previousGap = emaPrevious - vwapPrevious;
            double currentGap = emaCurrent - vwapCurrent;
            bool approachingBuy = EnableEarlyApproachEntry &&
                previousGap < 0 && currentGap < 0 && currentGap > previousGap &&
                Math.Abs(currentGap) <= EarlyCrossMaxAtrFraction * atrPrice &&
                close > emaCurrent && close > Bars.ClosePrices.Last(1);
            bool approachingSell = EnableEarlyApproachEntry &&
                previousGap > 0 && currentGap > 0 && currentGap < previousGap &&
                Math.Abs(currentGap) <= EarlyCrossMaxAtrFraction * atrPrice &&
                close < emaCurrent && close < Bars.ClosePrices.Last(1);

            bool buy = buyCross || approachingBuy;
            bool sell = sellCross || approachingSell;
            if (buy == sell)
                return;

            TradeType direction = buy ? TradeType.Buy : TradeType.Sell;
            if (!EnableTrading || _dayBlocked || LossLimitReached() || !PassesOptionalH1Trend(direction))
                return;

            if (ExitOnReverse)
            {
                foreach (var position in Positions.FindAll(BotLabel, SymbolName))
                {
                    if (position.TradeType == direction)
                        continue;
                    var result = ClosePosition(position);
                    if (!result.IsSuccessful)
                    {
                        Print("Opposite position close failed: {0}", result.Error);
                        return;
                    }
                }
            }

            if (Positions.FindAll(BotLabel, SymbolName).Length > 0)
                return;
            if (CountEntriesToday() >= MaxEntriesPerDay || CountPortfolioEntriesToday() >= MaxTotalEntriesPerDay)
                return;
            if (Positions.FindAll(BotLabel).Length >= MaxTotalBotPositions)
            {
                Print("Portfolio position cap reached. New entry blocked.");
                return;
            }
            if (current - _lastEntryBar < CooldownBars)
                return;

            double spreadPips = Symbol.Spread / Symbol.PipSize;
            double atrPips = atrPrice / Symbol.PipSize;
            if (!MathEdgeRiskMath.SpreadAcceptable(spreadPips, atrPips, MaxSpreadAtrFraction, _profileSpreadPips))
            {
                Print("Signal skipped due to high spread: {0:F2} pips", spreadPips);
                return;
            }

            // Protective ATR stop is NOT the "-25 to -30%" option premium threshold
            // referenced in the video. For spot/CFDs, such a percent is not transferable.
            double stopPips = Math.Max(_profileMinStopPips, atrPips * Math.Min(AtrStopMultiplier, MaxAtrStopMultiplier));
            if (double.IsNaN(stopPips) || double.IsInfinity(stopPips) || stopPips <= 0)
                return;
            double? takeProfitPips = UseFixedTakeProfit ? stopPips * RewardRiskRatio : (double?)null;

            double rawVolume = Symbol.VolumeForProportionalRisk(
                ProportionalAmountType.Equity, _profileRiskPercent, stopPips, RoundingMode.Down);
            if (double.IsNaN(rawVolume) || double.IsInfinity(rawVolume) || rawVolume <= 0)
                return;

            double volume = Symbol.NormalizeVolumeInUnits(
                Math.Min(rawVolume, Symbol.VolumeInUnitsMax), RoundingMode.Down);
            if (volume < Symbol.VolumeInUnitsMin)
            {
                Print("Signal skipped: risk budget below broker minimum volume.");
                return;
            }

            double margin = Symbol.GetEstimatedMargin(direction, volume);
            if (double.IsNaN(margin) || double.IsInfinity(margin) || margin > Account.FreeMargin)
            {
                Print("Signal skipped: insufficient estimated margin.");
                return;
            }

            var order = ExecuteMarketOrder(direction, SymbolName, volume, BotLabel, stopPips, takeProfitPips);
            if (order.IsSuccessful)
            {
                _lastEntryBar = current;
                Print("Opened {0} ({1}), units={2}, SL={3:F2} pips, fixed TP enabled={4}",
                    direction, buyCross || sellCross ? "confirmed cross" : "experimental near-cross",
                    volume, stopPips, UseFixedTakeProfit);
            }
            else
                Print("Order rejected: {0}", order.Error);
        }

        private void ConfigureMarketProfile()
        {
            MathEdgeRiskMath.MarketCategory category = MathEdgeRiskMath.Classify(SymbolName);
            _profileSpreadPips = MathEdgeRiskMath.ProfileSpreadCapPips(category, MaxSpreadPips);
            _profileRiskPercent = AutoMarketProfile
                ? MathEdgeRiskMath.ProfileRiskPercent(category, RiskPercent)
                : RiskPercent;
            _profileMinStopPips = MinStopPips;
            _profileDailyLossLimit = AutoMarketProfile
                ? MathEdgeRiskMath.ProfileDailyLossPercent(category, DailyLossLimitPercent)
                : DailyLossLimitPercent;

            // Forex additionally uses a 3-pip absolute spread cap unless overridden.
            // Other markets use spread/ATR; broker conventions make fixed pip values unreliable.
            Print("Market {0} ({1}): risk {2}%, daily symbol loss {3}%, absolute spread cap {4}, spread/ATR max {5}",
                SymbolName, category, _profileRiskPercent, _profileDailyLossLimit,
                _profileSpreadPips, MaxSpreadAtrFraction);
        }

        private bool PassesOptionalH1Trend(TradeType direction)
        {
            if (!UseH1TrendFilter)
                return true;
            if (_h1Bars == null || _h1Bars.Count < H1TrendPeriod + 3)
                return false;

            double h1Close = _h1Bars.ClosePrices.Last(1);
            double trend = _h1TrendEma.Result.Last(1);
            return direction == TradeType.Buy ? h1Close > trend : h1Close < trend;
        }

        private double CalculateSessionVwap(int barIndex)
        {
            DateTime sessionDate = MathEdgeRiskMath.VwapSessionDate(Bars.OpenTimes[barIndex], VwapResetUtcHour);
            double priceTimesVolume = 0.0;
            double totalVolume = 0.0;
            int k = barIndex;
            for (; k >= 0 && MathEdgeRiskMath.VwapSessionDate(Bars.OpenTimes[k], VwapResetUtcHour) == sessionDate; k--)
            {
                double vol = Bars.TickVolumes[k];
                if (vol <= 0)
                    continue;
                double typical = (Bars.HighPrices[k] + Bars.LowPrices[k] + Bars.ClosePrices[k]) / 3.0;
                priceTimesVolume += typical * vol;
                totalVolume += vol;
            }
            if (k < 0 || totalVolume <= 0)
                return double.NaN;
            return priceTimesVolume / totalVolume;
        }

        private void ResetDayIfNeeded()
        {
            DateTime utcToday = Server.Time.Date;
            if (_day == utcToday)
                return;
            _day = utcToday;
            _dayBlocked = false;
            RefreshRealizedProfit();
        }

        private void RefreshRealizedProfit()
        {
            double symbolRealized = 0;
            double portfolioRealized = 0;
            foreach (var trade in History.FindAll(BotLabel))
            {
                if (trade.ClosingTime.Date != _day)
                    continue;
                portfolioRealized += trade.NetProfit;
                if (trade.SymbolName == SymbolName)
                    symbolRealized += trade.NetProfit;
            }
            _realizedToday = symbolRealized;
            _portfolioRealizedToday = portfolioRealized;
        }

        private int CountEntriesToday()
        {
            var ids = new HashSet<int>();
            foreach (var trade in History.FindAll(BotLabel, SymbolName))
                if (trade.EntryTime.Date == _day)
                    ids.Add(trade.PositionId);
            foreach (var position in Positions.FindAll(BotLabel, SymbolName))
                if (position.EntryTime.Date == _day)
                    ids.Add(position.Id);
            return ids.Count;
        }

        private int CountPortfolioEntriesToday()
        {
            var ids = new HashSet<int>();
            foreach (var trade in History.FindAll(BotLabel))
                if (trade.EntryTime.Date == _day)
                    ids.Add(trade.PositionId);
            foreach (var position in Positions.FindAll(BotLabel))
                if (position.EntryTime.Date == _day)
                    ids.Add(position.Id);
            return ids.Count;
        }

        private bool LossLimitReached()
        {
            if (_dayBlocked)
                return true;

            double symbolOpenPnl = 0;
            foreach (var position in Positions.FindAll(BotLabel, SymbolName))
                symbolOpenPnl += position.NetProfit;

            double portfolioOpenPnl = 0;
            foreach (var position in Positions.FindAll(BotLabel))
                portfolioOpenPnl += position.NetProfit;

            // Global circuit breaker uses all markets sharing the same label,
            // regardless of which chart each instance is attached to.
            double baseline = Math.Max(Account.Balance, 1.0);
            return MathEdgeRiskMath.DailyLossReached(
                       _realizedToday, symbolOpenPnl, baseline, _profileDailyLossLimit) ||
                   MathEdgeRiskMath.DailyLossReached(
                       _portfolioRealizedToday, portfolioOpenPnl, baseline, PortfolioDailyLossPercent);
        }

        private void CloseOwnPositions()
        {
            foreach (var position in Positions.FindAll(BotLabel, SymbolName))
            {
                var result = ClosePosition(position);
                if (!result.IsSuccessful)
                    Print("Risk-close error for position {0}: {1}", position.Id, result.Error);
            }
        }

        private void OnBotPositionClosed(PositionClosedEventArgs args)
        {
            if (args.Position.Label != BotLabel)
                return;
            ResetDayIfNeeded();
            RefreshRealizedProfit();
        }

        protected override void OnError(Error error)
        {
            Print("cTrader trade error: {0}", error.Code);
        }
    }
}
