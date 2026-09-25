using System;
using System.Collections.Generic;
using System.Linq;
using cAlgo.API;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    // Experimental smart paired hedge bot.
    // It does NOT open continuously: it waits for a compressed range, a fresh breakout impulse,
    // and low normalized spread, then launches BUY + SELL with a fixed 2:1 target/stop geometry.
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class DualHedgeSmart2R : Robot
    {
        [Parameter("Demo ONLY - block live", DefaultValue = true, Group = "Safety")]
        public bool DemoOnly { get; set; }

        [Parameter("Base label", DefaultValue = "HEDGE-SMART-2R-V2-RELAXED", Group = "Safety")]
        public string BaseLabel { get; set; }

        [Parameter("FX total pair risk (%)", DefaultValue = 0.20, MinValue = 0.02, MaxValue = 2.0, Group = "Risk")]
        public double FxPairRiskPercent { get; set; }

        [Parameter("Gold total pair risk (%)", DefaultValue = 0.30, MinValue = 0.02, MaxValue = 2.0, Group = "Risk")]
        public double GoldPairRiskPercent { get; set; }

        [Parameter("Max cycles per UTC day", DefaultValue = 40, MinValue = 1, MaxValue = 100, Group = "Risk")]
        public int MaxCyclesPerDay { get; set; }

        [Parameter("Cooldown after flat (sec)", DefaultValue = 30, MinValue = 0, MaxValue = 3600, Group = "Risk")]
        public int CooldownSeconds { get; set; }

        [Parameter("EURUSD symbol", DefaultValue = "EURUSD", Group = "Markets")]
        public string EurUsdName { get; set; }

        [Parameter("GBPUSD symbol", DefaultValue = "GBPUSD", Group = "Markets")]
        public string GbpUsdName { get; set; }

        [Parameter("USDJPY symbol", DefaultValue = "USDJPY", Group = "Markets")]
        public string UsdJpyName { get; set; }

        [Parameter("GOLD symbol", DefaultValue = "XAUUSD", Group = "Markets")]
        public string GoldName { get; set; }

        [Parameter("Enable EURUSD", DefaultValue = true, Group = "Markets")]
        public bool EnableEurUsd { get; set; }

        [Parameter("Enable GBPUSD", DefaultValue = true, Group = "Markets")]
        public bool EnableGbpUsd { get; set; }

        [Parameter("Enable USDJPY", DefaultValue = true, Group = "Markets")]
        public bool EnableUsdJpy { get; set; }

        [Parameter("Enable GOLD", DefaultValue = true, Group = "Markets")]
        public bool EnableGold { get; set; }

        [Parameter("Gold-only test", DefaultValue = false, Group = "Markets")]
        public bool GoldOnlyTest { get; set; }

        [Parameter("ATR period", DefaultValue = 14, MinValue = 2, MaxValue = 100, Group = "Setup")]
        public int AtrPeriod { get; set; }

        [Parameter("Compression bars", DefaultValue = 8, MinValue = 4, MaxValue = 30, Group = "Setup")]
        public int CompressionBars { get; set; }

        [Parameter("Compression max ratio", DefaultValue = 1.20, MinValue = 0.20, MaxValue = 1.50, Group = "Setup")]
        public double CompressionMaxRatio { get; set; }

        [Parameter("Min breakout body / ATR", DefaultValue = 0.15, MinValue = 0.05, MaxValue = 3.0, Group = "Setup")]
        public double MinBreakoutBodyAtr { get; set; }

        [Parameter("Max breakout chase / ATR", DefaultValue = 0.90, MinValue = 0.05, MaxValue = 2.0, Group = "Setup")]
        public double MaxBreakoutChaseAtr { get; set; }

        [Parameter("Stop ATR multiplier", DefaultValue = 1.35, MinValue = 0.2, MaxValue = 10.0, Group = "Stops")]
        public double StopAtrMultiplier { get; set; }

        [Parameter("FX min stop pips", DefaultValue = 4.0, MinValue = 0.5, MaxValue = 100, Group = "Stops")]
        public double FxMinStopPips { get; set; }

        [Parameter("FX max stop pips", DefaultValue = 15.0, MinValue = 1.0, MaxValue = 200, Group = "Stops")]
        public double FxMaxStopPips { get; set; }

        [Parameter("Gold min stop USD", DefaultValue = 0.60, MinValue = 0.05, MaxValue = 20.0, Group = "Stops")]
        public double GoldMinStopPrice { get; set; }

        [Parameter("Gold max stop USD", DefaultValue = 8.0, MinValue = 0.10, MaxValue = 100.0, Group = "Stops")]
        public double GoldMaxStopPrice { get; set; }

        [Parameter("Reward / risk", DefaultValue = 2.0, MinValue = 2.0, MaxValue = 2.0, Group = "Stops")]
        public double RewardRisk { get; set; }

        [Parameter("Max spread / stop", DefaultValue = 0.25, MinValue = 0.01, MaxValue = 0.50, Group = "Execution")]
        public double MaxSpreadToStop { get; set; }

        [Parameter("Max spread / ATR", DefaultValue = 0.35, MinValue = 0.01, MaxValue = 1.0, Group = "Execution")]
        public double MaxSpreadToAtr { get; set; }

        [Parameter("Max actual risk / budget", DefaultValue = 1.10, MinValue = 1.0, MaxValue = 2.0, Group = "Execution")]
        public double MaxActualRiskToBudget { get; set; }

        [Parameter("Diagnostic logs", DefaultValue = true, Group = "Logs")]
        public bool DiagnosticLogs { get; set; }

        private sealed class MarketInfo
        {
            public Symbol Symbol;
            public Bars M1;
            public bool Gold;
            public DateTime LastExamined = DateTime.MinValue;
        }

        private sealed class Candidate
        {
            public MarketInfo Market;
            public double AtrPrice;
            public double StopPips;
            public double SpreadPips;
            public double SpreadToStop;
            public double SpreadToAtr;
            public double CompressionRatio;
            public double BreakoutBodyAtr;
            public double ChaseAtr;
            public string BreakoutSide;
            public double SetupQuality;
        }

        private readonly List<MarketInfo> _markets = new List<MarketInfo>();
        private readonly Dictionary<string, string> _notes = new Dictionary<string, string>();
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
                Print("HEDGE-SMART safety block: DemoOnly=true on LIVE account.");
                Stop();
                return;
            }

            if (string.IsNullOrWhiteSpace(BaseLabel) ||
                FxMinStopPips > FxMaxStopPips ||
                GoldMinStopPrice > GoldMaxStopPrice ||
                CompressionBars < 4)
            {
                Print("HEDGE-SMART invalid parameters.");
                Stop();
                return;
            }

            AddMarket(EnableEurUsd && !GoldOnlyTest, EurUsdName, false);
            AddMarket(EnableGbpUsd && !GoldOnlyTest, GbpUsdName, false);
            AddMarket(EnableUsdJpy && !GoldOnlyTest, UsdJpyName, false);
            AddMarket(EnableGold, GoldName, true);

            if (_markets.Count == 0)
            {
                Print("HEDGE-SMART no broker symbols loaded.");
                Stop();
                return;
            }

            _utcDay = Server.Time.Date;
            RecoverToday();
            Timer.Start(5);

            Print("HEDGE-SMART 2R V2 RELAXED ON | markets={0} | lower entry thresholds, LOWEST normalized spread | RR=2:1",
                string.Join(",", _markets.Select(m => m.Symbol.Name)));
            Print("ENTRY ZONE = prior compression + fresh breakout impulse + no late chase. GOLD enabled={0}, goldOnly={1}.",
                EnableGold, GoldOnlyTest);
            Print("Normalized spread comparison uses spread/stop and spread/ATR; raw FX pips vs GOLD price spread are NOT compared directly.");
        }

        private void AddMarket(bool enabled, string name, bool gold)
        {
            if (!enabled || string.IsNullOrWhiteSpace(name)) return;
            try
            {
                var s = Symbols.GetSymbol(name.Trim());
                if (s == null)
                {
                    Print("HEDGE-SMART missing broker symbol {0}.", name);
                    return;
                }
                var bars = MarketData.GetBars(TimeFrame.Minute, s.Name);
                _markets.Add(new MarketInfo { Symbol = s, M1 = bars, Gold = gold });
                Print("HEDGE-SMART tracking {0} as {1}, minVolume={2}, pip={3}",
                    s.Name, gold ? "GOLD" : "FX", s.VolumeInUnitsMin, s.PipSize);
            }
            catch (Exception ex)
            {
                Print("HEDGE-SMART failed to load {0}: {1}", name, ex.Message);
            }
        }

        private Position[] BotPositions()
        {
            var names = new HashSet<string>(_markets.Select(m => m.Symbol.Name));
            return Positions.Where(p => names.Contains(p.SymbolName) &&
                (p.Label == BuyLabel || p.Label == SellLabel)).ToArray();
        }

        private void RecoverToday()
        {
            var names = new HashSet<string>(_markets.Select(m => m.Symbol.Name));
            int buys = History.FindAll(BuyLabel)
                .Count(h => names.Contains(h.SymbolName) && h.EntryTime.Date == _utcDay);
            int sells = History.FindAll(SellLabel)
                .Count(h => names.Contains(h.SymbolName) && h.EntryTime.Date == _utcDay);

            _cyclesToday = Math.Max(buys, sells);
            if (BotPositions().Length > 0) _cyclesToday++;
            _wasInCycle = BotPositions().Length > 0;
            _lastFlat = Server.Time;

            Print("HEDGE-SMART RESTORED cyclesToday={0}, openLegs={1}", _cyclesToday, BotPositions().Length);
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
                    Print("HEDGE-SMART ACTIVE openLegs={0}, cycles={1}/{2}", open.Length, _cyclesToday, MaxCyclesPerDay);
                    _nextHeartbeat = Server.Time.AddMinutes(5);
                }
                return;
            }

            if (_wasInCycle)
            {
                _wasInCycle = false;
                _lastFlat = Server.Time;
                Print("HEDGE-SMART cycle flat. Cooldown={0}s.", CooldownSeconds);
            }

            if (_cyclesToday >= MaxCyclesPerDay) return;
            if ((Server.Time - _lastFlat).TotalSeconds < CooldownSeconds) return;

            var candidates = new List<Candidate>();
            foreach (var market in _markets)
            {
                if (!market.Symbol.MarketHours.IsOpened())
                {
                    _notes[market.Symbol.Name] = "market closed";
                    continue;
                }

                Candidate c = Evaluate(market);
                if (c != null) candidates.Add(c);
            }

            if (candidates.Count == 0)
            {
                if (DiagnosticLogs && Server.Time >= _nextHeartbeat)
                {
                    Print("HEDGE-SMART WAIT | {0}",
                        string.Join("; ", _markets.Select(m => m.Symbol.Name + "=" +
                            (_notes.ContainsKey(m.Symbol.Name) ? _notes[m.Symbol.Name] : "waiting"))));
                    _nextHeartbeat = Server.Time.AddMinutes(5);
                }
                return;
            }

            // "Lowest spread" across different instruments must be normalized.
            // Primary rank: spread relative to stop. Secondary: spread relative to ATR.
            // Only then use setup quality.
            var selected = candidates
                .OrderBy(c => c.SpreadToStop)
                .ThenBy(c => c.SpreadToAtr)
                .ThenByDescending(c => c.SetupQuality)
                .First();

            Print("HEDGE-SMART SELECT {0}: normSpread/SL={1:F3}, spread/ATR={2:F3}, compression={3:F2}, breakout={4}, body/ATR={5:F2}, chase/ATR={6:F2}",
                selected.Market.Symbol.Name, selected.SpreadToStop, selected.SpreadToAtr,
                selected.CompressionRatio, selected.BreakoutSide,
                selected.BreakoutBodyAtr, selected.ChaseAtr);

            OpenPair(selected);
        }

        private Candidate Evaluate(MarketInfo market)
        {
            Bars b = market.M1;
            Symbol s = market.Symbol;
            int minBars = Math.Max(120, CompressionBars + 60);
            if (b == null || b.Count < minBars)
            {
                _notes[s.Name] = "loading history";
                return null;
            }

            DateTime barTime = b.OpenTimes.Last(1);
            if (barTime <= market.LastExamined) return null;
            market.LastExamined = barTime;

            if ((Server.Time - barTime).TotalSeconds > 170)
            {
                _notes[s.Name] = "stale M1";
                return null;
            }

            double atr = ClosedAtr(b, AtrPeriod, 70);
            if (atr <= 0 || s.PipSize <= 0)
            {
                _notes[s.Name] = "ATR unavailable";
                return null;
            }

            // Compression is measured BEFORE the breakout candle.
            double recentTr = AverageTrueRangeWindow(b, 2, CompressionBars);
            double baselineTr = AverageTrueRangeWindow(b, CompressionBars + 2, 30);
            if (recentTr <= 0 || baselineTr <= 0)
            {
                _notes[s.Name] = "TR history unavailable";
                return null;
            }

            double compression = recentTr / baselineTr;
            if (compression > CompressionMaxRatio)
            {
                _notes[s.Name] = string.Format("no squeeze {0:F2}>{1:F2}", compression, CompressionMaxRatio);
                return null;
            }

            double priorHigh = double.MinValue;
            double priorLow = double.MaxValue;
            for (int i = 2; i < CompressionBars + 2; i++)
            {
                priorHigh = Math.Max(priorHigh, b.HighPrices.Last(i));
                priorLow = Math.Min(priorLow, b.LowPrices.Last(i));
            }

            double open = b.OpenPrices.Last(1);
            double close = b.ClosePrices.Last(1);
            double bodyAtr = Math.Abs(close - open) / atr;
            if (bodyAtr < MinBreakoutBodyAtr)
            {
                _notes[s.Name] = string.Format("weak impulse body/ATR={0:F2}", bodyAtr);
                return null;
            }

            bool up = close > priorHigh;
            bool down = close < priorLow;
            if (!up && !down)
            {
                _notes[s.Name] = "still inside compressed range";
                return null;
            }

            double chase = up ? (close - priorHigh) / atr : (priorLow - close) / atr;
            if (chase < 0 || chase > MaxBreakoutChaseAtr)
            {
                _notes[s.Name] = string.Format("late breakout chase/ATR={0:F2}", chase);
                return null;
            }

            double spreadPrice = s.Ask - s.Bid;
            if (spreadPrice <= 0)
            {
                _notes[s.Name] = "invalid spread";
                return null;
            }

            double stopPrice = Math.Max(atr * StopAtrMultiplier, spreadPrice * 4.0);
            if (market.Gold)
            {
                stopPrice = Math.Max(stopPrice, GoldMinStopPrice);
                if (stopPrice > GoldMaxStopPrice)
                {
                    _notes[s.Name] = "gold stop too wide";
                    return null;
                }
            }
            else
            {
                stopPrice = Math.Max(stopPrice, FxMinStopPips * s.PipSize);
                if (stopPrice / s.PipSize > FxMaxStopPips)
                {
                    _notes[s.Name] = "FX stop too wide";
                    return null;
                }
            }

            double spreadToStop = spreadPrice / stopPrice;
            double spreadToAtr = spreadPrice / atr;
            if (spreadToStop > MaxSpreadToStop || spreadToAtr > MaxSpreadToAtr)
            {
                _notes[s.Name] = string.Format("spread costly s/SL={0:F2}, s/ATR={1:F2}", spreadToStop, spreadToAtr);
                return null;
            }

            double setupQuality =
                (CompressionMaxRatio - compression) * 2.0 +
                Math.Min(1.5, bodyAtr) * 0.60 +
                (MaxBreakoutChaseAtr - chase) * 0.40 -
                spreadToStop * 2.0;

            _notes[s.Name] = string.Format("QUALIFIED s/SL={0:F3}", spreadToStop);
            return new Candidate
            {
                Market = market,
                AtrPrice = atr,
                StopPips = stopPrice / s.PipSize,
                SpreadPips = spreadPrice / s.PipSize,
                SpreadToStop = spreadToStop,
                SpreadToAtr = spreadToAtr,
                CompressionRatio = compression,
                BreakoutBodyAtr = bodyAtr,
                ChaseAtr = chase,
                BreakoutSide = up ? "UP" : "DOWN",
                SetupQuality = setupQuality
            };
        }

        private void OpenPair(Candidate c)
        {
            if (BotPositions().Length > 0) return;

            Symbol s = c.Market.Symbol;
            if (!s.MarketHours.IsOpened()) return;

            double liveSpreadPrice = s.Ask - s.Bid;
            if (liveSpreadPrice <= 0) return;
            double liveSpreadToStop = liveSpreadPrice / (c.StopPips * s.PipSize);
            if (liveSpreadToStop > MaxSpreadToStop)
            {
                Print("HEDGE-SMART CANCEL {0}: spread worsened before execution.", s.Name);
                return;
            }

            double pairRisk = c.Market.Gold ? GoldPairRiskPercent : FxPairRiskPercent;
            double legRiskPct = pairRisk / 2.0;
            double units = s.VolumeForProportionalRisk(
                ProportionalAmountType.Equity, legRiskPct, c.StopPips, RoundingMode.Down);
            units = s.NormalizeVolumeInUnits(units, RoundingMode.Down);

            if (units < s.VolumeInUnitsMin)
            {
                Print("HEDGE-SMART RISK SKIP {0}: calculated volume={1} < broker min={2}. No forced gold/FX risk.",
                    s.Name, units, s.VolumeInUnitsMin);
                return;
            }
            if (units > s.VolumeInUnitsMax) units = s.VolumeInUnitsMax;

            double cashBudget = Account.Equity * legRiskPct / 100.0;
            double actualRisk = s.AmountRisked(units, c.StopPips);
            if (actualRisk <= 0 || actualRisk > cashBudget * MaxActualRiskToBudget)
            {
                Print("HEDGE-SMART RISK SKIP {0}: stop={1:F2}p units={2} risk≈{3:F2} > budget={4:F2}",
                    s.Name, c.StopPips, units, actualRisk, cashBudget);
                return;
            }

            double tpPips = c.StopPips * 2.0;

            TradeResult buy = ExecuteMarketOrder(TradeType.Buy, s.Name, units, BuyLabel, c.StopPips, tpPips);
            if (!buy.IsSuccessful || buy.Position == null)
            {
                Print("HEDGE-SMART BUY FAILED {0}: {1}", s.Name, buy.Error);
                return;
            }

            TradeResult sell = ExecuteMarketOrder(TradeType.Sell, s.Name, units, SellLabel, c.StopPips, tpPips);
            if (!sell.IsSuccessful || sell.Position == null)
            {
                Print("HEDGE-SMART SELL FAILED {0}: {1}; closing unpaired BUY#{2}.", s.Name, sell.Error, buy.Position.Id);
                var flatten = ClosePosition(buy.Position);
                if (!flatten.IsSuccessful)
                    Print("HEDGE-SMART EMERGENCY CLOSE FAILED BUY#{0}: {1}", buy.Position.Id, flatten.Error);
                return;
            }

            _cyclesToday++;
            _wasInCycle = true;
            Print("HEDGE-SMART OPEN {0} cycle={1}/{2} BUY#{3}+SELL#{4} units={5} SL={6:F2}p TP={7:F2}p RR=2:1 normalizedSpread={8:F3} setup={9:F2} breakout={10}",
                s.Name, _cyclesToday, MaxCyclesPerDay, buy.Position.Id, sell.Position.Id,
                units, c.StopPips, tpPips, liveSpreadToStop, c.SetupQuality, c.BreakoutSide);
        }

        private double AverageTrueRangeWindow(Bars b, int startLastIndex, int count)
        {
            if (b.Count < startLastIndex + count + 2) return 0;
            double sum = 0;
            for (int i = startLastIndex; i < startLastIndex + count; i++)
            {
                double high = b.HighPrices.Last(i);
                double low = b.LowPrices.Last(i);
                double prev = b.ClosePrices.Last(i + 1);
                sum += Math.Max(high - low,
                    Math.Max(Math.Abs(high - prev), Math.Abs(low - prev)));
            }
            return sum / count;
        }

        private double ClosedAtr(Bars b, int period, int warmup)
        {
            int oldest = Math.Min(b.Count - 3, warmup + period);
            if (oldest <= period + 1) return 0;
            double atr = 0;
            int n = 0;
            for (int i = oldest; i >= 1; i--)
            {
                double high = b.HighPrices.Last(i);
                double low = b.LowPrices.Last(i);
                double prev = b.ClosePrices.Last(i + 1);
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
            Print("HEDGE-SMART cTrader error {0}", error.Code);
        }

        protected override void OnStop()
        {
            Timer.Stop();
            Print("HEDGE-SMART stopped.");
        }
    }
}
