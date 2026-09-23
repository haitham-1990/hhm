using System;
using cAlgo.API;
using cAlgo.API.Indicators;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class KhutwaScalpV2 : Robot
    {
        // =========================
        // Signal engine (M1 default)
        // =========================

        [Parameter("Fast EMA", DefaultValue = 9, MinValue = 2)]
        public int FastEmaPeriod { get; set; }

        [Parameter("Slow EMA", DefaultValue = 21, MinValue = 3)]
        public int SlowEmaPeriod { get; set; }

        [Parameter("RSI Period", DefaultValue = 7, MinValue = 2)]
        public int RsiPeriod { get; set; }

        [Parameter("Buy RSI Min", DefaultValue = 53.0, MinValue = 0, MaxValue = 100)]
        public double BuyRsiMin { get; set; }

        [Parameter("Buy RSI Max", DefaultValue = 74.0, MinValue = 0, MaxValue = 100)]
        public double BuyRsiMax { get; set; }

        [Parameter("Sell RSI Max", DefaultValue = 47.0, MinValue = 0, MaxValue = 100)]
        public double SellRsiMax { get; set; }

        [Parameter("Sell RSI Min", DefaultValue = 26.0, MinValue = 0, MaxValue = 100)]
        public double SellRsiMin { get; set; }

        [Parameter("ATR Period", DefaultValue = 14, MinValue = 2)]
        public int AtrPeriod { get; set; }

        [Parameter("Volume Lookback", DefaultValue = 20, MinValue = 5)]
        public int VolumeLookback { get; set; }

        [Parameter("Min Tick-Volume Ratio", DefaultValue = 1.05, MinValue = 0.5)]
        public double MinVolumeRatio { get; set; }

        [Parameter("Min Trend / ATR", DefaultValue = 0.12, MinValue = 0)]
        public double MinTrendAtrRatio { get; set; }

        [Parameter("Min Signal Score", DefaultValue = 3.25, MinValue = 1.0, MaxValue = 5.0)]
        public double MinSignalScore { get; set; }


        // =========================
        // Stop / target mathematics
        // =========================

        [Parameter("ATR Stop Multiplier", DefaultValue = 0.90, MinValue = 0.2)]
        public double AtrStopMultiplier { get; set; }

        [Parameter("Minimum Stop (pips)", DefaultValue = 3.0, MinValue = 0.5)]
        public double MinimumStopPips { get; set; }

        [Parameter("Reward / Risk", DefaultValue = 1.25, MinValue = 0.5)]
        public double RewardRiskRatio { get; set; }

        [Parameter("Break-even Trigger (R)", DefaultValue = 0.70, MinValue = 0.1)]
        public double BreakEvenTriggerR { get; set; }

        [Parameter("Break-even Lock (R)", DefaultValue = 0.05, MinValue = 0)]
        public double BreakEvenLockR { get; set; }

        [Parameter("Max Minutes in Trade", DefaultValue = 8, MinValue = 1)]
        public int MaxMinutesInTrade { get; set; }


        // =========================
        // Cost filters
        // =========================

        [Parameter("Max Spread (pips)", DefaultValue = 0.8, MinValue = 0.1)]
        public double MaxSpreadPips { get; set; }

        // IMPORTANT:
        // This is an estimate of round-turn non-spread cost expressed in pips.
        // Adjust it after checking the actual commission shown by your Fiper cTrader account.
        [Parameter("Extra Round-Turn Cost (pips)", DefaultValue = 0.50, MinValue = 0)]
        public double ExtraRoundTurnCostPips { get; set; }

        [Parameter("Max Cost / Stop", DefaultValue = 0.18, MinValue = 0.01, MaxValue = 1.0)]
        public double MaxCostToStopRatio { get; set; }

        [Parameter("Max Break-even Win Rate", DefaultValue = 54.0, MinValue = 1, MaxValue = 99)]
        public double MaxBreakEvenWinRatePct { get; set; }


        // =========================
        // Risk / speed controls
        // =========================

        [Parameter("Risk % per Trade", DefaultValue = 0.50, MinValue = 0.01, MaxValue = 5.0)]
        public double RiskPercent { get; set; }

        [Parameter("Max Trades / Day", DefaultValue = 30, MinValue = 1, MaxValue = 100)]
        public int MaxTradesPerDay { get; set; }

        [Parameter("Max Daily Equity Loss %", DefaultValue = 3.0, MinValue = 0.5, MaxValue = 20)]
        public double MaxDailyLossPct { get; set; }

        [Parameter("Max Consecutive Losses", DefaultValue = 3, MinValue = 1, MaxValue = 10)]
        public int MaxConsecutiveLosses { get; set; }

        [Parameter("Cooldown Seconds", DefaultValue = 60, MinValue = 0)]
        public int CooldownSeconds { get; set; }


        // =========================
        // Trading session (UTC)
        // =========================

        [Parameter("Session Start UTC Hour", DefaultValue = 7, MinValue = 0, MaxValue = 23)]
        public int SessionStartUtcHour { get; set; }

        [Parameter("Session End UTC Hour", DefaultValue = 17, MinValue = 1, MaxValue = 24)]
        public int SessionEndUtcHour { get; set; }


        [Parameter("Allow Buy", DefaultValue = true)]
        public bool AllowBuy { get; set; }

        [Parameter("Allow Sell", DefaultValue = true)]
        public bool AllowSell { get; set; }

        [Parameter("Bot Label", DefaultValue = "Khutwa-Scalp-V2")]
        public string BotLabel { get; set; }


        private ExponentialMovingAverage _fastEma;
        private ExponentialMovingAverage _slowEma;
        private RelativeStrengthIndex _rsi;
        private AverageTrueRange _atr;

        private DateTime _day;
        private double _dayStartEquity;
        private int _tradesToday;
        private int _consecutiveLosses;
        private DateTime _lastEntryTime = DateTime.MinValue;
        private bool _dayBlocked;


        protected override void OnStart()
        {
            if (FastEmaPeriod >= SlowEmaPeriod)
            {
                Print("ERROR: Fast EMA must be smaller than Slow EMA.");
                Stop();
                return;
            }

            if (RewardRiskRatio <= 0)
            {
                Print("ERROR: Reward/Risk must be positive.");
                Stop();
                return;
            }

            _fastEma = Indicators.ExponentialMovingAverage(Bars.ClosePrices, FastEmaPeriod);
            _slowEma = Indicators.ExponentialMovingAverage(Bars.ClosePrices, SlowEmaPeriod);
            _rsi = Indicators.RelativeStrengthIndex(Bars.ClosePrices, RsiPeriod);
            _atr = Indicators.AverageTrueRange(AtrPeriod, MovingAverageType.Exponential);

            _day = Server.Time.Date;
            _dayStartEquity = Account.Equity;
            _tradesToday = 0;
            _consecutiveLosses = 0;
            _dayBlocked = false;

            Positions.Closed += OnPositionClosed;

            Print("KhutwaScalpV2 started.");
            Print("Symbol={0}, TimeFrame={1}", SymbolName, TimeFrame);
            Print("Recommended first profile: EURUSD / M1 / DEMO.");
            Print("Risk={0}% | MaxTrades={1} | DailyLossLimit={2}%",
                RiskPercent, MaxTradesPerDay, MaxDailyLossPct);
        }


        protected override void OnBarClosed()
        {
            ResetDayIfNeeded();

            if (_dayBlocked)
                return;

            if (!Symbol.MarketHours.IsOpened())
                return;

            int hour = Server.Time.Hour;
            if (!IsInsideSession(hour))
                return;

            if (_tradesToday >= MaxTradesPerDay)
                return;

            if (_consecutiveLosses >= MaxConsecutiveLosses)
            {
                _dayBlocked = true;
                Print("DAY BLOCKED: consecutive-loss limit reached.");
                return;
            }

            if (DailyLossLimitReached())
            {
                _dayBlocked = true;
                Print("DAY BLOCKED: daily equity loss limit reached.");
                return;
            }

            if ((Server.Time - _lastEntryTime).TotalSeconds < CooldownSeconds)
                return;

            if (Positions.FindAll(BotLabel, SymbolName).Length > 0)
                return;

            int minBars = Math.Max(SlowEmaPeriod, Math.Max(AtrPeriod, VolumeLookback)) + 5;
            if (Bars.Count < minBars)
                return;

            double spreadPips = (Symbol.Ask - Symbol.Bid) / Symbol.PipSize;

            if (spreadPips <= 0 || spreadPips > MaxSpreadPips)
                return;

            double atrPips = _atr.Result.Last(0) / Symbol.PipSize;
            if (atrPips <= 0)
                return;

            double stopPips = Math.Max(
                MinimumStopPips,
                Math.Max(atrPips * AtrStopMultiplier, spreadPips * 3.0));

            double estimatedTotalCostPips = spreadPips + ExtraRoundTurnCostPips;
            double costToStop = estimatedTotalCostPips / stopPips;

            // If winner makes RR*R and each trade pays c*R in costs:
            // breakeven p = (1 + c) / (1 + RR)
            double breakEvenWinRate = (1.0 + costToStop) / (1.0 + RewardRiskRatio);

            if (costToStop > MaxCostToStopRatio)
                return;

            if (breakEvenWinRate * 100.0 > MaxBreakEvenWinRatePct)
                return;

            double fast = _fastEma.Result.Last(0);
            double slow = _slowEma.Result.Last(0);
            double rsi = _rsi.Result.Last(0);

            double trendAtrRatio = Math.Abs(fast - slow) / _atr.Result.Last(0);

            double currentVolume = Bars.TickVolumes.Last(0);
            double averageVolume = AverageTickVolume(1, VolumeLookback);

            if (averageVolume <= 0)
                return;

            double volumeRatio = currentVolume / averageVolume;

            double close = Bars.ClosePrices.Last(0);
            double open = Bars.OpenPrices.Last(0);
            double prevHigh = Bars.HighPrices.Last(1);
            double prevLow = Bars.LowPrices.Last(1);

            bool upTrend = fast > slow;
            bool downTrend = fast < slow;

            bool buyRsiOk = rsi >= BuyRsiMin && rsi <= BuyRsiMax;
            bool sellRsiOk = rsi <= SellRsiMax && rsi >= SellRsiMin;

            bool volumeOk = volumeRatio >= MinVolumeRatio;
            bool trendStrengthOk = trendAtrRatio >= MinTrendAtrRatio;

            bool buyBreakout = close > prevHigh;
            bool sellBreakout = close < prevLow;

            bool bullishCandle = close > open;
            bool bearishCandle = close < open;

            double buyScore = 0.0;
            double sellScore = 0.0;

            if (upTrend) buyScore += 1.25;
            if (downTrend) sellScore += 1.25;

            if (buyRsiOk) buyScore += 1.00;
            if (sellRsiOk) sellScore += 1.00;

            if (volumeOk)
            {
                buyScore += 0.75;
                sellScore += 0.75;
            }

            if (trendStrengthOk)
            {
                buyScore += 0.50;
                sellScore += 0.50;
            }

            if (buyBreakout) buyScore += 1.00;
            else if (bullishCandle && close > fast) buyScore += 0.50;

            if (sellBreakout) sellScore += 1.00;
            else if (bearishCandle && close < fast) sellScore += 0.50;

            if (AllowBuy && buyScore >= MinSignalScore && buyScore > sellScore)
            {
                OpenScalp(TradeType.Buy, stopPips, breakEvenWinRate, buyScore);
            }
            else if (AllowSell && sellScore >= MinSignalScore && sellScore > buyScore)
            {
                OpenScalp(TradeType.Sell, stopPips, breakEvenWinRate, sellScore);
            }
        }


        protected override void OnTick()
        {
            ResetDayIfNeeded();

            var positions = Positions.FindAll(BotLabel, SymbolName);

            foreach (var position in positions)
            {
                double initialRiskPips = GetInitialRiskPips(position);

                if (initialRiskPips <= 0)
                    continue;

                // Time-based exit: this is a scalper, not a swing bot.
                if ((Server.Time - position.EntryTime).TotalMinutes >= MaxMinutesInTrade)
                {
                    ClosePosition(position);
                    continue;
                }

                // Break-even / small lock-in after price moves enough in our favour.
                if (position.Pips >= initialRiskPips * BreakEvenTriggerR)
                {
                    double lockPips = initialRiskPips * BreakEvenLockR;

                    double newStop = position.TradeType == TradeType.Buy
                        ? position.EntryPrice + lockPips * Symbol.PipSize
                        : position.EntryPrice - lockPips * Symbol.PipSize;

                    bool shouldMove =
                        !position.StopLoss.HasValue ||
                        (position.TradeType == TradeType.Buy && position.StopLoss.Value < newStop) ||
                        (position.TradeType == TradeType.Sell && position.StopLoss.Value > newStop);

                    if (shouldMove)
                        ModifyPosition(position, newStop, position.TakeProfit);
                }
            }
        }


        private void OpenScalp(TradeType type, double stopPips, double breakEvenWinRate, double score)
        {
            double takeProfitPips = stopPips * RewardRiskRatio;

            double volume = Symbol.VolumeForProportionalRisk(
                ProportionalAmountType.Equity,
                RiskPercent,
                stopPips,
                RoundingMode.Down);

            volume = Symbol.NormalizeVolumeInUnits(volume, RoundingMode.Down);

            if (volume < Symbol.VolumeInUnitsMin)
            {
                Print("SKIP: calculated volume {0} is below broker minimum {1}.",
                    volume, Symbol.VolumeInUnitsMin);
                return;
            }

            if (volume > Symbol.VolumeInUnitsMax)
                volume = Symbol.VolumeInUnitsMax;

            var result = ExecuteMarketOrder(
                type,
                SymbolName,
                volume,
                BotLabel,
                stopPips,
                takeProfitPips);

            if (!result.IsSuccessful)
            {
                Print("ORDER FAILED: {0}", result.Error);
                return;
            }

            _tradesToday++;
            _lastEntryTime = Server.Time;

            Print(
                "{0} | Score={1:F2} | SL={2:F2}p | TP={3:F2}p | BE win-rate≈{4:F1}% | Volume={5}",
                type,
                score,
                stopPips,
                takeProfitPips,
                breakEvenWinRate * 100.0,
                volume);
        }


        private double AverageTickVolume(int startOffset, int count)
        {
            double sum = 0.0;

            for (int i = startOffset; i < startOffset + count; i++)
                sum += Bars.TickVolumes.Last(i);

            return sum / count;
        }


        private double GetInitialRiskPips(Position position)
        {
            if (position.TakeProfit.HasValue && RewardRiskRatio > 0)
            {
                double targetDistancePips =
                    Math.Abs(position.TakeProfit.Value - position.EntryPrice) / Symbol.PipSize;

                return targetDistancePips / RewardRiskRatio;
            }

            if (position.StopLoss.HasValue)
                return Math.Abs(position.EntryPrice - position.StopLoss.Value) / Symbol.PipSize;

            return 0.0;
        }


        private bool DailyLossLimitReached()
        {
            if (_dayStartEquity <= 0)
                return false;

            double lossPct = ((_dayStartEquity - Account.Equity) / _dayStartEquity) * 100.0;
            return lossPct >= MaxDailyLossPct;
        }


        private bool IsInsideSession(int hour)
        {
            if (SessionStartUtcHour < SessionEndUtcHour)
                return hour >= SessionStartUtcHour && hour < SessionEndUtcHour;

            // Supports sessions crossing midnight.
            return hour >= SessionStartUtcHour || hour < SessionEndUtcHour;
        }


        private void ResetDayIfNeeded()
        {
            if (Server.Time.Date == _day)
                return;

            _day = Server.Time.Date;
            _dayStartEquity = Account.Equity;
            _tradesToday = 0;
            _consecutiveLosses = 0;
            _dayBlocked = false;

            Print("New UTC day: counters reset. Start equity={0:F2}", _dayStartEquity);
        }


        private void OnPositionClosed(PositionClosedEventArgs args)
        {
            var position = args.Position;

            if (position.Label != BotLabel || position.SymbolName != SymbolName)
                return;

            if (position.NetProfit < 0)
                _consecutiveLosses++;
            else if (position.NetProfit > 0)
                _consecutiveLosses = 0;

            Print(
                "CLOSED | Net={0:F2} | Pips={1:F1} | Consecutive losses={2}",
                position.NetProfit,
                position.Pips,
                _consecutiveLosses);
        }


        protected override void OnError(Error error)
        {
            Print("cTrader error: {0}", error.Code);
        }


        protected override void OnStop()
        {
            Positions.Closed -= OnPositionClosed;
            Print("KhutwaScalpV2 stopped.");
        }
    }
}