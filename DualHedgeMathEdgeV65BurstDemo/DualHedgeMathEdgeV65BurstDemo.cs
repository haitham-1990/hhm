using System;
using System.Collections.Generic;
using System.Linq;
using cAlgo.API;
using cAlgo.API.Internals;
using cAlgo.API.Indicators;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class DualHedgeMathEdgeV65BurstDemo : Robot
    {
        [Parameter("Base label", DefaultValue = "MATH-EDGE-V6-5-BURST", Group = "Safety")]
        public string BaseLabel { get; set; }

        [Parameter("Burst total positions", DefaultValue = 200, MinValue = 2, MaxValue = 200, Group = "Burst")]
        public int BurstTotalPositions { get; set; }

        [Parameter("Max bursts per UTC day", DefaultValue = 1, MinValue = 1, MaxValue = 5, Group = "Burst")]
        public int MaxBurstsPerDay { get; set; }

        [Parameter("FX total pair risk (%)", DefaultValue = 0.02, MinValue = 0.01, MaxValue = 0.20, Group = "Risk")]
        public double FxPairRiskPercent { get; set; }

        [Parameter("Gold total pair risk (%)", DefaultValue = 0.02, MinValue = 0.01, MaxValue = 0.20, Group = "Risk")]
        public double GoldPairRiskPercent { get; set; }

        [Parameter("Max nominal burst risk (%)", DefaultValue = 2.00, MinValue = 0.20, MaxValue = 5.00, Group = "Risk")]
        public double MaxNominalBurstRiskPercent { get; set; }

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

        [Parameter("ATR period", DefaultValue = 14, MinValue = 5, MaxValue = 50, Group = "Setup")]
        public int AtrPeriod { get; set; }

        [Parameter("M1 EMA fast", DefaultValue = 9, MinValue = 2, MaxValue = 50, Group = "Setup")]
        public int M1FastPeriod { get; set; }

        [Parameter("M1 EMA slow", DefaultValue = 21, MinValue = 5, MaxValue = 100, Group = "Setup")]
        public int M1SlowPeriod { get; set; }

        [Parameter("M5 EMA fast", DefaultValue = 20, MinValue = 5, MaxValue = 100, Group = "Setup")]
        public int M5FastPeriod { get; set; }

        [Parameter("M5 EMA slow", DefaultValue = 50, MinValue = 10, MaxValue = 200, Group = "Setup")]
        public int M5SlowPeriod { get; set; }

        [Parameter("RSI period", DefaultValue = 7, MinValue = 2, MaxValue = 50, Group = "Setup")]
        public int RsiPeriod { get; set; }

        [Parameter("Kaufman ER period", DefaultValue = 12, MinValue = 5, MaxValue = 50, Group = "Setup")]
        public int ErPeriod { get; set; }

        [Parameter("ADX period", DefaultValue = 14, MinValue = 5, MaxValue = 50, Group = "Setup")]
        public int AdxPeriod { get; set; }

        [Parameter("Compression bars", DefaultValue = 8, MinValue = 4, MaxValue = 30, Group = "Setup")]
        public int CompressionBars { get; set; }

        [Parameter("Compression max ratio", DefaultValue = 1.35, MinValue = 0.30, MaxValue = 2.00, Group = "Setup")]
        public double CompressionMaxRatio { get; set; }

        [Parameter("Min breakout body / ATR", DefaultValue = 0.10, MinValue = 0.03, MaxValue = 2.00, Group = "Setup")]
        public double MinBreakoutBodyAtr { get; set; }

        [Parameter("Min Math Edge score", DefaultValue = 2.50, MinValue = 0.0, MaxValue = 10.0, Group = "Setup")]
        public double MinMathEdgeScore { get; set; }

        [Parameter("Stop ATR multiplier", DefaultValue = 0.65, MinValue = 0.20, MaxValue = 3.00, Group = "Stops")]
        public double StopAtrMultiplier { get; set; }

        [Parameter("FX min stop pips", DefaultValue = 2.0, MinValue = 0.5, MaxValue = 20.0, Group = "Stops")]
        public double FxMinStopPips { get; set; }

        [Parameter("FX max stop pips", DefaultValue = 6.0, MinValue = 1.0, MaxValue = 50.0, Group = "Stops")]
        public double FxMaxStopPips { get; set; }

        [Parameter("Gold min stop USD", DefaultValue = 0.30, MinValue = 0.05, MaxValue = 10.0, Group = "Stops")]
        public double GoldMinStopPrice { get; set; }

        [Parameter("Gold max stop USD", DefaultValue = 1.50, MinValue = 0.10, MaxValue = 50.0, Group = "Stops")]
        public double GoldMaxStopPrice { get; set; }

        [Parameter("Reward / risk", DefaultValue = 2.0, MinValue = 2.0, MaxValue = 2.0, Group = "Stops")]
        public double RewardRisk { get; set; }

        [Parameter("Max spread / stop", DefaultValue = 0.30, MinValue = 0.01, MaxValue = 0.60, Group = "Costs")]
        public double MaxSpreadToStop { get; set; }

        [Parameter("Max spread / ATR", DefaultValue = 0.45, MinValue = 0.01, MaxValue = 1.0, Group = "Costs")]
        public double MaxSpreadToAtr { get; set; }

        [Parameter("Quick winner min profit (R)", DefaultValue = 0.01, MinValue = 0.0, MaxValue = 0.30, Group = "Quick Exit")]
        public double QuickWinnerMinProfitR { get; set; }

        [Parameter("Whipsaw cooldown sec", DefaultValue = 20, MinValue = 0, MaxValue = 300, Group = "Quick Exit")]
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

        private sealed class Candidate
        {
            public MarketInfo Market;
            public double StopPips;
            public double AtrPrice;
            public double Score;
            public double SpreadToStop;
            public string BreakoutSide;
        }

        private sealed class PairState
        {
            public long PairId;
            public string SymbolName;
            public long BuyId;
            public long SellId;
            public double LegRiskCash;
            public double RealizedNet;
            public bool FirstSlOccurred;
            public long SisterId;
            public DateTime FirstSlTime;
        }

        private readonly List<MarketInfo> _markets = new List<MarketInfo>();
        private readonly Dictionary<long, PairState> _pairs = new Dictionary<long, PairState>();
        private readonly Dictionary<long, long> _positionToPair = new Dictionary<long, long>();
        private readonly Dictionary<string, DateTime> _whipsawUntil = new Dictionary<string, DateTime>();
        private DateTime _utcDay;
        private int _burstsToday;
        private long _nextPairId = 1;
        private DateTime _nextHeartbeat = DateTime.MinValue;

        protected override void OnStart()
        {
            // Hard safety lock: this stress version never runs on live accounts.
            if (Account.IsLive)
            {
                Print("MATH EDGE V6.5 BURST is DEMO-ONLY and is hard-blocked on LIVE.");
                Stop();
                return;
            }

            AddMarket(EnableEurUsd, EurUsdName, false);
            AddMarket(EnableGbpUsd, GbpUsdName, false);
            AddMarket(EnableUsdJpy, UsdJpyName, false);
            AddMarket(EnableGold, GoldName, true);

            if (_markets.Count == 0)
            {
                Print("MATH EDGE V6.5 BURST: no symbols loaded.");
                Stop();
                return;
            }

            _utcDay = Server.Time.Date;
            Positions.Closed += OnPositionClosed;
            Timer.Start(1);

            Print("MATH EDGE V6.5 BURST DEMO ON | requested burst={0} positions ({1} pairs) | max bursts/day={2}.",
                BurstTotalPositions, BurstTotalPositions / 2, MaxBurstsPerDay);
            Print("Hard LIVE block enabled. Pair risk FX={0:F2}% GOLD={1:F2}% and no forced minimum volume.",
                FxPairRiskPercent, GoldPairRiskPercent);
            Print("After first SL, sister closes as soon as its NetProfit is positive and >= {0:F2}R.", QuickWinnerMinProfitR);
        }

        private void AddMarket(bool enabled, string name, bool gold)
        {
            if (!enabled || string.IsNullOrWhiteSpace(name)) return;
            try
            {
                Symbol s = Symbols.GetSymbol(name.Trim());
                if (s == null) return;

                Bars m1 = MarketData.GetBars(TimeFrame.Minute, s.Name);
                Bars m5 = MarketData.GetBars(TimeFrame.Minute5, s.Name);

                _markets.Add(new MarketInfo
                {
                    Symbol = s,
                    M1 = m1,
                    M5 = m5,
                    M1Fast = Indicators.ExponentialMovingAverage(m1.ClosePrices, M1FastPeriod),
                    M1Slow = Indicators.ExponentialMovingAverage(m1.ClosePrices, M1SlowPeriod),
                    M5Fast = Indicators.ExponentialMovingAverage(m5.ClosePrices, M5FastPeriod),
                    M5Slow = Indicators.ExponentialMovingAverage(m5.ClosePrices, M5SlowPeriod),
                    Rsi = Indicators.RelativeStrengthIndex(m1.ClosePrices, RsiPeriod),
                    Gold = gold
                });
            }
            catch (Exception ex)
            {
                Print("LOAD FAILED {0}: {1}", name, ex.Message);
            }
        }

        protected override void OnTimer()
        {
            ResetDay();
            QuickManageSisters();

            if (_burstsToday >= MaxBurstsPerDay)
                return;

            Candidate[] ranked = _markets
                .Where(m => m.Symbol.MarketHours.IsOpened())
                .Select(Evaluate)
                .Where(c => c != null)
                .OrderByDescending(c => c.Score)
                .ThenBy(c => c.SpreadToStop)
                .ToArray();

            if (ranked.Length == 0)
            {
                if (DiagnosticLogs && Server.Time >= _nextHeartbeat)
                {
                    Print("MATH EDGE BURST WAIT: no qualified setup.");
                    _nextHeartbeat = Server.Time.AddMinutes(2);
                }
                return;
            }

            int desiredPairs = Math.Max(1, BurstTotalPositions / 2);
            double worstPairRisk = Math.Max(FxPairRiskPercent, GoldPairRiskPercent);
            int riskCappedPairs = (int)Math.Floor(MaxNominalBurstRiskPercent / Math.Max(0.0001, worstPairRisk));
            int pairsToAttempt = Math.Min(desiredPairs, Math.Max(1, riskCappedPairs));

            int openedPairs = 0;
            int consecutiveFailures = 0;

            for (int i = 0; i < pairsToAttempt; i++)
            {
                Candidate c = ranked[i % ranked.Length];

                DateTime wait;
                if (_whipsawUntil.TryGetValue(c.Market.Symbol.Name, out wait) && Server.Time < wait)
                    continue;

                bool ok = OpenOnePair(c);
                if (ok)
                {
                    openedPairs++;
                    consecutiveFailures = 0;
                }
                else
                {
                    consecutiveFailures++;
                    if (consecutiveFailures >= 3)
                    {
                        Print("MATH EDGE BURST stopped after 3 consecutive broker/risk failures. Opened {0}/{1} pairs.",
                            openedPairs, pairsToAttempt);
                        break;
                    }
                }
            }

            if (openedPairs > 0)
            {
                _burstsToday++;
                Print("MATH EDGE BURST COMPLETE: opened {0} pairs = {1} positions requested target={2}.",
                    openedPairs, openedPairs * 2, BurstTotalPositions);
            }
        }

        private Candidate Evaluate(MarketInfo m)
        {
            Bars b = m.M1;
            Symbol s = m.Symbol;
            if (b.Count < 240 || m.M5.Count < M5SlowPeriod + 20) return null;

            DateTime barTime = b.OpenTimes.Last(1);
            if (barTime <= m.LastExamined) return null;
            m.LastExamined = barTime;

            double atr = ClosedAtr(b, AtrPeriod, 80);
            if (atr <= 0 || s.PipSize <= 0) return null;

            double recentTr = AverageTrueRangeWindow(b, 2, CompressionBars);
            double baselineTr = AverageTrueRangeWindow(b, CompressionBars + 2, 30);
            if (recentTr <= 0 || baselineTr <= 0) return null;

            double compression = recentTr / baselineTr;
            if (compression > CompressionMaxRatio) return null;

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
            if (bodyAtr < MinBreakoutBodyAtr) return null;

            bool up = close > priorHigh;
            bool down = close < priorLow;
            if (!up && !down) return null;

            double spread = s.Ask - s.Bid;
            if (spread <= 0) return null;

            double stopPrice = Math.Max(atr * StopAtrMultiplier, spread * 4.0);
            if (m.Gold)
            {
                stopPrice = Math.Max(stopPrice, GoldMinStopPrice);
                if (stopPrice > GoldMaxStopPrice) return null;
            }
            else
            {
                stopPrice = Math.Max(stopPrice, FxMinStopPips * s.PipSize);
                if (stopPrice / s.PipSize > FxMaxStopPips) return null;
            }

            double spreadToStop = spread / stopPrice;
            double spreadToAtr = spread / atr;
            if (spreadToStop > MaxSpreadToStop || spreadToAtr > MaxSpreadToAtr) return null;

            double m1Fast = m.M1Fast.Result.Last(1);
            double m1Slow = m.M1Slow.Result.Last(1);
            double m5Fast = m.M5Fast.Result.Last(1);
            double m5Slow = m.M5Slow.Result.Last(1);
            double rsi = m.Rsi.Result.Last(1);
            double er = KaufmanEfficiencyRatio(b, ErPeriod, 1);
            double adx = CalculateAdx(b, AdxPeriod, 1);

            bool m1Align = up ? m1Fast > m1Slow : m1Fast < m1Slow;
            bool m5Align = up ? m5Fast > m5Slow : m5Fast < m5Slow;
            bool rsiAlign = up ? rsi >= 50 : rsi <= 50;

            double score = 0;
            score += m1Align ? 1.0 : 0;
            score += m5Align ? 1.25 : 0;
            score += rsiAlign ? 0.75 : 0;
            score += Math.Min(1.5, er * 2.0);
            score += Math.Min(1.25, Math.Max(0, adx - 12.0) / 20.0);
            score += Math.Min(0.75, bodyAtr * 0.6);
            score -= spreadToStop * 2.5;
            score -= spreadToAtr * 0.5;

            if (score < MinMathEdgeScore) return null;

            return new Candidate
            {
                Market = m,
                StopPips = stopPrice / s.PipSize,
                AtrPrice = atr,
                Score = score,
                SpreadToStop = spreadToStop,
                BreakoutSide = up ? "UP" : "DOWN"
            };
        }

        private bool OpenOnePair(Candidate c)
        {
            Symbol s = c.Market.Symbol;
            double pairRisk = c.Market.Gold ? GoldPairRiskPercent : FxPairRiskPercent;
            double legRiskPct = pairRisk / 2.0;

            double units = s.VolumeForProportionalRisk(
                ProportionalAmountType.Equity, legRiskPct, c.StopPips, RoundingMode.Down);
            units = s.NormalizeVolumeInUnits(units, RoundingMode.Down);

            if (units < s.VolumeInUnitsMin)
            {
                Print("BURST RISK SKIP {0}: calculated units={1} < broker min={2}; not forcing risk.",
                    s.Name, units, s.VolumeInUnitsMin);
                return false;
            }

            if (units > s.VolumeInUnitsMax)
                units = s.VolumeInUnitsMax;

            double budget = Account.Equity * legRiskPct / 100.0;
            double actualRisk = s.AmountRisked(units, c.StopPips);
            if (actualRisk <= 0 || actualRisk > budget * 1.10)
            {
                Print("BURST RISK SKIP {0}: actual risk≈{1:F2} > budget={2:F2}.", s.Name, actualRisk, budget);
                return false;
            }

            long pairId = _nextPairId++;
            string prefix = BaseLabel + "-P" + pairId;
            string buyLabel = prefix + "-B";
            string sellLabel = prefix + "-S";
            double tpPips = c.StopPips * 2.0;

            TradeResult buy = ExecuteMarketOrder(TradeType.Buy, s.Name, units, buyLabel, c.StopPips, tpPips);
            if (!buy.IsSuccessful || buy.Position == null)
                return false;

            TradeResult sell = ExecuteMarketOrder(TradeType.Sell, s.Name, units, sellLabel, c.StopPips, tpPips);
            if (!sell.IsSuccessful || sell.Position == null)
            {
                ClosePosition(buy.Position);
                return false;
            }

            var state = new PairState
            {
                PairId = pairId,
                SymbolName = s.Name,
                BuyId = buy.Position.Id,
                SellId = sell.Position.Id,
                LegRiskCash = actualRisk,
                RealizedNet = 0
            };
            _pairs[pairId] = state;
            _positionToPair[buy.Position.Id] = pairId;
            _positionToPair[sell.Position.Id] = pairId;

            if (DiagnosticLogs && pairId % 10 == 0)
                Print("BURST OPEN pair#{0} {1} units={2} SL={3:F2}p score={4:F2}.",
                    pairId, s.Name, units, c.StopPips, c.Score);

            return true;
        }

        private void OnPositionClosed(PositionClosedEventArgs e)
        {
            Position closed = e.Position;
            if (closed == null) return;

            long pairId;
            if (!_positionToPair.TryGetValue(closed.Id, out pairId)) return;

            PairState state;
            if (!_pairs.TryGetValue(pairId, out state)) return;

            state.RealizedNet += closed.NetProfit;
            _positionToPair.Remove(closed.Id);

            if (e.Reason == PositionCloseReason.StopLoss && !state.FirstSlOccurred)
            {
                state.FirstSlOccurred = true;
                state.FirstSlTime = Server.Time;
                state.SisterId = closed.Id == state.BuyId ? state.SellId : state.BuyId;
                _whipsawUntil[state.SymbolName] = Server.Time.AddSeconds(WhipsawCooldownSeconds);

                Position sister = Positions.FirstOrDefault(p => p.Id == state.SisterId);
                if (sister != null)
                    TryQuickClose(state, sister);
            }

            bool buyOpen = Positions.Any(p => p.Id == state.BuyId);
            bool sellOpen = Positions.Any(p => p.Id == state.SellId);
            if (!buyOpen && !sellOpen)
            {
                double finalR = state.LegRiskCash > 0 ? state.RealizedNet / state.LegRiskCash : 0;
                Print("BURST PAIR RESULT #{0} {1}: net={2:F2} R={3:F3}.",
                    state.PairId, state.SymbolName, state.RealizedNet, finalR);
                _pairs.Remove(pairId);
            }
        }

        private void QuickManageSisters()
        {
            foreach (var state in _pairs.Values.Where(x => x.FirstSlOccurred).ToArray())
            {
                Position sister = Positions.FirstOrDefault(p => p.Id == state.SisterId);
                if (sister != null)
                    TryQuickClose(state, sister);
            }
        }

        private void TryQuickClose(PairState state, Position sister)
        {
            if (state.LegRiskCash <= 0) return;

            double r = sister.NetProfit / state.LegRiskCash;
            if (sister.NetProfit <= 0 || r < QuickWinnerMinProfitR)
                return;

            double net = sister.NetProfit;
            TradeResult close = ClosePosition(sister);
            if (close.IsSuccessful)
                Print("BURST QUICK EXIT pair#{0} {1}: sister net≈{2:F2} ({3:F3}R).",
                    state.PairId, state.SymbolName, net, r);
        }

        private void ResetDay()
        {
            if (Server.Time.Date == _utcDay) return;
            _utcDay = Server.Time.Date;
            _burstsToday = 0;
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

        private double AverageTrueRangeWindow(Bars b, int startLastIndex, int count)
        {
            if (b.Count < startLastIndex + count + 2) return 0;

            double sum = 0;
            for (int i = startLastIndex; i < startLastIndex + count; i++)
            {
                double high = b.HighPrices.Last(i);
                double low = b.LowPrices.Last(i);
                double prev = b.ClosePrices.Last(i + 1);
                sum += Math.Max(high - low, Math.Max(Math.Abs(high - prev), Math.Abs(low - prev)));
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
            Print("MATH EDGE V6.5 BURST cTrader error {0}", error.Code);
        }

        protected override void OnStop()
        {
            Positions.Closed -= OnPositionClosed;
            Timer.Stop();
            Print("MATH EDGE V6.5 BURST stopped.");
        }
    }
}
