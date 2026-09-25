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
    public class DualHedgeMathEdge : Robot
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

        [Parameter("Kaufman ER period", DefaultValue = 10, MinValue = 5, MaxValue = 50, Group = "Math indicators")]
        public int ErPeriod { get; set; }

        [Parameter("ADX period", DefaultValue = 14, MinValue = 5, MaxValue = 50, Group = "Math indicators")]
        public int AdxPeriod { get; set; }

        [Parameter("ATR percentile lookback", DefaultValue = 80, MinValue = 30, MaxValue = 300, Group = "Math indicators")]
        public int AtrPercentileLookback { get; set; }

        [Parameter("Bollinger width period", DefaultValue = 20, MinValue = 10, MaxValue = 60, Group = "Math indicators")]
        public int BbPeriod { get; set; }

        [Parameter("Bollinger expansion lookback", DefaultValue = 10, MinValue = 5, MaxValue = 40, Group = "Math indicators")]
        public int BbExpansionLookback { get; set; }

        [Parameter("Minimum Math Edge score", DefaultValue = 3.75, MinValue = 0.0, MaxValue = 10.0, Group = "Math indicators")]
        public double MinMathEdgeScore { get; set; }

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

        [Parameter("Initial survivor safety lock (R)", DefaultValue = 0.45, MinValue = 0.05, MaxValue = 0.90, Group = "Math exits")]
        public double InitialSafetyLockR { get; set; }

        [Parameter("Extra modeled costs (R)", DefaultValue = 0.05, MinValue = 0.0, MaxValue = 0.50, Group = "Math exits")]
        public double ExtraModeledCostR { get; set; }

        [Parameter("Pair BE trigger cushion (R)", DefaultValue = 0.10, MinValue = 0.0, MaxValue = 0.50, Group = "Math exits")]
        public double PairBreakEvenCushionR { get; set; }

        [Parameter("Late partial at (R)", DefaultValue = 1.50, MinValue = 1.10, MaxValue = 1.95, Group = "Math exits")]
        public double LatePartialTriggerR { get; set; }

        [Parameter("Late partial (%)", DefaultValue = 25.0, MinValue = 0.0, MaxValue = 50.0, Group = "Math exits")]
        public double LatePartialPercent { get; set; }

        [Parameter("Trail activation (R)", DefaultValue = 1.65, MinValue = 1.10, MaxValue = 1.95, Group = "Math exits")]
        public double MathTrailActivationR { get; set; }

        [Parameter("Trail distance (R)", DefaultValue = 0.35, MinValue = 0.10, MaxValue = 0.80, Group = "Math exits")]
        public double MathTrailDistanceR { get; set; }

        [Parameter("Whipsaw cooldown after SL (sec)", DefaultValue = 45, MinValue = 0, MaxValue = 600, Group = "Math exits")]
        public int WhipsawCooldownSeconds { get; set; }

        [Parameter("100-pair evaluation target", DefaultValue = 100, MinValue = 20, MaxValue = 1000, Group = "Math study")]
        public int EvaluationPairCount { get; set; }

        [Parameter("Use correlation filter", DefaultValue = true, Group = "Math study")]
        public bool UseCorrelationFilter { get; set; }

        [Parameter("Max abs M5 correlation", DefaultValue = 0.88, MinValue = 0.50, MaxValue = 0.99, Group = "Math study")]
        public double MaxAbsCorrelation { get; set; }

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

        private sealed class PairState
        {
            public string SymbolName;
            public DateTime StartTime;
            public long BuyId;
            public long SellId;
            public double StopPips;
            public double LegRiskCash;
            public double SpreadToStop;
            public double ModeledCostR;
            public double EntryScore;
            public double Er;
            public double Adx;
            public double AtrPercentile;
            public double BbExpansion;
            public string BreakoutSide;
            public double RealizedNet;
            public double MaxPairR = double.MinValue;
            public double MinPairR = double.MaxValue;
            public bool FirstStopSeen;
            public bool LatePartialTaken;
            public bool PairBreakEvenArmed;
        }

        private sealed class PairStats
        {
            public int Count;
            public int Wins;
            public int Losses;
            public double SumR;
            public double GrossWinR;
            public double GrossLossR;
            public double SumMfeR;
            public double SumMaeR;
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
            public double Er;
            public double Adx;
            public double AtrPercentile;
            public double BbExpansion;
            public double M1SlopeAtr;
            public double M5SlopeAtr;
            public double TotalScore;
        }

        private readonly List<MarketInfo> _markets = new List<MarketInfo>();
        private readonly Dictionary<string, string> _notes = new Dictionary<string, string>();
        private readonly Dictionary<string, DateTime> _lastLaunchBySymbol = new Dictionary<string, DateTime>();
        private readonly Dictionary<string, DateTime> _whipsawUntil = new Dictionary<string, DateTime>();
        private readonly Dictionary<string, PairState> _pairStates = new Dictionary<string, PairState>();
        private readonly Dictionary<string, PairStats> _pairStatsByMarket = new Dictionary<string, PairStats>();
        private DateTime _utcDay;
        private DateTime _lastFlat = DateTime.MinValue;
        private int _cyclesToday;
        private bool _wasInCycle;
        private DateTime _nextHeartbeat = DateTime.MinValue;
        private DateTime _nextStats = DateTime.MinValue;
        private int _completedPairs;
        private int _pairWins;
        private int _pairLosses;
        private double _sumPairR;
        private double _grossPairWinR;
        private double _grossPairLossR;
        private double _sumPairMfeR;
        private double _sumPairMaeR;

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
            RecoverActivePairStates();
            Positions.Closed += OnPositionClosed;
            Timer.Start(2);

            Print("HEDGE MATH EDGE V6.2 ON | markets={0} | scan=2s | maxPairs={1} ({2} positions) | NO time exit | RR=2:1",
                string.Join(",", _markets.Select(m => m.Symbol.Name)),
                MaxSimultaneousPairs, MaxSimultaneousPairs * 2);
            Print("Risk: FX pair={0:F2}%, GOLD pair={1:F2}%, nominal portfolio cap={2:F2}%.",
                FxPairRiskPercent, GoldPairRiskPercent, MaxNominalOpenRiskPercent);
            Print("MATH EDGE: ER+ADX+ATR percentile+BB expansion+EMA slope+RSI+volume. Min score={0:F2}. Correlation filter={1} maxAbs={2:F2}.",
                MinMathEdgeScore, UseCorrelationFilter, MaxAbsCorrelation);
            Print("PAIR MANAGEMENT: initial lock<={0:F2}R; pair-BE lock covers modeled costs; late partial {1:F0}% at {2:F2}R; trail starts {3:F2}R.",
                InitialSafetyLockR, LatePartialPercent, LatePartialTriggerR, MathTrailActivationR);
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

            ManagePairStates();

            if (Server.Time >= _nextStats)
            {
                PrintPairStats();
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
                    Print("MATH EDGE ACTIVE open={0}/{1}, pairs≈{2}/{3}, cycles={4}/{5}, oldestSec={6:F0}",
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
                Print("MATH EDGE all pairs flat. Cooldown={0}s.", CooldownSeconds);
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

            var selectedThisScan = new List<Candidate>();
            foreach (var selected in ranked)
            {
                if (_cyclesToday >= MaxCyclesPerDay) break;
                if (BotPositions().Length >= MaxSimultaneousPairs * 2) break;
                if (BotPositions().Any(p => p.SymbolName == selected.Market.Symbol.Name)) continue;

                if (UseCorrelationFilter && IsCorrelationConflict(selected, selectedThisScan))
                {
                    if (DiagnosticLogs)
                        Print("MATH EDGE CORR SKIP {0}: highly correlated with an already selected/open same-direction setup.",
                            selected.Market.Symbol.Name);
                    continue;
                }

                Print("MATH EDGE SELECT {0}: score={1:F2} ER={2:F2} ADX={3:F1} ATRpct={4:P0} BBx={5:F2} spread/SL={6:F3} breakout={7}",
                    selected.Market.Symbol.Name, selected.TotalScore, selected.Er, selected.Adx,
                    selected.AtrPercentile, selected.BbExpansion, selected.SpreadToStop, selected.BreakoutSide);

                if (OpenPair(selected))
                    selectedThisScan.Add(selected);
            }
        }

        private Candidate Evaluate(MarketInfo market)
        {
            Bars b = market.M1;
            Symbol s = market.Symbol;
            int minBars = Math.Max(120, Math.Max(CompressionBars + 60, VolumeLookback + 20));
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
            bool rsiAlign = up ? (rsi >= 50 && rsi <= 76) : (rsi <= 50 && rsi >= 24);

            double volMean = 0;
            for (int i = 2; i < VolumeLookback + 2; i++)
                volMean += b.TickVolumes.Last(i);
            volMean /= VolumeLookback;
            double volumeRatio = volMean > 0 ? b.TickVolumes.Last(1) / volMean : 0;

            double m5Atr = ClosedAtr(market.M5, AtrPeriod, 50);
            double m1SlopeAtr = atr > 0 ? (market.M1Fast.Result.Last(1) - market.M1Fast.Result.Last(4)) / atr : 0;
            double m5SlopeAtr = m5Atr > 0 ? (market.M5Fast.Result.Last(1) - market.M5Fast.Result.Last(3)) / m5Atr : 0;
            bool m1SlopeAlign = up ? m1SlopeAtr > 0.05 : m1SlopeAtr < -0.05;
            bool m5SlopeAlign = up ? m5SlopeAtr > 0.03 : m5SlopeAtr < -0.03;

            double er = KaufmanEfficiencyRatio(b, ErPeriod);
            double adx = ClosedAdx(b, AdxPeriod);
            double atrPercentile = AtrPercentile(b, AtrPeriod, AtrPercentileLookback);
            double bbExpansion = BollingerWidthExpansion(b, BbPeriod, BbExpansionLookback);

            double indicatorScore =
                (m1Align ? 0.80 : 0) +
                (m5Align ? 1.00 : 0) +
                (m1SlopeAlign ? 0.45 : 0) +
                (m5SlopeAlign ? 0.55 : 0) +
                (rsiAlign ? 0.55 : 0) +
                (volumeRatio >= 1.00 ? 0.35 : 0) +
                (volumeRatio >= 1.20 ? 0.20 : 0);

            if (indicatorScore < MinIndicatorScore)
            {
                _notes[s.Name] = string.Format("base score {0:F2}<{1:F2}", indicatorScore, MinIndicatorScore);
                return null;
            }

            double regimeScore = 0;
            regimeScore += er >= 0.55 ? 1.25 : (er >= 0.35 ? 0.75 : (er >= 0.20 ? 0.25 : -0.25));
            regimeScore += adx >= 25 ? 1.25 : (adx >= 20 ? 0.75 : (adx >= 15 ? 0.25 : -0.25));
            regimeScore += (atrPercentile >= 0.40 && atrPercentile <= 0.90) ? 0.75 :
                (atrPercentile >= 0.20 && atrPercentile < 0.95 ? 0.25 : -0.40);
            regimeScore += bbExpansion >= 1.10 ? 0.75 : (bbExpansion >= 0.95 ? 0.25 : -0.25);

            double setupQuality =
                (CompressionMaxRatio - compression) * 1.00 +
                Math.Min(1.5, bodyAtr) * 0.60 +
                (MaxBreakoutChaseAtr - chase) * 0.20;

            double costPenalty = spreadToStop * 3.0 + spreadToAtr * 0.75;
            double totalScore = indicatorScore + regimeScore + setupQuality - costPenalty;

            if (totalScore < MinMathEdgeScore)
            {
                _notes[s.Name] = string.Format("math score {0:F2}<{1:F2}", totalScore, MinMathEdgeScore);
                return null;
            }

            _notes[s.Name] = string.Format("QUALIFIED math={0:F2} ER={1:F2} ADX={2:F1}", totalScore, er, adx);
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
                Er = er,
                Adx = adx,
                AtrPercentile = atrPercentile,
                BbExpansion = bbExpansion,
                M1SlopeAtr = m1SlopeAtr,
                M5SlopeAtr = m5SlopeAtr,
                TotalScore = totalScore
            };
        }

        private void RecoverActivePairStates()
        {
            foreach (var market in _markets)
            {
                var open = BotPositions().Where(p => p.SymbolName == market.Symbol.Name).ToArray();
                if (open.Length == 0 || _pairStates.ContainsKey(market.Symbol.Name)) continue;

                Position sample = open[0];
                double stopPips = 0;
                if (sample.TakeProfit.HasValue)
                    stopPips = Math.Abs(sample.TakeProfit.Value - sample.EntryPrice) / market.Symbol.PipSize / 2.0;
                if (stopPips <= 0 && sample.StopLoss.HasValue)
                    stopPips = Math.Abs(sample.StopLoss.Value - sample.EntryPrice) / market.Symbol.PipSize;
                if (stopPips <= 0) continue;

                double legRisk = market.Symbol.AmountRisked(sample.VolumeInUnits, stopPips);
                double spreadToStop = ((market.Symbol.Ask - market.Symbol.Bid) / market.Symbol.PipSize) / stopPips;

                _pairStates[market.Symbol.Name] = new PairState
                {
                    SymbolName = market.Symbol.Name,
                    StartTime = open.Min(p => p.EntryTime),
                    BuyId = open.FirstOrDefault(p => p.TradeType == TradeType.Buy)?.Id ?? 0,
                    SellId = open.FirstOrDefault(p => p.TradeType == TradeType.Sell)?.Id ?? 0,
                    StopPips = stopPips,
                    LegRiskCash = Math.Max(0.0001, legRisk),
                    SpreadToStop = spreadToStop,
                    ModeledCostR = 2.0 * spreadToStop + ExtraModeledCostR,
                    EntryScore = 0,
                    BreakoutSide = "RESTORED"
                };

                Print("MATH EDGE RESTORED active pair {0}: stop={1:F2}p modeledCost≈{2:F2}R.",
                    market.Symbol.Name, stopPips, 2.0 * spreadToStop + ExtraModeledCostR);
            }
        }

        private void OnPositionClosed(PositionClosedEventArgs e)
        {
            Position closed = e.Position;
            if (closed == null) return;
            if (closed.Label != BuyLabel && closed.Label != SellLabel) return;

            PairState state;
            if (!_pairStates.TryGetValue(closed.SymbolName, out state))
                return;

            state.RealizedNet += closed.NetProfit;

            if (e.Reason == PositionCloseReason.StopLoss)
            {
                _whipsawUntil[closed.SymbolName] = Server.Time.AddSeconds(WhipsawCooldownSeconds);
                state.FirstStopSeen = true;

                string sisterLabel = closed.Label == BuyLabel ? SellLabel : BuyLabel;
                Position sister = Positions.FirstOrDefault(p =>
                    p.SymbolName == closed.SymbolName && p.Label == sisterLabel);

                if (sister != null)
                    ApplyInitialSurvivorSafety(sister, state);
            }

            if (!Positions.Any(p => p.SymbolName == closed.SymbolName &&
                (p.Label == BuyLabel || p.Label == SellLabel)))
            {
                FinalizePair(state);
            }
        }

        private void ApplyInitialSurvivorSafety(Position survivor, PairState state)
        {
            MarketInfo market = _markets.FirstOrDefault(m => m.Symbol.Name == survivor.SymbolName);
            if (market == null || state.StopPips <= 0) return;

            Symbol s = market.Symbol;
            double favorablePips = survivor.TradeType == TradeType.Buy
                ? (s.Bid - survivor.EntryPrice) / s.PipSize
                : (survivor.EntryPrice - s.Ask) / s.PipSize;
            double favorableR = favorablePips / state.StopPips;

            if (favorableR <= 0.08)
            {
                Print("MATH EDGE survivor {0} has only {1:F2}R after opposite SL; no forced close, original protection remains.",
                    survivor.Id, favorableR);
                return;
            }

            double targetLockR = Math.Min(InitialSafetyLockR, Math.Max(0.05, favorableR * 0.60));
            ImproveStopToR(survivor, state, targetLockR, "INITIAL-SAFETY");
        }

        private void ManagePairStates()
        {
            foreach (var kv in _pairStates.ToArray())
            {
                PairState state = kv.Value;
                var open = BotPositions().Where(p => p.SymbolName == state.SymbolName).ToArray();

                double openNet = open.Sum(p => p.NetProfit);
                double pairR = state.LegRiskCash > 0
                    ? (state.RealizedNet + openNet) / state.LegRiskCash
                    : 0;

                state.MaxPairR = Math.Max(state.MaxPairR, pairR);
                state.MinPairR = Math.Min(state.MinPairR, pairR);

                if (open.Length == 0)
                {
                    FinalizePair(state);
                    continue;
                }

                if (!state.FirstStopSeen || open.Length != 1)
                    continue;

                Position survivor = open[0];
                MarketInfo market = _markets.FirstOrDefault(m => m.Symbol.Name == survivor.SymbolName);
                if (market == null || state.StopPips <= 0) continue;

                Symbol s = market.Symbol;
                double currentPrice = survivor.TradeType == TradeType.Buy ? s.Bid : s.Ask;
                double favorablePips = survivor.TradeType == TradeType.Buy
                    ? (currentPrice - survivor.EntryPrice) / s.PipSize
                    : (survivor.EntryPrice - currentPrice) / s.PipSize;
                double favorableR = favorablePips / state.StopPips;

                double pairBeLockR = 1.0 + state.ModeledCostR;
                double pairBeTriggerR = pairBeLockR + PairBreakEvenCushionR;

                if (favorableR >= pairBeTriggerR)
                {
                    ImproveStopToR(survivor, state, pairBeLockR, "PAIR-BE");
                    state.PairBreakEvenArmed = true;
                }

                if (!state.LatePartialTaken && LatePartialPercent > 0 &&
                    favorableR >= LatePartialTriggerR)
                {
                    TryLatePartial(survivor, state);
                }

                if (favorableR >= MathTrailActivationR)
                {
                    double trailLockR = Math.Max(pairBeLockR, favorableR - MathTrailDistanceR);
                    ImproveStopToR(survivor, state, trailLockR, "TRAIL");
                }
            }
        }

        private bool ImproveStopToR(Position p, PairState state, double lockR, string reason)
        {
            MarketInfo market = _markets.FirstOrDefault(m => m.Symbol.Name == p.SymbolName);
            if (market == null || state.StopPips <= 0) return false;
            Symbol s = market.Symbol;

            double desired = p.EntryPrice +
                (p.TradeType == TradeType.Buy ? 1.0 : -1.0) * lockR * state.StopPips * s.PipSize;

            double gap = Math.Max(2.0 * s.PipSize, (s.Ask - s.Bid) * 1.5);
            double safe = p.TradeType == TradeType.Buy
                ? Math.Min(desired, s.Bid - gap)
                : Math.Max(desired, s.Ask + gap);

            bool positive = p.TradeType == TradeType.Buy ? safe > p.EntryPrice : safe < p.EntryPrice;
            if (!positive) return false;

            bool improves = !p.StopLoss.HasValue ||
                (p.TradeType == TradeType.Buy
                    ? safe > p.StopLoss.Value + 0.10 * s.PipSize
                    : safe < p.StopLoss.Value - 0.10 * s.PipSize);
            if (!improves) return false;

            TradeResult mod = p.ModifyStopLossPrice(Math.Round(safe, s.Digits));
            if (mod.IsSuccessful)
            {
                double actualLockR = Math.Abs(safe - p.EntryPrice) / s.PipSize / state.StopPips;
                Print("MATH EDGE {0} {1} id={2}: stop now ≈{3:F2}R; pair modeled cost={4:F2}R.",
                    reason, p.SymbolName, p.Id, actualLockR, state.ModeledCostR);
                return true;
            }

            Print("MATH EDGE STOP MODIFY FAILED {0} id={1} error={2}.", reason, p.Id, mod.Error);
            return false;
        }

        private void TryLatePartial(Position p, PairState state)
        {
            MarketInfo market = _markets.FirstOrDefault(m => m.Symbol.Name == p.SymbolName);
            if (market == null) return;
            Symbol s = market.Symbol;

            double closeUnits = s.NormalizeVolumeInUnits(
                p.VolumeInUnits * LatePartialPercent / 100.0, RoundingMode.Down);
            double remaining = p.VolumeInUnits - closeUnits;

            if (closeUnits < s.VolumeInUnitsMin || remaining < s.VolumeInUnitsMin)
            {
                state.LatePartialTaken = true;
                Print("MATH EDGE LATE PARTIAL SKIP {0}: volume too small. Full survivor continues.", p.SymbolName);
                return;
            }

            double beforeNet = p.NetProfit;
            double fraction = closeUnits / p.VolumeInUnits;
            TradeResult partial = ClosePosition(p, closeUnits);
            if (partial.IsSuccessful)
            {
                state.RealizedNet += beforeNet * fraction;
                state.LatePartialTaken = true;
                Print("MATH EDGE LATE PARTIAL {0}: closed {1:F0}% after >= {2:F2}R; remaining aims for 2R/trailing.",
                    p.SymbolName, LatePartialPercent, LatePartialTriggerR);
            }
        }

        private void FinalizePair(PairState state)
        {
            if (state == null || !_pairStates.ContainsKey(state.SymbolName)) return;

            if (state.MaxPairR == double.MinValue) state.MaxPairR = 0;
            if (state.MinPairR == double.MaxValue) state.MinPairR = 0;

            double pairR = state.LegRiskCash > 0 ? state.RealizedNet / state.LegRiskCash : 0;
            _completedPairs++;
            _sumPairR += pairR;
            _sumPairMfeR += state.MaxPairR;
            _sumPairMaeR += state.MinPairR;

            if (pairR > 0)
            {
                _pairWins++;
                _grossPairWinR += pairR;
            }
            else if (pairR < 0)
            {
                _pairLosses++;
                _grossPairLossR += -pairR;
            }

            PairStats ms;
            if (!_pairStatsByMarket.TryGetValue(state.SymbolName, out ms))
            {
                ms = new PairStats();
                _pairStatsByMarket[state.SymbolName] = ms;
            }
            ms.Count++;
            ms.SumR += pairR;
            ms.SumMfeR += state.MaxPairR;
            ms.SumMaeR += state.MinPairR;
            if (pairR > 0) { ms.Wins++; ms.GrossWinR += pairR; }
            else if (pairR < 0) { ms.Losses++; ms.GrossLossR += -pairR; }

            Print("MATH EDGE PAIR CLOSED #{0} {1}: PairR={2:F2} MFE={3:F2}R MAE={4:F2}R score={5:F2} ER={6:F2} ADX={7:F1} ATRpct={8:P0} BBx={9:F2}",
                _completedPairs, state.SymbolName, pairR, state.MaxPairR, state.MinPairR,
                state.EntryScore, state.Er, state.Adx, state.AtrPercentile, state.BbExpansion);

            _pairStates.Remove(state.SymbolName);

            if (_completedPairs % 10 == 0 || _completedPairs == EvaluationPairCount)
                PrintEvaluationSummary(_completedPairs == EvaluationPairCount);
        }

        private void PrintPairStats()
        {
            if (_completedPairs == 0) return;
            PrintEvaluationSummary(false);

            foreach (var kv in _pairStatsByMarket.OrderBy(x => x.Key))
            {
                PairStats st = kv.Value;
                double pf = st.GrossLossR > 0 ? st.GrossWinR / st.GrossLossR :
                    (st.GrossWinR > 0 ? 999.0 : 0.0);
                Print("MATH EDGE MARKET {0}: pairs={1} W/L={2}/{3} sumR={4:F2} expectancy={5:F3}R PF={6:F2} avgMFE={7:F2} avgMAE={8:F2}",
                    kv.Key, st.Count, st.Wins, st.Losses, st.SumR,
                    st.Count > 0 ? st.SumR / st.Count : 0, pf,
                    st.Count > 0 ? st.SumMfeR / st.Count : 0,
                    st.Count > 0 ? st.SumMaeR / st.Count : 0);
            }
        }

        private void PrintEvaluationSummary(bool targetReached)
        {
            if (_completedPairs == 0) return;
            double pf = _grossPairLossR > 0 ? _grossPairWinR / _grossPairLossR :
                (_grossPairWinR > 0 ? 999.0 : 0.0);
            double winRate = 100.0 * _pairWins / Math.Max(1, _pairWins + _pairLosses);

            Print("MATH EDGE {0} pairs={1}/{2} W/L={3}/{4} winRate={5:F1}% sumR={6:F2} expectancy={7:F3}R PF={8:F2} avgMFE={9:F2} avgMAE={10:F2}",
                targetReached ? "100-SAMPLE" : "SUMMARY",
                _completedPairs, EvaluationPairCount, _pairWins, _pairLosses, winRate,
                _sumPairR, _sumPairR / _completedPairs, pf,
                _sumPairMfeR / _completedPairs, _sumPairMaeR / _completedPairs);
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

        private bool OpenPair(Candidate c)
        {
            if (BotPositions().Length >= MaxSimultaneousPairs * 2) return false;
            if (BotPositions().Any(p => p.SymbolName == c.Market.Symbol.Name)) return false;

            Symbol s = c.Market.Symbol;
            if (!s.MarketHours.IsOpened()) return false;

            double liveSpreadPrice = s.Ask - s.Bid;
            if (liveSpreadPrice <= 0) return false;
            double liveSpreadToStop = liveSpreadPrice / (c.StopPips * s.PipSize);
            if (liveSpreadToStop > MaxSpreadToStop)
            {
                Print("HEDGE-SMART CANCEL {0}: spread worsened before execution.", s.Name);
                return false;
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
                return false;
            }

            double legRiskPct = pairRisk / 2.0;
            double units = s.VolumeForProportionalRisk(
                ProportionalAmountType.Equity, legRiskPct, c.StopPips, RoundingMode.Down);
            units = s.NormalizeVolumeInUnits(units, RoundingMode.Down);

            if (units < s.VolumeInUnitsMin)
            {
                Print("HEDGE-SMART RISK SKIP {0}: calculated volume={1} < broker min={2}. No forced gold/FX risk.",
                    s.Name, units, s.VolumeInUnitsMin);
                return false;
            }
            if (units > s.VolumeInUnitsMax) units = s.VolumeInUnitsMax;

            double cashBudget = Account.Equity * legRiskPct / 100.0;
            double actualRisk = s.AmountRisked(units, c.StopPips);
            if (actualRisk <= 0 || actualRisk > cashBudget * MaxActualRiskToBudget)
            {
                Print("HEDGE-SMART RISK SKIP {0}: stop={1:F2}p units={2} risk≈{3:F2} > budget={4:F2}",
                    s.Name, c.StopPips, units, actualRisk, cashBudget);
                return false;
            }

            double tpPips = c.StopPips * 2.0;

            TradeResult buy = ExecuteMarketOrder(TradeType.Buy, s.Name, units, BuyLabel, c.StopPips, tpPips);
            if (!buy.IsSuccessful || buy.Position == null)
            {
                Print("HEDGE-SMART BUY FAILED {0}: {1}", s.Name, buy.Error);
                return false;
            }

            TradeResult sell = ExecuteMarketOrder(TradeType.Sell, s.Name, units, SellLabel, c.StopPips, tpPips);
            if (!sell.IsSuccessful || sell.Position == null)
            {
                Print("HEDGE-SMART SELL FAILED {0}: {1}; closing unpaired BUY#{2}.", s.Name, sell.Error, buy.Position.Id);
                var flatten = ClosePosition(buy.Position);
                if (!flatten.IsSuccessful)
                    Print("HEDGE-SMART EMERGENCY CLOSE FAILED BUY#{0}: {1}", buy.Position.Id, flatten.Error);
                return false;
            }

            _cyclesToday++;
            _wasInCycle = true;
            _lastLaunchBySymbol[s.Name] = Server.Time;

            double legRiskCash = Math.Max(0.0001, s.AmountRisked(units, c.StopPips));
            _pairStates[s.Name] = new PairState
            {
                SymbolName = s.Name,
                StartTime = Server.Time,
                BuyId = buy.Position.Id,
                SellId = sell.Position.Id,
                StopPips = c.StopPips,
                LegRiskCash = legRiskCash,
                SpreadToStop = liveSpreadToStop,
                ModeledCostR = 2.0 * liveSpreadToStop + ExtraModeledCostR,
                EntryScore = c.TotalScore,
                Er = c.Er,
                Adx = c.Adx,
                AtrPercentile = c.AtrPercentile,
                BbExpansion = c.BbExpansion,
                BreakoutSide = c.BreakoutSide
            };

            Print("MATH EDGE OPEN {0} cycle={1}/{2} BUY#{3}+SELL#{4} units={5} SL={6:F2}p TP={7:F2}p score={8:F2} cost≈{9:F2}R ER={10:F2} ADX={11:F1}",
                s.Name, _cyclesToday, MaxCyclesPerDay, buy.Position.Id, sell.Position.Id,
                units, c.StopPips, tpPips, c.TotalScore,
                2.0 * liveSpreadToStop + ExtraModeledCostR, c.Er, c.Adx);
            return true;
        }

        private bool IsCorrelationConflict(Candidate candidate, List<Candidate> selectedThisScan)
        {
            foreach (var other in selectedThisScan)
            {
                if (other.BreakoutSide != candidate.BreakoutSide) continue;
                double corr = ReturnCorrelation(candidate.Market.M5, other.Market.M5, 30);
                if (Math.Abs(corr) >= MaxAbsCorrelation) return true;
            }

            foreach (var state in _pairStates.Values)
            {
                if (state.SymbolName == candidate.Market.Symbol.Name) continue;
                if (state.BreakoutSide != candidate.BreakoutSide) continue;
                MarketInfo openMarket = _markets.FirstOrDefault(m => m.Symbol.Name == state.SymbolName);
                if (openMarket == null) continue;
                double corr = ReturnCorrelation(candidate.Market.M5, openMarket.M5, 30);
                if (Math.Abs(corr) >= MaxAbsCorrelation) return true;
            }
            return false;
        }

        private double ReturnCorrelation(Bars a, Bars b, int lookback)
        {
            int n = Math.Min(lookback, Math.Min(a.Count - 3, b.Count - 3));
            if (n < 10) return 0;

            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            for (int i = 1; i <= n; i++)
            {
                double ax0 = a.ClosePrices.Last(i);
                double ax1 = a.ClosePrices.Last(i + 1);
                double by0 = b.ClosePrices.Last(i);
                double by1 = b.ClosePrices.Last(i + 1);
                if (ax1 == 0 || by1 == 0) continue;

                double x = (ax0 - ax1) / ax1;
                double y = (by0 - by1) / by1;
                sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y;
            }
            double den = Math.Sqrt(Math.Max(0, (n * sxx - sx * sx) * (n * syy - sy * sy)));
            return den > 0 ? (n * sxy - sx * sy) / den : 0;
        }

        private double KaufmanEfficiencyRatio(Bars b, int period)
        {
            if (b.Count < period + 3) return 0;
            double change = Math.Abs(b.ClosePrices.Last(1) - b.ClosePrices.Last(period + 1));
            double noise = 0;
            for (int i = 1; i <= period; i++)
                noise += Math.Abs(b.ClosePrices.Last(i) - b.ClosePrices.Last(i + 1));
            return noise > 0 ? change / noise : 0;
        }

        private double ClosedAdx(Bars b, int period)
        {
            if (b.Count < period * 2 + 5) return 0;
            double dxSum = 0;
            int dxCount = 0;

            for (int offset = 1; offset <= period; offset++)
            {
                double trSum = 0, plusSum = 0, minusSum = 0;
                for (int j = offset; j < offset + period; j++)
                {
                    double high = b.HighPrices.Last(j);
                    double low = b.LowPrices.Last(j);
                    double prevClose = b.ClosePrices.Last(j + 1);
                    double prevHigh = b.HighPrices.Last(j + 1);
                    double prevLow = b.LowPrices.Last(j + 1);

                    double tr = Math.Max(high - low,
                        Math.Max(Math.Abs(high - prevClose), Math.Abs(low - prevClose)));
                    double upMove = high - prevHigh;
                    double downMove = prevLow - low;
                    double plusDm = upMove > downMove && upMove > 0 ? upMove : 0;
                    double minusDm = downMove > upMove && downMove > 0 ? downMove : 0;

                    trSum += tr;
                    plusSum += plusDm;
                    minusSum += minusDm;
                }

                if (trSum <= 0) continue;
                double plusDi = 100.0 * plusSum / trSum;
                double minusDi = 100.0 * minusSum / trSum;
                double sumDi = plusDi + minusDi;
                if (sumDi <= 0) continue;
                dxSum += 100.0 * Math.Abs(plusDi - minusDi) / sumDi;
                dxCount++;
            }

            return dxCount > 0 ? dxSum / dxCount : 0;
        }

        private double AtrPercentile(Bars b, int period, int lookback)
        {
            int usable = Math.Min(lookback, b.Count - period - 3);
            if (usable < 20) return 0.5;

            double current = AverageTrueRangeWindow(b, 1, period);
            if (current <= 0) return 0.5;

            int below = 0;
            int count = 0;
            for (int offset = 2; offset < usable + 2; offset++)
            {
                double v = AverageTrueRangeWindow(b, offset, period);
                if (v <= 0) continue;
                if (v <= current) below++;
                count++;
            }
            return count > 0 ? (double)below / count : 0.5;
        }

        private double BollingerWidthExpansion(Bars b, int period, int lookback)
        {
            double current = CloseStdDev(b, 1, period);
            if (current <= 0) return 1.0;

            double sum = 0;
            int count = 0;
            for (int k = 2; k < lookback + 2; k++)
            {
                double sd = CloseStdDev(b, k, period);
                if (sd <= 0) continue;
                sum += sd;
                count++;
            }
            if (count == 0) return 1.0;
            double avg = sum / count;
            return avg > 0 ? current / avg : 1.0;
        }

        private double CloseStdDev(Bars b, int startLastIndex, int period)
        {
            if (b.Count < startLastIndex + period + 2) return 0;
            double mean = 0;
            for (int i = startLastIndex; i < startLastIndex + period; i++)
                mean += b.ClosePrices.Last(i);
            mean /= period;

            double variance = 0;
            for (int i = startLastIndex; i < startLastIndex + period; i++)
            {
                double d = b.ClosePrices.Last(i) - mean;
                variance += d * d;
            }
            return Math.Sqrt(variance / period);
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
