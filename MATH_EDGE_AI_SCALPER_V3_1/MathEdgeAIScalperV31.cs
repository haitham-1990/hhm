// MATH EDGE AI SCALPER v3.1
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
    public class MathEdgeAIScalperV31 : Robot
    {
        [Parameter("Enable Orders (DEMO first)", DefaultValue = false)]
        public bool EnableOrders { get; set; }

        [Parameter("Allow Live Account (DANGER)", DefaultValue = false)]
        public bool AllowLive { get; set; }

        [Parameter("Risk % Per Trade", DefaultValue = 0.25, MinValue = 0.1, MaxValue = 0.5)]
        public double RiskPct { get; set; }

        [Parameter("Daily Equity Loss Limit %", DefaultValue = 2.0, MinValue = 0.5, MaxValue = 2.0)]
        public double DailyLossPct { get; set; }

        [Parameter("Instance Drawdown Limit %", DefaultValue = 5.0, MinValue = 1.0, MaxValue = 5.0)]
        public double DrawdownPct { get; set; }

        [Parameter("Max Entries per UTC Day", DefaultValue = 8, MinValue = 1, MaxValue = 10)]
        public int MaxEntries { get; set; }

        [Parameter("Min Directional Score Gap", DefaultValue = 0.04, MinValue = 0.02, MaxValue = 0.20)]
        public double MinDirectionalGap { get; set; }

        [Parameter("Calibration Signal Percentile", DefaultValue = 55, MinValue = 35, MaxValue = 75)]
        public int SignalPercentile { get; set; }

        [Parameter("Training Bars", DefaultValue = 1200, MinValue = 650, MaxValue = 2200)]
        public int Lookback { get; set; }

        [Parameter("Retrain Every N Bars", DefaultValue = 36, MinValue = 12, MaxValue = 120)]
        public int RetrainBars { get; set; }

        [Parameter("Assumed Roundtrip Cost Pips", DefaultValue = 25, MinValue = 5, MaxValue = 150)]
        public double AssumedCostPips { get; set; }

        [Parameter("SL ATR Multiple", DefaultValue = 1.5, MinValue = 1.2, MaxValue = 3.0)]
        public double SlAtrMultiple { get; set; }

        [Parameter("Take Profit / Stop Loss", DefaultValue = 1.4, MinValue = 1.2, MaxValue = 3.0)]
        public double RewardRisk { get; set; }

        [Parameter("Maximum Spread / ATR", DefaultValue = 0.12, MinValue = 0.02, MaxValue = 0.18)]
        public double MaxSpreadAtr { get; set; }

        [Parameter("Cooldown Minutes", DefaultValue = 10, MinValue = 5, MaxValue = 120)]
        public int CooldownMinutes { get; set; }

        [Parameter("UTC Start Hour", DefaultValue = 6, MinValue = 0, MaxValue = 23)]
        public int UtcStart { get; set; }

        [Parameter("UTC End Hour", DefaultValue = 20, MinValue = 1, MaxValue = 24)]
        public int UtcEnd { get; set; }

        [Parameter("Max Trade Duration (Minutes)", DefaultValue = 30, MinValue = 15, MaxValue = 90)]
        public int MaxHoldMinutes { get; set; }

        [Parameter("Min Higher TF Confluence", DefaultValue = 0.025, MinValue = 0.015, MaxValue = 0.25)]
        public double MinConfluence { get; set; }

        private const string Label = "MATH_EDGE_SCALP_V31";
        private const int Features = 18;
        private const int Hidden = 12;
        private const int Horizon = 6; // Six M5 bars (30 minutes)
        private Bars _m15;
        private Bars _h1;
        private Bars _h4;
        private Bars _d1;
        private NeuralNet _model;
        private bool _qualified;
        private bool _hardLocked;
        private DateTime _dailyDate;
        private bool _dailyLocked;
        private double _instancePeak;
        private int _lastModelBar = -1;
        private int _consecutiveFailures;
        private double _edgeCutoff = double.PositiveInfinity;
        private DateTime _lastProcessedBarOpen = DateTime.MinValue;
        private int _skippedStaleBars;

        protected override void OnStart()
        {
            if (!SymbolName.StartsWith("XAUUSD", StringComparison.OrdinalIgnoreCase))
            {
                Print("STOP: attach only to gold/XAUUSD."); Stop(); return;
            }
            if (TimeFrame != TimeFrame.Minute5)
            {
                Print("STOP: use M5 chart."); Stop(); return;
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
                for (int attempt = 0; attempt < 12 && Bars.Count < Lookback + 90; attempt++)
                {
                    int count = Bars.LoadMoreHistory();
                    if (count <= 0) break;
                }
            }
            catch (Exception e)
            {
                Print("History request issue: {0}. Continue with available candles.", e.Message);
            }
            try
            {
                _m15 = MarketData.GetBars(TimeFrame.Minute15, SymbolName);
                _h1 = MarketData.GetBars(TimeFrame.Hour, SymbolName);
                _h4 = MarketData.GetBars(TimeFrame.Hour4, SymbolName);
                _d1 = MarketData.GetBars(TimeFrame.Daily, SymbolName);
                ExpandHtfHistory(_m15, "M15");
                ExpandHtfHistory(_h1, "H1");
                ExpandHtfHistory(_h4, "H4");
                ExpandHtfHistory(_d1, "D1");
            }
            catch (Exception e)
            {
                Print("HIGHER TF HISTORY ERROR -> FAIL CLOSED: {0}", e.Message);
                Stop(); return;
            }
            Print("MATH EDGE AI SCALPER v3.1: Demo={0}, Orders={1}, CloudCompatible=Yes, ExternalAPI=NONE, M5Bars={2}",
                !Account.IsLive, EnableOrders, Bars.Count);
            Print("Higher timeframes: M15={0}, H1={1}, H4={2}, D1={3}; closed-only historical alignment.",
                _m15.Count, _h1.Count, _h4.Count, _d1.Count);
            Print("ML=18 features (10 M5 + M15/H1/H4/D1 trends and RSIs), 12-hidden/3-class neural net; trades subject to untouched holdout and risk gates.");
            Print("Important: Instance drawdown peak resets on cloud restart. Daily floor rebuilt from account history when possible.");
            CheckRisk();
        }

        protected override void OnTimer()
        {
            CheckRisk();
            CheckMaximumHoldingTime();
        }

        private void CheckMaximumHoldingTime()
        {
            if (!EnableOrders) return;
            foreach (var position in Positions.Where(p => p.Label == Label && p.SymbolName == SymbolName).ToArray())
            {
                if ((Server.Time - position.EntryTime).TotalMinutes < MaxHoldMinutes) continue;
                var closed = ClosePosition(position);
                if (!closed.IsSuccessful)
                    Print("TIME EXIT FAILED position={0}: {1}", position.Id, closed.Error);
                else
                    Print("TIME EXIT: position={0} after {1} minutes.", position.Id, MaxHoldMinutes);
            }
        }

        private void ExpandHtfHistory(Bars series, string name)
        {
            if (series == null) throw new InvalidOperationException(name + " bars unavailable.");
            try
            {
                for (int j = 0; j < 8 && series.Count < 150; j++)
                    if (series.LoadMoreHistory() <= 0) break;
            }
            catch (Exception e) { Print("HTF history limit for {0}: {1}", name, e.Message); }
            if (series.Count < 40)
                throw new InvalidOperationException(name + " fewer than 40 bars; refusing to trade.");
        }

        protected override void OnBarClosed()
        {
            CheckRisk();
            CheckMaximumHoldingTime();
            if (_hardLocked || _dailyLocked || Account.Equity <= 0 || Account.IsLive && !AllowLive) return;
            if (Bars.Count < 520)
            {
                if (Bars.Count % 16 == 0)
                    Print("WAIT: insufficient M5 bars ({0}); need at least 520 for neural training.", Bars.Count);
                return;
            }

            int i = Bars.Count - 1; // OnBarClosed excludes the currently forming bar.
            DateTime barOpen = Bars.OpenTimes[i];
            if (barOpen == _lastProcessedBarOpen) return; // Suppress duplicate callbacks.
            _lastProcessedBarOpen = barOpen;
            // A cloud restart may replay dozens of historical OnBarClosed callbacks in seconds.
            // Never issue an order based on an old bar; retrain only against near-real-time bars.
            if ((Server.Time - barOpen).TotalMinutes > 12)
            {
                _skippedStaleBars++;
                if (_skippedStaleBars == 1)
                    Print("SKIP: historical bar replay; waiting for a fresh M5 closed bar.");
                return;
            }
            if (_lastModelBar < 0 || i - _lastModelBar >= RetrainBars)
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
                // Show preliminary predictions for research, NEVER orders.
                if (_model != null && i % 3 == 0)
                {
                    double shadowAtr;
                    double[] xx = GetFeatures(i, out shadowAtr);
                    if (xx != null)
                    {
                        double[] pp = _model.Predict(xx);
                        int preliminary = DirectionWithoutThreshold(pp, xx, MinConfluence);
                        Print("SHADOW (NO ORDERS): BUY={0:F3} WAIT={1:F3} SELL={2:F3}, HTFscore={3:F3}, bias={4}, VALIDATION=BLOCKED",
                            pp[0], pp[1], pp[2], HigherTimeframeScore(xx),
                            preliminary == 1 ? "BUY" : preliminary == -1 ? "SELL" : "WAIT");
                    }
                }
                if (i % 6 == 0) Print("AI WAIT: validation gate not passed. See MODEL / DIAGNOSTIC.");
                return;
            }

            if (Server.Time.Hour < UtcStart || Server.Time.Hour >= UtcEnd) return;
            if ((Server.Time - Bars.OpenTimes[i]).TotalMinutes > 12) return;
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
            int direction = Choose(p, _edgeCutoff, x, MinConfluence);
            Print("ML M5: BUY={0:F3}, WAIT={1:F3}, SELL={2:F3}; gap={3:F3}, cutoff={4:F3}, MTFscore={5:F3}, selection={6} (scores NOT calibrated)",
                p[0], p[1], p[2], p[0] - p[2], _edgeCutoff, HigherTimeframeScore(x),
                direction == 1 ? "BUY" : direction == -1 ? "SELL" : "WAIT");
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
            _edgeCutoff = double.PositiveInfinity;
            int sampleCount = Math.Min(Lookback, last - 65 - Horizon);
            if (sampleCount < 430)
            {
                Print("WAIT: insufficient training samples.");
                return;
            }

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
                samples.Add(new Sample
                {
                    X = x, Y = label, Atr = atr, Delta = diff, Cost = costPrice / atr,
                    SourceIndex = t
                });
            }

            int n = samples.Count;
            int holdoutSize = Math.Max(90, n / 5);
            int holdoutStart = n - holdoutSize;
            int calibrationSize = Math.Max(70, n / 6);
            int trainingEnd = holdoutStart - calibrationSize - 2 * Horizon;
            int calibrationStart = trainingEnd + Horizon;
            int calibrationEnd = holdoutStart - Horizon;
            if (trainingEnd < 250 || calibrationEnd <= calibrationStart + 50)
            {
                Print("WAIT: training/calibration/holdout split too short.");
                return;
            }

            var net = new NeuralNet(Features, Hidden, 3, 2419);
            int[] trainLabels = new int[3];
            for (int k = 0; k < trainingEnd; k++) trainLabels[samples[k].Y]++;
            double[] weights = new double[3];
            for (int c = 0; c < 3; c++)
                weights[c] = Math.Min(2.5, Math.Sqrt((double)trainingEnd / (3 * Math.Max(1, trainLabels[c]))));

            var rng = new Random(72491);
            int[] order = Enumerable.Range(0, trainingEnd).ToArray();
            for (int epoch = 0; epoch < 28; epoch++)
            {
                for (int k = order.Length - 1; k > 0; k--)
                {
                    int j = rng.Next(k + 1);
                    int tmp = order[k]; order[k] = order[j]; order[j] = tmp;
                }
                double learningRate = 0.018 / (1.0 + epoch * 0.025);
                foreach (int k in order)
                {
                    var sample = samples[k];
                    net.Train(sample.X, sample.Y, weights[sample.Y], learningRate);
                }
            }

            // Separate training, calibration, and final holdout time ranges.
            // Model is also used for shadow-only diagnostics on failed gates.
            _model = net;
            var calibrationGaps = new List<double>();
            int calibrationDirectional = 0;
            int calibrationHtfAligned = 0;
            int calibrationM5Compatible = 0;
            for (int k = calibrationStart; k < calibrationEnd; k++)
            {
                var sample = samples[k];
                double[] probs = net.Predict(sample.X);
                double gap = probs[0] - probs[2];
                double multi = HigherTimeframeScore(sample.X);
                if (Math.Abs(gap) >= 0.0001) calibrationDirectional++;
                if ((gap > 0 && multi >= MinConfluence) || (gap < 0 && multi <= -MinConfluence))
                    calibrationHtfAligned++;
                if ((gap > 0 && sample.X[4] > -0.45) || (gap < 0 && sample.X[4] < 0.45))
                    calibrationM5Compatible++;
                int candidate = DirectionWithoutThreshold(probs, sample.X, MinConfluence);
                if (candidate != 0) calibrationGaps.Add(Math.Abs(gap));
            }

            Print("CALIBRATION FUNNEL: bars={0}, directional={1}, higher-TF aligned={2}, M5 compatible={3}, final candidates={4}.",
                calibrationEnd - calibrationStart, calibrationDirectional, calibrationHtfAligned, calibrationM5Compatible, calibrationGaps.Count);
            // V3's fixed minimum 20 blocked model BEFORE holdout testing.
            // Eight is only a calibration floor; the held-out validation
            // still blocks ALL order placement if results fail.
            if (calibrationGaps.Count < 8)
            {
                Print("CALIBRATION FAILED: only {0} candidates; SHADOW only. No orders.", calibrationGaps.Count);
                return;
            }
            calibrationGaps.Sort();
            int percentileIndex = Math.Min(calibrationGaps.Count - 1,
                Math.Max(0, (int)Math.Floor((calibrationGaps.Count - 1) * SignalPercentile / 100.0)));
            _edgeCutoff = Math.Max(MinDirectionalGap, calibrationGaps[percentileIndex]);
            Print("CALIBRATION: train={0}, calibration={1}, holdout={2}; candidateCount={3}, selectedEdgeGap={4:F4}, floor={5:F4}, percentile={6}%.",
                trainingEnd, calibrationEnd - calibrationStart, holdoutSize, calibrationGaps.Count,
                _edgeCutoff, MinDirectionalGap, SignalPercentile);

            int[] testLabels = new int[3];
            int directionCandidates = 0, picked = 0, wins = 0;
            int lastPicked = -Horizon;
            double winsSum = 0, lossesSum = 0, netSum = 0;
            double meanBuy = 0, meanWait = 0, meanSell = 0;
            for (int k = holdoutStart; k < n; k++)
            {
                Sample sample = samples[k];
                testLabels[sample.Y]++;
                double[] probs = net.Predict(sample.X);
                meanBuy += probs[0]; meanWait += probs[1]; meanSell += probs[2];
                int direction = Choose(probs, _edgeCutoff, sample.X, MinConfluence);
                if (direction == 0) continue;
                directionCandidates++;
                if (k - lastPicked < Horizon) continue;

                // Conservative approximation: enter at next bar's OPEN, not
                // signal bar's CLOSE, simulate stop-first when TP+SL touch in
                // the same bar, and charge an assumed round-trip cost.
                double outcome = SimulateValidationTrade(sample, direction);
                if (!Valid(outcome)) continue;
                netSum += outcome;
                if (outcome > 0) { wins++; winsSum += outcome; }
                else lossesSum -= outcome;
                picked++;
                lastPicked = k;
            }
            double hitRate = picked > 0 ? (double)wins / picked : 0;
            double profitFactor = lossesSum > 0 ? winsSum / lossesSum : 0;
            double meanReturn = picked > 0 ? netSum / picked : 0;
            _qualified = picked >= 8 && hitRate >= 0.50 && profitFactor >= 1.15 && meanReturn > 0.06;
            _model = net;

            Print("DIAGNOSTIC: train BUY={0} WAIT={1} SELL={2}; holdout BUY={3} WAIT={4} SELL={5}.",
                trainLabels[0], trainLabels[1], trainLabels[2], testLabels[0], testLabels[1], testLabels[2]);
            Print("DIAGNOSTIC: holdout scores BUY={0:F3}, WAIT={1:F3}, SELL={2:F3}; rawCandidates={3}, independentSignals={4}.",
                meanBuy / holdoutSize, meanWait / holdoutSize, meanSell / holdoutSize, directionCandidates, picked);
            Print("MODEL: holdout={0}, trades={1}, win={2:P1}, PF={3:F2}, meanNetATR={4:F3}, VALIDATED={5}.",
                holdoutSize, picked, hitRate, profitFactor, meanReturn, _qualified);
            if (!_qualified)
                Print("VALIDATION BLOCKED: {0} — NO TRADE.",
                    picked < 8 ? "FEWER_THAN_8_INDEPENDENT_SIGNALS" :
                    hitRate < .50 ? "LOW_HIT_RATE" : profitFactor < 1.15 ? "LOW_PROFIT_FACTOR" : "LOW_MEAN_RETURN");
            Print("Holdout is a conservative SHORT-HORIZON PROXY (not broker backtest); see actual cTrader backtesting before demo orders.");
        }

        private double SimulateValidationTrade(Sample sample, int side)
        {
            int index = sample.SourceIndex;
            double entry = Bars.OpenPrices[index + 1];
            double stopDistance = Math.Max(SlAtrMultiple * sample.Atr, 2 * Symbol.PipSize);
            double takeDistance = stopDistance * RewardRisk;
            double stop = entry - side * stopDistance;
            double take = entry + side * takeDistance;
            double grossPrice = 0;
            bool resolved = false;
            for (int next = index + 1; next <= index + Horizon; next++)
            {
                double low = Bars.LowPrices[next], high = Bars.HighPrices[next];
                bool hitStop = side > 0 ? low <= stop : high >= stop;
                bool hitTake = side > 0 ? high >= take : low <= take;
                if (hitStop)
                {
                    grossPrice = -stopDistance; resolved = true; break;
                }
                if (hitTake)
                {
                    grossPrice = takeDistance; resolved = true; break;
                }
            }
            if (!resolved)
                grossPrice = side * (Bars.ClosePrices[index + Horizon] - entry);
            return (grossPrice / sample.Atr) - sample.Cost;
        }

        // Weigh direction of ALL higher timeframes, without demanding all
        // four agree. RSI confirms the trend and does not overrule M5 alone.
        private static double HigherTimeframeScore(double[] x)
        {
            if (x == null || x.Length != Features) return 0;
            double trend = 0.25 * x[10] + 0.30 * x[11] +
                           0.30 * x[12] + 0.15 * x[13];
            double oscillator = 0.035 * (x[14] + x[15] + x[16] + x[17]);
            return trend + oscillator;
        }

        private static int DirectionWithoutThreshold(double[] p, double[] x, double minConfluence)
        {
            if (x == null || x.Length != Features) return 0;
            double gap = p[0] - p[2];
            double multi = HigherTimeframeScore(x);
            // M5 only vetoes a STRONG opposite trend, not all divergence.
            if (gap > 0 && x[4] > -0.45 && multi >= minConfluence && p[0] >= p[1] - 0.15)
                return 1;
            if (gap < 0 && x[4] < 0.45 && multi <= -minConfluence && p[2] >= p[1] - 0.15)
                return -1;
            return 0;
        }

        private static int Choose(double[] p, double threshold, double[] x, double minConfluence)
        {
            if (Math.Abs(p[0] - p[2]) < threshold) return 0;
            return DirectionWithoutThreshold(p, x, minConfluence);
        }

        // Every sample is aligned to the timestamp when its M5 bar closed.
        // For each higher timeframe we use the last FULLY CLOSED bar at that
        // timestamp, never the current higher timeframe's partially formed bar.
        private int LastClosedIndex(Bars series, DateTime baseClose, int durationMinutes)
        {
            if (series == null || series.Count < 40) return -1;
            int left = 0, right = series.Count - 1, found = -1;
            while (left <= right)
            {
                int mid = left + (right - left) / 2;
                DateTime higherClose = series.OpenTimes[mid].AddMinutes(durationMinutes);
                if (higherClose <= baseClose)
                {
                    found = mid;
                    left = mid + 1;
                }
                else right = mid - 1;
            }
            return found >= 35 ? found : -1;
        }

        private double[] GetFeatures(int i, out double atr)
        {
            atr = 0;
            if (i < 62 || i >= Bars.Count) return null;
            atr = Atr(i, 14);
            if (!Valid(atr) || atr <= Symbol.PipSize) return null;
            var m5Close = Bars.OpenTimes[i].AddMinutes(5);
            int m15 = LastClosedIndex(_m15, m5Close, 15);
            int h1 = LastClosedIndex(_h1, m5Close, 60);
            int h4 = LastClosedIndex(_h4, m5Close, 240);
            int d1 = LastClosedIndex(_d1, m5Close, 1440);
            if (m15 < 0 || h1 < 0 || h4 < 0 || d1 < 0) return null;

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
                Clip((rsi - 50) / 25.0),
                HigherTrend(_m15, m15),
                HigherTrend(_h1, h1),
                HigherTrend(_h4, h4),
                HigherTrend(_d1, d1),
                HigherRsi(_m15, m15),
                HigherRsi(_h1, h1),
                HigherRsi(_h4, h4),
                HigherRsi(_d1, d1)
            };
        }

        private double HigherTrend(Bars series, int at)
        {
            double fast = HigherSma(series, at, 8);
            double slow = HigherSma(series, at, 34);
            double volatility = HigherAtr(series, at, 14);
            return Clip(volatility > 0 ? (fast - slow) / volatility : 0);
        }

        private double HigherRsi(Bars series, int at)
        {
            double up = 0, down = 0;
            for (int j = at - 13; j <= at; j++)
            {
                double change = series.ClosePrices[j] - series.ClosePrices[j - 1];
                if (change > 0) up += change;
                else down -= change;
            }
            return Clip(((100.0 * up / Math.Max(1e-10, up + down)) - 50) / 25.0);
        }

        private double HigherSma(Bars series, int at, int length)
        {
            double sum = 0;
            for (int j = at - length + 1; j <= at; j++) sum += series.ClosePrices[j];
            return sum / length;
        }

        private double HigherAtr(Bars series, int at, int length)
        {
            double sum = 0;
            for (int j = at - length + 1; j <= at; j++)
            {
                double h = series.HighPrices[j], l = series.LowPrices[j];
                double pc = series.ClosePrices[j - 1];
                sum += Math.Max(h - l, Math.Max(Math.Abs(h - pc), Math.Abs(l - pc)));
            }
            return sum / length;
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
            public int SourceIndex;
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
