using System;
using System.Collections.Generic;
using System.Linq;
using cAlgo.API;
using cAlgo.API.Internals;
using cAlgo.API.Indicators;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class SaqrDailySelectorV2 : Robot
    {
        [Parameter("Demo ONLY - block live", DefaultValue = true, Group = "Safety")]
        public bool DemoOnly { get; set; }

        [Parameter("Base label", DefaultValue = "SAQR-DAILY-SELECTOR-V2", Group = "Safety")]
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

            Print("SAQR DAILY SELECTOR V2 ON | reads W1 + previous D1 + recent H4/H1 | max new trades/day={0} | risk/trade={1:F2}% | RR={2:F2}",
                MaxNewTradesPerDay, RiskPerTradePercent, RewardRisk);
            Print("Indicators are WEIGHTS, not individual hard gates. Safety/cost/risk gates remain mandatory.");
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
            if (m.W1.Count < 40 || m.D1.Count < 230 || m.H4.Count < 80 || m.H1.Count < 120)
            {
                _notes[s.Name] = "loading history";
                return null;
            }

            double dAtr = ClosedAtr(m.D1, AtrPeriod, 100);
            if (dAtr <= 0 || s.PipSize <= 0) return null;

            double spread = s.Ask - s.Bid;
            double spreadToAtr = spread / dAtr;
            if (spread <= 0 || spreadToAtr > MaxSpreadToDailyAtr)
            {
                _notes[s.Name] = string.Format("cost too high spread/D1ATR={0:F3}", spreadToAtr);
                return null;
            }

            double bull = 50.0;
            double bear = 50.0;
            var bullWhy = new List<string>();
            var bearWhy = new List<string>();

            // 1) Previous week: broad trend and weekly candle.
            double wClose = m.W1.ClosePrices.Last(1);
            double wOpen = m.W1.OpenPrices.Last(1);
            double wFast = m.W1Fast.Result.Last(1);
            double wSlow = m.W1Slow.Result.Last(1);

            AddDirectional(ref bull, ref bear, wFast > wSlow, 8, "W1 EMA", bullWhy, bearWhy);
            AddDirectional(ref bull, ref bear, wClose > wOpen, 5, "prev week candle", bullWhy, bearWhy);

            // 2) Previous day: long trend + candle structure.
            double dClose = m.D1.ClosePrices.Last(1);
            double dOpen = m.D1.OpenPrices.Last(1);
            double dHigh = m.D1.HighPrices.Last(1);
            double dLow = m.D1.LowPrices.Last(1);
            double d20 = m.D1Fast.Result.Last(1);
            double d50 = m.D1Slow.Result.Last(1);
            double d200 = m.D1Long.Result.Last(1);

            AddDirectional(ref bull, ref bear, d20 > d50, 7, "D1 20/50", bullWhy, bearWhy);
            AddDirectional(ref bull, ref bear, d50 > d200, 9, "D1 50/200", bullWhy, bearWhy);
            AddDirectional(ref bull, ref bear, dClose > dOpen, 5, "prev day candle", bullWhy, bearWhy);

            double dRange = Math.Max(s.PipSize, dHigh - dLow);
            double dCloseLocation = (dClose - dLow) / dRange;
            if (dCloseLocation >= 0.70) { bull += 4; bullWhy.Add("D1 close near high"); }
            if (dCloseLocation <= 0.30) { bear += 4; bearWhy.Add("D1 close near low"); }

            // 3) Recent H4: trend and slope.
            double h4Fast = m.H4Fast.Result.Last(1);
            double h4Slow = m.H4Slow.Result.Last(1);
            double h4FastPrev = m.H4Fast.Result.Last(4);
            AddDirectional(ref bull, ref bear, h4Fast > h4Slow, 7, "H4 trend", bullWhy, bearWhy);
            AddDirectional(ref bull, ref bear, h4Fast > h4FastPrev, 4, "H4 slope", bullWhy, bearWhy);

            // 4) Recent hours H1: immediate momentum, RSI, and market structure.
            double h1Fast = m.H1Fast.Result.Last(1);
            double h1Slow = m.H1Slow.Result.Last(1);
            double h1Rsi = m.H1Rsi.Result.Last(1);
            AddDirectional(ref bull, ref bear, h1Fast > h1Slow, 6, "H1 trend", bullWhy, bearWhy);

            if (h1Rsi >= 55 && h1Rsi <= 72) { bull += 5; bullWhy.Add("H1 RSI momentum"); }
            else if (h1Rsi <= 45 && h1Rsi >= 28) { bear += 5; bearWhy.Add("H1 RSI momentum"); }

            double h1Close = m.H1.ClosePrices.Last(1);
            double recentHigh = double.MinValue;
            double recentLow = double.MaxValue;
            for (int i = 2; i <= 7; i++)
            {
                recentHigh = Math.Max(recentHigh, m.H1.HighPrices.Last(i));
                recentLow = Math.Min(recentLow, m.H1.LowPrices.Last(i));
            }
            if (h1Close > recentHigh) { bull += 5; bullWhy.Add("H1 breakout"); }
            if (h1Close < recentLow) { bear += 5; bearWhy.Add("H1 breakout"); }

            double adx = CalculateAdx(m.H1, 14, 1);
            if (adx >= 20)
            {
                if (h1Fast > h1Slow) { bull += 4; bullWhy.Add("H1 ADX"); }
                else { bear += 4; bearWhy.Add("H1 ADX"); }
            }

            // Pullback quality: preference, not a hard condition.
            bool bullPullback = m.H4.LowPrices.Last(1) <= h4Fast || m.H1.LowPrices.Last(1) <= h1Fast;
            bool bearPullback = m.H4.HighPrices.Last(1) >= h4Fast || m.H1.HighPrices.Last(1) >= h1Fast;
            if (bullPullback) { bull += 3; bullWhy.Add("pullback"); }
            if (bearPullback) { bear += 3; bearWhy.Add("pullback"); }

            // Cost penalty.
            double costPenalty = Math.Min(8.0, spreadToAtr * 40.0);
            bull -= costPenalty;
            bear -= costPenalty;

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
                ? string.Join(", ", bullWhy.Take(5))
                : string.Join(", ", bearWhy.Take(5));

            _notes[s.Name] = string.Format("{0} {1:F1}/100", side, score);

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

        private void AddDirectional(ref double bull, ref double bear, bool bullish, double points, string name,
            List<string> bullWhy, List<string> bearWhy)
        {
            if (bullish)
            {
                bull += points;
                bullWhy.Add(name);
            }
            else
            {
                bear += points;
                bearWhy.Add(name);
            }
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
            Print("SAQR DAILY SELECTOR V2 stopped.");
        }
    }
}
