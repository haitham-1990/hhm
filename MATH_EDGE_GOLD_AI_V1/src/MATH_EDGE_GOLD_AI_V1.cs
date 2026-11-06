// MATH EDGE GOLD AI V1 | Demo-only adaptive XAUUSD trading bot
// Intended for cTrader Automate SDK. Build with: dotnet build -c Release -p:IncludeSource=True
// DISCLAIMER: No claim of profitability. Do not run on a live account.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using cAlgo.API;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public sealed class MathEdgeGoldAiV1 : Robot
    {
        private const string OrderLabel = "MATH_EDGE_GOLD_AI_V1";
        private const string StorageKey = "MEG1SAFESTATEV1";
        private const string ModelKey = "MEG1ADAPTIVEV1";
        private static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

        [Parameter("Enable DEMO orders", Group = "Safety", DefaultValue = false)]
        public bool EnableDemoOrders { get; set; }

        [Parameter("Reference capital USD", Group = "Safety", DefaultValue = 300, MinValue = 100)]
        public double ReferenceCapitalUsd { get; set; }

        [Parameter("Account tolerance %", Group = "Safety", DefaultValue = 15, MinValue = 0, MaxValue = 50)]
        public double AccountTolerancePercent { get; set; }

        [Parameter("Risk per trade %", Group = "Risk", DefaultValue = 1.0, MinValue = 0.10, MaxValue = 1.0, Step = 0.05)]
        public double RiskPercent { get; set; }

        [Parameter("Daily loss limit %", Group = "Risk", DefaultValue = 1.5, MinValue = 0.5, MaxValue = 5)]
        public double DailyLossPercent { get; set; }

        [Parameter("Weekly loss limit %", Group = "Risk", DefaultValue = 4.0, MinValue = 1, MaxValue = 10)]
        public double WeeklyLossPercent { get; set; }

        [Parameter("Peak drawdown limit %", Group = "Risk", DefaultValue = 8.0, MinValue = 2, MaxValue = 15)]
        public double PeakDrawdownPercent { get; set; }

        [Parameter("Max consecutive losses", Group = "Risk", DefaultValue = 3, MinValue = 1, MaxValue = 6)]
        public int MaxConsecutiveLosses { get; set; }

        [Parameter("Maximum spread in pips", Group = "Execution", DefaultValue = 45, MinValue = 1, MaxValue = 300)]
        public double MaxSpreadPips { get; set; }

        [Parameter("Max spread / ATR fraction", Group = "Execution", DefaultValue = 0.16, MinValue = 0.02, MaxValue = 0.6)]
        public double MaxSpreadAtrFraction { get; set; }

        [Parameter("Cooldown minutes", Group = "Execution", DefaultValue = 10, MinValue = 1, MaxValue = 120)]
        public int CooldownMinutes { get; set; }

        [Parameter("Stop ATR multiplier", Group = "Execution", DefaultValue = 1.4, MinValue = 0.5, MaxValue = 3.5)]
        public double StopAtrMultiplier { get; set; }

        [Parameter("Reward / risk target", Group = "Execution", DefaultValue = 1.45, MinValue = 1.0, MaxValue = 4)]
        public double RewardRisk { get; set; }

        [Parameter("Min stop in pips", Group = "Execution", DefaultValue = 50, MinValue = 5, MaxValue = 1000)]
        public double MinStopPips { get; set; }

        [Parameter("Max holding minutes", Group = "Execution", DefaultValue = 90, MinValue = 10, MaxValue = 360)]
        public int MaxHoldingMinutes { get; set; }

        [Parameter("Min signal score", Group = "Learning", DefaultValue = 0.69, MinValue = 0.55, MaxValue = 0.95)]
        public double MinSignalScore { get; set; }

        [Parameter("Learning enabled", Group = "Learning", DefaultValue = true)]
        public bool LearningEnabled { get; set; }

        [Parameter("News times UTC (CSV yyyy-MM-dd HH:mm)", Group = "News", DefaultValue = "")]
        public string NewsTimesUtc { get; set; } = "";

        [Parameter("Block minutes before news", Group = "News", DefaultValue = 25, MinValue = 0, MaxValue = 120)]
        public int MinutesBeforeNews { get; set; }

        [Parameter("Block minutes after news", Group = "News", DefaultValue = 25, MinValue = 0, MaxValue = 120)]
        public int MinutesAfterNews { get; set; }

        [Parameter("Show decision log", Group = "Diagnostics", DefaultValue = true)]
        public bool VerboseLog { get; set; }

        private Bars _m1 = null!;
        private Bars _m5 = null!;
        private Bars _m15 = null!;
        private Bars _h1 = null!;
        private Bars _h4 = null!;
        private DateTime _lastEvaluatedM5 = DateTime.MinValue;
        private DateTime _lastEntryUtc = DateTime.MinValue;
        private DateTime _day = DateTime.MinValue;
        private DateTime _week = DateTime.MinValue;
        private double _dayStartEquity;
        private double _weekStartEquity;
        private double _peakEquity;
        private int _consecutiveLosses;
        private bool _riskHalted;
        private string _riskHaltReason = "";
        private readonly AdaptiveLearner _learner = new AdaptiveLearner();
        private readonly List<DateTime> _newsTimes = new List<DateTime>();

        protected override void OnStart()
        {
            if (Account.IsLive)
            {
                Print("SAFETY: This version is DEMO-ONLY. Live account blocked.");
                Stop();
                return;
            }
            if (Account.Asset == null || !string.Equals(Account.Asset.Name, "USD", StringComparison.OrdinalIgnoreCase))
            {
                Print("SAFETY: account currency must be USD for reference-capital controls.");
                Stop();
                return;
            }
            string upper = SymbolName.ToUpperInvariant();
            if (!upper.Contains("XAUUSD") && !upper.Contains("GOLDUSD") && upper != "GOLD")
            {
                Print("SAFETY: attach this cBot to an XAUUSD / GOLD chart, not {0}.", SymbolName);
                Stop();
                return;
            }
            _m1 = MarketData.GetBars(TimeFrame.Minute);
            _m5 = MarketData.GetBars(TimeFrame.Minute5);
            _m15 = MarketData.GetBars(TimeFrame.Minute15);
            _h1 = MarketData.GetBars(TimeFrame.Hour);
            _h4 = MarketData.GetBars(TimeFrame.Hour4);
            LoadState();
            _learner.Load(LocalStorage.GetString(ModelKey));
            ParseNewsTimes();
            RollSessions();
            // Ignore the candle completed before startup; await the next actual M5 close.
            if (_m5.Count > 2) _lastEvaluatedM5 = _m5.OpenTimes[_m5.Count - 2];
            Positions.Closed += OnPositionClosed;
            Print("MATH EDGE GOLD AI V1 | DEMO | {0} | equity={1:F2} USD | reference={2:F2} | orders={3}",
                  SymbolName, Account.Equity, ReferenceCapitalUsd, EnableDemoOrders);
            Print("Learning status: {0} | {1}", LearningEnabled, _learner.Stats());
            Print("IMPORTANT: Profitability is unverified. Actual risk can exceed estimates due to slippage/gaps.");
        }

        protected override void OnTick()
        {
            if (Account.IsLive) return;
            RollSessions();
            EvaluateRisk();
            ManagePositions();

            // Evaluate exactly once per new M5 candle, using the PREVIOUS completed candle only.
            if (_m5.Count < 220 || _m1.Count < 40 || _m15.Count < 220 || _h1.Count < 220 || _h4.Count < 220)
                return;
            int closedM5 = _m5.Count - 2;
            if (closedM5 < 210) return;
            DateTime barTime = _m5.OpenTimes[closedM5];
            if (barTime <= _lastEvaluatedM5) return;
            _lastEvaluatedM5 = barTime;
            SaveState(); // Keep the equity high-water mark through restarts.
            if (!EnableDemoOrders || _riskHalted) return;
            if (IsNewsBlocked()) { Log("BLOCK: manually configured high-impact news window"); return; }
            if (!AccountMatchesReference()) { Log("BLOCK: demo equity differs from reference capital; adjust demo balance."); return; }
            // Treat ANY exposure to this gold symbol as conflicting, even from another bot.
            if (Positions.Any(p => p.SymbolName == SymbolName)) return;
            if (Server.Time - _lastEntryUtc < TimeSpan.FromMinutes(CooldownMinutes)) return;
            TryOpenPosition();
        }

        protected override void OnStop()
        {
            Positions.Closed -= OnPositionClosed;
            SaveState();
            SaveLearning();
            Print("Stopped | {0} | halted={1} | {2}", _learner.Stats(), _riskHalted, _riskHaltReason);
        }

        private void TryOpenPosition()
        {
            int i5 = _m5.Count - 2;
            int i15 = _m15.Count - 2;
            int i1 = _m1.Count - 2;
            int iH1 = _h1.Count - 2;
            int iH4 = _h4.Count - 2;
            double atrPrice = PriceMath.Atr(_m5, i5, 14);
            double adx = PriceMath.Adx(_m5, i5, 14);
            if (!IsPositive(atrPrice) || !IsPositive(adx) || !IsValidQuote()) return;
            double spreadPrice = Symbol.Ask - Symbol.Bid;
            double spreadPips = spreadPrice / Symbol.PipSize;
            if (spreadPips > MaxSpreadPips || spreadPrice / atrPrice > MaxSpreadAtrFraction)
            {
                Log("BLOCK: spread too large relative to ATR: " + spreadPips.ToString("F1", Inv) + " pips");
                return;
            }
            double recentAtr = PriceMath.Atr(_m5, i5 - 20, 14);
            if (!IsPositive(recentAtr) || atrPrice > 2.5 * recentAtr || atrPrice < 0.3 * recentAtr)
            {
                Log("BLOCK: unusually high / low volatility");
                return;
            }
            var regime = adx >= 23 ? "T" : (adx < 19 ? "R" : "N");
            double ema9 = PriceMath.Ema(_m5, i5, 9);
            double ema21 = PriceMath.Ema(_m5, i5, 21);
            double prevEma9 = PriceMath.Ema(_m5, i5 - 1, 9);
            double h1Fast = PriceMath.Ema(_h1, iH1, 50);
            double h1Slow = PriceMath.Ema(_h1, iH1, 200);
            double h4Fast = PriceMath.Ema(_h4, iH4, 50);
            double h4Slow = PriceMath.Ema(_h4, iH4, 200);
            double m15Fast = PriceMath.Ema(_m15, i15, 21);
            double m15Slow = PriceMath.Ema(_m15, i15, 50);
            double rsi1 = PriceMath.Rsi(_m1, i1, 14);
            double rsi5 = PriceMath.Rsi(_m5, i5, 14);
            if (!IsPositive(ema9) || !IsPositive(h1Slow) || !IsPositive(h4Slow) || !IsPositive(rsi1)) return;
            double close = _m5.ClosePrices[i5];
            double prevClose = _m5.ClosePrices[i5 - 1];
            double m1Close = _m1.ClosePrices[i1];
            double m1Ema = PriceMath.Ema(_m1, i1, 9);
            var candidates = new List<TradeSignal>();

            // Trend pullback: all higher timeframes must agree and M5 reclaims EMA9.
            if (regime == "T")
            {
                if (h4Fast > h4Slow && h1Fast > h1Slow && m15Fast > m15Slow && ema9 > ema21 &&
                    prevClose <= prevEma9 && close > ema9 && m1Close >= m1Ema && rsi1 > 49 && rsi1 < 75)
                    candidates.Add(new TradeSignal("T", "T", TradeType.Buy, 0.72 + Math.Min(0.07, (adx - 23) / 250.0)));
                if (h4Fast < h4Slow && h1Fast < h1Slow && m15Fast < m15Slow && ema9 < ema21 &&
                    prevClose >= prevEma9 && close < ema9 && m1Close <= m1Ema && rsi1 < 51 && rsi1 > 25)
                    candidates.Add(new TradeSignal("T", "T", TradeType.Sell, 0.72 + Math.Min(0.07, (adx - 23) / 250.0)));
            }

            // Breakout: confirmed close past the previous 12 completed M5 candles.
            if (regime == "T" || regime == "N")
            {
                double high = PriceMath.Highest(_m5, i5 - 12, i5 - 1);
                double low = PriceMath.Lowest(_m5, i5 - 12, i5 - 1);
                double body = Math.Abs(close - _m5.OpenPrices[i5]);
                if (body >= atrPrice * 0.58 && adx >= 20)
                {
                    if (close > high && ema9 > ema21 && h1Fast > h1Slow && rsi5 > 54 && rsi5 < 80)
                        candidates.Add(new TradeSignal("B", regime, TradeType.Buy, 0.70));
                    if (close < low && ema9 < ema21 && h1Fast < h1Slow && rsi5 < 46 && rsi5 > 20)
                        candidates.Add(new TradeSignal("B", regime, TradeType.Sell, 0.70));
                }
            }

            // Mean reversion: low ADX and a genuine rejection candle outside 20-bar band.
            if (regime == "R")
            {
                double avg = PriceMath.Sma(_m5, i5 - 1, 20);
                double sd = PriceMath.StdDev(_m5, i5 - 1, 20);
                if (sd > 0)
                {
                    double zPrev = (prevClose - avg) / sd;
                    bool bullish = close > _m5.OpenPrices[i5];
                    bool bearish = close < _m5.OpenPrices[i5];
                    if (zPrev < -1.9 && bullish && close > prevClose && rsi5 < 48 && rsi1 > 34)
                        candidates.Add(new TradeSignal("R", "R", TradeType.Buy, 0.70));
                    if (zPrev > 1.9 && bearish && close < prevClose && rsi5 > 52 && rsi1 < 66)
                        candidates.Add(new TradeSignal("R", "R", TradeType.Sell, 0.70));
                }
            }

            if (candidates.Count == 0) { Log("M5 closed: no qualified signal | ADX=" + adx.ToString("F1", Inv)); return; }
            TradeSignal best = candidates.OrderByDescending(s => s.BaseScore + _learner.Adjustment(s.Strategy, s.Regime)).First();
            double effectiveScore = best.BaseScore + (LearningEnabled ? _learner.Adjustment(best.Strategy, best.Regime) : 0);
            if (effectiveScore < MinSignalScore) { Log("BLOCK: score " + effectiveScore.ToString("F3", Inv)); return; }

            double stopPips = Math.Max(MinStopPips, StopAtrMultiplier * atrPrice / Symbol.PipSize);
            double takePips = stopPips * RewardRisk;
            if (!IsPositive(stopPips) || stopPips < 1 || !IsPositive(takePips)) return;
            // Hold back 20% of risk budget for commission / spread / execution uncertainty.
            double allowedRisk = Math.Min(ReferenceCapitalUsd, Account.Equity) * Math.Min(RiskPercent, 1.0) / 100.0;
            double plannedRisk = allowedRisk * 0.80;
            double rawVolume = Symbol.VolumeForFixedRisk(plannedRisk, stopPips, RoundingMode.Down);
            if (!IsPositive(rawVolume)) return;
            double volume = Symbol.NormalizeVolumeInUnits(rawVolume, RoundingMode.Down);
            if (volume < Symbol.VolumeInUnitsMin || volume > Symbol.VolumeInUnitsMax)
            {
                Log("BLOCK: minimum/maximum broker volume violates 300 USD account risk allowance.");
                return;
            }
            double estimate = Symbol.AmountRisked(volume, stopPips);
            if (!IsPositive(estimate) || estimate > plannedRisk + 0.005)
            {
                Log("BLOCK: estimated stop loss would exceed allowed planned risk.");
                return;
            }
            double margin = Symbol.GetEstimatedMargin(best.Direction, volume);
            if (!IsPositive(margin) || margin > Account.FreeMargin * 0.80)
            {
                Log("BLOCK: insufficient margin");
                return;
            }
            if (_riskHalted || Account.IsLive || !EnableDemoOrders || Positions.Any(p => p.SymbolName == SymbolName)) return;

            string comment = "MEG1|" + best.Strategy + "|" + best.Regime + "|" + stopPips.ToString("F2", Inv)
                                  + "|" + plannedRisk.ToString("F2", Inv);
            var result = ExecuteMarketOrder(best.Direction, SymbolName, volume, OrderLabel, stopPips, takePips, comment);
            if (!result.IsSuccessful || result.Position == null)
            {
                Print("ORDER REJECTED: {0}", result.Error);
                return;
            }
            _lastEntryUtc = Server.Time;
            SaveState();
            Print("DEMO ORDER: {0} {1} units; strategy={2}; score={3:F3}; SL={4:F1}p TP={5:F1}p; est. risk={6:F2} USD",
                  best.Direction, volume, best.Strategy, effectiveScore, stopPips, takePips, estimate);
        }

        private void ParseNewsTimes()
        {
            _newsTimes.Clear();
            foreach (string entry in (NewsTimesUtc ?? "").Split(new[] { ",", ";" }, StringSplitOptions.RemoveEmptyEntries))
            {
                if (DateTime.TryParseExact(entry.Trim(), "yyyy-MM-dd HH:mm", Inv, DateTimeStyles.None, out DateTime dt))
                    _newsTimes.Add(DateTime.SpecifyKind(dt, DateTimeKind.Utc));
                else Print("Invalid news timestamp UTC (expected yyyy-MM-dd HH:mm): {0}", entry.Trim());
            }
            if (_newsTimes.Count == 0) Print("NEWS FILTER: no event times supplied. Configure high-impact UTC news dates manually.");
        }

        private bool IsNewsBlocked() => _newsTimes.Any(dt => Server.Time >= dt.AddMinutes(-MinutesBeforeNews)
                                                             && Server.Time <= dt.AddMinutes(MinutesAfterNews));

        private void ManagePositions()
        {
            foreach (var position in Positions.FindAll(OrderLabel, SymbolName))
            {
                // Never create new exposure here; only manage protection on existing demo trades.
                if (position.StopLoss == null)
                {
                    Print("WARNING: Position {0} has no stop loss. Attempting emergency close.", position.Id);
                    var closed = ClosePosition(position);
                    if (!closed.IsSuccessful) Print("EMERGENCY CLOSE FAILED: {0}", closed.Error);
                    continue;
                }
                if (Server.Time - position.EntryTime >= TimeSpan.FromMinutes(MaxHoldingMinutes))
                {
                    var closed = ClosePosition(position);
                    if (!closed.IsSuccessful) Print("TIME EXIT FAILED: {0}", closed.Error);
                    continue;
                }
                string[] parts = (position.Comment ?? "").Split('|');
                if (parts.Length != 5 || !double.TryParse(parts[3], NumberStyles.Float, Inv, out double initialStopPips)
                    || initialStopPips <= 0) continue;
                double profitPips = position.Pips;
                if (profitPips < initialStopPips * 1.0) continue;
                bool buy = position.TradeType == TradeType.Buy;
                double breakEven = position.EntryPrice + (buy ? 1 : -1) * Symbol.PipSize * 2;
                double desired = breakEven;
                if (profitPips >= 1.5 * initialStopPips && _m5.Count > 40)
                {
                    double atr = PriceMath.Atr(_m5, _m5.Count - 2, 14);
                    if (IsPositive(atr))
                    {
                        double trailing = (buy ? Symbol.Bid - 1.1 * atr : Symbol.Ask + 1.1 * atr);
                        desired = buy ? Math.Max(breakEven, trailing) : Math.Min(breakEven, trailing);
                    }
                }
                desired = Math.Round(desired / Symbol.TickSize) * Symbol.TickSize;
                // Do not place an SL on wrong side of market or loosen an existing stop.
                if (buy && (desired >= Symbol.Bid - 3 * Symbol.TickSize || desired <= position.StopLoss.Value + 3 * Symbol.TickSize)) continue;
                if (!buy && (desired <= Symbol.Ask + 3 * Symbol.TickSize || desired >= position.StopLoss.Value - 3 * Symbol.TickSize)) continue;
                var modified = ModifyPosition(position, desired, position.TakeProfit, ProtectionType.Absolute);
                if (!modified.IsSuccessful) Print("SL MODIFY REJECTED: {0}", modified.Error);
            }
        }

        private void OnPositionClosed(PositionClosedEventArgs e)
        {
            Position position = e.Position;
            if (position.Label != OrderLabel || position.SymbolName != SymbolName) return;
            _consecutiveLosses = position.NetProfit < 0 ? _consecutiveLosses + 1 : 0;
            string[] p = (position.Comment ?? "").Split('|');
            if (LearningEnabled && p.Length == 5 && p[0] == "MEG1" &&
                double.TryParse(p[4], NumberStyles.Float, Inv, out double risk) && risk > 0.01)
            {
                _learner.Update(p[1], p[2], position.NetProfit / risk);
                SaveLearning();
            }
            EvaluateRisk();
            SaveState();
            Print("CLOSED: {0} | net={1:F2} USD | reason={2} | losses in row={3} | model={4}",
                  position.Id, position.NetProfit, e.Reason, _consecutiveLosses, _learner.Stats());
        }

        private bool AccountMatchesReference() =>
            Account.Equity >= ReferenceCapitalUsd * (1 - AccountTolerancePercent / 100.0) &&
            Account.Equity <= ReferenceCapitalUsd * (1 + AccountTolerancePercent / 100.0);

        private bool IsValidQuote() => Symbol.Ask > Symbol.Bid && Symbol.Bid > 0 && Symbol.PipSize > 0 && Symbol.TickSize > 0;
        private static bool IsPositive(double v) => !double.IsNaN(v) && !double.IsInfinity(v) && v > 0;
        private void Log(string message) { if (VerboseLog) Print(message); }

        private void EvaluateRisk()
        {
            // Equity high-water mark is never reset by day/week rollovers.
            _peakEquity = Math.Max(_peakEquity, Account.Equity);
            if (_riskHalted) return; // Hard-lock persists until explicit reset/manual new safe instance.
            if (_consecutiveLosses >= MaxConsecutiveLosses)
                Halt("CONSECUTIVE_LOSSES");
            else if (Account.Equity <= _dayStartEquity * (1 - DailyLossPercent / 100.0))
                Halt("DAILY_LIMIT");
            else if (Account.Equity <= _weekStartEquity * (1 - WeeklyLossPercent / 100.0))
                Halt("WEEKLY_LIMIT");
            else if (Account.Equity <= _peakEquity * (1 - PeakDrawdownPercent / 100.0))
                Halt("MAX_DRAWDOWN");
        }

        private void Halt(string reason)
        {
            _riskHalted = true;
            _riskHaltReason = reason;
            Print("RISK LOCKED: {0}. New entries blocked until safety state is manually reviewed.", reason);
            SaveState();
        }

        private static DateTime WeekStart(DateTime d) => d.Date.AddDays(-((int)d.DayOfWeek + 6) % 7);
        private void RollSessions()
        {
            var today = Server.Time.Date;
            var week = WeekStart(today);
            if (_day != today)
            {
                _day = today;
                _dayStartEquity = Account.Equity;
                // Loss-streak halt intentionally does not reset automatically.
                SaveState();
            }
            if (_week != week)
            {
                _week = week;
                _weekStartEquity = Account.Equity;
                SaveState();
            }
        }

        private void LoadState()
        {
            _day = Server.Time.Date;
            _week = WeekStart(_day);
            _dayStartEquity = _weekStartEquity = _peakEquity = Account.Equity;
            string raw = LocalStorage.GetString(StorageKey);
            if (string.IsNullOrWhiteSpace(raw)) return;
            try
            {
                var f = raw.Split('|');
                if (f.Length != 10 || f[0] != "V2") return;
                if (!DateTime.TryParseExact(f[1], "yyyyMMdd", Inv, DateTimeStyles.None, out DateTime savedDay)) return;
                if (!DateTime.TryParseExact(f[3], "yyyyMMdd", Inv, DateTimeStyles.None, out DateTime savedWeek)) return;
                if (!double.TryParse(f[2], NumberStyles.Float, Inv, out double savedDayEquity)) return;
                if (!double.TryParse(f[4], NumberStyles.Float, Inv, out double savedWeekEquity)) return;
                if (!double.TryParse(f[5], NumberStyles.Float, Inv, out double savedPeak)) return;
                if (!int.TryParse(f[6], out int savedLosses)) return;
                if (!bool.TryParse(f[7], out bool savedLocked)) return;
                if (savedDay == _day && IsPositive(savedDayEquity)) _dayStartEquity = savedDayEquity;
                if (savedWeek == _week && IsPositive(savedWeekEquity)) _weekStartEquity = savedWeekEquity;
                if (IsPositive(savedPeak)) _peakEquity = Math.Max(Account.Equity, savedPeak);
                _consecutiveLosses = Math.Max(0, savedLosses);
                _riskHalted = savedLocked;
                _riskHaltReason = f[8];
                if (long.TryParse(f[9], out long ticks) && ticks > 0)
                    _lastEntryUtc = new DateTime(ticks, DateTimeKind.Utc);
            }
            catch (Exception ex) { Print("Unable to load risk state: {0}", ex.Message); }
        }

        private void SaveState()
        {
            string state = "V2|" + _day.ToString("yyyyMMdd", Inv) + "|" + _dayStartEquity.ToString("R", Inv) + "|"
                         + _week.ToString("yyyyMMdd", Inv) + "|" + _weekStartEquity.ToString("R", Inv) + "|"
                         + _peakEquity.ToString("R", Inv) + "|" + _consecutiveLosses + "|"
                         + _riskHalted + "|" + _riskHaltReason + "|" + _lastEntryUtc.Ticks;
            LocalStorage.SetString(StorageKey, state);
            LocalStorage.Flush(LocalStorageScope.Instance);
        }

        private void SaveLearning()
        {
            LocalStorage.SetString(ModelKey, _learner.Save());
            LocalStorage.Flush(LocalStorageScope.Instance);
        }

        private sealed class TradeSignal
        {
            public readonly string Strategy;
            public readonly string Regime;
            public readonly TradeType Direction;
            public readonly double BaseScore;
            public TradeSignal(string strategy, string regime, TradeType direction, double baseScore)
            { Strategy = strategy; Regime = regime; Direction = direction; BaseScore = baseScore; }
        }
    }

    // A small, bounded contextual reward learner. NOT a deep-learning model, not a profit guarantee.
    internal sealed class AdaptiveLearner
    {
        private sealed class Bin
        {
            public int Count;
            public double RewardSum;
            public double Ewma;
        }
        private readonly Dictionary<string, Bin> _bins = new Dictionary<string, Bin>();
        private static string Key(string strategy, string regime) => strategy + ":" + regime;

        public double Adjustment(string strategy, string regime)
        {
            if (!_bins.TryGetValue(Key(strategy, regime), out Bin? b) || b.Count < 12) return 0;
            // Shrinkage toward zero protects against small samples; bounded +/- 0.10 score.
            double mean = b.RewardSum / b.Count;
            double shrink = b.Count / (b.Count + 40.0);
            double bounded = Math.Max(-0.10, Math.Min(0.10, 0.075 * mean * shrink));
            // Very recent performance deterioration reduces weight; cannot increase trade risk.
            if (b.Count >= 20 && b.Ewma < mean - 0.75) bounded = Math.Min(bounded, -0.03);
            return bounded;
        }

        public void Update(string strategy, string regime, double riskAdjustedNetPnl)
        {
            if ((strategy != "T" && strategy != "B" && strategy != "R") ||
                (regime != "T" && regime != "N" && regime != "R") || double.IsNaN(riskAdjustedNetPnl) ||
                double.IsInfinity(riskAdjustedNetPnl)) return;
            var key = Key(strategy, regime);
            if (!_bins.TryGetValue(key, out Bin? b)) _bins[key] = b = new Bin();
            double reward = Math.Max(-2, Math.Min(2, riskAdjustedNetPnl));
            b.Ewma = b.Count == 0 ? reward : b.Ewma * 0.85 + reward * 0.15;
            b.Count++;
            b.RewardSum += reward;
        }

        public string Stats() => string.Join(", ", _bins.OrderBy(kv => kv.Key).Select(kv => kv.Key + "=" + kv.Value.Count));

        public string Save()
        {
            return "V1;" + string.Join(";", _bins.OrderBy(kv => kv.Key).Select(kv =>
                kv.Key + "," + kv.Value.Count + "," + kv.Value.RewardSum.ToString("R", CultureInfo.InvariantCulture)
                + "," + kv.Value.Ewma.ToString("R", CultureInfo.InvariantCulture)));
        }

        public void Load(string? text)
        {
            _bins.Clear();
            if (string.IsNullOrWhiteSpace(text) || !text.StartsWith("V1;", StringComparison.Ordinal)) return;
            foreach (var entry in text.Split(';').Skip(1))
            {
                var f = entry.Split(',');
                if (f.Length != 4 || f[0].Length > 8) continue;
                if (int.TryParse(f[1], out int n) && n > 0 && n < 10000000 &&
                    double.TryParse(f[2], NumberStyles.Float, CultureInfo.InvariantCulture, out double sum) &&
                    double.TryParse(f[3], NumberStyles.Float, CultureInfo.InvariantCulture, out double ewma) &&
                    !double.IsNaN(sum) && !double.IsInfinity(sum) && !double.IsNaN(ewma) && !double.IsInfinity(ewma))
                    _bins[f[0]] = new Bin { Count = n, RewardSum = sum, Ewma = ewma };
            }
        }
    }

    // Indicators are calculated exclusively from CLOSED bars (indices <= Bars.Count - 2).
    internal static class PriceMath
    {
        public static double Sma(Bars bars, int end, int length)
        {
            if (end < length - 1) return double.NaN;
            double sum = 0;
            for (int i = end - length + 1; i <= end; i++) sum += bars.ClosePrices[i];
            return sum / length;
        }
        public static double Ema(Bars bars, int end, int length)
        {
            if (end < length + 2) return double.NaN;
            int start = Math.Max(length - 1, end - 450);
            double ema = Sma(bars, start, length);
            double alpha = 2.0 / (length + 1);
            for (int i = start + 1; i <= end; i++) ema += alpha * (bars.ClosePrices[i] - ema);
            return ema;
        }
        public static double StdDev(Bars bars, int end, int length)
        {
            double mean = Sma(bars, end, length);
            if (double.IsNaN(mean)) return double.NaN;
            double sum = 0;
            for (int i = end - length + 1; i <= end; i++)
            {
                double d = bars.ClosePrices[i] - mean;
                sum += d * d;
            }
            return Math.Sqrt(sum / length);
        }
        public static double Highest(Bars bars, int start, int end)
        {
            if (start < 0 || start > end) return double.NaN;
            double result = double.MinValue;
            for (int i = start; i <= end; i++) result = Math.Max(result, bars.HighPrices[i]);
            return result;
        }
        public static double Lowest(Bars bars, int start, int end)
        {
            if (start < 0 || start > end) return double.NaN;
            double result = double.MaxValue;
            for (int i = start; i <= end; i++) result = Math.Min(result, bars.LowPrices[i]);
            return result;
        }
        private static double TrueRange(Bars b, int i) =>
            Math.Max(b.HighPrices[i] - b.LowPrices[i],
                Math.Max(Math.Abs(b.HighPrices[i] - b.ClosePrices[i - 1]), Math.Abs(b.LowPrices[i] - b.ClosePrices[i - 1])));

        public static double Atr(Bars bars, int end, int length)
        {
            if (end < length + 1) return double.NaN;
            // Wilder-smoothed ATR initialized from an older fixed-length historical window.
            int start = Math.Max(1, end - 120);
            double atr = 0;
            for (int i = start; i < start + length; i++) atr += TrueRange(bars, i);
            atr /= length;
            for (int i = start + length; i <= end; i++) atr = ((length - 1) * atr + TrueRange(bars, i)) / length;
            return atr;
        }

        public static double Rsi(Bars b, int end, int length)
        {
            if (end < length + 2) return double.NaN;
            int start = Math.Max(1, end - 130);
            double up = 0, down = 0;
            for (int i = start; i < start + length; i++)
            {
                double d = b.ClosePrices[i] - b.ClosePrices[i - 1];
                up += Math.Max(0, d); down += Math.Max(0, -d);
            }
            up /= length; down /= length;
            for (int i = start + length; i <= end; i++)
            {
                double d = b.ClosePrices[i] - b.ClosePrices[i - 1];
                up = ((length - 1) * up + Math.Max(0, d)) / length;
                down = ((length - 1) * down + Math.Max(0, -d)) / length;
            }
            if (down == 0) return up == 0 ? 50 : 100;
            return 100 - 100 / (1 + up / down);
        }

        public static double Adx(Bars b, int end, int length)
        {
            if (end < length * 3 + 3) return double.NaN;
            int start = Math.Max(1, end - 140);
            double tr = 0, pdm = 0, mdm = 0;
            for (int i = start; i < start + length; i++)
            {
                AddDirectional(b, i, out double t, out double p, out double m);
                tr += t; pdm += p; mdm += m;
            }
            double adx = 0;
            int dxCount = 0;
            for (int i = start + length; i <= end; i++)
            {
                AddDirectional(b, i, out double t, out double p, out double m);
                tr = tr - tr / length + t;
                pdm = pdm - pdm / length + p;
                mdm = mdm - mdm / length + m;
                double plus = tr > 0 ? 100 * pdm / tr : 0;
                double minus = tr > 0 ? 100 * mdm / tr : 0;
                double dx = plus + minus > 0 ? 100 * Math.Abs(plus - minus) / (plus + minus) : 0;
                if (dxCount < length) adx = (adx * dxCount + dx) / ++dxCount;
                else adx = ((length - 1) * adx + dx) / length;
            }
            return adx;
        }
        private static void AddDirectional(Bars b, int i, out double tr, out double plus, out double minus)
        {
            tr = TrueRange(b, i);
            double up = b.HighPrices[i] - b.HighPrices[i - 1];
            double down = b.LowPrices[i - 1] - b.LowPrices[i];
            plus = up > down && up > 0 ? up : 0;
            minus = down > up && down > 0 ? down : 0;
        }
    }
}