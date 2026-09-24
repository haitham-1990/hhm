using System;
using System.Linq;
using cAlgo.API;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    // Experimental paired hedge bot. Requires an account/broker that permits hedging.
    // BUY and SELL are submitted sequentially, so they are near-simultaneous, not atomic.
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class DualHedge2R : Robot
    {
        [Parameter("Demo ONLY - block live", DefaultValue = true, Group = "Safety")]
        public bool DemoOnly { get; set; }

        [Parameter("Base label", DefaultValue = "HEDGE-2R-V1", Group = "Safety")]
        public string BaseLabel { get; set; }

        [Parameter("Total pair risk (%)", DefaultValue = 0.20, MinValue = 0.02, MaxValue = 2.0, Group = "Risk")]
        public double TotalPairRiskPercent { get; set; }

        [Parameter("Max cycles per UTC day", DefaultValue = 20, MinValue = 1, MaxValue = 100, Group = "Risk")]
        public int MaxCyclesPerDay { get; set; }

        [Parameter("Cooldown after flat (sec)", DefaultValue = 60, MinValue = 0, MaxValue = 3600, Group = "Risk")]
        public int CooldownSeconds { get; set; }

        [Parameter("ATR period", DefaultValue = 14, MinValue = 2, MaxValue = 100, Group = "Stops")]
        public int AtrPeriod { get; set; }

        [Parameter("Stop ATR multiplier", DefaultValue = 1.5, MinValue = 0.2, MaxValue = 10.0, Group = "Stops")]
        public double StopAtrMultiplier { get; set; }

        [Parameter("Minimum stop (pips)", DefaultValue = 4.0, MinValue = 0.5, MaxValue = 10000, Group = "Stops")]
        public double MinimumStopPips { get; set; }

        [Parameter("Reward / risk", DefaultValue = 2.0, MinValue = 0.5, MaxValue = 10.0, Group = "Stops")]
        public double RewardRisk { get; set; }

        [Parameter("Max spread / stop", DefaultValue = 0.20, MinValue = 0.01, MaxValue = 1.0, Group = "Execution")]
        public double MaxSpreadToStop { get; set; }

        [Parameter("Max actual risk / budget", DefaultValue = 1.10, MinValue = 1.0, MaxValue = 2.0, Group = "Execution")]
        public double MaxActualRiskToBudget { get; set; }

        [Parameter("Diagnostic logs", DefaultValue = true, Group = "Logs")]
        public bool DiagnosticLogs { get; set; }

        private Bars _m1;
        private DateTime _utcDay;
        private DateTime _lastFlat = DateTime.MinValue;
        private int _cyclesToday;
        private bool _wasInCycle;
        private DateTime _nextHeartbeat = DateTime.MinValue;

        private string BuyLabel { get { return BaseLabel + "-BUY"; } }
        private string SellLabel { get { return BaseLabel + "-SELL"; } }

        protected override void OnStart()
        {
            if (DemoOnly && Account.IsLive)
            {
                Print("HEDGE-2R safety block: DemoOnly=true on LIVE account.");
                Stop();
                return;
            }

            if (string.IsNullOrWhiteSpace(BaseLabel) || RewardRisk <= 0 ||
                TotalPairRiskPercent <= 0 || MinimumStopPips <= 0)
            {
                Print("HEDGE-2R invalid parameters.");
                Stop();
                return;
            }

            _m1 = MarketData.GetBars(TimeFrame.Minute, Symbol.Name);
            _utcDay = Server.Time.Date;
            RecoverToday();
            Timer.Start(2);

            Print("HEDGE-2R ON | symbol={0} | pairRisk={1:F2}% ({2:F2}% each leg) | RR={3:F2}:1 | cycles={4}/{5}",
                Symbol.Name, TotalPairRiskPercent, TotalPairRiskPercent / 2.0,
                RewardRisk, _cyclesToday, MaxCyclesPerDay);
            Print("HEDGE-2R NOTE: opposing orders are sequential, not atomic. Account must permit hedge positions.");
        }

        private Position[] BotPositions()
        {
            return Positions.Where(p =>
                p.SymbolName == Symbol.Name &&
                (p.Label == BuyLabel || p.Label == SellLabel)).ToArray();
        }

        private void RecoverToday()
        {
            int buys = History.FindAll(BuyLabel)
                .Count(h => h.SymbolName == Symbol.Name && h.EntryTime.Date == _utcDay);
            int sells = History.FindAll(SellLabel)
                .Count(h => h.SymbolName == Symbol.Name && h.EntryTime.Date == _utcDay);

            bool buyOpen = Positions.Any(p => p.SymbolName == Symbol.Name && p.Label == BuyLabel);
            bool sellOpen = Positions.Any(p => p.SymbolName == Symbol.Name && p.Label == SellLabel);
            _cyclesToday = Math.Max(buys + (buyOpen ? 1 : 0), sells + (sellOpen ? 1 : 0));
            _wasInCycle = buyOpen || sellOpen;
            _lastFlat = Server.Time;

            Print("HEDGE-2R RESTORED cyclesToday={0}, openLegs={1}", _cyclesToday, BotPositions().Length);
        }

        private void ResetDay()
        {
            if (Server.Time.Date == _utcDay) return;
            _utcDay = Server.Time.Date;
            _cyclesToday = 0;
            _lastFlat = Server.Time;
            RecoverToday();
        }

        protected override void OnTimer()
        {
            ResetDay();

            var open = BotPositions();
            if (open.Length > 0)
            {
                _wasInCycle = true;
                if (Server.Time >= _nextHeartbeat)
                {
                    Print("HEDGE-2R ACTIVE openLegs={0}, cyclesToday={1}/{2}", open.Length, _cyclesToday, MaxCyclesPerDay);
                    _nextHeartbeat = Server.Time.AddMinutes(5);
                }
                return;
            }

            if (_wasInCycle)
            {
                _wasInCycle = false;
                _lastFlat = Server.Time;
                Print("HEDGE-2R cycle fully closed. Cooldown {0}s.", CooldownSeconds);
            }

            if (_cyclesToday >= MaxCyclesPerDay) return;
            if (!Symbol.MarketHours.IsOpened()) return;
            if ((Server.Time - _lastFlat).TotalSeconds < CooldownSeconds) return;

            OpenPair();
        }

        private void OpenPair()
        {
            if (_m1 == null || _m1.Count < Math.Max(60, AtrPeriod + 10)) return;

            double atrPrice = ClosedAtr(_m1, AtrPeriod, 50);
            if (atrPrice <= 0 || Symbol.PipSize <= 0) return;

            double spreadPips = (Symbol.Ask - Symbol.Bid) / Symbol.PipSize;
            if (spreadPips <= 0) return;

            double atrPips = atrPrice / Symbol.PipSize;
            double stopPips = Math.Max(MinimumStopPips, atrPips * StopAtrMultiplier);
            double targetPips = stopPips * RewardRisk;

            if (spreadPips / stopPips > MaxSpreadToStop)
            {
                if (DiagnosticLogs)
                    Print("HEDGE-2R WAIT spread/stop={0:F3} > max={1:F3}", spreadPips / stopPips, MaxSpreadToStop);
                return;
            }

            double legRiskPct = TotalPairRiskPercent / 2.0;
            double units = Symbol.VolumeForProportionalRisk(
                ProportionalAmountType.Equity, legRiskPct, stopPips, RoundingMode.Down);
            units = Symbol.NormalizeVolumeInUnits(units, RoundingMode.Down);

            if (units < Symbol.VolumeInUnitsMin)
            {
                Print("HEDGE-2R SKIP calculated volume={0} below broker minimum={1}; risk not forced upward.",
                    units, Symbol.VolumeInUnitsMin);
                return;
            }
            if (units > Symbol.VolumeInUnitsMax)
                units = Symbol.VolumeInUnitsMax;

            double legBudget = Account.Equity * legRiskPct / 100.0;
            double cashRisk = Symbol.AmountRisked(units, stopPips);
            if (cashRisk <= 0 || cashRisk > legBudget * MaxActualRiskToBudget)
            {
                Print("HEDGE-2R RISK SKIP stop={0:F2}p units={1} cashRisk≈{2:F2} budget={3:F2}",
                    stopPips, units, cashRisk, legBudget);
                return;
            }

            // Place first leg, then the opposite leg immediately. If the second fails,
            // close the first so the strategy never intentionally keeps an unpaired launch.
            TradeResult buy = ExecuteMarketOrder(TradeType.Buy, Symbol.Name, units, BuyLabel, stopPips, targetPips);
            if (!buy.IsSuccessful || buy.Position == null)
            {
                Print("HEDGE-2R BUY FAILED error={0}", buy.Error);
                return;
            }

            TradeResult sell = ExecuteMarketOrder(TradeType.Sell, Symbol.Name, units, SellLabel, stopPips, targetPips);
            if (!sell.IsSuccessful || sell.Position == null)
            {
                Print("HEDGE-2R SELL FAILED error={0}; closing BUY id={1} to avoid unpaired exposure.", sell.Error, buy.Position.Id);
                var flatten = ClosePosition(buy.Position);
                if (!flatten.IsSuccessful)
                    Print("HEDGE-2R EMERGENCY CLOSE FAILED id={0} error={1}", buy.Position.Id, flatten.Error);
                return;
            }

            _cyclesToday++;
            _wasInCycle = true;
            Print("HEDGE-2R OPEN cycle={0}/{1} BUY#{2} SELL#{3} units={4} SL={5:F2}p TP={6:F2}p RR={7:F2}:1 spread={8:F2}p riskEach≈{9:F2}%",
                _cyclesToday, MaxCyclesPerDay, buy.Position.Id, sell.Position.Id,
                units, stopPips, targetPips, RewardRisk, spreadPips, legRiskPct);
        }

        private double ClosedAtr(Bars bars, int period, int warmup)
        {
            int oldest = Math.Min(bars.Count - 3, warmup + period);
            if (oldest <= period + 1) return 0;

            double atr = 0;
            int n = 0;
            for (int i = oldest; i >= 1; i--)
            {
                double high = bars.HighPrices.Last(i);
                double low = bars.LowPrices.Last(i);
                double prev = bars.ClosePrices.Last(i + 1);
                double tr = Math.Max(high - low,
                    Math.Max(Math.Abs(high - prev), Math.Abs(low - prev)));

                if (n < period)
                {
                    atr += tr;
                    n++;
                    if (n == period) atr /= period;
                }
                else
                    atr = (atr * (period - 1) + tr) / period;
            }
            return n == period ? atr : 0;
        }

        protected override void OnError(Error error)
        {
            Print("HEDGE-2R cTrader error {0}", error.Code);
        }

        protected override void OnStop()
        {
            Timer.Stop();
            Print("HEDGE-2R stopped.");
        }
    }
}
