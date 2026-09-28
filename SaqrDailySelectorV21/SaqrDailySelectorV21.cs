using System;
using System.Collections.Generic;
using System.Linq;
using cAlgo.API;
using cAlgo.API.Internals;
using cAlgo.API.Indicators;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class SaqrDailySelectorV21 : Robot
    {
        [Parameter("Demo ONLY - block live", DefaultValue = true, Group = "Safety")]
        public bool DemoOnly { get; set; }

        [Parameter("Base label", DefaultValue = "SAQR-DAILY-SELECTOR-V2-1-25F", Group = "Safety")]
        public string BaseLabel { get; set; }

        [Parameter("Risk per trade (%)", DefaultValue = 0.10, MinValue = 0.02, MaxValue = 0.30, Group = "Risk")]
        public double RiskPerTradePercent { get; set; }

        [Parameter("New trades per day", DefaultValue = 2, MinValue = 1, MaxValue = 2, Group = "Risk")]
        public int MaxNewTradesPerDay { get; set; }

        [Parameter("Max open positions", DefaultValue = 4, MinValue = 1, MaxValue = 6, Group = "Risk")]
        public int MaxOpenPositions { get; set; }

        [Parameter("Max portfolio nominal risk (%)", DefaultValue = 0.40, MinValue = 0.10, MaxValue = 1.0, Group = "Risk")]
        public double MaxPortfolioRiskPercent { get; set; }

        [Parameter("Daily equity loss block (%)", DefaultValue = 0.75, MinValue = 0.20, MaxValue = 3.0, Group = "Risk")]
        public double DailyLossBlockPercent { get; set; }

        [Parameter("Weekly equity loss block (%)", DefaultValue = 1.50, MinValue = 0.50, MaxValue = 5.0, Group = "Risk")]
        public double WeeklyLossBlockPercent { get; set; }

        [Parameter("EURUSD", DefaultValue = "EURUSD", Group = "Markets")]
        public string EurUsdName { get; set; }

        [Parameter("GBPUSD", DefaultValue = "GBPUSD", Group = "Markets")]
        public string GbpUsdName { get; set; }

        [Parameter("USDJPY", DefaultValue = "USDJPY", Group = "Markets")]
        public string UsdJpyName { get; set; }

        [Parameter("GOLD", DefaultValue = "XAUUSD", Group = "Markets")]
        public string GoldName { get; set; }

        [Parameter("Enable EURUSD", DefaultValue = true, Group = "Markets")]
        public bool EnableEurUsd { get; set; }

        [Parameter("Enable GBPUSD", DefaultValue = true, Group = "Markets")]
        public bool EnableGbpUsd { get; set; }

        [Parameter("Enable USDJPY", DefaultValue = true, Group = "Markets")]
        public bool EnableUsdJpy { get; set; }

        [Parameter("Enable GOLD", DefaultValue = true, Group = "Markets")]
        public bool EnableGold { get; set; }

        [Parameter("Decision hour UTC", DefaultValue = 1, MinValue = 0, MaxValue = 23, Group = "Schedule")]
        public int DecisionHourUtc { get; set; }

        [Parameter("Minimum total score", DefaultValue = 58.0, MinValue = 45.0, MaxValue = 85.0, Group = "Scoring")]
        public double MinimumScore { get; set; }

        [Parameter("Second trade max score gap", DefaultValue = 12.0, MinValue = 0.0, MaxValue = 30.0, Group = "Scoring")]
        public double SecondTradeMaxGap { get; set; }

        [Parameter("ATR period", DefaultValue = 14, MinValue = 5, MaxValue = 50, Group = "Stops")]
        public int AtrPeriod { get; set; }

        [Parameter("ATR stop multiplier", DefaultValue = 1.70, MinValue = 1.0, MaxValue = 4.0, Group = "Stops")]
        public double AtrStopMultiplier { get; set; }

        [Parameter("Swing lookback D1", DefaultValue = 7, MinValue = 3, MaxValue = 20, Group = "Stops")]
        public int SwingLookback { get; set; }

        [Parameter("Reward / risk", DefaultValue = 2.50, MinValue = 1.50, MaxValue = 5.0, Group = "Stops")]
        public double RewardRisk { get; set; }

        [Parameter("Break-even activation (R)", DefaultValue = 1.00, MinValue = 0.50, MaxValue = 2.0, Group = "Management")]
        public double BreakEvenAtR { get; set; }

        [Parameter("Break-even lock (R)", DefaultValue = 0.10, MinValue = 0.0, MaxValue = 0.50, Group = "Management")]
        public double BreakEvenLockR { get; set; }

        [Parameter("Trail activation (R)", DefaultValue = 1.60, MinValue = 1.0, MaxValue = 3.0, Group = "Management")]
        public double TrailAtR { get; set; }

        [Parameter("Trail ATR multiplier", DefaultValue = 1.30, MinValue = 0.50, MaxValue = 3.0, Group = "Management")]
        public double TrailAtrMultiplier { get; set; }

        [Parameter("Max spread / D1 ATR", DefaultValue = 0.10, MinValue = 0.01, MaxValue = 0.30, Group = "Execution")]
        public double MaxSpreadToDailyAtr { get; set; }

        [Parameter("Avoid EURUSD+GBPUSD together", DefaultValue = true, Group = "Execution")]
        public bool AvoidCorrelatedMajors { get; set; }

        [Parameter("Diagnostic logs", DefaultValue = true, Group = "Logs")]
        public bool DiagnosticLogs { get; set; }

        private sealed class MarketState
        {
            public Symbol Symbol;
            public Bars W1;
            public Bars D1;
            public Bars H4;
            public Bars H1;
            public ExponentialMovingAverage W1Fast;
            public ExponentialMovingAverage W1Slow;
            public ExponentialMovingAverage D1Fast;
            public ExponentialMovingAverage D1Slow;
            public ExponentialMovingAverage D1Long;
            public ExponentialMovingAverage H4Fast;
            public ExponentialMovingAverage H4Slow;
            public RelativeStrengthIndex H4Rsi;
            public ExponentialMovingAverage H1Fast;
            public ExponentialMovingAverage H1Slow;
            public RelativeStrengthIndex H1Rsi;
            public bool Gold;
        }

        private sealed class Candidate
        {
            public MarketState Market;
            public TradeType Side;
            public double Score;
            public double BullScore;
            public double BearScore;
            public double StopPips;
            public double DailyAtr;
            public double SpreadToAtr;
            public string Reason;
        }

        private sealed class ManagedTrade
        {
            public long PositionId;
            public double OriginalStopPips;
            public double BestPrice;
            public bool BreakEvenDone;
        }

        private readonly List<MarketState> _markets = new List<MarketState>();
        private readonly Dictionary<long, ManagedTrade> _managed = new Dictionary<long, ManagedTrade>();
        private readonly Dictionary<string, string> _notes = new Dictionary<string, string>();

        private DateTime _day;
        private DateTime _weekStart;
        private double _dayStartEquity;
        private double _weekStartEquity;
        private int _openedToday;
        private bool _dailyDecisionDone;
        private DateTime _nextHeartbeat = DateTime.MinValue;

        protected override void OnStart()
        {
            if (DemoOnly && Account.IsLive)
            {
                Print("SAQR DAILY SELECTOR safety block: DemoOnly=true on LIVE.");
                Stop();
                return;
            }

            AddMarket(EnableEurUsd, EurUsdName, false);
            AddMarket(EnableGbpUsd, GbpUsdName, false);
            AddMarket(EnableUsdJpy, UsdJpyName, false);
            AddMarket(EnableGold, GoldName, true);

            if (_markets.Count == 0)
            {
                Print("SAQR DAILY SELECTOR no broker symbols loaded.");
                Stop();
                return;
            }

            _day = Server.Time.Date;
            _weekStart = StartOfWeek(_day);
            _dayStartEquity = Account.Equity;
            _weekStartEquity = Account.Equity;
            _openedToday = 0;
            _dailyDecisionDone = false;

            Timer.Start(30);

            Print("SAQR DAILY SELECTOR V2.1 25-FACTOR ON | reads W1 + previous D1 + recent H4/H1 | max new trades/day={0} | risk/trade={1:F2}% | RR={2:F2}",
                MaxNewTradesPerDay, RiskPerTradePercent, RewardRisk);
            Print("Exactly 25 scoring factors: W1=5, D1=8, H4=6, H1=6. Factors are WEIGHTS, not hard gates; safety/cost/risk gates remain mandatory.");
        }

        private void AddMarket(bool enabled, string name, bool gold)
        {
            if (!enabled || string.IsNullOrWhiteSpace(name)) return;
            try
            {
                var s = Symbols.GetSymbol(name.Trim());
                if (s == null)
                {
                    Print("SAQR DAILY SELECTOR missing symbol {0}", name);
                    return;
                }

                var w1 = MarketData.GetBars(TimeFrame.Weekly, s.Name);
                var d1 = MarketData.GetBars(TimeFrame.Daily, s.Name);
                var h4 = MarketData.GetBars(TimeFrame.Hour4, s.Name);
                var h1 = MarketData.GetBars(TimeFrame.Hour, s.Name);

                _markets.Add(new MarketState
                {
                    Symbol = s,
                    W1 = w1,
                    D1 = d1,
                    H4 = h4,
                    H1 = h1,
                    W1Fast = Indicators.ExponentialMovingAverage(w1.ClosePrices, 10),
                    W1Slow = Indicators.ExponentialMovingAverage(w1.ClosePrices, 20),
                    D1Fast = Indicators.ExponentialMovingAverage(d1.ClosePrices, 20),
                    D1Slow = Indicators.ExponentialMovingAverage(d1.ClosePrices, 50),
                    D1Long = Indicators.ExponentialMovingAverage(d1.ClosePrices, 200),
                    H4Fast = Indicators.ExponentialMovingAverage(h4.ClosePrices, 20),
                    H4Slow = Indicators.ExponentialMovingAverage(h4.ClosePrices, 50),
                    H4Rsi = Indicators.RelativeStrengthIndex(h4.ClosePrices, 14),
                    H1Fast = Indicators.ExponentialMovingAverage(h1.ClosePrices, 20),
                    H1Slow = Indicators.ExponentialMovingAverage(h1.ClosePrices, 50),
                    H1Rsi = Indicators.RelativeStrengthIndex(h1.ClosePrices, 14),
                    Gold = gold
                });

                Print("SAQR DAILY SELECTOR tracking {0}: W1/D1/H4/H1.", s.Name);
            }
            catch (Exception ex)
            {
                Print("SAQR DAILY SELECTOR load failed {0}: {1}", name, ex.Message);
            }
        }

        protected override void OnTimer()
        {
            ResetAnchors();
            ManageOpenPositions();

            if (RiskBlocked())
            {
                if (Server.Time >= _nextHeartbeat)
                {
                    Print("SAQR DAILY SELECTOR RISK BLOCK dayDD={0:F2}% weekDD={1:F2}%",
                        DrawdownPct(_dayStartEquity), DrawdownPct(_weekStartEquity));
                    _nextHeartbeat = Server.Time.AddHours(1);
                }
                return;
            }

            if (!_dailyDecisionDone && Server.Time.Hour >= DecisionHourUtc)
            {
                RunDailySelection();
                _dailyDecisionDone = true;
            }

            if (DiagnosticLogs && Server.Time >= _nextHeartbeat)
            {
                Print("SAQR DAILY SELECTOR heartbeat open={0}/{1} openedToday={2}/{3} decisionDone={4} | {5}",
                    BotPositions().Length, MaxOpenPositions, _openedToday, MaxNewTradesPerDay, _dailyDecisionDone,
                    string.Join("; ", _markets.Select(m => m.Symbol.Name + "=" +
                        (_notes.ContainsKey(m.Symbol.Name) ? _notes[m.Symbol.Name] : "ready"))));
                _nextHeartbeat = Server.Time.AddHours(1);
            }
        }

        private void RunDailySelection()
        {
            var candidates = new List<Candidate>();

            foreach (var m in _markets)
            {
                if (!m.Symbol.MarketHours.IsOpened())
                {
                    _notes[m.Symbol.Name] = "market closed";
                    continue;
                }

                if (BotPositions().Any(p => p.SymbolName == m.Symbol.Name))
                {
                    _notes[m.Symbol.Name] = "already open";
                    continue;
                }

                Candidate c = ScoreMarket(m);
                if (c != null)
                    candidates.Add(c);
            }

            var ranked = candidates
                .OrderByDescending(c => c.Score)
                .ThenBy(c => c.SpreadToAtr)
                .ToList();

            if (ranked.Count == 0)
            {
                Print("SAQR DAILY SELECTOR: no safe candidates today.");
                return;
            }

            Candidate best = ranked[0];
            if (best.Score < MinimumScore)
            {
                Print("SAQR DAILY SELECTOR: best score {0:F1} < minimum {1:F1}; no trade today.", best.Score, MinimumScore);
                return;
            }

            TryOpenCandidate(best);

            if (_openedToday >= MaxNewTradesPerDay || BotPositions().Length >= MaxOpenPositions)
                return;

            Candidate second = ranked.Skip(1)
                .FirstOrDefault(c =>
                    c.Score >= MinimumScore &&
                    best.Score - c.Score <= SecondTradeMaxGap &&
                    !WouldCorrelateWithOpen(c.Market.Symbol.Name));

            if (second != null)
                TryOpenCandidate(second);
        }

        private Candidate ScoreMarket(MarketState m)
        {
            Symbol s = m.Symbol;
            if (m.W1.Count < 50 || m.D1.Count < 260 || m.H4.Count < 120 || m.H1.Count < 180)
            {
                _notes[s.Name] = "loading history";
                return null;
            }

            double dAtr = ClosedAtr(m.D1, AtrPeriod, 120);
            if (dAtr <= 0 || s.PipSize <= 0) return null;

            double spread = s.Ask - s.Bid;
            double spreadToAtr = spread / dAtr;
            if (spread <= 0 || spreadToAtr > MaxSpreadToDailyAtr)
            {
                _notes[s.Name] = string.Format("cost too high spread/D1ATR={0:F3}", spreadToAtr);
                return null;
            }

            double bull = 0.0;
            double bear = 0.0;
            var bullWhy = new List<string>();
            var bearWhy = new List<string>();

            // =========================
            // W1: 5 factors = 20 points
            // =========================
            double wClose1 = m.W1.ClosePrices.Last(1);
            double wOpen1 = m.W1.OpenPrices.Last(1);
            double wHigh1 = m.W1.HighPrices.Last(1);
            double wLow1 = m.W1.LowPrices.Last(1);
            double wFast = m.W1Fast.Result.Last(1);
            double wSlow = m.W1Slow.Result.Last(1);
            double wRange = Math.Max(s.PipSize, wHigh1 - wLow1);
            double wCloseLoc = (wClose1 - wLow1) / wRange;
            double wAtr = ClosedAtr(m.W1, 14, 35);

            AddVote(ref bull, ref bear, wFast > wSlow, 6, "1 W1 EMA10/20", bullWhy, bearWhy);                         // 1
            AddVote(ref bull, ref bear, wClose1 > wOpen1, 4, "2 W1 candle", bullWhy, bearWhy);                         // 2
            if (wCloseLoc >= 0.65) AddBull(ref bull, 4, "3 W1 close location", bullWhy);
            else if (wCloseLoc <= 0.35) AddBear(ref bear, 4, "3 W1 close location", bearWhy);                           // 3

            bool wHigher = wHigh1 > m.W1.HighPrices.Last(2) && wLow1 > m.W1.LowPrices.Last(2);
            bool wLower = wHigh1 < m.W1.HighPrices.Last(2) && wLow1 < m.W1.LowPrices.Last(2);
            if (wHigher) AddBull(ref bull, 4, "4 W1 structure", bullWhy);
            else if (wLower) AddBear(ref bear, 4, "4 W1 structure", bearWhy);                                           // 4

            if (wAtr > 0 && wRange >= 0.90 * wAtr)
            {
                if (wClose1 >= wOpen1) AddBull(ref bull, 2, "5 W1 range/ATR", bullWhy);
                else AddBear(ref bear, 2, "5 W1 range/ATR", bearWhy);
            }                                                                                                          // 5

            // =========================
            // D1: 8 factors = 30 points
            // =========================
            double dClose = m.D1.ClosePrices.Last(1);
            double dOpen = m.D1.OpenPrices.Last(1);
            double dHigh = m.D1.HighPrices.Last(1);
            double dLow = m.D1.LowPrices.Last(1);
            double d20 = m.D1Fast.Result.Last(1);
            double d50 = m.D1Slow.Result.Last(1);
            double d200 = m.D1Long.Result.Last(1);
            double d50Prev = m.D1Slow.Result.Last(6);
            double dRange = Math.Max(s.PipSize, dHigh - dLow);
            double dBody = Math.Abs(dClose - dOpen);
            double dCloseLocation = (dClose - dLow) / dRange;

            AddVote(ref bull, ref bear, d20 > d50, 5, "6 D1 EMA20/50", bullWhy, bearWhy);                               // 6
            AddVote(ref bull, ref bear, d50 > d200, 6, "7 D1 EMA50/200", bullWhy, bearWhy);                             // 7
            AddVote(ref bull, ref bear, d50 > d50Prev, 4, "8 D1 EMA50 slope", bullWhy, bearWhy);                        // 8
            AddVote(ref bull, ref bear, dClose > dOpen, 3, "9 D1 candle", bullWhy, bearWhy);                            // 9

            if (dCloseLocation >= 0.67) AddBull(ref bull, 3, "10 D1 close location", bullWhy);
            else if (dCloseLocation <= 0.33) AddBear(ref bear, 3, "10 D1 close location", bearWhy);                     // 10

            if (dBody / dRange >= 0.55)
            {
                if (dClose > dOpen) AddBull(ref bull, 3, "11 D1 body/range", bullWhy);
                else AddBear(ref bear, 3, "11 D1 body/range", bearWhy);
            }                                                                                                          // 11

            double dAtrPct = AtrPercentile(m.D1, AtrPeriod, 80);
            if (dAtrPct >= 30 && dAtrPct <= 85)
            {
                bull += 3;
                bear += 3;
            }
            else if (dAtrPct > 15 && dAtrPct < 95)
            {
                bull += 1;
                bear += 1;
            }                                                                                                          // 12

            bool dBullPullback = dLow <= d20 + 0.15 * dAtr && dClose > d20;
            bool dBearPullback = dHigh >= d20 - 0.15 * dAtr && dClose < d20;
            if (dBullPullback) AddBull(ref bull, 3, "13 D1 EMA20 pullback", bullWhy);
            else if (dBearPullback) AddBear(ref bear, 3, "13 D1 EMA20 pullback", bearWhy);                              // 13

            // =========================
            // H4: 6 factors = 25 points
            // =========================
            double h4Fast = m.H4Fast.Result.Last(1);
            double h4Slow = m.H4Slow.Result.Last(1);
            double h4FastPrev = m.H4Fast.Result.Last(4);
            double h4Close = m.H4.ClosePrices.Last(1);
            double h4Open = m.H4.OpenPrices.Last(1);
            double h4Rsi = m.H4Rsi.Result.Last(1);
            double h4Adx = CalculateAdx(m.H4, 14, 1);

            AddVote(ref bull, ref bear, h4Fast > h4Slow, 5, "14 H4 EMA20/50", bullWhy, bearWhy);                         // 14
            AddVote(ref bull, ref bear, h4Fast > h4FastPrev, 4, "15 H4 EMA20 slope", bullWhy, bearWhy);                 // 15

            if (h4Adx >= 18)
            {
                if (h4Fast > h4Slow) AddBull(ref bull, 4, "16 H4 ADX", bullWhy);
                else AddBear(ref bear, 4, "16 H4 ADX", bearWhy);
            }                                                                                                          // 16

            if (h4Rsi >= 54 && h4Rsi <= 72) AddBull(ref bull, 4, "17 H4 RSI", bullWhy);
            else if (h4Rsi <= 46 && h4Rsi >= 28) AddBear(ref bear, 4, "17 H4 RSI", bearWhy);                            // 17

            bool h4Higher = m.H4.HighPrices.Last(1) > m.H4.HighPrices.Last(2) &&
                            m.H4.LowPrices.Last(1) > m.H4.LowPrices.Last(2);
            bool h4Lower = m.H4.HighPrices.Last(1) < m.H4.HighPrices.Last(2) &&
                           m.H4.LowPrices.Last(1) < m.H4.LowPrices.Last(2);
            if (h4Higher) AddBull(ref bull, 4, "18 H4 structure", bullWhy);
            else if (h4Lower) AddBear(ref bear, 4, "18 H4 structure", bearWhy);                                         // 18

            bool h4BullPullback = m.H4.LowPrices.Last(2) <= m.H4Fast.Result.Last(2) && h4Close > h4Fast && h4Close > h4Open;
            bool h4BearPullback = m.H4.HighPrices.Last(2) >= m.H4Fast.Result.Last(2) && h4Close < h4Fast && h4Close < h4Open;
            if (h4BullPullback) AddBull(ref bull, 4, "19 H4 pullback/resume", bullWhy);
            else if (h4BearPullback) AddBear(ref bear, 4, "19 H4 pullback/resume", bearWhy);                            // 19

            // =========================
            // H1: 6 factors = 25 points
            // =========================
            double h1Fast = m.H1Fast.Result.Last(1);
            double h1Slow = m.H1Slow.Result.Last(1);
            double h1Rsi = m.H1Rsi.Result.Last(1);
            double h1Adx = CalculateAdx(m.H1, 14, 1);
            double h1Close = m.H1.ClosePrices.Last(1);
            double h1Open = m.H1.OpenPrices.Last(1);
            double h1High = m.H1.HighPrices.Last(1);
            double h1Low = m.H1.LowPrices.Last(1);

            AddVote(ref bull, ref bear, h1Fast > h1Slow, 5, "20 H1 EMA20/50", bullWhy, bearWhy);                         // 20

            if (h1Rsi >= 55 && h1Rsi <= 72) AddBull(ref bull, 4, "21 H1 RSI", bullWhy);
            else if (h1Rsi <= 45 && h1Rsi >= 28) AddBear(ref bear, 4, "21 H1 RSI", bearWhy);                            // 21

            if (h1Adx >= 18)
            {
                if (h1Fast > h1Slow) AddBull(ref bull, 5, "22 H1 ADX", bullWhy);
                else AddBear(ref bear, 5, "22 H1 ADX", bearWhy);
            }                                                                                                          // 22

            double recentHigh = double.MinValue;
            double recentLow = double.MaxValue;
            for (int i = 2; i <= 7; i++)
            {
                recentHigh = Math.Max(recentHigh, m.H1.HighPrices.Last(i));
                recentLow = Math.Min(recentLow, m.H1.LowPrices.Last(i));
            }
            if (h1Close > recentHigh) AddBull(ref bull, 5, "23 H1 6h breakout", bullWhy);
            else if (h1Close < recentLow) AddBear(ref bear, 5, "23 H1 6h breakout", bearWhy);                           // 23

            double volAvg = AverageTickVolume(m.H1, 2, 20);
            double volRatio = volAvg > 0 ? m.H1.TickVolumes.Last(1) / volAvg : 0;
            if (volRatio >= 1.15)
            {
                if (h1Close > h1Open) AddBull(ref bull, 3, "24 H1 volume impulse", bullWhy);
                else if (h1Close < h1Open) AddBear(ref bear, 3, "24 H1 volume impulse", bearWhy);
            }                                                                                                          // 24

            double h1Range = Math.Max(s.PipSize, h1High - h1Low);
            double h1BodyRatio = Math.Abs(h1Close - h1Open) / h1Range;
            if (h1BodyRatio >= 0.60)
            {
                if (h1Close > h1Open) AddBull(ref bull, 3, "25 H1 body/wick", bullWhy);
                else AddBear(ref bear, 3, "25 H1 body/wick", bearWhy);
            }                                                                                                          // 25

            // Cost is NOT counted among the 25 factors. It remains an execution penalty/gate.
            double costPenalty = Math.Min(10.0, spreadToAtr * 50.0);
            bull = Math.Max(0, bull - costPenalty);
            bear = Math.Max(0, bear - costPenalty);

            TradeType side = bull >= bear ? TradeType.Buy : TradeType.Sell;
            double score = Math.Max(bull, bear);

            double swingLow = double.MaxValue;
            double swingHigh = double.MinValue;
            for (int i = 1; i <= SwingLookback; i++)
            {
                swingLow = Math.Min(swingLow, m.D1.LowPrices.Last(i));
                swingHigh = Math.Max(swingHigh, m.D1.HighPrices.Last(i));
            }

            double entry = side == TradeType.Buy ? s.Ask : s.Bid;
            double atrStop = dAtr * AtrStopMultiplier;
            double swingStop = side == TradeType.Buy
                ? Math.Max(0, entry - (swingLow - 0.10 * dAtr))
                : Math.Max(0, (swingHigh + 0.10 * dAtr) - entry);
            double stopPrice = Math.Max(atrStop, swingStop);
            if (stopPrice <= 0) return null;

            string reason = side == TradeType.Buy
                ? string.Join(", ", bullWhy.Take(8))
                : string.Join(", ", bearWhy.Take(8));

            _notes[s.Name] = string.Format("{0} {1:F1}/100 (25F)", side, score);

            return new Candidate
            {
                Market = m,
                Side = side,
                Score = score,
                BullScore = bull,
                BearScore = bear,
                StopPips = stopPrice / s.PipSize,
                DailyAtr = dAtr,
                SpreadToAtr = spreadToAtr,
                Reason = reason
            };
        }

        private void AddVote(ref double bull, ref double bear, bool bullish, double points, string name,
            List<string> bullWhy, List<string> bearWhy)
        {
            if (bullish) AddBull(ref bull, points, name, bullWhy);
            else AddBear(ref bear, points, name, bearWhy);
        }

        private void AddBull(ref double bull, double points, string name, List<string> why)
        {
            bull += points;
            why.Add(name);
        }

        private void AddBear(ref double bear, double points, string name, List<string> why)
        {
            bear += points;
            why.Add(name);
        }

        private double AverageTickVolume(Bars b, int startLastIndex, int count)
        {
            if (b.Count < startLastIndex + count + 2) return 0;
            double sum = 0;
            for (int i = startLastIndex; i < startLastIndex + count; i++)
                sum += b.TickVolumes.Last(i);
            return sum / count;
        }

        private double AtrPercentile(Bars b, int period, int lookback)
        {
            double current = AverageTrueRangeWindow(b, 1, period);
            if (current <= 0) return 0;

            int below = 0;
            int count = 0;
            for (int shift = 2; shift < lookback + 2; shift++)
            {
                double v = AverageTrueRangeWindow(b, shift, period);
                if (v <= 0) continue;
                if (v <= current) below++;
                count++;
            }
            return count > 0 ? 100.0 * below / count : 0;
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

        private void TryOpenCandidate(Candidate c)
        {
            if (_openedToday >= MaxNewTradesPerDay || BotPositions().Length >= MaxOpenPositions) return;
            if (WouldCorrelateWithOpen(c.Market.Symbol.Name)) return;

            double nominalOpenRisk = BotPositions().Length * RiskPerTradePercent;
            if (nominalOpenRisk + RiskPerTradePercent > MaxPortfolioRiskPercent + 1e-9)
            {
                Print("SAQR DAILY SELECTOR portfolio risk cap blocks {0}.", c.Market.Symbol.Name);
                return;
            }

            Symbol s = c.Market.Symbol;
            double units = s.VolumeForProportionalRisk(
                ProportionalAmountType.Equity, RiskPerTradePercent, c.StopPips, RoundingMode.Down);
            units = s.NormalizeVolumeInUnits(units, RoundingMode.Down);

            if (units < s.VolumeInUnitsMin)
            {
                Print("SAQR DAILY SELECTOR risk skip {0}: units={1} < min={2}.", s.Name, units, s.VolumeInUnitsMin);
                return;
            }
            if (units > s.VolumeInUnitsMax) units = s.VolumeInUnitsMax;

            double budget = Account.Equity * RiskPerTradePercent / 100.0;
            double actualRisk = s.AmountRisked(units, c.StopPips);
            if (actualRisk <= 0 || actualRisk > budget * 1.10)
            {
                Print("SAQR DAILY SELECTOR risk skip {0}: risk≈{1:F2} budget={2:F2}.", s.Name, actualRisk, budget);
                return;
            }

            double tpPips = c.StopPips * RewardRisk;
            var tr = ExecuteMarketOrder(c.Side, s.Name, units, BaseLabel, c.StopPips, tpPips);
            if (!tr.IsSuccessful || tr.Position == null)
            {
                Print("SAQR DAILY SELECTOR OPEN FAILED {0} {1}: {2}", s.Name, c.Side, tr.Error);
                return;
            }

            _openedToday++;
            _managed[tr.Position.Id] = new ManagedTrade
            {
                PositionId = tr.Position.Id,
                OriginalStopPips = c.StopPips,
                BestPrice = c.Side == TradeType.Buy ? s.Bid : s.Ask,
                BreakEvenDone = false
            };

            Print("SAQR DAILY SELECTED #{0}/{1}: {2} {3} score={4:F1}/100 bull={5:F1} bear={6:F1} risk={7:F2}% SL={8:F1}p TP={9:F1}p | {10}",
                _openedToday, MaxNewTradesPerDay, s.Name, c.Side, c.Score, c.BullScore, c.BearScore,
                RiskPerTradePercent, c.StopPips, tpPips, c.Reason);
        }

        private void ManageOpenPositions()
        {
            foreach (var p in BotPositions())
            {
                var market = _markets.FirstOrDefault(m => m.Symbol.Name == p.SymbolName);
                if (market == null) continue;

                ManagedTrade state;
                if (!_managed.TryGetValue(p.Id, out state))
                {
                    if (!p.StopLoss.HasValue) continue;
                    state = new ManagedTrade
                    {
                        PositionId = p.Id,
                        OriginalStopPips = Math.Abs(p.EntryPrice - p.StopLoss.Value) / market.Symbol.PipSize,
                        BestPrice = p.TradeType == TradeType.Buy ? market.Symbol.Bid : market.Symbol.Ask
                    };
                    _managed[p.Id] = state;
                }

                if (state.OriginalStopPips <= 0) continue;
                Symbol s = market.Symbol;
                double current = p.TradeType == TradeType.Buy ? s.Bid : s.Ask;

                if (p.TradeType == TradeType.Buy) state.BestPrice = Math.Max(state.BestPrice, current);
                else state.BestPrice = Math.Min(state.BestPrice, current);

                double favorablePips = p.TradeType == TradeType.Buy
                    ? (state.BestPrice - p.EntryPrice) / s.PipSize
                    : (p.EntryPrice - state.BestPrice) / s.PipSize;
                double r = favorablePips / state.OriginalStopPips;

                if (!state.BreakEvenDone && r >= BreakEvenAtR)
                {
                    double desired = p.EntryPrice +
                        (p.TradeType == TradeType.Buy ? 1.0 : -1.0) *
                        BreakEvenLockR * state.OriginalStopPips * s.PipSize;
                    if (ImproveStop(p, s, desired, "BE"))
                        state.BreakEvenDone = true;
                }

                if (r >= TrailAtR)
                {
                    double atr = ClosedAtr(market.D1, AtrPeriod, 100);
                    if (atr <= 0) continue;

                    double proposed = p.TradeType == TradeType.Buy
                        ? state.BestPrice - atr * TrailAtrMultiplier
                        : state.BestPrice + atr * TrailAtrMultiplier;

                    double floor = p.EntryPrice +
                        (p.TradeType == TradeType.Buy ? 1.0 : -1.0) *
                        BreakEvenLockR * state.OriginalStopPips * s.PipSize;

                    proposed = p.TradeType == TradeType.Buy ? Math.Max(proposed, floor) : Math.Min(proposed, floor);
                    ImproveStop(p, s, proposed, "TRAIL");
                }
            }

            foreach (var id in _managed.Keys.ToArray())
                if (!Positions.Any(p => p.Id == id))
                    _managed.Remove(id);
        }

        private bool ImproveStop(Position p, Symbol s, double desired, string reason)
        {
            double gap = Math.Max(2 * s.PipSize, (s.Ask - s.Bid) * 1.5);
            double safe = p.TradeType == TradeType.Buy
                ? Math.Min(desired, s.Bid - gap)
                : Math.Max(desired, s.Ask + gap);

            bool improves = !p.StopLoss.HasValue ||
                (p.TradeType == TradeType.Buy
                    ? safe > p.StopLoss.Value + 0.2 * s.PipSize
                    : safe < p.StopLoss.Value - 0.2 * s.PipSize);

            if (!improves) return false;

            var mod = p.ModifyStopLossPrice(Math.Round(safe, s.Digits));
            if (mod.IsSuccessful && DiagnosticLogs)
                Print("SAQR DAILY SELECTOR {0} {1} id={2} stop={3}", reason, p.SymbolName, p.Id, safe);

            return mod.IsSuccessful;
        }

        private bool WouldCorrelateWithOpen(string name)
        {
            if (!AvoidCorrelatedMajors) return false;
            bool eur = name.IndexOf("EURUSD", StringComparison.OrdinalIgnoreCase) >= 0;
            bool gbp = name.IndexOf("GBPUSD", StringComparison.OrdinalIgnoreCase) >= 0;
            if (!eur && !gbp) return false;

            string other = eur ? "GBPUSD" : "EURUSD";
            return BotPositions().Any(p => p.SymbolName.IndexOf(other, StringComparison.OrdinalIgnoreCase) >= 0);
        }

        private Position[] BotPositions()
        {
            var names = new HashSet<string>(_markets.Select(m => m.Symbol.Name));
            return Positions.Where(p => p.Label == BaseLabel && names.Contains(p.SymbolName)).ToArray();
        }

        private bool RiskBlocked()
        {
            return DrawdownPct(_dayStartEquity) >= DailyLossBlockPercent ||
                   DrawdownPct(_weekStartEquity) >= WeeklyLossBlockPercent;
        }

        private double DrawdownPct(double anchor)
        {
            if (anchor <= 0) return 0;
            return Math.Max(0, (anchor - Account.Equity) / anchor * 100.0);
        }

        private void ResetAnchors()
        {
            DateTime now = Server.Time.Date;
            if (now != _day)
            {
                _day = now;
                _dayStartEquity = Account.Equity;
                _openedToday = 0;
                _dailyDecisionDone = false;
            }

            DateTime ws = StartOfWeek(now);
            if (ws != _weekStart)
            {
                _weekStart = ws;
                _weekStartEquity = Account.Equity;
            }
        }

        private DateTime StartOfWeek(DateTime d)
        {
            int diff = ((int)d.DayOfWeek + 6) % 7;
            return d.AddDays(-diff).Date;
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

                trSum += tr;
                if (upMove > downMove && upMove > 0) plusSum += upMove;
                if (downMove > upMove && downMove > 0) minusSum += downMove;
            }

            if (trSum <= 0) return 0;
            double plusDi = 100 * plusSum / trSum;
            double minusDi = 100 * minusSum / trSum;
            double denom = plusDi + minusDi;
            return denom > 0 ? 100 * Math.Abs(plusDi - minusDi) / denom : 0;
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
                double tr = Math.Max(high - low, Math.Max(Math.Abs(high - prev), Math.Abs(low - prev)));

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
            Print("SAQR DAILY SELECTOR error {0}", error.Code);
        }

        protected override void OnStop()
        {
            Timer.Stop();
            Print("SAQR DAILY SELECTOR V2.1 25-FACTOR stopped.");
        }
    }
}
