// MATH EDGE AI INTERNAL v2.1
// Cloud-compatible offline neural-network cBot. No HTTP / API calls.
// Research prototype: train on CLOSED candles, purged chronological validation, strict risk brakes.
// Not a guarantee of profits; run DEMO and backtest before enabling orders.
using System;
using System.Collections.Generic;
using System.Linq;
using cAlgo.API;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class MathEdgeAIInternalV21 : Robot
    {
        [Parameter("Enable Orders (DEMO first)", DefaultValue = false)]
        public bool EnableOrders { get; set; }

        [Parameter("Allow Live Account (DANGER)", DefaultValue = false)]
        public bool AllowLive { get; set; }

        [Parameter("Risk % Per Trade", DefaultValue = 0.5, MinValue = 0.1, MaxValue = 0.5)]
        public double RiskPct { get; set; }

        [Parameter("Daily Equity Loss Limit %", DefaultValue = 2.0, MinValue = 0.5, MaxValue = 2.0)]
        public double DailyLossPct { get; set; }

        [Parameter("Instance Drawdown Limit %", DefaultValue = 5.0, MinValue = 1.0, MaxValue = 5.0)]
        public double DrawdownPct { get; set; }

        [Parameter("Max Entries per UTC Day", DefaultValue = 3, MinValue = 1, MaxValue = 3)]
        public int MaxEntries { get; set; }

        [Parameter("Minimum Model Probability", DefaultValue = 0.55, MinValue = 0.50, MaxValue = 0.9)]
        public double MinProbability { get; set; }

        [Parameter("Training Bars", DefaultValue = 780, MinValue = 450, MaxValue = 1200)]
        public int Lookback { get; set; }

        [Parameter("Retrain Every N Bars", DefaultValue = 24, MinValue = 6, MaxValue = 96)]
        public int RetrainBars { get; set; }

        [Parameter("Assumed Roundtrip Cost Pips", DefaultValue = 25, MinValue = 5, MaxValue = 150)]
        public double AssumedCostPips { get; set; }

        [Parameter("SL ATR Multiple", DefaultValue = 2.0, MinValue = 1.3, MaxValue = 4.0)]
        public double SlAtrMultiple { get; set; }

        [Parameter("Take Profit / Stop Loss", DefaultValue = 1.8, MinValue = 1.2, MaxValue = 3.0)]
        public double RewardRisk { get; set; }

        [Parameter("Maximum Spread / ATR", DefaultValue = 0.12, MinValue = 0.02, MaxValue = 0.2)]
        public double MaxSpreadAtr { get; set; }

        [Parameter("Cooldown Minutes", DefaultValue = 60, MinValue = 15, MaxValue = 360)]
        public int CooldownMinutes { get; set; }

        [Parameter("UTC Start Hour", DefaultValue = 6, MinValue = 0, MaxValue = 23)]
        public int UtcStart { get; set; }

        [Parameter("UTC End Hour", DefaultValue = 20, MinValue = 1, MaxValue = 24)]
        public int UtcEnd { get; set; }

        private const string Label = "MATH_EDGE_AI_INT_V21";
        private const int Features = 10;
        private const int Hidden = 12;
        private const int Horizon = 3;
        private NeuralNet _model;
        private bool _qualified;
        private bool _hardLocked;
        private DateTime _dailyDate;
        private bool _dailyLocked;
        private double _instancePeak;
        private int _lastModelBar = -1;
        private int _consecutiveFailures;
        private DateTime _lastProcessedBarOpen = DateTime.MinValue;
        private int _skippedStaleBars;

        protected override void OnStart()
        {
            if (!SymbolName.StartsWith("XAUUSD", StringComparison.OrdinalIgnoreCase))
            {
                Print("STOP: attach only to gold/XAUUSD."); Stop(); return;
            }
            if (TimeFrame != TimeFrame.Minute15)
            {
                Print("STOP: use M15 chart."); Stop(); return;
            }
            if (UtcEnd <= UtcStart)
            {
                Print("STOP: invalid UTC hours."); Stop(); return;
            }
            if (Account.IsLive && !AllowLive)
            {
                Print("STOP: LIVE ACCOUNT BLOCKED. Select DEMO."); Stop(); return;
            }
            _instancePeak = Account.Equity;
            _dailyDate = Server.Time.Date;
            Positions.Closed += PositionClosed;
            Timer.Start(60);
            // Data is obtained ONLY from cTrader's own historical-bar API.
            try
            {
                for (int attempt = 0; attempt < 8 && Bars.Count < Lookback + 70; attempt++)
                {
                    int count = Bars.LoadMoreHistory();
                    if (count <= 0) break;
                }
            }
            catch (Exception e)
            {
                Print("History request issue: {0}. Continue with available candles.", e.Message);
            }
            Print("MATH EDGE AI INTERNAL v2.1: Demo={0}, Orders={1}, CloudCompatible=Yes, ExternalAPI=NONE, Bars={2}",
                !Account.IsLive, EnableOrders, Bars.Count);
            Print("Internal ML: 10-feature/12-hidden/3-class neural network, chronological purged holdout; uncalibrated model scores.");
            Print("Important: Instance drawdown peak resets on cloud restart. Daily floor rebuilt from account history when possible.");
            CheckRisk();
        }

        protected override void OnTimer() { CheckRisk(); }

        protected override void OnBarClosed()
        {
            CheckRisk();
            if (_hardLocked || _dailyLocked || Account.Equity <= 0 || Account.IsLive && !AllowLive) return;
            if (Bars.Count < 520)
            {
                if (Bars.Count % 16 == 0)
                    Print("WAIT: insufficient closed bars ({0}); need at least 520 for neural training.", Bars.Count);
                return;
            }

            int i = Bars.Count - 1; // OnBarClosed excludes the currently forming bar.
            DateTime barOpen = Bars.OpenTimes[i];
            if (barOpen == _lastProcessedBarOpen) return; // Suppress duplicate callbacks.
            _lastProcessedBarOpen = barOpen;
            // A cloud restart may replay dozens of historical OnBarClosed callbacks in seconds.
            // Never issue an order based on an old bar; retrain only against near-real-time bars.
            if ((Server.Time - barOpen).TotalMinutes > 35)
            {
                _skippedStaleBars++;
                if (_skippedStaleBars == 1)
                    Print("SKIP: historical bar replay; waiting for a fresh M15 closed bar.");
                return;
            }
            if (_model == null || i - _lastModelBar >= RetrainBars)
            {
                try { FitAndEvaluate(i); }
                catch (Exception e)
                {
                    _model = null; _qualified = false;
                    Print("MODEL ERROR => WAIT: {0}", e.Message);
                }
                _lastModelBar = i;
            }
            if (!_qualified || _model == null)
            {
                if (i % 4 == 0)
                    Print("AI WAIT: validation gate not passed. See MODEL / DIAGNOSTIC results.");
                return;
            }

            if (Server.Time.Hour < UtcStart || Server.Time.Hour >= UtcEnd) return;
            if ((Server.Time - Bars.OpenTimes[i]).TotalMinutes > 31) return;
            if (Positions.Count != 0) return; // Fail closed if any manual/bot position is open.
            var todays = BotTradesToday();
            if (todays.entryCount >= MaxEntries || todays.lossStreak >= 3) return;
            if (todays.lastEntry != DateTime.MinValue && (Server.Time - todays.lastEntry).TotalMinutes < CooldownMinutes) return;

            double atr;
            double[] x = GetFeatures(i, out atr);
            if (x == null || !Valid(atr) || atr <= 0) return;
            double spread = Symbol.Ask - Symbol.Bid;
            if (!Valid(spread) || spread < 0 || spread / atr > MaxSpreadAtr)
            {
                Print("WAIT: spread / ATR too high."); return;
            }
            double[] p = _model.Predict(x);
            int direction = Choose(p, MinProbability, x[4]);
            Print("ML SIGNAL: BUY={0:F3}, WAIT={1:F3}, SELL={2:F3}, selection={3} (scores are NOT calibrated)",
                p[0], p[1], p[2], direction == 1 ? "BUY" : direction == -1 ? "SELL" : "WAIT");
            if (direction == 0) return;
            if (CheckRisk()) return;

            double stopPips = Math.Max(SlAtrMultiple * atr / Symbol.PipSize, 2.0 * spread / Symbol.PipSize);
            double tpPips = stopPips * RewardRisk;
            if (!Valid(stopPips) || !Valid(tpPips) || stopPips <= 0 || Symbol.PipSize <= 0) return;

            var side = direction == 1 ? TradeType.Buy : TradeType.Sell;
            double volume = Symbol.VolumeForProportionalRisk(ProportionalAmountType.Equity, RiskPct, stopPips, RoundingMode.Down);
            if (!Valid(volume) || volume <= 0) return;
            volume = Symbol.NormalizeVolumeInUnits(Math.Min(volume, Symbol.VolumeInUnitsMax), RoundingMode.Down);
            if (volume < Symbol.VolumeInUnitsMin) return;
            double margin = Symbol.GetEstimatedMargin(side, volume);
            if (!Valid(margin) || margin <= 0 || margin > Account.FreeMargin * 0.20) return;
            // This caps requested risk before order submission; slippage can still exceed the plan.
            Print("PLAN: {0}, units={1}, SL={2:F1} pips, TP={3:F1} pips, risk target<={4:F2}%",
                side, volume, stopPips, tpPips, RiskPct);
            if (!EnableOrders)
            {
                Print("SIGNAL-ONLY: Enable Orders is FALSE; NO TRADE SENT.");
                return;
            }
            if (Account.IsLive && !AllowLive) return;
            if (Positions.Count != 0) return;
            var result = ExecuteMarketOrder(side, SymbolName, volume, Label, stopPips, tpPips);
            if (!result.IsSuccessful)
            {
                _consecutiveFailures++;
                Print("ORDER ERROR (no retry): {0}", result.Error);
                if (_consecutiveFailures >= 3) { _hardLocked = true; Print("LOCKED after 3 order errors."); }
                return;
            }
            _consecutiveFailures = 0;
            Print("DEMO ORDER OPENED: position={0}", result.Position == null ? 0 : result.Position.Id);
        }

        private void PositionClosed(PositionClosedEventArgs e)
        {
            if (e.Position.Label != Label) return;
            Print("BOT POSITION CLOSED: net={0:F2}", e.Position.NetProfit);
            CheckRisk();
        }

        // Reconstruct start-of-day balance from ALL closed account deals to resist
        // simple bot restarts. Account deposits/withdrawals and incomplete history can affect it.
        // If history access fails, NO new trades are allowed.
        private bool CheckRisk()
        {
            if (Server.Time.Date != _dailyDate)
            {
                _dailyDate = Server.Time.Date;
                _dailyLocked = false;
            }
            if (_hardLocked) return true;
            try
            {
                if (!Valid(Account.Equity) || Account.Equity <= 0) { _hardLocked = true; return true; }
                _instancePeak = Math.Max(_instancePeak, Account.Equity);
                double closedToday = 0;
                foreach (var h in History)
                    if (h.ClosingTime.Date == Server.Time.Date) closedToday += h.NetProfit;
                double dayStartBalance = Account.Balance - closedToday;
                if (!Valid(dayStartBalance) || dayStartBalance <= 0) { _hardLocked = true; return true; }
                if (Account.Equity <= dayStartBalance * (1.0 - DailyLossPct / 100.0))
                {
                    if (!_dailyLocked) Print("DAILY LOSS LOCK: equity below reconstructed UTC daily floor.");
                    _dailyLocked = true;
                }
                if (Account.Equity <= _instancePeak * (1.0 - DrawdownPct / 100.0))
                {
                    if (!_hardLocked) Print("HARD LOCK: instance equity drawdown exceeded.");
                    _hardLocked = true;
                }
                if ((_dailyLocked || _hardLocked) && EnableOrders)
                {
                    foreach (var pos in Positions.Where(p => p.Label == Label).ToArray())
                    {
                        var res = ClosePosition(pos);
                        if (!res.IsSuccessful) Print("CLOSE FAILED: {0}", res.Error);
                    }
                }
                return _dailyLocked || _hardLocked;
            }
            catch (Exception e)
            {
                _hardLocked = true;
                Print("RISK DATA FAILURE -> HARD LOCK: {0}", e.Message);
                return true;
            }
        }

        private (int entryCount, int lossStreak, DateTime lastEntry) BotTradesToday()
        {
            var done = History.FindAll(Label, SymbolName)
                .Where(x => x.EntryTime.Date == Server.Time.Date)
                .GroupBy(x => x.PositionId)
                .Select(g => new {
                    Id = g.Key,
                    Entry = g.Min(t => t.EntryTime),
                    Closing = g.Max(t => t.ClosingTime),
                    Net = g.Sum(t => t.NetProfit)
                }).ToArray();
            var open = Positions.Where(p => p.Label == Label && p.EntryTime.Date == Server.Time.Date).ToArray();
            int total = done.Length + open.Count(p => !done.Any(x => x.Id == p.Id));
            DateTime latest = done.Select(x => x.Entry)
                .Concat(open.Select(p => p.EntryTime))
                .DefaultIfEmpty(DateTime.MinValue).Max();
            int streak = 0;
            foreach (var x in done.OrderByDescending(x => x.Closing))
            {
                if (x.Net < 0) streak++;
                else break;
            }
            return (total, streak, latest);
        }

        private void FitAndEvaluate(int last)
        {
            _qualified = false;
            _model = null;
            int sampleCount = Math.Min(Lookback, last - 65 - Horizon);
            if (sampleCount < 430) { Print("WAIT: not enough learning samples."); return; }
            int first = last - Horizon - sampleCount + 1;
            var samples = new List<Sample>(sampleCount);
            double fixedCostPrice = Math.Max(0, AssumedCostPips * Symbol.PipSize);
            for (int k = 0; k < sampleCount; k++)
            {
                int t = first + k;
                double atr;
                var x = GetFeatures(t, out atr);
                if (x == null) continue;
                double diff = Bars.ClosePrices[t + Horizon] - Bars.ClosePrices[t];
                double costPrice = Math.Max(fixedCostPrice, atr * 0.15);
                double threshold = 0.38 * atr + costPrice;
                int label = diff > threshold ? 0 : diff < -threshold ? 2 : 1;
                samples.Add(new Sample {
                    X = x, Y = label, Atr = atr, Delta = diff, Cost = costPrice / atr
                });
            }
            int n = samples.Count;
            int validationSize = Math.Max(90, n / 5);
            int trainingEnd = n - validationSize - Horizon; // purge boundary samples
            if (trainingEnd < 250) { Print("WAIT: insufficient train/validation split."); return; }

            var net = new NeuralNet(Features, Hidden, 3, 2419);
            int[] counts = new int[3];
            for (int k = 0; k < trainingEnd; k++) counts[samples[k].Y]++;
            double[] weights = new double[3];
            for (int c = 0; c < 3; c++)
                weights[c] = Math.Min(2.5, Math.Sqrt((double)trainingEnd / (3.0 * Math.Max(1, counts[c]))));
            var rng = new Random(72491);
            int[] order = Enumerable.Range(0, trainingEnd).ToArray();
            for (int epoch = 0; epoch < 28; epoch++)
            {
                for (int k = order.Length - 1; k > 0; k--)
                {
                    int j = rng.Next(k + 1);
                    int swap = order[k]; order[k] = order[j]; order[j] = swap;
                }
                double lr = 0.018 / (1.0 + epoch * 0.025);
                for (int k = 0; k < trainingEnd; k++)
                {
                    var s = samples[order[k]];
                    net.Train(s.X, s.Y, weights[s.Y], lr);
                }
            }
            int trades = 0, wins = 0;
            double grossProfit = 0, grossLoss = 0, netSum = 0;
            int lastPicked = -Horizon;
            int[] holdoutCounts = new int[3];
            int[] diagnosticCandidateSignals = new int[4];
            double meanBuy = 0, meanWait = 0, meanSell = 0;
            double[] testThresholds = { 0.50, 0.55, 0.60, 0.65 };
            for (int k = n - validationSize; k < n; k++)
            {
                Sample s = samples[k];
                holdoutCounts[s.Y]++;
                double[] prediction = net.Predict(s.X);
                meanBuy += prediction[0];
                meanWait += prediction[1];
                meanSell += prediction[2];
                for (int v = 0; v < testThresholds.Length; v++)
                    if (Choose(prediction, testThresholds[v], s.X[4]) != 0)
                        diagnosticCandidateSignals[v]++;

                if (k - lastPicked < Horizon) continue; // avoid overlapping 3-bar outcomes
                int direction = Choose(prediction, MinProbability, s.X[4]);
                if (direction == 0) continue;
                double result = direction * s.Delta / s.Atr - s.Cost;
                netSum += result;
                if (result > 0) { wins++; grossProfit += result; }
                else grossLoss -= result;
                trades++;
                lastPicked = k;
            }
            Print("DIAGNOSTIC: train labels BUY={0}, WAIT={1}, SELL={2}; validation labels BUY={3}, WAIT={4}, SELL={5}.",
                counts[0], counts[1], counts[2], holdoutCounts[0], holdoutCounts[1], holdoutCounts[2]);
            Print("DIAGNOSTIC: mean class scores BUY={0:F3}, WAIT={1:F3}, SELL={2:F3}; candidate signal counts at .50/.55/.60/.65={3}/{4}/{5}/{6}.",
                meanBuy / validationSize, meanWait / validationSize, meanSell / validationSize,
                diagnosticCandidateSignals[0], diagnosticCandidateSignals[1],
                diagnosticCandidateSignals[2], diagnosticCandidateSignals[3]);
            double hit = trades > 0 ? (double)wins / trades : 0;
            double pf = grossLoss > 0 ? grossProfit / grossLoss : 0;
            double avg = trades > 0 ? netSum / trades : 0;
            _qualified = trades >= 8 && hit >= 0.50 && pf >= 1.15 && avg > 0.06;
            _model = net;
            if (!_qualified)
                Print("VALIDATION BLOCKED: {0}. No trades permitted until chronological holdout passes all checks.",
                    trades < 8 ? "FEWER_THAN_8_SIGNALS" : hit < 0.50 ? "LOW_HIT_RATE" :
                    pf < 1.15 ? "LOW_PROFIT_FACTOR" : "LOW_MEAN_RETURN");
            Print("MODEL: train={0}, holdout={1}, trades={2}, win={3:P1}, PF={4:F2}, meanNetATR={5:F3}, VALIDATED={6}.",
                trainingEnd, validationSize, trades, hit, pf, avg, _qualified);
            Print("Holdout is an internal screening test, NOT broker-grade backtesting, and does not prove future profitability.");
        }

        // Scores refer to a model's classes, not calibrated odds of profits.
        private static int Choose(double[] p, double threshold, double trend)
        {
            if (p[0] >= threshold && p[0] > p[1] + 0.06 && p[0] > p[2] + 0.12 && trend > 0.06) return 1;
            if (p[2] >= threshold && p[2] > p[1] + 0.06 && p[2] > p[0] + 0.12 && trend < -0.06) return -1;
            return 0;
        }

        private double[] GetFeatures(int i, out double atr)
        {
            atr = 0;
            if (i < 62 || i >= Bars.Count) return null;
            atr = Atr(i, 14);
            if (!Valid(atr) || atr <= Symbol.PipSize) return null;
            double c = Bars.ClosePrices[i], prev = Bars.ClosePrices[i - 1];
            double hi = Bars.HighPrices[i], lo = Bars.LowPrices[i];
            double fast = Sma(i, 8), slow = Sma(i, 34);
            double vol10 = 0, vol40 = 0;
            for (int j = 0; j < 10; j++) vol10 += Bars.TickVolumes[i - j];
            for (int j = 0; j < 40; j++) vol40 += Bars.TickVolumes[i - j];
            double range = Math.Max(hi - lo, Symbol.PipSize);
            double priorAtr = Atr(i - 14, 14);
            double rsi = Rsi(i, 14);
            return new[] {
                Clip((c - prev) / atr),
                Clip((c - Bars.ClosePrices[i - 3]) / atr),
                Clip((c - Bars.ClosePrices[i - 8]) / atr),
                Clip((c - Bars.ClosePrices[i - 20]) / atr),
                Clip((fast - slow) / atr),
                Clip((c - Bars.OpenPrices[i]) / atr),
                Clip(2.0 * (c - lo) / range - 1.0),
                Clip(vol40 > 0 ? (vol10 / 10.0) / (vol40 / 40.0) - 1 : 0),
                Clip(priorAtr > 0 ? atr / priorAtr - 1.0 : 0),
                Clip((rsi - 50) / 25.0)
            };
        }

        private double Sma(int index, int period)
        {
            double sum = 0;
            for (int i = index - period + 1; i <= index; i++) sum += Bars.ClosePrices[i];
            return sum / period;
        }
        private double Atr(int index, int period)
        {
            if (index < period) return 0;
            double sum = 0;
            for (int i = index - period + 1; i <= index; i++)
            {
                double hi = Bars.HighPrices[i], lo = Bars.LowPrices[i], pc = Bars.ClosePrices[i - 1];
                sum += Math.Max(hi - lo, Math.Max(Math.Abs(hi - pc), Math.Abs(lo - pc)));
            }
            return sum / period;
        }
        private double Rsi(int index, int period)
        {
            double up = 0, down = 0;
            for (int i = index - period + 1; i <= index; i++)
            {
                double d = Bars.ClosePrices[i] - Bars.ClosePrices[i - 1];
                if (d >= 0) up += d; else down -= d;
            }
            return 100.0 * up / Math.Max(1e-10, up + down);
        }
        private static double Clip(double x) { return Math.Max(-3, Math.Min(3, Valid(x) ? x : 0)); }
        private static bool Valid(double x) { return !double.IsNaN(x) && !double.IsInfinity(x); }

        private sealed class Sample
        {
            public double[] X;
            public int Y;
            public double Atr, Delta, Cost;
        }

        // Compact nonlinear neural network trained from scratch within the cBot.
        // Explicit forward/backprop avoids dependencies on external inference engines.
        private sealed class NeuralNet
        {
            private readonly int _d, _h, _c;
            private readonly double[,] _w1, _w2;
            private readonly double[] _b1, _b2;
            public NeuralNet(int d, int hidden, int classes, int seed)
            {
                _d = d; _h = hidden; _c = classes;
                _w1 = new double[hidden, d]; _w2 = new double[classes, hidden];
                _b1 = new double[hidden]; _b2 = new double[classes];
                var rng = new Random(seed);
                for (int j = 0; j < hidden; j++)
                    for (int k = 0; k < d; k++) _w1[j, k] = (rng.NextDouble() - .5) * .24;
                for (int c = 0; c < classes; c++)
                    for (int j = 0; j < hidden; j++) _w2[c, j] = (rng.NextDouble() - .5) * .24;
            }
            private double[] Forward(double[] x, out double[] hidden)
            {
                hidden = new double[_h];
                for (int j = 0; j < _h; j++)
                {
                    double sum = _b1[j];
                    for (int k = 0; k < _d; k++) sum += _w1[j, k] * x[k];
                    hidden[j] = Math.Tanh(sum);
                }
                var logits = new double[_c];
                double max = double.NegativeInfinity;
                for (int c = 0; c < _c; c++)
                {
                    double z = _b2[c];
                    for (int j = 0; j < _h; j++) z += _w2[c, j] * hidden[j];
                    logits[c] = z;
                    max = Math.Max(max, z);
                }
                double den = 0;
                for (int c = 0; c < _c; c++) { logits[c] = Math.Exp(logits[c] - max); den += logits[c]; }
                for (int c = 0; c < _c; c++) logits[c] /= den;
                return logits;
            }
            public double[] Predict(double[] x) { double[] h; return Forward(x, out h); }
            public void Train(double[] x, int target, double classWeight, double rate)
            {
                double[] hidden;
                double[] p = Forward(x, out hidden);
                var e = new double[_c];
                for (int c = 0; c < _c; c++) e[c] = classWeight * (p[c] - (target == c ? 1.0 : 0.0));
                var dh = new double[_h];
                for (int j = 0; j < _h; j++)
                {
                    double g = 0;
                    for (int c = 0; c < _c; c++) g += e[c] * _w2[c, j];
                    dh[j] = g * (1 - hidden[j] * hidden[j]);
                }
                const double decay = 0.0001;
                for (int c = 0; c < _c; c++)
                {
                    for (int j = 0; j < _h; j++)
                        _w2[c, j] -= rate * (e[c] * hidden[j] + decay * _w2[c, j]);
                    _b2[c] -= rate * e[c];
                }
                for (int j = 0; j < _h; j++)
                {
                    for (int k = 0; k < _d; k++)
                        _w1[j, k] -= rate * (dh[j] * x[k] + decay * _w1[j, k]);
                    _b1[j] -= rate * dh[j];
                }
            }
        }
    }
}
