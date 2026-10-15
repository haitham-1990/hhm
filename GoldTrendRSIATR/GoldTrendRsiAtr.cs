using System;
using cAlgo.API;
using cAlgo.API.Indicators;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class GoldTrendRsiAtr : Robot
    {
        [Parameter("Fast EMA", Group = "Trend", DefaultValue = 20, MinValue = 2)]
        public int FastPeriod { get; set; }

        [Parameter("Slow EMA", Group = "Trend", DefaultValue = 50, MinValue = 3)]
        public int SlowPeriod { get; set; }

        [Parameter("Trend EMA", Group = "Trend", DefaultValue = 200, MinValue = 4)]
        public int TrendPeriod { get; set; }

        [Parameter("RSI Period", Group = "Signals", DefaultValue = 14, MinValue = 2)]
        public int RsiPeriod { get; set; }

        [Parameter("RSI Buy Level", Group = "Signals", DefaultValue = 52.0, MinValue = 1.0, MaxValue = 99.0)]
        public double RsiBuyLevel { get; set; }

        [Parameter("RSI Sell Level", Group = "Signals", DefaultValue = 48.0, MinValue = 1.0, MaxValue = 99.0)]
        public double RsiSellLevel { get; set; }

        [Parameter("ATR Period", Group = "Stops", DefaultValue = 14, MinValue = 2)]
        public int AtrPeriod { get; set; }

        [Parameter("Stop Loss ATR Multiplier", Group = "Stops", DefaultValue = 2.0, MinValue = 0.1)]
        public double SlAtr { get; set; }

        [Parameter("Take Profit ATR Multiplier", Group = "Stops", DefaultValue = 3.0, MinValue = 0.1)]
        public double TpAtr { get; set; }

        [Parameter("Risk % of Balance", Group = "Risk", DefaultValue = 0.5, MinValue = 0.01, MaxValue = 2.0)]
        public double RiskPercent { get; set; }

        [Parameter("Maximum Open Positions", Group = "Risk", DefaultValue = 1, MinValue = 1, MaxValue = 10)]
        public int MaxPositions { get; set; }

        [Parameter("Maximum Spread (pips, 0 = off)", Group = "Risk", DefaultValue = 30.0, MinValue = 0.0)]
        public double MaxSpreadPips { get; set; }

        [Parameter("Bot Label", Group = "Other", DefaultValue = "GoldTrendRSIATR")]
        public string BotLabel { get; set; }

        private MovingAverage _fastEma;
        private MovingAverage _slowEma;
        private MovingAverage _trendEma;
        private RelativeStrengthIndex _rsi;
        private AverageTrueRange _atr;

        protected override void OnStart()
        {
            if (FastPeriod >= SlowPeriod || SlowPeriod >= TrendPeriod || string.IsNullOrWhiteSpace(BotLabel))
            {
                Print("Invalid EMA periods or bot label.");
                Stop();
                return;
            }
            _fastEma = Indicators.MovingAverage(Bars.ClosePrices, FastPeriod, MovingAverageType.Exponential);
            _slowEma = Indicators.MovingAverage(Bars.ClosePrices, SlowPeriod, MovingAverageType.Exponential);
            _trendEma = Indicators.MovingAverage(Bars.ClosePrices, TrendPeriod, MovingAverageType.Exponential);
            _rsi = Indicators.RelativeStrengthIndex(Bars.ClosePrices, RsiPeriod);
            _atr = Indicators.AverageTrueRange(AtrPeriod, MovingAverageType.Exponential);
            Print("GoldTrendRsiAtr started on {0} {1}. Backtest and demo first.", SymbolName, TimeFrame);
        }

        protected override void OnBarClosed()
        {
            int requiredBars = Math.Max(TrendPeriod, Math.Max(SlowPeriod, Math.Max(RsiPeriod, AtrPeriod))) + 5;
            if (Bars.Count < requiredBars || Symbol.PipSize <= 0)
                return;

            if (Positions.FindAll(BotLabel, SymbolName).Length >= MaxPositions)
                return;

            double spreadPips = Symbol.Spread / Symbol.PipSize;
            if (MaxSpreadPips > 0 && spreadPips > MaxSpreadPips)
                return;

            double fast = _fastEma.Result.Last(0);
            double slow = _slowEma.Result.Last(0);
            double trend = _trendEma.Result.Last(0);
            double close = Bars.ClosePrices.Last(0);
            double rsiNow = _rsi.Result.Last(0);
            double rsiPrev = _rsi.Result.Last(1);
            double atr = _atr.Result.Last(0);

            if (!Valid(fast) || !Valid(slow) || !Valid(trend) || !Valid(close) ||
                !Valid(rsiNow) || !Valid(rsiPrev) || !Valid(atr) || atr <= 0)
                return;

            bool upTrend = fast > slow && close > trend;
            bool downTrend = fast < slow && close < trend;
            bool buySignal = upTrend && rsiPrev < RsiBuyLevel && rsiNow >= RsiBuyLevel;
            bool sellSignal = downTrend && rsiPrev > RsiSellLevel && rsiNow <= RsiSellLevel;

            if (!buySignal && !sellSignal)
                return;

            double slPips = atr * SlAtr / Symbol.PipSize;
            double tpPips = atr * TpAtr / Symbol.PipSize;
            if (!Valid(slPips) || !Valid(tpPips) || slPips <= 0 || tpPips <= 0)
                return;

            double volume = CalculateVolume(slPips);
            if (volume <= 0)
                return;

            var type = buySignal ? TradeType.Buy : TradeType.Sell;
            var result = ExecuteMarketOrder(type, SymbolName, volume, BotLabel, slPips, tpPips);
            if (!result.IsSuccessful)
                Print("Order failed: {0} side={1} volume={2}", result.Error, type, volume);
            else
                Print("Opened {0} volume={1} SL={2:F1}p TP={3:F1}p", type, volume, slPips, tpPips);
        }

        private double CalculateVolume(double slPips)
        {
            double raw = Symbol.VolumeForProportionalRisk(
                ProportionalAmountType.Balance, RiskPercent, slPips, RoundingMode.Down);
            if (!Valid(raw) || raw < Symbol.VolumeInUnitsMin)
                return 0;
            double capped = Math.Min(raw, Symbol.VolumeInUnitsMax);
            double units = Symbol.NormalizeVolumeInUnits(capped, RoundingMode.Down);
            return Valid(units) && units >= Symbol.VolumeInUnitsMin ? units : 0;
        }

        private static bool Valid(double value)
        {
            return !double.IsNaN(value) && !double.IsInfinity(value);
        }
    }
}
