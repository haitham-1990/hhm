using System;
using System.Collections.Generic;
using System.Linq;
using cAlgo.API;
using cAlgo.API.Internals;
using cAlgo.API.Indicators;

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

        [Parameter("Base label", DefaultValue = "HEDGE-MATH-EDGE-V6-2", Group = "Safety")]
        public string BaseLabel { get; set; }

        [Parameter("FX total pair risk (%)", DefaultValue = 0.30, MinValue = 0.02, MaxValue = 2.0, Group = "Risk")]
        public double FxPairRiskPercent { get; set; }

        [Parameter("Gold total pair risk (%)", DefaultValue = 0.40, MinValue = 0.02, MaxValue = 2.0, Group = "Risk")]
        public double GoldPairRiskPercent { get; set; }

        [Parameter("Max cycles per UTC day", DefaultValue = 150, MinValue = 1, MaxValue = 300, Group = "Risk")]
        public int MaxCyclesPerDay { get; set; }

        [Parameter("Cooldown after flat (sec)", DefaultValue = 5, MinValue = 0, MaxValue = 3600, Group = "Risk")]
        public int CooldownSeconds { get; set; }

        [Parameter("Max simultaneous pairs", DefaultValue = 4, MinValue = 1, MaxValue = 4, Group = "Risk")]
        public int MaxSimultaneousPairs { get; set; }

        [Parameter("Max nominal open risk (%)", DefaultValue = 1.50, MinValue = 0.20, MaxValue = 5.0, Group = "Risk")]
        public double MaxNominalOpenRiskPercent { get; set; }

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

        [Parameter("M1 EMA fast", DefaultValue = 9, MinValue = 2, MaxValue = 100, Group = "Indicators")]
        public int M1EmaFast { get; set; }

        [Parameter("M1 EMA slow", DefaultValue = 21, MinValue = 3, MaxValue = 200, Group = "Indicators")]
        public int M1EmaSlow { get; set; }

        [Parameter("M5 EMA fast", DefaultValue = 20, MinValue = 2, MaxValue = 100, Group = "Indicators")]
        public int M5EmaFast { get; set; }

        [Parameter("M5 EMA slow", DefaultValue = 50, MinValue = 3, MaxValue = 200, Group = "Indicators")]
        public int M5EmaSlow { get; set; }

        [Parameter("RSI period", DefaultValue = 7, MinValue = 2, MaxValue = 100, Group = "Indicators")]
        public int RsiPeriod { get; set; }

        [Parameter("Volume lookback", DefaultValue = 20, MinValue = 5, MaxValue = 100, Group = "Indicators")]
        public int VolumeLookback { get; set; }

        [Parameter("Min indicator score", DefaultValue = 0.75, MinValue = 0.0, MaxValue = 4.0, Group = "Indicators")]
        public double MinIndicatorScore { get; set; }

        [Parameter("Kaufman ER period", DefaultValue = 12, MinValue = 5, MaxValue = 50, Group = "Math Edge")]
        public int EfficiencyRatioPeriod { get; set; }

        [Parameter("ADX period", DefaultValue = 14, MinValue = 5, MaxValue = 50, Group = "Math Edge")]
        public int AdxPeriod { get; set; }

        [Parameter("ATR percentile lookback", DefaultValue = 60, MinValue = 30, MaxValue = 200, Group = "Math Edge")]
        public int AtrPercentileLookback { get; set; }

        [Parameter("Bollinger period", DefaultValue = 20, MinValue = 10, MaxValue = 50, Group = "Math Edge")]
        public int BollingerPeriod { get; set; }

        [Parameter("Min Math Edge score", DefaultValue = 2.75, MinValue = 0.0, MaxValue = 10.0, Group = "Math Edge")]
        public double MinMathEdgeScore { get; set; }

        [Parameter("Avoid correlated EUR/GBP pairs", DefaultValue = true, Group = "Math Edge")]
        public bool AvoidCorrelatedFxPairs { get; set; }

        [Parameter("Compression bars", DefaultValue = 8, MinValue = 4, MaxValue = 30, Group = "Setup")]
        public int CompressionBars { get; set; }

        [Parameter("Compression max ratio", DefaultValue = 1.35, MinValue = 0.20, MaxValue = 1.50, Group = "Setup")]
        public double CompressionMaxRatio { get; set; }

        [Parameter("Min breakout body / ATR", DefaultValue = 0.10, MinValue = 0.05, MaxValue = 3.0, Group = "Setup")]
        public double MinBreakoutBodyAtr { get; set; }

        [Parameter("Max breakout chase / ATR", DefaultValue = 1.20, MinValue = 0.05, MaxValue = 2.0, Group = "Setup")]
        public double MaxBreakoutChaseAtr { get; set; }

        [Parameter("Stop ATR multiplier", DefaultValue = 0.65, MinValue = 0.2, MaxValue = 10.0, Group = "Stops")]
        public double StopAtrMultiplier { get; set; }

        [Parameter("FX min stop pips", DefaultValue = 2.0, MinValue = 0.5, MaxValue = 100, Group = "Stops")]
        public double FxMinStopPips { get; set; }

        [Parameter("FX max stop pips", DefaultValue = 6.0, MinValue = 1.0, MaxValue = 200, Group = "Stops")]
        public double FxMaxStopPips { get; set; }

        [Parameter("Gold min stop USD", DefaultValue = 0.30, MinValue = 0.05, MaxValue = 20.0, Group = "Stops")]
        public double GoldMinStopPrice { get; set; }

        [Parameter("Gold max stop USD", DefaultValue = 1.50, MinValue = 0.10, MaxValue = 100.0, Group = "Stops")]
        public double GoldMaxStopPrice { get; set; }

        [Parameter("Reward / risk", DefaultValue = 2.0, MinValue = 2.0, MaxValue = 2.0, Group = "Stops")]
        public double RewardRisk { get; set; }

        [Parameter("Max spread / stop", DefaultValue = 0.30, MinValue = 0.01, MaxValue = 0.50, Group = "Execution")]
        public double MaxSpreadToStop { get; set; }

        [Parameter("Max spread / ATR", DefaultValue = 0.45, MinValue = 0.01, MaxValue = 1.0, Group = "Execution")]
        public double MaxSpreadToAtr { get; set; }

        [Parameter("Max actual risk / budget", DefaultValue = 1.10, MinValue = 1.0, MaxValue = 2.0, Group = "Execution")]
        public double MaxActualRiskToBudget { get; set; }

        [Parameter("Max hold seconds (0=OFF)", DefaultValue = 0, MinValue = 0, MaxValue = 1800, Group = "Exits")]
        public int MaxHoldSeconds { get; set; }

        [Parameter("Initial sister floor after SL (R)", DefaultValue = 0.10, MinValue = 0.0, MaxValue = 0.50, Group = "Pair Math")]
        public double InitialSisterFloorR { get; set; }

        [Parameter("Pair recovery trigger (R)", DefaultValue = 1.25, MinValue = 1.0, MaxValue = 1.90, Group = "Pair Math")]
        public double PairRecoveryTriggerR { get; set; }

        [Parameter("Pair locked buffer over loss (R)", DefaultValue = 0.12, MinValue = 0.02, MaxValue = 0.50, Group = "Pair Math")]
        public double PairLockedBufferR { get; set; }

        [Parameter("Late partial profit (%)", DefaultValue = 0.0, MinValue = 0.0, MaxValue = 50.0, Group = "Pair Math")]
        public double SisterPartialPercent { get; set; }

        [Parameter("Late partial trigger (R)", DefaultValue = 1.50, MinValue = 1.10, MaxValue = 1.95, Group = "Pair Math")]
        public double SisterPartialTriggerR { get; set; }

        [Parameter("Trail distance (R)", DefaultValue = 0.35, MinValue = 0.10, MaxValue = 1.00, Group = "Pair Math")]
        public double SisterTrailR { get; set; }

        [Parameter("Trail activation (R)", DefaultValue = 1.60, MinValue = 1.10, MaxValue = 1.95, Group = "Pair Math")]
        public double SisterTrailActivationR { get; set; }

        [Parameter("Close sister if protection fails", DefaultValue = true, Group = "Pair Math")]
        public bool CloseSisterIfLockFails { get; set; }

        [Parameter("Whipsaw cooldown after SL (sec)", DefaultValue = 45, MinValue = 0, MaxValue = 600, Group = "Exits")]
        public int WhipsawCooldownSeconds { get; set; }

        [Parameter("Diagnostic logs", DefaultValue = true, Group = "Logs")]
        public bool DiagnosticLogs { get; set; }

        private sealed class MarketInfo
        {
            public Symbol Symbol;
            public Bars M1;
            public Bars M5;
            public ExponentialMovingAverage M1Fast;
            public ExponentialMovingAverage M1Slow;
            public ExponentialMovingAverage M5Fast;
            public ExponentialMovingAverage M5Slow;
            public RelativeStrengthIndex Rsi;
            public bool Gold;
            public DateTime LastExamined = DateTime.MinValue;
        }

        private sealed class ProtectedLeg
        {
            public long PositionId;
            public string SymbolName;
            public double OriginalStopPips;
            public double BestPrice;
            public bool PartialTaken;
            public bool RecoveryLocked;
            public double FirstLossCash;
            public double LegRiskCash;
        }

        private sealed class PairExperiment
        {
            public string SymbolName;
            public long BuyId;
            public long SellId;
            public double LegRiskCash;
            public double RealizedNet;
            public double MfeR;
            public double MaeR;
            public DateTime OpenTime;
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
            public double IndicatorScore;
            public double VolumeRatio;
            public double EfficiencyRatio;
            public double Adx;
            public double AtrPercentile;
            public double BollingerExpansion;
            public double EmaSlopeAtr;
            public double BodyWickRatio;
            public double MathEdgeScore;
            public double TotalScore;
        }

        private readonly List<MarketInfo> _markets = new List<MarketInfo>();
        private readonly Dictionary<string, string> _notes = new Dictionary<string, string>();
        private readonly Dictionary<string, DateTime> _lastLaunchBySymbol = new Dictionary<string, DateTime>();
        private readonly Dictionary<string, DateTime> _whipsawUntil = new Dictionary<string, DateTime>();
        private readonly Dictionary<long, ProtectedLeg> _protectedLegs = new Dictionary<long, ProtectedLeg>();
        private readonly Dictionary<string, PairExperiment> _pairExperiments = new Dictionary<string, PairExperiment>();
        private readonly List<double> _completedPairR = new List<double>();
        private DateTime _utcDay;
        private DateTime _lastFlat = DateTime.MinValue;
        private int _cyclesToday;
        private bool _wasInCycle;
        private DateTime _nextHeartbeat = DateTime.MinValue;
        private DateTime _nextStats = DateTime.MinValue;

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
                M1EmaFast >= M1EmaSlow || M5EmaFast >= M5EmaSlow ||
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
            Positions.Closed += OnPositionClosed;
            Timer.Start(2);

            Print("HEDGE MATH EDGE V6.2 ON | markets={0} | scan=2s | maxPairs={1} ({2} positions) | maxHold={3}s (0=OFF) | RR=2:1",
                string.Join(",", _markets.Select(m => m.Symbol.Name)),
                MaxSimultaneousPairs, MaxSimultaneousPairs * 2, MaxHoldSeconds);
            Print("Risk: FX pair={0:F2}%, GOLD pair={1:F2}%, nominal portfolio cap={2:F2}%. Indicators score EMA9/21 + M5 EMA20/50 + RSI7 + tick-volume + breakout/ATR.",
                FxPairRiskPercent, GoldPairRiskPercent, MaxNominalOpenRiskPercent);
            Print("PAIR MATH: first SL => floor={0:F2}R; recovery at {1:F2}R; then lock enough to cover ACTUAL first loss + {2:F2}R buffer. Partial={3:F0}% only after {4:F2}R; trail after {5:F2}R.",
                InitialSisterFloorR, PairRecoveryTriggerR, PairLockedBufferR,
                SisterPartialPercent, SisterPartialTriggerR, SisterTrailActivationR);
            Print("MATH EDGE SCORE uses Kaufman ER + ADX + ATR percentile + Bollinger expansion + EMA slope + body/wick + volume + cost.");
            Print("EXPERIMENT: evaluate 100 completed PAIRS (not individual legs). Expectancy/MFE/MAE are reported in R.");
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
                var bars5 = MarketData.GetBars(TimeFrame.Minute5, s.Name);
                _markets.Add(new MarketInfo
                {
                    Symbol = s,
                    M1 = bars,
                    M5 = bars5,
                    M1Fast = Indicators.ExponentialMovingAverage(bars.ClosePrices, M1EmaFast),
                    M1Slow = Indicators.ExponentialMovingAverage(bars.ClosePrices, M1EmaSlow),
                    M5Fast = Indicators.ExponentialMovingAverage(bars5.ClosePrices, M5EmaFast),
                    M5Slow = Indicators.ExponentialMovingAverage(bars5.ClosePrices, M5EmaSlow),
                    Rsi = Indicators.RelativeStrengthIndex(bars.ClosePrices, RsiPeriod),
                    Gold = gold
                });
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

            int openPairSymbols = BotPositions().Select(p => p.SymbolName).Distinct().Count();
            _cyclesToday = Math.Max(buys, sells) + openPairSymbols;
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

            UpdatePairExperiments();
            TrailProtectedSisters();

            if (Server.Time >= _nextStats)
            {
                PrintMarketStats();
                _nextStats = Server.Time.AddMinutes(5);
            }

            var open = BotPositions();
            if (open.Length > 0)
            {
                _wasInCycle = true;
                MaintainFastExits(open);
                open = BotPositions();

                if (Server.Time >= _nextHeartbeat)
                {
                    Print("HEDGE-FAST V6 ACTIVE open={0}/{1}, pairs≈{2}/{3}, cycles={4}/{5}, oldestSec={6:F0}",
                        open.Length, MaxSimultaneousPairs * 2,
                        (open.Length + 1) / 2, MaxSimultaneousPairs,
                        _cyclesToday, MaxCyclesPerDay,
                        open.Length > 0 ? open.Max(p => (Server.Time - p.EntryTime).TotalSeconds) : 0);
                    _nextHeartbeat = Server.Time.AddMinutes(2);
                }
            }

            if (BotPositions().Length == 0 && _wasInCycle)
            {
                _wasInCycle = false;
                _lastFlat = Server.Time;
                Print("HEDGE-FAST V6 all pairs flat. Cooldown={0}s.", CooldownSeconds);
            }

            if (_cyclesToday >= MaxCyclesPerDay) return;
            if (BotPositions().Length >= MaxSimultaneousPairs * 2) return;
            if (BotPositions().Length == 0 && (Server.Time - _lastFlat).TotalSeconds < CooldownSeconds) return;

            var candidates = new List<Candidate>();
            foreach (var market in _markets)
            {
                if (BotPositions().Any(p => p.SymbolName == market.Symbol.Name))
                {
                    _notes[market.Symbol.Name] = "pair already open";
                    continue;
                }

                DateTime whipsawUntil;
                if (_whipsawUntil.TryGetValue(market.Symbol.Name, out whipsawUntil) &&
                    Server.Time < whipsawUntil)
                {
                    _notes[market.Symbol.Name] = string.Format("whipsaw cooldown {0:F0}s",
                        (whipsawUntil - Server.Time).TotalSeconds);
                    continue;
                }

                DateTime lastLaunch;
                if (_lastLaunchBySymbol.TryGetValue(market.Symbol.Name, out lastLaunch) &&
                    (Server.Time - lastLaunch).TotalSeconds < CooldownSeconds)
                {
                    _notes[market.Symbol.Name] = "symbol cooldown";
                    continue;
                }

                if (!market.Symbol.MarketHours.IsOpened())
                {
                    _notes[market.Symbol.Name] = "market closed";
                    continue;
                }

                if (AvoidCorrelatedFxPairs && IsCorrelatedFxBlocked(market.Symbol.Name))
                {
                    _notes[market.Symbol.Name] = "correlation guard";
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

            // More indicators are used as a SCORE, not as hard all-or-nothing gates.
            // This keeps trade frequency high while preferring stronger/cheaper setups.
            var ranked = candidates
                .OrderByDescending(c => c.TotalScore)
                .ThenBy(c => c.SpreadToStop)
                .ThenBy(c => c.SpreadToAtr)
                .ToList();

            foreach (var selected in ranked)
            {
                if (_cyclesToday >= MaxCyclesPerDay) break;
                if (BotPositions().Length >= MaxSimultaneousPairs * 2) break;
                if (BotPositions().Any(p => p.SymbolName == selected.Market.Symbol.Name)) continue;

                Print("MATH EDGE SELECT {0}: total={1:F2}, math={2:F2}, ER={3:F2}, ADX={4:F1}, ATRpct={5:F0}, BBx={6:F2}, spread/SL={7:F3}, breakout={8}",
                    selected.Market.Symbol.Name, selected.TotalScore, selected.MathEdgeScore,
                    selected.EfficiencyRatio, selected.Adx, selected.AtrPercentile,
                    selected.BollingerExpansion, selected.SpreadToStop, selected.BreakoutSide);

                OpenPair(selected);
            }
        }

        private Candidate Evaluate(MarketInfo market)
        {
            Bars b = market.M1;
            Symbol s = market.Symbol;
            int minBars = Math.Max(220, Math.Max(CompressionBars + 80,
                Math.Max(VolumeLookback + 30, AtrPercentileLookback + AtrPeriod + 30)));
            if (b == null || market.M5 == null || b.Count < minBars || market.M5.Count < M5EmaSlow + 10)
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

            double m1Fast = market.M1Fast.Result.Last(1);
            double m1Slow = market.M1Slow.Result.Last(1);
            double m5Fast = market.M5Fast.Result.Last(1);
            double m5Slow = market.M5Slow.Result.Last(1);
            double rsi = market.Rsi.Result.Last(1);

            bool m1Align = up ? m1Fast > m1Slow : m1Fast < m1Slow;
            bool m5Align = up ? m5Fast > m5Slow : m5Fast < m5Slow;
            bool rsiAlign = up ? (rsi >= 50 && rsi <= 75) : (rsi <= 50 && rsi >= 25);

            double volMean = 0;
            for (int i = 2; i < VolumeLookback + 2; i++)
                volMean += b.TickVolumes.Last(i);
            volMean /= VolumeLookback;
            double volumeRatio = volMean > 0 ? b.TickVolumes.Last(1) / volMean : 0;

            double indicatorScore =
                (m1Align ? 1.00 : 0) +
                (m5Align ? 1.25 : 0) +
                (rsiAlign ? 0.75 : 0) +
                (volumeRatio >= 1.00 ? 0.50 : 0) +
                (volumeRatio >= 1.20 ? 0.25 : 0);

            if (indicatorScore < MinIndicatorScore)
            {
                _notes[s.Name] = string.Format("indicator score {0:F2}<{1:F2}", indicatorScore, MinIndicatorScore);
                return null;
            }

            double er = KaufmanEfficiencyRatio(b, EfficiencyRatioPeriod, 1);
            double adx = CalculateAdx(b, AdxPeriod, 1);
            double atrPercentile = AtrPercentile(b, AtrPeriod, AtrPercentileLookback);
            double bbExpansion = BollingerExpansionRatio(b, BollingerPeriod);
            double emaSlopeAtr = atr > 0
                ? Math.Abs(market.M1Fast.Result.Last(1) - market.M1Fast.Result.Last(4)) / (3.0 * atr)
                : 0;
            double candleRange = b.HighPrices.Last(1) - b.LowPrices.Last(1);
            double wick = Math.Max(0.0, candleRange - Math.Abs(close - open));
            double bodyWickRatio = wick > 0 ? Math.Abs(close - open) / wick : 5.0;

            bool slopeAlign = up
                ? market.M1Fast.Result.Last(1) > market.M1Fast.Result.Last(4)
                : market.M1Fast.Result.Last(1) < market.M1Fast.Result.Last(4);

            double mathEdgeScore = 0;
            mathEdgeScore += Math.Min(1.50, er * 2.0);
            mathEdgeScore += Math.Min(1.25, Math.Max(0, adx - 12.0) / 20.0);
            mathEdgeScore += (atrPercentile >= 35 && atrPercentile <= 90) ? 0.75 :
                (atrPercentile > 90 ? 0.25 : 0.0);
            mathEdgeScore += Math.Min(0.75, Math.Max(0, bbExpansion - 0.90));
            mathEdgeScore += slopeAlign ? Math.Min(0.75, emaSlopeAtr * 2.5) : 0;
            mathEdgeScore += Math.Min(0.50, bodyWickRatio / 4.0);
            mathEdgeScore += volumeRatio >= 1.0 ? Math.Min(0.50, (volumeRatio - 0.8) * 0.8) : 0;
            mathEdgeScore -= spreadToStop * 2.5;
            mathEdgeScore -= spreadToAtr * 0.5;

            if (mathEdgeScore < MinMathEdgeScore)
            {
                _notes[s.Name] = string.Format("math edge {0:F2}<{1:F2}", mathEdgeScore, MinMathEdgeScore);
                return null;
            }

            double setupQuality =
                (CompressionMaxRatio - compression) * 1.20 +
                Math.Min(1.5, bodyAtr) * 0.60 +
                (MaxBreakoutChaseAtr - chase) * 0.25 -
                spreadToStop * 2.0;

            double totalScore =
                indicatorScore +
                mathEdgeScore +
                setupQuality -
                spreadToStop * 1.5 -
                spreadToAtr * 0.5;

            _notes[s.Name] = string.Format("QUALIFIED total={0:F2} math={1:F2}", totalScore, mathEdgeScore);
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
                SetupQuality = setupQuality,
                IndicatorScore = indicatorScore,
                VolumeRatio = volumeRatio,
                EfficiencyRatio = er,
                Adx = adx,
                AtrPercentile = atrPercentile,
                BollingerExpansion = bbExpansion,
                EmaSlopeAtr = emaSlopeAtr,
                BodyWickRatio = bodyWickRatio,
                MathEdgeScore = mathEdgeScore,
                TotalScore = totalScore
            };
        }

        private void OnPositionClosed(PositionClosedEventArgs e)
        {
            Position closed = e.Position;
            if (closed == null) return;
            if (closed.Label != BuyLabel && closed.Label != SellLabel) return;
            if (!_markets.Any(m => m.Symbol.Name == closed.SymbolName)) return;

            _protectedLegs.Remove(closed.Id);
            RecordPairLegClose(closed);

            if (e.Reason == PositionCloseReason.StopLoss)
            {
                _whipsawUntil[closed.SymbolName] = Server.Time.AddSeconds(WhipsawCooldownSeconds);

                string sisterLabel = closed.Label == BuyLabel ? SellLabel : BuyLabel;
                Position sister = Positions.FirstOrDefault(p =>
                    p.SymbolName == closed.SymbolName && p.Label == sisterLabel);

                if (sister != null)
                    ProtectSisterImmediately(closed, sister);
            }
        }

        private void ProtectSisterImmediately(Position stopped, Position sister)
        {
            MarketInfo market = _markets.FirstOrDefault(m => m.Symbol.Name == sister.SymbolName);
            if (market == null) return;

            Symbol s = market.Symbol;
            if (s.PipSize <= 0)
            {
                if (CloseSisterIfLockFails) ClosePosition(sister);
                return;
            }

            double originalStopPips = 0;
            if (sister.TakeProfit.HasValue)
                originalStopPips = Math.Abs(sister.TakeProfit.Value - sister.EntryPrice) / s.PipSize / 2.0;
            if (originalStopPips <= 0 && stopped.StopLoss.HasValue)
                originalStopPips = Math.Abs(stopped.StopLoss.Value - stopped.EntryPrice) / s.PipSize;

            if (originalStopPips <= 0)
            {
                Print("MATH EDGE cannot infer 1R for sister id={0}.", sister.Id);
                if (CloseSisterIfLockFails) ClosePosition(sister);
                return;
            }

            double legRiskCash = s.AmountRisked(sister.VolumeInUnits, originalStopPips);
            if (legRiskCash <= 0)
                legRiskCash = Math.Max(0.01, Math.Abs(stopped.NetProfit));

            var state = new ProtectedLeg
            {
                PositionId = sister.Id,
                SymbolName = sister.SymbolName,
                OriginalStopPips = originalStopPips,
                BestPrice = sister.TradeType == TradeType.Buy ? s.Bid : s.Ask,
                PartialTaken = false,
                RecoveryLocked = false,
                FirstLossCash = Math.Abs(stopped.NetProfit),
                LegRiskCash = legRiskCash
            };
            _protectedLegs[sister.Id] = state;

            // Do NOT force the old +0.70R lock. Give the winner room to recover the pair,
            // while placing only a small positive floor if market distance allows it.
            if (InitialSisterFloorR > 0)
                TryImproveSisterStop(sister, state, InitialSisterFloorR, "INITIAL FLOOR");

            Print("MATH EDGE FIRST SL {0}: stopped#{1} net={2:F2}; sister#{3} continues toward pair recovery. 1Rcash≈{4:F2}",
                sister.SymbolName, stopped.Id, stopped.NetProfit, sister.Id, legRiskCash);
        }

        private bool TryImproveSisterStop(Position sister, ProtectedLeg state, double targetLockR, string reason)
        {
            MarketInfo market = _markets.FirstOrDefault(m => m.Symbol.Name == sister.SymbolName);
            if (market == null || state.OriginalStopPips <= 0) return false;

            Symbol s = market.Symbol;
            double desired = sister.EntryPrice +
                (sister.TradeType == TradeType.Buy ? 1.0 : -1.0) *
                targetLockR * state.OriginalStopPips * s.PipSize;

            double gap = Math.Max(2.0 * s.PipSize, (s.Ask - s.Bid) * 1.5);
            double safe = sister.TradeType == TradeType.Buy
                ? Math.Min(desired, s.Bid - gap)
                : Math.Max(desired, s.Ask + gap);

            bool positive = sister.TradeType == TradeType.Buy
                ? safe > sister.EntryPrice
                : safe < sister.EntryPrice;
            if (!positive) return false;

            bool improves = !sister.StopLoss.HasValue ||
                (sister.TradeType == TradeType.Buy
                    ? safe > sister.StopLoss.Value + 0.10 * s.PipSize
                    : safe < sister.StopLoss.Value - 0.10 * s.PipSize);
            if (!improves) return true;

            var mod = sister.ModifyStopLossPrice(Math.Round(safe, s.Digits));
            if (mod.IsSuccessful)
            {
                double actualR = Math.Abs(safe - sister.EntryPrice) / s.PipSize / state.OriginalStopPips;
                Print("MATH EDGE {0} {1} id={2}: stop locks≈{3:F2}R.", reason, sister.SymbolName, sister.Id, actualR);
                return true;
            }

            Print("MATH EDGE {0} failed id={1} error={2}", reason, sister.Id, mod.Error);
            return false;
        }

        private void TryLatePartial(Position sister, ProtectedLeg state, double favorableR)
        {
            if (state.PartialTaken || SisterPartialPercent <= 0 || favorableR < SisterPartialTriggerR) return;

            MarketInfo market = _markets.FirstOrDefault(m => m.Symbol.Name == sister.SymbolName);
            if (market == null) return;
            Symbol s = market.Symbol;

            double closeUnits = s.NormalizeVolumeInUnits(
                sister.VolumeInUnits * SisterPartialPercent / 100.0, RoundingMode.Down);
            double remaining = sister.VolumeInUnits - closeUnits;
            if (closeUnits < s.VolumeInUnitsMin || remaining < s.VolumeInUnitsMin) return;

            var partial = ClosePosition(sister, closeUnits);
            if (partial.IsSuccessful)
            {
                state.PartialTaken = true;
                Print("MATH EDGE LATE PARTIAL {0} id={1}: {2:F0}% at≈{3:F2}R.",
                    sister.SymbolName, sister.Id, SisterPartialPercent, favorableR);
            }
        }

        private void TrailProtectedSisters()
        {
            foreach (var kv in _protectedLegs.ToArray())
            {
                Position p = Positions.FirstOrDefault(x => x.Id == kv.Key);
                if (p == null)
                {
                    _protectedLegs.Remove(kv.Key);
                    continue;
                }

                ProtectedLeg state = kv.Value;
                MarketInfo market = _markets.FirstOrDefault(m => m.Symbol.Name == p.SymbolName);
                if (market == null || state.OriginalStopPips <= 0) continue;

                Symbol s = market.Symbol;
                double current = p.TradeType == TradeType.Buy ? s.Bid : s.Ask;

                if (p.TradeType == TradeType.Buy)
                    state.BestPrice = Math.Max(state.BestPrice, current);
                else
                    state.BestPrice = Math.Min(state.BestPrice, current);

                double favorablePips = p.TradeType == TradeType.Buy
                    ? (state.BestPrice - p.EntryPrice) / s.PipSize
                    : (p.EntryPrice - state.BestPrice) / s.PipSize;

                double favorableR = favorablePips / state.OriginalStopPips;

                // Once the winner has enough distance, lock enough R to offset the ACTUAL first loss
                // plus a small buffer. This is pair-level protection, not leg-level protection.
                if (!state.RecoveryLocked && favorableR >= PairRecoveryTriggerR)
                {
                    double lossR = state.LegRiskCash > 0 ? state.FirstLossCash / state.LegRiskCash : 1.0;
                    double requiredLockR = lossR + PairLockedBufferR;
                    bool locked = TryImproveSisterStop(p, state, requiredLockR, "PAIR RECOVERY");
                    if (locked)
                        state.RecoveryLocked = true;
                    else if (CloseSisterIfLockFails)
                    {
                        Print("MATH EDGE recovery lock unavailable; closing sister id={0} to avoid full reversal.", p.Id);
                        ClosePosition(p);
                        continue;
                    }
                }

                TryLatePartial(p, state, favorableR);

                if (!state.RecoveryLocked || favorableR < SisterTrailActivationR) continue;

                double atrPrice = ClosedAtr(market.M1, AtrPeriod, 40);
                double atrTrailPips = atrPrice > 0 ? (atrPrice / s.PipSize) * 0.35 : 0;
                double trailPips = Math.Max(state.OriginalStopPips * SisterTrailR, atrTrailPips);

                double proposed = p.TradeType == TradeType.Buy
                    ? state.BestPrice - trailPips * s.PipSize
                    : state.BestPrice + trailPips * s.PipSize;

                double trailLossR = state.LegRiskCash > 0 ? state.FirstLossCash / state.LegRiskCash : 1.0;
                double floorR = trailLossR + PairLockedBufferR;
                double floorLock = p.EntryPrice +
                    (p.TradeType == TradeType.Buy ? 1.0 : -1.0) *
                    state.OriginalStopPips * floorR * s.PipSize;

                if (p.TradeType == TradeType.Buy)
                    proposed = Math.Max(proposed, floorLock);
                else
                    proposed = Math.Min(proposed, floorLock);

                double gap = Math.Max(2.0 * s.PipSize, (s.Ask - s.Bid) * 1.5);
                proposed = p.TradeType == TradeType.Buy
                    ? Math.Min(proposed, s.Bid - gap)
                    : Math.Max(proposed, s.Ask + gap);

                bool improves = !p.StopLoss.HasValue ||
                    (p.TradeType == TradeType.Buy
                        ? proposed > p.StopLoss.Value + 0.10 * s.PipSize
                        : proposed < p.StopLoss.Value - 0.10 * s.PipSize);

                if (!improves) continue;

                var mod = p.ModifyStopLossPrice(Math.Round(proposed, s.Digits));
                if (mod.IsSuccessful && DiagnosticLogs)
                {
                    double lockedR = Math.Abs(proposed - p.EntryPrice) / s.PipSize / state.OriginalStopPips;
                    Print("MATH EDGE TRAIL id={0} {1}: best={2:F2}R stop≈{3:F2}R.",
                        p.Id, p.SymbolName, favorableR, lockedR);
                }
            }
        }

        private void PrintMarketStats()
        {
            foreach (var m in _markets)
            {
                var trades = History.FindAll(BuyLabel)
                    .Concat(History.FindAll(SellLabel))
                    .Where(h => h.SymbolName == m.Symbol.Name && h.EntryTime.Date == _utcDay)
                    .ToArray();

                if (trades.Length == 0) continue;

                double net = trades.Sum(h => h.NetProfit);
                double grossWin = trades.Where(h => h.NetProfit > 0).Sum(h => h.NetProfit);
                double grossLoss = -trades.Where(h => h.NetProfit < 0).Sum(h => h.NetProfit);
                int wins = trades.Count(h => h.NetProfit > 0);
                int losses = trades.Count(h => h.NetProfit < 0);
                double pf = grossLoss > 0 ? grossWin / grossLoss : (grossWin > 0 ? 999.0 : 0.0);

                Print("MATH EDGE LEG STATS {0}: closed={1} W/L={2}/{3} net={4:F2} PF={5:F2}",
                    m.Symbol.Name, trades.Length, wins, losses, net, pf);
            }
        }

        private void MaintainFastExits(Position[] open)
        {
            // V6.1: time-based exits are OFF by default.
            // Positions are left to TP/SL/sister protection/trailing unless the user explicitly sets MaxHoldSeconds > 0.
            if (MaxHoldSeconds <= 0) return;

            foreach (var p in open)
            {
                double ageSec = (Server.Time - p.EntryTime).TotalSeconds;
                if (ageSec < MaxHoldSeconds) continue;

                var close = ClosePosition(p);
                if (close.IsSuccessful)
                    Print("HEDGE-FAST OPTIONAL TIME EXIT id={0} {1} age={2:F0}s net={3:F2} pips={4:F1}",
                        p.Id, p.SymbolName, ageSec, p.NetProfit, p.Pips);
                else
                    Print("HEDGE-FAST OPTIONAL TIME EXIT FAILED id={0} error={1}", p.Id, close.Error);
            }
        }

        private void OpenPair(Candidate c)
        {
            if (BotPositions().Length >= MaxSimultaneousPairs * 2) return;
            if (BotPositions().Any(p => p.SymbolName == c.Market.Symbol.Name)) return;

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

            double currentNominalRisk = 0;
            foreach (var p in BotPositions())
            {
                var m = _markets.FirstOrDefault(x => x.Symbol.Name == p.SymbolName);
                if (m != null)
                    currentNominalRisk += (m.Gold ? GoldPairRiskPercent : FxPairRiskPercent) / 2.0;
            }
            if (currentNominalRisk + pairRisk > MaxNominalOpenRiskPercent + 1e-9)
            {
                Print("HEDGE-FAST V6 PORTFOLIO SKIP {0}: nominalRiskIfAdded={1:F2}% > cap={2:F2}%.",
                    s.Name, currentNominalRisk + pairRisk, MaxNominalOpenRiskPercent);
                return;
            }

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
            _lastLaunchBySymbol[s.Name] = Server.Time;
            _pairExperiments[s.Name] = new PairExperiment
            {
                SymbolName = s.Name,
                BuyId = buy.Position.Id,
                SellId = sell.Position.Id,
                LegRiskCash = actualRisk,
                RealizedNet = 0,
                MfeR = 0,
                MaeR = 0,
                OpenTime = Server.Time
            };
            Print("MATH EDGE OPEN {0} cycle={1}/{2} BUY#{3}+SELL#{4} units={5} SL={6:F2}p TP={7:F2}p RR=2:1 normalizedSpread={8:F3} setup={9:F2} breakout={10}",
                s.Name, _cyclesToday, MaxCyclesPerDay, buy.Position.Id, sell.Position.Id,
                units, c.StopPips, tpPips, liveSpreadToStop, c.SetupQuality, c.BreakoutSide);
        }

        private bool IsCorrelatedFxBlocked(string symbolName)
        {
            bool isEur = symbolName.IndexOf("EURUSD", StringComparison.OrdinalIgnoreCase) >= 0;
            bool isGbp = symbolName.IndexOf("GBPUSD", StringComparison.OrdinalIgnoreCase) >= 0;
            if (!isEur && !isGbp) return false;

            string other = isEur ? "GBPUSD" : "EURUSD";
            return BotPositions().Any(p =>
                p.SymbolName.IndexOf(other, StringComparison.OrdinalIgnoreCase) >= 0);
        }

        private void UpdatePairExperiments()
        {
            foreach (var kv in _pairExperiments.ToArray())
            {
                PairExperiment x = kv.Value;
                double openNet = Positions
                    .Where(p => p.Id == x.BuyId || p.Id == x.SellId)
                    .Sum(p => p.NetProfit);
                double pairNet = x.RealizedNet + openNet;
                double r = x.LegRiskCash > 0 ? pairNet / x.LegRiskCash : 0;
                x.MfeR = Math.Max(x.MfeR, r);
                x.MaeR = Math.Min(x.MaeR, r);

                bool buyOpen = Positions.Any(p => p.Id == x.BuyId);
                bool sellOpen = Positions.Any(p => p.Id == x.SellId);
                if (!buyOpen && !sellOpen)
                    FinalizePairExperiment(x);
            }
        }

        private void RecordPairLegClose(Position closed)
        {
            PairExperiment x;
            if (!_pairExperiments.TryGetValue(closed.SymbolName, out x)) return;
            if (closed.Id != x.BuyId && closed.Id != x.SellId) return;
            x.RealizedNet += closed.NetProfit;
        }

        private void FinalizePairExperiment(PairExperiment x)
        {
            if (!_pairExperiments.ContainsKey(x.SymbolName)) return;
            double finalR = x.LegRiskCash > 0 ? x.RealizedNet / x.LegRiskCash : 0;
            _completedPairR.Add(finalR);
            _pairExperiments.Remove(x.SymbolName);

            int n = _completedPairR.Count;
            double expectancy = _completedPairR.Average();
            int wins = _completedPairR.Count(v => v > 0);
            int losses = _completedPairR.Count(v => v < 0);
            double grossWin = _completedPairR.Where(v => v > 0).Sum();
            double grossLoss = -_completedPairR.Where(v => v < 0).Sum();
            double pf = grossLoss > 0 ? grossWin / grossLoss : (grossWin > 0 ? 999.0 : 0.0);

            Print("MATH EDGE PAIR RESULT {0}: R={1:F2}, MFE={2:F2}R, MAE={3:F2}R | SAMPLE {4}/100 expectancy={5:F3}R W/L={6}/{7} PF={8:F2}",
                x.SymbolName, finalR, x.MfeR, x.MaeR, n, expectancy, wins, losses, pf);

            if (n == 100)
                Print("MATH EDGE 100-PAIR CHECKPOINT: expectancy={0:F3}R, PF={1:F2}, wins={2}, losses={3}. Freeze settings and validate on a NEW sample before any live use.",
                    expectancy, pf, wins, losses);
        }

        private double KaufmanEfficiencyRatio(Bars b, int period, int shift)
        {
            if (b.Count < period + shift + 2) return 0;
            double change = Math.Abs(b.ClosePrices.Last(shift) - b.ClosePrices.Last(shift + period));
            double noise = 0;
            for (int i = shift; i < shift + period; i++)
                noise += Math.Abs(b.ClosePrices.Last(i) - b.ClosePrices.Last(i + 1));
            return noise > 0 ? change / noise : 0;
        }

        private double CalculateAdx(Bars b, int period, int shift)
        {
            if (b.Count < period * 2 + shift + 5) return 0;

            double trSum = 0, plusSum = 0, minusSum = 0;
            for (int i = shift; i < shift + period; i++)
            {
                double high = b.HighPrices.Last(i);
                double low = b.LowPrices.Last(i);
                double prevHigh = b.HighPrices.Last(i + 1);
                double prevLow = b.LowPrices.Last(i + 1);
                double prevClose = b.ClosePrices.Last(i + 1);

                double tr = Math.Max(high - low, Math.Max(Math.Abs(high - prevClose), Math.Abs(low - prevClose)));
                double upMove = high - prevHigh;
                double downMove = prevLow - low;
                double plusDm = upMove > downMove && upMove > 0 ? upMove : 0;
                double minusDm = downMove > upMove && downMove > 0 ? downMove : 0;

                trSum += tr;
                plusSum += plusDm;
                minusSum += minusDm;
            }

            if (trSum <= 0) return 0;
            double plusDi = 100.0 * plusSum / trSum;
            double minusDi = 100.0 * minusSum / trSum;
            double denom = plusDi + minusDi;
            return denom > 0 ? 100.0 * Math.Abs(plusDi - minusDi) / denom : 0;
        }

        private double AtrPercentile(Bars b, int period, int lookback)
        {
            double current = AverageTrueRangeWindow(b, 1, period);
            if (current <= 0) return 0;
            int below = 0, count = 0;
            for (int shift = 2; shift < lookback + 2; shift++)
            {
                double v = AverageTrueRangeWindow(b, shift, period);
                if (v <= 0) continue;
                if (v <= current) below++;
                count++;
            }
            return count > 0 ? 100.0 * below / count : 0;
        }

        private double BollingerExpansionRatio(Bars b, int period)
        {
            double current = BollingerWidth(b, period, 1);
            if (current <= 0) return 0;
            double avg = 0;
            int count = 0;
            for (int shift = 2; shift < 12; shift++)
            {
                double w = BollingerWidth(b, period, shift);
                if (w <= 0) continue;
                avg += w;
                count++;
            }
            return count > 0 && avg > 0 ? current / (avg / count) : 1.0;
        }

        private double BollingerWidth(Bars b, int period, int shift)
        {
            if (b.Count < period + shift + 2) return 0;
            double mean = 0;
            for (int i = shift; i < shift + period; i++)
                mean += b.ClosePrices.Last(i);
            mean /= period;
            if (mean == 0) return 0;

            double variance = 0;
            for (int i = shift; i < shift + period; i++)
            {
                double d = b.ClosePrices.Last(i) - mean;
                variance += d * d;
            }
            variance /= period;
            return 4.0 * Math.Sqrt(variance) / Math.Abs(mean);
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
            Positions.Closed -= OnPositionClosed;
            Timer.Stop();
            Print("HEDGE MATH EDGE V6.2 stopped.");
        }
    }
}
