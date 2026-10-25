using System;
using System.Collections.Generic;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    // Original EMA9 / session VWAP implementation inspired by the public reel description.
    // Not the proprietary ROBOQUANT strategy. VWAP is tick-volume-weighted from 00:00 UTC.
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

        [Parameter("Exit on reverse signal", DefaultValue = true, Group = "02 - Signal")]
        public bool ExitOnReverse { get; set; }

        [Parameter("Risk % of equity / trade", DefaultValue = 0.5, MinValue = 0.01, MaxValue = 3, Group = "03 - Risk")]
        public double RiskPercent { get; set; }

        [Parameter("Daily bot loss stop (%)", DefaultValue = 2.0, MinValue = 0.1, MaxValue = 10, Group = "03 - Risk")]
        public double DailyLossLimitPercent { get; set; }

        [Parameter("Maximum entries / UTC day", DefaultValue = 6, MinValue = 1, MaxValue = 30, Group = "03 - Risk")]
        public int MaxEntriesPerDay { get; set; }

        [Parameter("Maximum spread (pips)", DefaultValue = 3.0, MinValue = 0.1, Group = "03 - Risk")]
        public double MaxSpreadPips { get; set; }

        [Parameter("ATR period", DefaultValue = 14, MinValue = 2, Group = "04 - Exits")]
        public int AtrPeriod { get; set; }

        [Parameter("Stop = ATR x", DefaultValue = 1.5, MinValue = 0.5, MaxValue = 8, Group = "04 - Exits")]
        public double AtrStopMultiplier { get; set; }

        [Parameter("Minimum stop (pips)", DefaultValue = 8.0, MinValue = 0.1, Group = "04 - Exits")]
        public double MinStopPips { get; set; }

        [Parameter("Reward / risk", DefaultValue = 1.5, MinValue = 0.5, MaxValue = 10, Group = "04 - Exits")]
        public double RewardRiskRatio { get; set; }

        [Parameter("Minimum bars between entries", DefaultValue = 2, MinValue = 0, MaxValue = 100, Group = "04 - Exits")]
        public int CooldownBars { get; set; }

        private ExponentialMovingAverage _ema;
        private AverageTrueRange _atr;
        private Bars _h1Bars;
        private ExponentialMovingAverage _h1TrendEma;
        private DateTime _day;
        private double _realizedToday;
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
            Print("VWAP uses tick volume and is reset at midnight UTC.");
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
            int current = Bars.Count - 1; // OnBarClosed excludes the forming candle.
            int previous = current - 1;
            if (previous < Math.Max(EmaPeriod, AtrPeriod) + 2)
                return;

            if (Bars.OpenTimes[current].Date != Bars.OpenTimes[previous].Date)
                return;

            double vwapCurrent = CalculateSessionVwap(current);
            double vwapPrevious = CalculateSessionVwap(previous);
            if (double.IsNaN(vwapCurrent) || double.IsNaN(vwapPrevious))
                return;

            double emaCurrent = _ema.Result.Last(0);
            double emaPrevious = _ema.Result.Last(1);
            double close = Bars.ClosePrices.Last(0);

            bool buy = emaPrevious <= vwapPrevious && emaCurrent > vwapCurrent && close > vwapCurrent;
            bool sell = emaPrevious >= vwapPrevious && emaCurrent < vwapCurrent && close < vwapCurrent;
            if (!buy && !sell)
                return;

            TradeType direction = buy ? TradeType.Buy : TradeType.Sell;
            if (!PassesOptionalH1Trend(direction) || !EnableTrading || _dayBlocked || LossLimitReached())
                return;

            if (ExitOnReverse)
            {
                foreach (var position in Positions.FindAll(BotLabel, SymbolName))
                {
                    if (position.TradeType == direction)
                        continue;
                    var closed = ClosePosition(position);
                    if (!closed.IsSuccessful)
                    {
                        Print("Opposite-position close failed: {0}", closed.Error);
                        return;
                    }
                }
            }

            if (Positions.FindAll(BotLabel, SymbolName).Length > 0)
                return;
            if (CountEntriesToday() >= MaxEntriesPerDay)
                return;
            if (current - _lastEntryBar < CooldownBars)
                return;

            double spreadPips = Symbol.Spread / Symbol.PipSize;
            if (spreadPips > MaxSpreadPips)
            {
                Print("Signal skipped: spread {0:F2} > {1:F2}", spreadPips, MaxSpreadPips);
                return;
            }

            double atrPrice = _atr.Result.Last(0);
            if (double.IsNaN(atrPrice) || double.IsInfinity(atrPrice) || atrPrice <= 0)
                return;

            double stopPips = Math.Max(MinStopPips, (atrPrice / Symbol.PipSize) * AtrStopMultiplier);
            double takeProfitPips = stopPips * RewardRiskRatio;
            if (double.IsNaN(stopPips) || double.IsInfinity(stopPips) || stopPips <= 0)
                return;

            double rawVolume = Symbol.VolumeForProportionalRisk(
                ProportionalAmountType.Equity, RiskPercent, stopPips, RoundingMode.Down);
            if (double.IsNaN(rawVolume) || double.IsInfinity(rawVolume) || rawVolume <= 0)
                return;

            double volume = Symbol.NormalizeVolumeInUnits(Math.Min(rawVolume, Symbol.VolumeInUnitsMax), RoundingMode.Down);
            if (volume < Symbol.VolumeInUnitsMin)
            {
                Print("Signal skipped: risk budget below broker minimum volume.");
                return;
            }

            double requiredMargin = Symbol.GetEstimatedMargin(direction, volume);
            if (double.IsNaN(requiredMargin) || requiredMargin > Account.FreeMargin)
            {
                Print("Signal skipped: insufficient estimated margin.");
                return;
            }

            var result = ExecuteMarketOrder(direction, SymbolName, volume, BotLabel, stopPips, takeProfitPips);
            if (result.IsSuccessful)
            {
                _lastEntryBar = current;
                Print("Opened {0}: units={1}, SL={2:F2} pips TP={3:F2} pips", direction, volume, stopPips, takeProfitPips);
            }
            else
                Print("Order rejected: {0}", result.Error);
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
            DateTime sessionDate = Bars.OpenTimes[barIndex].Date;
            double priceTimesVolume = 0.0;
            double totalVolume = 0.0;
            int k = barIndex;
            for (; k >= 0 && Bars.OpenTimes[k].Date == sessionDate; k--)
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
            double realized = 0;
            foreach (var trade in History.FindAll(BotLabel, SymbolName))
                if (trade.ClosingTime.Date == _day)
                    realized += trade.NetProfit;
            _realizedToday = realized;
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

        private bool LossLimitReached()
        {
            if (_dayBlocked)
                return true;
            double botPnl = _realizedToday;
            foreach (var position in Positions.FindAll(BotLabel, SymbolName))
                botPnl += position.NetProfit;
            double dailyBudget = Math.Max(Account.Balance, 1.0) * DailyLossLimitPercent / 100.0;
            return botPnl <= -dailyBudget;
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
            if (args.Position.Label != BotLabel || args.Position.SymbolName != SymbolName)
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
