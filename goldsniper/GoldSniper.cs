using System;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class GoldSniperXAUUSD : Robot
    {
        [Parameter("Risk per trade (%)", DefaultValue = 0.35, MinValue = 0.05, MaxValue = 2.0, Step = 0.05)]
        public double RiskPerTradePct { get; set; }

        [Parameter("Max daily loss (%)", DefaultValue = 1.50, MinValue = 0.5, MaxValue = 5.0, Step = 0.1)]
        public double MaxDailyLossPct { get; set; }

        [Parameter("Reward / Risk", DefaultValue = 2.20, MinValue = 1.0, MaxValue = 5.0, Step = 0.1)]
        public double RewardRisk { get; set; }

        [Parameter("Stop ATR Multiplier", DefaultValue = 1.60, MinValue = 0.5, MaxValue = 5.0, Step = 0.1)]
        public double StopAtrMult { get; set; }

        [Parameter("Break-even at R", DefaultValue = 1.0)]
        public double BreakEvenAtR { get; set; }

        [Parameter("Trail start at R", DefaultValue = 1.50)]
        public double TrailStartR { get; set; }

        [Parameter("Trail ATR Multiplier", DefaultValue = 1.10)]
        public double TrailAtrMult { get; set; }

        [Parameter("Minimum score", DefaultValue = 8, MinValue = 4, MaxValue = 12)]
        public int MinScore { get; set; }

        [Parameter("ADX minimum", DefaultValue = 20, MinValue = 10, MaxValue = 50)]
        public int AdxMin { get; set; }

        [Parameter("Breakout lookback", DefaultValue = 12, MinValue = 5, MaxValue = 50)]
        public int BreakoutLookback { get; set; }

        [Parameter("Max candle / ATR", DefaultValue = 1.80)]
        public double MaxCandleAtr { get; set; }

        [Parameter("Max spread / ATR", DefaultValue = 0.12)]
        public double MaxSpreadAtr { get; set; }

        [Parameter("Use UTC session filter", DefaultValue = true)]
        public bool UseSessionFilter { get; set; }

        [Parameter("Session start UTC", DefaultValue = 6, MinValue = 0, MaxValue = 23)]
        public int SessionStartUtc { get; set; }

        [Parameter("Session end UTC", DefaultValue = 20, MinValue = 0, MaxValue = 23)]
        public int SessionEndUtc { get; set; }

        [Parameter("Label", DefaultValue = "GoldSniper")]
        public string BotLabel { get; set; }

        private Bars _m15;
        private Bars _h1;
        private Bars _h4;
        private ExponentialMovingAverage _ema20M15;
        private ExponentialMovingAverage _ema50M15;
        private RelativeStrengthIndex _rsiM15;
        private AverageTrueRange _atrM15;
        private ExponentialMovingAverage _ema50H1;
        private ExponentialMovingAverage _ema200H1;
        private DirectionalMovementSystem _dmsH1;
        private ExponentialMovingAverage _ema50H4;
        private ExponentialMovingAverage _ema200H4;

        private DateTime _lastM15OpenTime;
        private DateTime _day;
        private double _dayStartEquity;

        protected override void OnStart()
        {
            _m15 = MarketData.GetBars(TimeFrame.Minute15, SymbolName);
            _h1 = MarketData.GetBars(TimeFrame.Hour, SymbolName);
            _h4 = MarketData.GetBars(TimeFrame.Hour4, SymbolName);

            _ema20M15 = Indicators.ExponentialMovingAverage(_m15.ClosePrices, 20);
            _ema50M15 = Indicators.ExponentialMovingAverage(_m15.ClosePrices, 50);
            _rsiM15 = Indicators.RelativeStrengthIndex(_m15.ClosePrices, 14);
            _atrM15 = Indicators.AverageTrueRange(_m15, 14, MovingAverageType.Exponential);

            _ema50H1 = Indicators.ExponentialMovingAverage(_h1.ClosePrices, 50);
            _ema200H1 = Indicators.ExponentialMovingAverage(_h1.ClosePrices, 200);
            _dmsH1 = Indicators.DirectionalMovementSystem(_h1, 14);

            _ema50H4 = Indicators.ExponentialMovingAverage(_h4.ClosePrices, 50);
            _ema200H4 = Indicators.ExponentialMovingAverage(_h4.ClosePrices, 200);

            _lastM15OpenTime = _m15.OpenTimes.LastValue;
            ResetDay();
        }

        protected override void OnTick()
        {
            ResetDayIfNeeded();
            ManageOpenPosition();

            var t = _m15.OpenTimes.LastValue;
            if (t != _lastM15OpenTime)
            {
                _lastM15OpenTime = t;
                TryEntry();
            }
        }

        private void ResetDayIfNeeded()
        {
            if (Server.Time.Date != _day)
                ResetDay();
        }

        private void ResetDay()
        {
            _day = Server.Time.Date;
            _dayStartEquity = Account.Equity;
        }

        private bool DailyLossHit()
        {
            if (_dayStartEquity <= 0)
                return false;
            return ((_dayStartEquity - Account.Equity) / _dayStartEquity * 100.0) >= MaxDailyLossPct;
        }

        private bool InSession()
        {
            if (!UseSessionFilter)
                return true;

            int h = Server.Time.Hour;
            if (SessionStartUtc < SessionEndUtc)
                return h >= SessionStartUtc && h < SessionEndUtc;
            return h >= SessionStartUtc || h < SessionEndUtc;
        }

        private bool HasPosition()
        {
            return Positions.Find(BotLabel, SymbolName) != null;
        }

        private bool SpreadOk(double atr)
        {
            return (Symbol.Ask - Symbol.Bid) <= atr * MaxSpreadAtr;
        }

        private void TryEntry()
        {
            if (HasPosition() || DailyLossHit() || !InSession())
                return;

            if (_m15.Count < Math.Max(BreakoutLookback + 5, 60) || _h1.Count < 205 || _h4.Count < 205)
                return;

            double atr = _atrM15.Result.Last(1);
            if (atr <= 0 || !SpreadOk(atr))
                return;

            int ls = LongScore();
            int ss = ShortScore();

            if (ls < MinScore && ss < MinScore)
                return;
            if (ls == ss)
                return;

            if (ls > ss && ls >= MinScore)
                Enter(TradeType.Buy, ls, atr);
            else if (ss > ls && ss >= MinScore)
                Enter(TradeType.Sell, ss, atr);
        }

        private int LongScore()
        {
            double c1 = _m15.ClosePrices.Last(1);
            double o1 = _m15.OpenPrices.Last(1);
            double h1 = _m15.HighPrices.Last(1);
            double l1 = _m15.LowPrices.Last(1);
            double atr = _atrM15.Result.Last(1);

            if (h1 - l1 > atr * MaxCandleAtr)
                return 0;

            int s = 0;
            if (_ema50H1.Result.Last(1) > _ema200H1.Result.Last(1)) s += 2;
            if (_ema50H4.Result.Last(1) > _ema200H4.Result.Last(1)) s += 2;
            if (_h1.ClosePrices.Last(1) > _ema50H1.Result.Last(1)) s += 1;
            if (_dmsH1.ADX.Last(1) >= AdxMin && _dmsH1.DIPlus.Last(1) > _dmsH1.DIMinus.Last(1)) s += 2;
            if (_ema20M15.Result.Last(1) > _ema50M15.Result.Last(1) && c1 > _ema20M15.Result.Last(1)) s += 1;

            double rsi = _rsiM15.Result.Last(1);
            if (rsi >= 52 && rsi <= 70) s += 1;

            double hh = double.MinValue;
            for (int i = 2; i < 2 + BreakoutLookback; i++)
                hh = Math.Max(hh, _m15.HighPrices.Last(i));

            bool breakout = c1 > hh;
            bool pullback = l1 <= _ema20M15.Result.Last(1) + atr * 0.25 && c1 > _ema20M15.Result.Last(1) && c1 > o1;
            if (breakout || pullback) s += 2;

            double atrPct = atr / c1 * 100.0;
            if (atrPct >= 0.05 && atrPct <= 0.80) s += 1;

            return s;
        }

        private int ShortScore()
        {
            double c1 = _m15.ClosePrices.Last(1);
            double o1 = _m15.OpenPrices.Last(1);
            double h1 = _m15.HighPrices.Last(1);
            double l1 = _m15.LowPrices.Last(1);
            double atr = _atrM15.Result.Last(1);

            if (h1 - l1 > atr * MaxCandleAtr)
                return 0;

            int s = 0;
            if (_ema50H1.Result.Last(1) < _ema200H1.Result.Last(1)) s += 2;
            if (_ema50H4.Result.Last(1) < _ema200H4.Result.Last(1)) s += 2;
            if (_h1.ClosePrices.Last(1) < _ema50H1.Result.Last(1)) s += 1;
            if (_dmsH1.ADX.Last(1) >= AdxMin && _dmsH1.DIMinus.Last(1) > _dmsH1.DIPlus.Last(1)) s += 2;
            if (_ema20M15.Result.Last(1) < _ema50M15.Result.Last(1) && c1 < _ema20M15.Result.Last(1)) s += 1;

            double rsi = _rsiM15.Result.Last(1);
            if (rsi <= 48 && rsi >= 30) s += 1;

            double ll = double.MaxValue;
            for (int i = 2; i < 2 + BreakoutLookback; i++)
                ll = Math.Min(ll, _m15.LowPrices.Last(i));

            bool breakout = c1 < ll;
            bool pullback = h1 >= _ema20M15.Result.Last(1) - atr * 0.25 && c1 < _ema20M15.Result.Last(1) && c1 < o1;
            if (breakout || pullback) s += 2;

            double atrPct = atr / c1 * 100.0;
            if (atrPct >= 0.05 && atrPct <= 0.80) s += 1;

            return s;
        }

        private void Enter(TradeType type, int score, double atr)
        {
            double stopLossPips = StopAtrMult * atr / Symbol.PipSize;
            if (stopLossPips <= 0)
                return;

            double takeProfitPips = stopLossPips * RewardRisk;
            double volume = VolumeForRisk(stopLossPips);
            if (volume < Symbol.VolumeInUnitsMin)
                return;

            var result = ExecuteMarketOrder(type, SymbolName, volume, BotLabel, stopLossPips, takeProfitPips, "Score " + score);
            if (!result.IsSuccessful)
                Print("Entry failed: {0}", result.Error);
        }

        private double VolumeForRisk(double stopLossPips)
        {
            double riskMoney = Account.Balance * RiskPerTradePct / 100.0;
            if (riskMoney <= 0 || stopLossPips <= 0 || Symbol.PipValue <= 0)
                return 0;

            double raw = riskMoney / (stopLossPips * Symbol.PipValue);
            return Symbol.NormalizeVolumeInUnits(raw, RoundingMode.Down);
        }

        private void ManageOpenPosition()
        {
            var p = Positions.Find(BotLabel, SymbolName);
            if (p == null || !p.StopLoss.HasValue || !p.TakeProfit.HasValue)
                return;

            double atr = _atrM15.Result.LastValue;
            if (atr <= 0)
                return;

            double initialR = Math.Abs(p.TakeProfit.Value - p.EntryPrice) / RewardRisk;
            if (initialR <= 0)
                return;

            double? newStop = null;

            if (p.TradeType == TradeType.Buy)
            {
                double move = Symbol.Bid - p.EntryPrice;
                if (move >= BreakEvenAtR * initialR)
                    newStop = p.EntryPrice + 0.05 * initialR;
                if (move >= TrailStartR * initialR)
                {
                    double trail = Symbol.Bid - TrailAtrMult * atr;
                    newStop = newStop.HasValue ? Math.Max(newStop.Value, trail) : trail;
                }
                if (newStop.HasValue && newStop.Value > p.StopLoss.Value && newStop.Value < Symbol.Bid)
                    ModifyPosition(p, newStop, p.TakeProfit, ProtectionType.Absolute);
            }
            else
            {
                double move = p.EntryPrice - Symbol.Ask;
                if (move >= BreakEvenAtR * initialR)
                    newStop = p.EntryPrice - 0.05 * initialR;
                if (move >= TrailStartR * initialR)
                {
                    double trail = Symbol.Ask + TrailAtrMult * atr;
                    newStop = newStop.HasValue ? Math.Min(newStop.Value, trail) : trail;
                }
                if (newStop.HasValue && newStop.Value < p.StopLoss.Value && newStop.Value > Symbol.Ask)
                    ModifyPosition(p, newStop, p.TakeProfit, ProtectionType.Absolute);
            }
        }
    }
}