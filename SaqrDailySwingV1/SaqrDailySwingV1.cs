using System;
using System.Collections.Generic;
using System.Linq;
using cAlgo.API;
using cAlgo.API.Internals;
using cAlgo.API.Indicators;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class SaqrDailySwingV1 : Robot
    {
        [Parameter("Demo ONLY - block live", DefaultValue = true, Group = "Safety")]
        public bool DemoOnly { get; set; }

        [Parameter("Base label", DefaultValue = "SAQR-DAILY-SWING-V1", Group = "Safety")]
        public string BaseLabel { get; set; }

        [Parameter("Risk per trade (%)", DefaultValue = 0.15, MinValue = 0.02, MaxValue = 0.50, Group = "Risk")]
        public double RiskPerTradePercent { get; set; }

        [Parameter("Max open positions", DefaultValue = 2, MinValue = 1, MaxValue = 3, Group = "Risk")]
        public int MaxOpenPositions { get; set; }

        [Parameter("Max nominal portfolio risk (%)", DefaultValue = 0.30, MinValue = 0.05, MaxValue = 1.0, Group = "Risk")]
        public double MaxPortfolioRiskPercent { get; set; }

        [Parameter("Daily equity loss block (%)", DefaultValue = 1.00, MinValue = 0.20, MaxValue = 5.0, Group = "Risk")]
        public double DailyEquityLossBlockPercent { get; set; }

        [Parameter("Weekly equity loss block (%)", DefaultValue = 2.00, MinValue = 0.50, MaxValue = 10.0, Group = "Risk")]
        public double WeeklyEquityLossBlockPercent { get; set; }

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

        [Parameter("Trend EMA fast", DefaultValue = 50, MinValue = 10, MaxValue = 100, Group = "Daily Trend")]
        public int TrendFastPeriod { get; set; }

        [Parameter("Trend EMA slow", DefaultValue = 200, MinValue = 100, MaxValue = 300, Group = "Daily Trend")]
        public int TrendSlowPeriod { get; set; }

        [Parameter("Pullback EMA", DefaultValue = 20, MinValue = 5, MaxValue = 80, Group = "Daily Trend")]
        public int PullbackEmaPeriod { get; set; }

        [Parameter("ADX period", DefaultValue = 14, MinValue = 5, MaxValue = 50, Group = "Daily Trend")]
        public int AdxPeriod { get; set; }

        [Parameter("Minimum ADX", DefaultValue = 20.0, MinValue = 10.0, MaxValue = 50.0, Group = "Daily Trend")]
        public double MinAdx { get; set; }

        [Parameter("RSI period", DefaultValue = 14, MinValue = 5, MaxValue = 50, Group = "Daily Trend")]
        public int RsiPeriod { get; set; }

        [Parameter("ATR period", DefaultValue = 14, MinValue = 5, MaxValue = 50, Group = "Stops")]
        public int AtrPeriod { get; set; }

        [Parameter("ATR stop multiplier", DefaultValue = 1.50, MinValue = 0.80, MaxValue = 4.0, Group = "Stops")]
        public double AtrStopMultiplier { get; set; }

        [Parameter("Swing lookback", DefaultValue = 7, MinValue = 3, MaxValue = 20, Group = "Stops")]
        public int SwingLookback { get; set; }

        [Parameter("Reward / risk", DefaultValue = 2.50, MinValue = 1.50, MaxValue = 5.0, Group = "Stops")]
        public double RewardRisk { get; set; }

        [Parameter("Break-even activation (R)", DefaultValue = 1.00, MinValue = 0.50, MaxValue = 2.0, Group = "Management")]
        public double BreakEvenAtR { get; set; }

        [Parameter("Break-even lock (R)", DefaultValue = 0.10, MinValue = 0.0, MaxValue = 0.50, Group = "Management")]
        public double BreakEvenLockR { get; set; }

        [Parameter("Trail activation (R)", DefaultValue = 1.50, MinValue = 1.0, MaxValue = 3.0, Group = "Management")]
        public double TrailAtR { get; set; }

        [Parameter("Trail ATR multiplier", DefaultValue = 1.20, MinValue = 0.50, MaxValue = 3.0, Group = "Management")]
        public double TrailAtrMultiplier { get; set; }

        [Parameter("Max spread / ATR", DefaultValue = 0.12, MinValue = 0.01, MaxValue = 0.50, Group = "Execution")]
        public double MaxSpreadToAtr { get; set; }

        [Parameter("Avoid EURUSD+GBPUSD together", DefaultValue = true, Group = "Execution")]
        public bool AvoidCorrelatedMajors { get; set; }

        [Parameter("Diagnostic logs", DefaultValue = true, Group = "Logs")]
        public bool DiagnosticLogs { get; set; }

        private sealed class MarketState
        {
            public Symbol Symbol;
            public Bars Daily;
            public ExponentialMovingAverage Ema20;
            public ExponentialMovingAverage Ema50;
            public ExponentialMovingAverage Ema200;
            public RelativeStrengthIndex Rsi;
            public DateTime LastEvaluated = DateTime.MinValue;
            public bool Gold;
        }

        private sealed class ManagedTrade
        {
            public long PositionId;
            public double OriginalStopPips;
            public double BestPrice;
            public bool BreakEvenDone;
        }

        private sealed class Candidate
        {
            public MarketState Market;
            public TradeType Side;
            public double StopPips;
            public double Score;
            public double Adx;
            public double Rsi;
            public double Atr;
            public double SpreadToAtr;
        }

        private readonly List<MarketState> _markets = new List<MarketState>();
        private readonly Dictionary<long, ManagedTrade> _managed = new Dictionary<long, ManagedTrade>();
        private readonly Dictionary<string, string> _notes = new Dictionary<string, string>();
        private DateTime _day;
        private DateTime _weekStart;
        private double _dayStartEquity;
        private double _weekStartEquity;
        private DateTime _nextHeartbeat = DateTime.MinValue;

        protected override void OnStart()
        {
            if (DemoOnly && Account.IsLive)
            {
                Print("SAQR DAILY safety block: DemoOnly=true on LIVE account.");
                Stop();
                return;
            }

            if (TrendFastPeriod >= TrendSlowPeriod || PullbackEmaPeriod >= TrendFastPeriod)
            {
                Print("SAQR DAILY invalid EMA periods.");
                Stop();
                return;
            }

            AddMarket(EnableEurUsd, EurUsdName, false);
            AddMarket(EnableGbpUsd, GbpUsdName, false);
            AddMarket(EnableUsdJpy, UsdJpyName, false);
            AddMarket(EnableGold, GoldName, true);

            if (_markets.Count == 0)
            {
                Print("SAQR DAILY no broker symbols loaded.");
                Stop();
                return;
            }

            _day = Server.Time.Date;
            _weekStart = StartOfWeek(Server.Time.Date);
            _dayStartEquity = Account.Equity;
            _weekStartEquity = Account.Equity;

            Timer.Start(15);
            Print("SAQR DAILY SWING V1 ON | timeframe=D1 only | markets={0} | risk/trade={1:F2}% | maxOpen={2} | RR={3:F2}",
                string.Join(",", _markets.Select(m => m.Symbol.Name)),
                RiskPerTradePercent, MaxOpenPositions, RewardRisk);
            Print("Trend=EMA{0}/{1}; pullback EMA{2}; ADX>={3:F1}; RSI confirmation; ATR+swing stop; no time exit.",
                TrendFastPeriod, TrendSlowPeriod, PullbackEmaPeriod, MinAdx);
        }

        private void AddMarket(bool enabled, string name, bool gold)
        {
            if (!enabled || string.IsNullOrWhiteSpace(name)) return;
            try
            {
                var s = Symbols.GetSymbol(name.Trim());
                if (s == null)
                {
                    Print("SAQR DAILY missing broker symbol {0}.", name);
                    return;
                }

                var d1 = MarketData.GetBars(TimeFrame.Daily, s.Name);
                _markets.Add(new MarketState
                {
                    Symbol = s,
                    Daily = d1,
                    Ema20 = Indicators.ExponentialMovingAverage(d1.ClosePrices, PullbackEmaPeriod),
                    Ema50 = Indicators.ExponentialMovingAverage(d1.ClosePrices, TrendFastPeriod),
                    Ema200 = Indicators.ExponentialMovingAverage(d1.ClosePrices, TrendSlowPeriod),
                    Rsi = Indicators.RelativeStrengthIndex(d1.ClosePrices, RsiPeriod),
                    Gold = gold
                });

                Print("SAQR DAILY tracking {0} D1.", s.Name);
            }
            catch (Exception ex)
            {
                Print("SAQR DAILY failed to load {0}: {1}", name, ex.Message);
            }
        }

        protected override void OnTimer()
        {
            ResetRiskAnchorsIfNeeded();
            ManagePositions();

            if (DailyLossBlocked() || WeeklyLossBlocked())
            {
                if (Server.Time >= _nextHeartbeat)
                {
                    Print("SAQR DAILY RISK BLOCK | dayDD={0:F2}% weekDD={1:F2}%",
                        DrawdownPct(_dayStartEquity), DrawdownPct(_weekStartEquity));
                    _nextHeartbeat = Server.Time.AddHours(1);
                }
                return;
            }

            if (BotPositions().Length < MaxOpenPositions)
                ScanDailySignals();

            if (DiagnosticLogs && Server.Time >= _nextHeartbeat)
            {
                Print("SAQR DAILY heartbeat | open={0}/{1} | {2}",
                    BotPositions().Length, MaxOpenPositions,
                    string.Join("; ", _markets.Select(m => m.Symbol.Name + "=" +
                        (_notes.ContainsKey(m.Symbol.Name) ? _notes[m.Symbol.Name] : "ready"))));
                _nextHeartbeat = Server.Time.AddHours(1);
            }
        }

        private void ScanDailySignals()
        {
            var candidates = new List<Candidate>();

            foreach (var m in _markets)
            {
                if (BotPositions().Any(p => p.SymbolName == m.Symbol.Name))
                {
                    _notes[m.Symbol.Name] = "position already open";
                    continue;
                }

                if (!m.Symbol.MarketHours.IsOpened())
                {
                    _notes[m.Symbol.Name] = "market closed";
                    continue;
                }

                if (AvoidCorrelatedMajors && IsCorrelationBlocked(m.Symbol.Name))
                {
                    _notes[m.Symbol.Name] = "correlation guard";
                    continue;
                }

                Candidate c = EvaluateDaily(m);
                if (c != null)
                    candidates.Add(c);
            }

            foreach (var c in candidates.OrderByDescending(x => x.Score))
            {
                if (BotPositions().Length >= MaxOpenPositions) break;
                if (BotPositions().Any(p => p.SymbolName == c.Market.Symbol.Name)) continue;
                OpenTrade(c);
            }
        }

        private Candidate EvaluateDaily(MarketState m)
        {
            Bars b = m.Daily;
            Symbol s = m.Symbol;
            int need = Math.Max(TrendSlowPeriod + 30, Math.Max(AdxPeriod * 3, SwingLookback + 30));
            if (b == null || b.Count < need)
            {
                _notes[s.Name] = "loading D1 history";
                return null;
            }

            DateTime barTime = b.OpenTimes.Last(1);
            if (barTime <= m.LastEvaluated) return null;
            m.LastEvaluated = barTime;

            double atr = ClosedAtr(b, AtrPeriod, 120);
            if (atr <= 0 || s.PipSize <= 0)
            {
                _notes[s.Name] = "ATR unavailable";
                return null;
            }

            double spread = s.Ask - s.Bid;
            double spreadToAtr = spread / atr;
            if (spread <= 0 || spreadToAtr > MaxSpreadToAtr)
            {
                _notes[s.Name] = string.Format("spread/ATR={0:F3}", spreadToAtr);
                return null;
            }

            double close1 = b.ClosePrices.Last(1);
            double open1 = b.OpenPrices.Last(1);
            double high1 = b.HighPrices.Last(1);
            double low1 = b.LowPrices.Last(1);
            double high2 = b.HighPrices.Last(2);
            double low2 = b.LowPrices.Last(2);

            double ema20 = m.Ema20.Result.Last(1);
            double ema50 = m.Ema50.Result.Last(1);
            double ema200 = m.Ema200.Result.Last(1);
            double ema50Prev = m.Ema50.Result.Last(6);
            double rsi = m.Rsi.Result.Last(1);
            double adx = CalculateAdx(b, AdxPeriod, 1);

            bool bullTrend = ema50 > ema200 && close1 > ema50 && ema50 > ema50Prev;
            bool bearTrend = ema50 < ema200 && close1 < ema50 && ema50 < ema50Prev;

            bool bullPullback = low2 <= m.Ema20.Result.Last(2) || low2 <= m.Ema50.Result.Last(2);
            bool bearPullback = high2 >= m.Ema20.Result.Last(2) || high2 >= m.Ema50.Result.Last(2);

            bool bullResume = close1 > open1 && close1 > high2;
            bool bearResume = close1 < open1 && close1 < low2;

            bool bullRsi = rsi >= 50 && rsi <= 70;
            bool bearRsi = rsi <= 50 && rsi >= 30;

            TradeType? side = null;
            if (bullTrend && bullPullback && bullResume && bullRsi && adx >= MinAdx)
                side = TradeType.Buy;
            else if (bearTrend && bearPullback && bearResume && bearRsi && adx >= MinAdx)
                side = TradeType.Sell;

            if (!side.HasValue)
            {
                _notes[s.Name] = string.Format("no D1 setup ADX={0:F1} RSI={1:F1}", adx, rsi);
                return null;
            }

            double swingLow = double.MaxValue;
            double swingHigh = double.MinValue;
            for (int i = 1; i <= SwingLookback; i++)
            {
                swingLow = Math.Min(swingLow, b.LowPrices.Last(i));
                swingHigh = Math.Max(swingHigh, b.HighPrices.Last(i));
            }

            double entry = side.Value == TradeType.Buy ? s.Ask : s.Bid;
            double atrStop = atr * AtrStopMultiplier;
            double swingStop = side.Value == TradeType.Buy
                ? Math.Max(0, entry - (swingLow - 0.15 * atr))
                : Math.Max(0, (swingHigh + 0.15 * atr) - entry);
            double stopPrice = Math.Max(atrStop, swingStop);

            if (stopPrice <= 0)
            {
                _notes[s.Name] = "invalid stop";
                return null;
            }

            double body = Math.Abs(close1 - open1);
            double range = Math.Max(s.PipSize, high1 - low1);
            double bodyRatio = body / range;
            double trendSepAtr = Math.Abs(ema50 - ema200) / atr;
            double slopeAtr = Math.Abs(ema50 - ema50Prev) / (5.0 * atr);

            double score = 0;
            score += Math.Min(1.5, adx / 25.0);
            score += Math.Min(1.0, trendSepAtr);
            score += Math.Min(1.0, slopeAtr * 4.0);
            score += Math.Min(0.75, bodyRatio);
            score += side.Value == TradeType.Buy ? Math.Max(0, (rsi - 50) / 20.0) : Math.Max(0, (50 - rsi) / 20.0);
            score -= spreadToAtr * 2.0;

            _notes[s.Name] = string.Format("QUALIFIED {0} score={1:F2}", side.Value, score);
            return new Candidate
            {
                Market = m,
                Side = side.Value,
                StopPips = stopPrice / s.PipSize,
                Score = score,
                Adx = adx,
                Rsi = rsi,
                Atr = atr,
                SpreadToAtr = spreadToAtr
            };
        }

        private void OpenTrade(Candidate c)
        {
            Symbol s = c.Market.Symbol;
            double currentRisk = BotPositions().Length * RiskPerTradePercent;
            if (currentRisk + RiskPerTradePercent > MaxPortfolioRiskPercent + 1e-9)
            {
                Print("SAQR DAILY portfolio cap blocks {0}.", s.Name);
                return;
            }

            double units = s.VolumeForProportionalRisk(
                ProportionalAmountType.Equity,
                RiskPerTradePercent,
                c.StopPips,
                RoundingMode.Down);
            units = s.NormalizeVolumeInUnits(units, RoundingMode.Down);

            if (units < s.VolumeInUnitsMin)
            {
                Print("SAQR DAILY risk skip {0}: units={1} < broker min={2}.", s.Name, units, s.VolumeInUnitsMin);
                return;
            }
            if (units > s.VolumeInUnitsMax) units = s.VolumeInUnitsMax;

            double actualRisk = s.AmountRisked(units, c.StopPips);
            double budget = Account.Equity * RiskPerTradePercent / 100.0;
            if (actualRisk <= 0 || actualRisk > budget * 1.10)
            {
                Print("SAQR DAILY risk skip {0}: actual≈{1:F2} budget={2:F2}.", s.Name, actualRisk, budget);
                return;
            }

            double tpPips = c.StopPips * RewardRisk;
            TradeResult tr = ExecuteMarketOrder(c.Side, s.Name, units, BaseLabel, c.StopPips, tpPips);
            if (!tr.IsSuccessful || tr.Position == null)
            {
                Print("SAQR DAILY OPEN FAILED {0} {1}: {2}", s.Name, c.Side, tr.Error);
                return;
            }

            _managed[tr.Position.Id] = new ManagedTrade
            {
                PositionId = tr.Position.Id,
                OriginalStopPips = c.StopPips,
                BestPrice = c.Side == TradeType.Buy ? s.Bid : s.Ask,
                BreakEvenDone = false
            };

            Print("SAQR DAILY OPEN {0} {1} id={2} units={3} SL={4:F1}p TP={5:F1}p score={6:F2} ADX={7:F1} RSI={8:F1}",
                s.Name, c.Side, tr.Position.Id, units, c.StopPips, tpPips, c.Score, c.Adx, c.Rsi);
        }

        private void ManagePositions()
        {
            foreach (var p in BotPositions())
            {
                ManagedTrade state;
                if (!_managed.TryGetValue(p.Id, out state))
                {
                    if (!p.StopLoss.HasValue) continue;
                    var m = _markets.FirstOrDefault(x => x.Symbol.Name == p.SymbolName);
                    if (m == null) continue;
                    double originalStopPips = Math.Abs(p.EntryPrice - p.StopLoss.Value) / m.Symbol.PipSize;
                    state = new ManagedTrade
                    {
                        PositionId = p.Id,
                        OriginalStopPips = originalStopPips,
                        BestPrice = p.TradeType == TradeType.Buy ? m.Symbol.Bid : m.Symbol.Ask
                    };
                    _managed[p.Id] = state;
                }

                var market = _markets.FirstOrDefault(x => x.Symbol.Name == p.SymbolName);
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

                if (!state.BreakEvenDone && favorableR >= BreakEvenAtR)
                {
                    double desired = p.EntryPrice +
                        (p.TradeType == TradeType.Buy ? 1.0 : -1.0) *
                        BreakEvenLockR * state.OriginalStopPips * s.PipSize;

                    if (TryImproveStop(p, s, desired, "BREAK-EVEN"))
                        state.BreakEvenDone = true;
                }

                if (favorableR >= TrailAtR)
                {
                    double atr = ClosedAtr(market.Daily, AtrPeriod, 80);
                    if (atr <= 0) continue;

                    double proposed = p.TradeType == TradeType.Buy
                        ? state.BestPrice - atr * TrailAtrMultiplier
                        : state.BestPrice + atr * TrailAtrMultiplier;

                    double floor = p.EntryPrice +
                        (p.TradeType == TradeType.Buy ? 1.0 : -1.0) *
                        BreakEvenLockR * state.OriginalStopPips * s.PipSize;

                    proposed = p.TradeType == TradeType.Buy
                        ? Math.Max(proposed, floor)
                        : Math.Min(proposed, floor);

                    TryImproveStop(p, s, proposed, "TRAIL");
                }
            }

            foreach (var id in _managed.Keys.ToArray())
                if (!Positions.Any(p => p.Id == id))
                    _managed.Remove(id);
        }

        private bool TryImproveStop(Position p, Symbol s, double desired, string reason)
        {
            double gap = Math.Max(2.0 * s.PipSize, (s.Ask - s.Bid) * 1.5);
            double safe = p.TradeType == TradeType.Buy
                ? Math.Min(desired, s.Bid - gap)
                : Math.Max(desired, s.Ask + gap);

            bool improves = !p.StopLoss.HasValue ||
                (p.TradeType == TradeType.Buy
                    ? safe > p.StopLoss.Value + 0.2 * s.PipSize
                    : safe < p.StopLoss.Value - 0.2 * s.PipSize);
            if (!improves) return false;

            var mod = p.ModifyStopLossPrice(Math.Round(safe, s.Digits));
            if (mod.IsSuccessful)
            {
                if (DiagnosticLogs)
                    Print("SAQR DAILY {0} {1} id={2} stop={3}", reason, p.SymbolName, p.Id, safe);
                return true;
            }
            return false;
        }

        private Position[] BotPositions()
        {
            var names = new HashSet<string>(_markets.Select(m => m.Symbol.Name));
            return Positions.Where(p => p.Label == BaseLabel && names.Contains(p.SymbolName)).ToArray();
        }

        private bool IsCorrelationBlocked(string name)
        {
            bool eur = name.IndexOf("EURUSD", StringComparison.OrdinalIgnoreCase) >= 0;
            bool gbp = name.IndexOf("GBPUSD", StringComparison.OrdinalIgnoreCase) >= 0;
            if (!eur && !gbp) return false;
            string other = eur ? "GBPUSD" : "EURUSD";
            return BotPositions().Any(p => p.SymbolName.IndexOf(other, StringComparison.OrdinalIgnoreCase) >= 0);
        }

        private bool DailyLossBlocked()
        {
            return DrawdownPct(_dayStartEquity) >= DailyEquityLossBlockPercent;
        }

        private bool WeeklyLossBlocked()
        {
            return DrawdownPct(_weekStartEquity) >= WeeklyEquityLossBlockPercent;
        }

        private double DrawdownPct(double anchor)
        {
            if (anchor <= 0) return 0;
            return Math.Max(0, (anchor - Account.Equity) / anchor * 100.0);
        }

        private void ResetRiskAnchorsIfNeeded()
        {
            DateTime nowDay = Server.Time.Date;
            if (nowDay != _day)
            {
                _day = nowDay;
                _dayStartEquity = Account.Equity;
            }

            DateTime ws = StartOfWeek(nowDay);
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
            double plusDi = 100.0 * plusSum / trSum;
            double minusDi = 100.0 * minusSum / trSum;
            double denom = plusDi + minusDi;
            return denom > 0 ? 100.0 * Math.Abs(plusDi - minusDi) / denom : 0;
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
            Print("SAQR DAILY cTrader error {0}", error.Code);
        }

        protected override void OnStop()
        {
            Timer.Stop();
            Print("SAQR DAILY SWING V1 stopped.");
        }
    }
}
