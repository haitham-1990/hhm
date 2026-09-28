using System;
using System.Collections.Generic;
using System.Linq;
using cAlgo.API;
using cAlgo.API.Internals;
using cAlgo.API.Indicators;

namespace cAlgo.Robots
{
    // Research-only simulator. It NEVER sends, modifies, or closes broker orders.
    // It replays the paired BUY+SELL logic virtually and reports pair-level R statistics.
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class DualHedgeMathEdgeAnalyzer : Robot
    {
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

        [Parameter("Max simultaneous virtual pairs", DefaultValue = 4, MinValue = 1, MaxValue = 4, Group = "Simulation")]
        public int MaxSimultaneousPairs { get; set; }

        [Parameter("Target completed pairs", DefaultValue = 100, MinValue = 20, MaxValue = 1000, Group = "Simulation")]
        public int TargetPairs { get; set; }

        [Parameter("ATR period", DefaultValue = 14, MinValue = 2, MaxValue = 100, Group = "Setup")]
        public int AtrPeriod { get; set; }

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

        [Parameter("Max spread / stop", DefaultValue = 0.30, MinValue = 0.01, MaxValue = 0.50, Group = "Costs")]
        public double MaxSpreadToStop { get; set; }

        [Parameter("Max spread / ATR", DefaultValue = 0.45, MinValue = 0.01, MaxValue = 1.0, Group = "Costs")]
        public double MaxSpreadToAtr { get; set; }

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

        [Parameter("Initial sister floor (R)", DefaultValue = 0.10, MinValue = 0.0, MaxValue = 0.50, Group = "Pair Math")]
        public double InitialSisterFloorR { get; set; }

        [Parameter("Pair recovery trigger (R)", DefaultValue = 1.25, MinValue = 1.0, MaxValue = 1.90, Group = "Pair Math")]
        public double PairRecoveryTriggerR { get; set; }

        [Parameter("Pair buffer over first loss (R)", DefaultValue = 0.12, MinValue = 0.02, MaxValue = 0.50, Group = "Pair Math")]
        public double PairLockedBufferR { get; set; }

        [Parameter("Trail activation (R)", DefaultValue = 1.60, MinValue = 1.10, MaxValue = 1.95, Group = "Pair Math")]
        public double TrailActivationR { get; set; }

        [Parameter("Trail distance (R)", DefaultValue = 0.35, MinValue = 0.10, MaxValue = 1.00, Group = "Pair Math")]
        public double TrailDistanceR { get; set; }

        [Parameter("Emergency pair loss cap (R)", DefaultValue = 1.25, MinValue = 0.50, MaxValue = 2.50, Group = "Pair Math")]
        public double EmergencyPairLossR { get; set; }

        [Parameter("Adaptive window pairs", DefaultValue = 20, MinValue = 10, MaxValue = 100, Group = "Adaptive Guard")]
        public int AdaptiveWindowPairs { get; set; }

        [Parameter("Adaptive minimum sample", DefaultValue = 15, MinValue = 10, MaxValue = 100, Group = "Adaptive Guard")]
        public int AdaptiveMinPairs { get; set; }

        [Parameter("Pause if PF below", DefaultValue = 0.80, MinValue = 0.20, MaxValue = 1.20, Group = "Adaptive Guard")]
        public double AdaptiveMinPf { get; set; }

        [Parameter("Pause if expectancy below (R)", DefaultValue = -0.05, MinValue = -0.50, MaxValue = 0.10, Group = "Adaptive Guard")]
        public double AdaptiveMinExpectancyR { get; set; }

        [Parameter("Adaptive pause minutes", DefaultValue = 120, MinValue = 15, MaxValue = 1440, Group = "Adaptive Guard")]
        public int AdaptivePauseMinutes { get; set; }

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
            public double AtrPrice;
            public double StopPrice;
            public double SpreadToStop;
            public double SpreadToAtr;
            public double SetupQuality;
            public double IndicatorScore;
            public double MathEdgeScore;
            public double EfficiencyRatio;
            public double Adx;
            public double AtrPercentile;
            public double BollingerExpansion;
            public double VolumeRatio;
            public string BreakoutSide;
            public double TotalScore;
        }

        private sealed class VirtualPair
        {
            public MarketInfo Market;
            public DateTime OpenTime;
            public double StopPrice;
            public double BuyEntry;
            public double SellEntry;
            public double BuyStop;
            public double BuyTp;
            public double SellStop;
            public double SellTp;
            public bool BuyOpen = true;
            public bool SellOpen = true;
            public double RealizedR;
            public double MfeR;
            public double MaeR;
            public bool FirstSlOccurred;
            public bool SisterIsBuy;
            public double FirstLossR;
            public bool RecoveryLocked;
            public double BestSisterPrice;
            public string EntryMode;
            public double EntryScore;
        }

        private sealed class PairResult
        {
            public string SymbolName;
            public double R;
            public double MfeR;
            public double MaeR;
            public DateTime ClosedTime;
        }

        private readonly List<MarketInfo> _markets = new List<MarketInfo>();
        private readonly List<VirtualPair> _openPairs = new List<VirtualPair>();
        private readonly List<PairResult> _results = new List<PairResult>();
        private readonly Dictionary<string, DateTime> _adaptivePauseUntil = new Dictionary<string, DateTime>();
        private readonly Dictionary<string, string> _notes = new Dictionary<string, string>();
        private DateTime _nextHeartbeat = DateTime.MinValue;

        protected override void OnStart()
        {
            if (M1EmaFast >= M1EmaSlow || M5EmaFast >= M5EmaSlow ||
                FxMinStopPips > FxMaxStopPips || GoldMinStopPrice > GoldMaxStopPrice)
            {
                Print("ANALYZER invalid parameters.");
                Stop();
                return;
            }

            AddMarket(EnableEurUsd, EurUsdName, false);
            AddMarket(EnableGbpUsd, GbpUsdName, false);
            AddMarket(EnableUsdJpy, UsdJpyName, false);
            AddMarket(EnableGold, GoldName, true);

            if (_markets.Count == 0)
            {
                Print("ANALYZER no broker symbols loaded.");
                Stop();
                return;
            }

            Timer.Start(1);

            Print("V6.3 MATH EDGE ANALYZER started. RESEARCH ONLY: no broker orders will be sent.");
            Print("Markets={0}; target={1} completed pairs; max virtual pairs={2}.",
                string.Join(",", _markets.Select(m => m.Symbol.Name)), TargetPairs, MaxSimultaneousPairs);
            Print("Guards: emergency pair loss={0:F2}R; adaptive pause after >= {1} symbol pairs if PF<{2:F2} AND expectancy<{3:F2}R.",
                EmergencyPairLossR, AdaptiveMinPairs, AdaptiveMinPf, AdaptiveMinExpectancyR);
        }

        private void AddMarket(bool enabled, string name, bool gold)
        {
            if (!enabled || string.IsNullOrWhiteSpace(name)) return;
            try
            {
                var s = Symbols.GetSymbol(name.Trim());
                if (s == null)
                {
                    Print("ANALYZER missing symbol {0}.", name);
                    return;
                }

                var m1 = MarketData.GetBars(TimeFrame.Minute, s.Name);
                var m5 = MarketData.GetBars(TimeFrame.Minute5, s.Name);
                _markets.Add(new MarketInfo
                {
                    Symbol = s,
                    M1 = m1,
                    M5 = m5,
                    M1Fast = Indicators.ExponentialMovingAverage(m1.ClosePrices, M1EmaFast),
                    M1Slow = Indicators.ExponentialMovingAverage(m1.ClosePrices, M1EmaSlow),
                    M5Fast = Indicators.ExponentialMovingAverage(m5.ClosePrices, M5EmaFast),
                    M5Slow = Indicators.ExponentialMovingAverage(m5.ClosePrices, M5EmaSlow),
                    Rsi = Indicators.RelativeStrengthIndex(m1.ClosePrices, RsiPeriod),
                    Gold = gold
                });
                Print("ANALYZER tracking {0} ({1}).", s.Name, gold ? "GOLD" : "FX");
            }
            catch (Exception ex)
            {
                Print("ANALYZER load failed {0}: {1}", name, ex.Message);
            }
        }

        protected override void OnTimer()
        {
            ManageVirtualPairs();

            if (_results.Count >= TargetPairs)
            {
                PrintFinalReport();
                Stop();
                return;
            }

            if (_openPairs.Count < MaxSimultaneousPairs)
                ScanEntries();

            if (Server.Time >= _nextHeartbeat)
            {
                PrintHeartbeat();
                _nextHeartbeat = Server.Time.AddMinutes(5);
            }
        }

        private void ScanEntries()
        {
            var candidates = new List<Candidate>();

            foreach (var market in _markets)
            {
                string name = market.Symbol.Name;
                if (_openPairs.Any(p => p.Market.Symbol.Name == name))
                {
                    _notes[name] = "virtual pair already open";
                    continue;
                }

                DateTime pauseUntil;
                if (_adaptivePauseUntil.TryGetValue(name, out pauseUntil) && Server.Time < pauseUntil)
                {
                    _notes[name] = string.Format("adaptive pause {0:F0}m", (pauseUntil - Server.Time).TotalMinutes);
                    continue;
                }

                if (!market.Symbol.MarketHours.IsOpened())
                {
                    _notes[name] = "market closed";
                    continue;
                }

                if (AvoidCorrelatedFxPairs && IsCorrelationBlocked(name))
                {
                    _notes[name] = "correlation guard";
                    continue;
                }

                var c = Evaluate(market);
                if (c != null)
                    candidates.Add(c);
            }

            foreach (var c in candidates
                .OrderByDescending(x => x.TotalScore)
                .ThenBy(x => x.SpreadToStop))
            {
                if (_openPairs.Count >= MaxSimultaneousPairs) break;
                if (_openPairs.Any(p => p.Market.Symbol.Name == c.Market.Symbol.Name)) continue;
                OpenVirtualPair(c);
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
            if (atr <= 0 || s.PipSize <= 0) return null;

            double recentTr = AverageTrueRangeWindow(b, 2, CompressionBars);
            double baselineTr = AverageTrueRangeWindow(b, CompressionBars + 2, 30);
            if (recentTr <= 0 || baselineTr <= 0) return null;

            double compression = recentTr / baselineTr;
            if (compression > CompressionMaxRatio)
            {
                _notes[s.Name] = "no squeeze";
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
            if (bodyAtr < MinBreakoutBodyAtr) return null;

            bool up = close > priorHigh;
            bool down = close < priorLow;
            if (!up && !down) return null;

            double chase = up ? (close - priorHigh) / atr : (priorLow - close) / atr;
            if (chase < 0 || chase > MaxBreakoutChaseAtr) return null;

            double spread = s.Ask - s.Bid;
            if (spread <= 0) return null;

            double stop = Math.Max(atr * StopAtrMultiplier, spread * 4.0);
            if (market.Gold)
            {
                stop = Math.Max(stop, GoldMinStopPrice);
                if (stop > GoldMaxStopPrice) return null;
            }
            else
            {
                stop = Math.Max(stop, FxMinStopPips * s.PipSize);
                if (stop / s.PipSize > FxMaxStopPips) return null;
            }

            double spreadToStop = spread / stop;
            double spreadToAtr = spread / atr;
            if (spreadToStop > MaxSpreadToStop || spreadToAtr > MaxSpreadToAtr) return null;

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

            if (indicatorScore < MinIndicatorScore) return null;

            double er = KaufmanEfficiencyRatio(b, EfficiencyRatioPeriod, 1);
            double adx = CalculateAdx(b, AdxPeriod, 1);
            double atrPct = AtrPercentile(b, AtrPeriod, AtrPercentileLookback);
            double bbx = BollingerExpansionRatio(b, BollingerPeriod);
            double emaSlopeAtr = atr > 0
                ? Math.Abs(market.M1Fast.Result.Last(1) - market.M1Fast.Result.Last(4)) / (3.0 * atr)
                : 0;

            double candleRange = b.HighPrices.Last(1) - b.LowPrices.Last(1);
            double wick = Math.Max(0.0, candleRange - Math.Abs(close - open));
            double bodyWickRatio = wick > 0 ? Math.Abs(close - open) / wick : 5.0;
            bool slopeAlign = up
                ? market.M1Fast.Result.Last(1) > market.M1Fast.Result.Last(4)
                : market.M1Fast.Result.Last(1) < market.M1Fast.Result.Last(4);

            double math = 0;
            math += Math.Min(1.50, er * 2.0);
            math += Math.Min(1.25, Math.Max(0, adx - 12.0) / 20.0);
            math += (atrPct >= 35 && atrPct <= 90) ? 0.75 : (atrPct > 90 ? 0.25 : 0.0);
            math += Math.Min(0.75, Math.Max(0, bbx - 0.90));
            math += slopeAlign ? Math.Min(0.75, emaSlopeAtr * 2.5) : 0;
            math += Math.Min(0.50, bodyWickRatio / 4.0);
            math += volumeRatio >= 1.0 ? Math.Min(0.50, (volumeRatio - 0.8) * 0.8) : 0;
            math -= spreadToStop * 2.5;
            math -= spreadToAtr * 0.5;

            if (math < MinMathEdgeScore)
            {
                _notes[s.Name] = string.Format("math {0:F2}<{1:F2}", math, MinMathEdgeScore);
                return null;
            }

            double setup =
                (CompressionMaxRatio - compression) * 1.20 +
                Math.Min(1.5, bodyAtr) * 0.60 +
                (MaxBreakoutChaseAtr - chase) * 0.25 -
                spreadToStop * 2.0;

            return new Candidate
            {
                Market = market,
                AtrPrice = atr,
                StopPrice = stop,
                SpreadToStop = spreadToStop,
                SpreadToAtr = spreadToAtr,
                SetupQuality = setup,
                IndicatorScore = indicatorScore,
                MathEdgeScore = math,
                EfficiencyRatio = er,
                Adx = adx,
                AtrPercentile = atrPct,
                BollingerExpansion = bbx,
                VolumeRatio = volumeRatio,
                BreakoutSide = up ? "UP" : "DOWN",
                TotalScore = indicatorScore + math + setup - spreadToStop * 1.5 - spreadToAtr * 0.5
            };
        }

        private void OpenVirtualPair(Candidate c)
        {
            Symbol s = c.Market.Symbol;
            if (s.Ask <= 0 || s.Bid <= 0 || c.StopPrice <= 0) return;

            var p = new VirtualPair
            {
                Market = c.Market,
                OpenTime = Server.Time,
                StopPrice = c.StopPrice,
                BuyEntry = s.Ask,
                SellEntry = s.Bid,
                BuyStop = s.Ask - c.StopPrice,
                BuyTp = s.Ask + 2.0 * c.StopPrice,
                SellStop = s.Bid + c.StopPrice,
                SellTp = s.Bid - 2.0 * c.StopPrice,
                MfeR = 0,
                MaeR = 0,
                EntryMode = c.BreakoutSide,
                EntryScore = c.TotalScore
            };
            _openPairs.Add(p);

            Print("SIM OPEN {0}: BUY@{1} SELL@{2} SL={3:F2}p score={4:F2} ER={5:F2} ADX={6:F1} ATRpct={7:F0} BBx={8:F2}",
                s.Name, p.BuyEntry, p.SellEntry, c.StopPrice / s.PipSize,
                c.TotalScore, c.EfficiencyRatio, c.Adx, c.AtrPercentile, c.BollingerExpansion);
        }

        private void ManageVirtualPairs()
        {
            foreach (var p in _openPairs.ToArray())
            {
                Symbol s = p.Market.Symbol;
                if (s.Bid <= 0 || s.Ask <= 0) continue;

                double pairMarkR = p.RealizedR;
                if (p.BuyOpen) pairMarkR += (s.Bid - p.BuyEntry) / p.StopPrice;
                if (p.SellOpen) pairMarkR += (p.SellEntry - s.Ask) / p.StopPrice;

                p.MfeR = Math.Max(p.MfeR, pairMarkR);
                p.MaeR = Math.Min(p.MaeR, pairMarkR);

                if (pairMarkR <= -EmergencyPairLossR)
                {
                    CloseAllVirtualAtMarket(p, "EMERGENCY CAP");
                    FinalizePair(p);
                    continue;
                }

                bool buyHitSl = p.BuyOpen && s.Bid <= p.BuyStop;
                bool buyHitTp = p.BuyOpen && s.Bid >= p.BuyTp;
                bool sellHitSl = p.SellOpen && s.Ask >= p.SellStop;
                bool sellHitTp = p.SellOpen && s.Ask <= p.SellTp;

                if (buyHitSl) CloseBuy(p, p.BuyStop, true);
                else if (buyHitTp) CloseBuy(p, p.BuyTp, false);

                if (sellHitSl) CloseSell(p, p.SellStop, true);
                else if (sellHitTp) CloseSell(p, p.SellTp, false);

                if (!p.FirstSlOccurred)
                {
                    if (!p.BuyOpen && p.SellOpen && p.RealizedR < 0)
                        StartSisterManagement(p, false);
                    else if (!p.SellOpen && p.BuyOpen && p.RealizedR < 0)
                        StartSisterManagement(p, true);
                }

                if (p.FirstSlOccurred)
                    ManageSister(p);

                if (!p.BuyOpen && !p.SellOpen)
                    FinalizePair(p);
            }
        }

        private void StartSisterManagement(VirtualPair p, bool sisterIsBuy)
        {
            p.FirstSlOccurred = true;
            p.SisterIsBuy = sisterIsBuy;
            p.FirstLossR = Math.Abs(p.RealizedR);
            Symbol s = p.Market.Symbol;
            p.BestSisterPrice = sisterIsBuy ? s.Bid : s.Ask;

            if (InitialSisterFloorR > 0)
                ImproveVirtualSisterStop(p, InitialSisterFloorR);

            Print("SIM FIRST SL {0}: firstLoss={1:F2}R; sister={2}.",
                s.Name, p.FirstLossR, sisterIsBuy ? "BUY" : "SELL");
        }

        private void ManageSister(VirtualPair p)
        {
            if ((p.SisterIsBuy && !p.BuyOpen) || (!p.SisterIsBuy && !p.SellOpen)) return;

            Symbol s = p.Market.Symbol;
            double current = p.SisterIsBuy ? s.Bid : s.Ask;
            if (p.SisterIsBuy) p.BestSisterPrice = Math.Max(p.BestSisterPrice, current);
            else p.BestSisterPrice = Math.Min(p.BestSisterPrice, current);

            double entry = p.SisterIsBuy ? p.BuyEntry : p.SellEntry;
            double favorableR = p.SisterIsBuy
                ? (p.BestSisterPrice - entry) / p.StopPrice
                : (entry - p.BestSisterPrice) / p.StopPrice;

            if (!p.RecoveryLocked && favorableR >= PairRecoveryTriggerR)
            {
                double targetLockR = p.FirstLossR + PairLockedBufferR;
                ImproveVirtualSisterStop(p, targetLockR);
                p.RecoveryLocked = true;
                Print("SIM PAIR RECOVERY {0}: best={1:F2}R lock target={2:F2}R.",
                    s.Name, favorableR, targetLockR);
            }

            if (!p.RecoveryLocked || favorableR < TrailActivationR) return;

            double atr = ClosedAtr(p.Market.M1, AtrPeriod, 40);
            double atrTrailR = atr > 0 ? 0.35 * atr / p.StopPrice : 0;
            double trailR = Math.Max(TrailDistanceR, atrTrailR);

            double proposed = p.SisterIsBuy
                ? p.BestSisterPrice - trailR * p.StopPrice
                : p.BestSisterPrice + trailR * p.StopPrice;

            double floorR = p.FirstLossR + PairLockedBufferR;
            double floor = p.SisterIsBuy
                ? p.BuyEntry + floorR * p.StopPrice
                : p.SellEntry - floorR * p.StopPrice;

            if (p.SisterIsBuy)
            {
                proposed = Math.Max(proposed, floor);
                if (proposed < s.Bid)
                    p.BuyStop = Math.Max(p.BuyStop, proposed);
            }
            else
            {
                proposed = Math.Min(proposed, floor);
                if (proposed > s.Ask)
                    p.SellStop = Math.Min(p.SellStop, proposed);
            }
        }

        private void ImproveVirtualSisterStop(VirtualPair p, double lockR)
        {
            Symbol s = p.Market.Symbol;
            if (p.SisterIsBuy && p.BuyOpen)
            {
                double desired = p.BuyEntry + lockR * p.StopPrice;
                if (desired < s.Bid)
                    p.BuyStop = Math.Max(p.BuyStop, desired);
            }
            else if (!p.SisterIsBuy && p.SellOpen)
            {
                double desired = p.SellEntry - lockR * p.StopPrice;
                if (desired > s.Ask)
                    p.SellStop = Math.Min(p.SellStop, desired);
            }
        }

        private void CloseBuy(VirtualPair p, double exitPrice, bool isStop)
        {
            if (!p.BuyOpen) return;
            double r = (exitPrice - p.BuyEntry) / p.StopPrice;
            p.RealizedR += r;
            p.BuyOpen = false;
            if (DiagnosticLogs)
                Print("SIM BUY CLOSE {0}: {1} {2:F2}R", p.Market.Symbol.Name, isStop ? "SL" : "TP", r);
        }

        private void CloseSell(VirtualPair p, double exitPrice, bool isStop)
        {
            if (!p.SellOpen) return;
            double r = (p.SellEntry - exitPrice) / p.StopPrice;
            p.RealizedR += r;
            p.SellOpen = false;
            if (DiagnosticLogs)
                Print("SIM SELL CLOSE {0}: {1} {2:F2}R", p.Market.Symbol.Name, isStop ? "SL" : "TP", r);
        }

        private void CloseAllVirtualAtMarket(VirtualPair p, string reason)
        {
            Symbol s = p.Market.Symbol;
            if (p.BuyOpen) CloseBuy(p, s.Bid, true);
            if (p.SellOpen) CloseSell(p, s.Ask, true);
            Print("SIM {0} {1}: pair={2:F2}R", reason, s.Name, p.RealizedR);
        }

        private void FinalizePair(VirtualPair p)
        {
            if (!_openPairs.Remove(p)) return;

            var result = new PairResult
            {
                SymbolName = p.Market.Symbol.Name,
                R = p.RealizedR,
                MfeR = p.MfeR,
                MaeR = p.MaeR,
                ClosedTime = Server.Time
            };
            _results.Add(result);

            Print("SIM PAIR RESULT {0}: R={1:F2} MFE={2:F2} MAE={3:F2} | total={4}/{5}",
                result.SymbolName, result.R, result.MfeR, result.MaeR, _results.Count, TargetPairs);

            EvaluateAdaptiveGuard(result.SymbolName);
            PrintSymbolReport(result.SymbolName);

            if (_results.Count % 25 == 0)
                PrintOverallReport("CHECKPOINT " + _results.Count);
        }

        private void EvaluateAdaptiveGuard(string symbolName)
        {
            var all = _results.Where(r => r.SymbolName == symbolName).ToList();
            if (all.Count < AdaptiveMinPairs) return;

            var sample = all.Skip(Math.Max(0, all.Count - AdaptiveWindowPairs)).ToList();
            double expectancy = sample.Average(r => r.R);
            double wins = sample.Where(r => r.R > 0).Sum(r => r.R);
            double losses = -sample.Where(r => r.R < 0).Sum(r => r.R);
            double pf = losses > 0 ? wins / losses : (wins > 0 ? 999 : 0);

            if (pf < AdaptiveMinPf && expectancy < AdaptiveMinExpectancyR)
            {
                _adaptivePauseUntil[symbolName] = Server.Time.AddMinutes(AdaptivePauseMinutes);
                Print("SIM ADAPTIVE PAUSE {0}: n={1} PF={2:F2} expectancy={3:F3}R; paused {4}m.",
                    symbolName, sample.Count, pf, expectancy, AdaptivePauseMinutes);
            }
        }

        private void PrintSymbolReport(string symbolName)
        {
            var x = _results.Where(r => r.SymbolName == symbolName).ToArray();
            if (x.Length == 0) return;
            double expectancy = x.Average(r => r.R);
            double gw = x.Where(r => r.R > 0).Sum(r => r.R);
            double gl = -x.Where(r => r.R < 0).Sum(r => r.R);
            double pf = gl > 0 ? gw / gl : (gw > 0 ? 999 : 0);
            int w = x.Count(r => r.R > 0);
            int l = x.Count(r => r.R < 0);
            Print("SIM SYMBOL {0}: pairs={1} W/L={2}/{3} net={4:F2}R expectancy={5:F3}R PF={6:F2} worst={7:F2}R",
                symbolName, x.Length, w, l, x.Sum(r => r.R), expectancy, pf, x.Min(r => r.R));
        }

        private void PrintOverallReport(string title)
        {
            if (_results.Count == 0) return;
            double expectancy = _results.Average(r => r.R);
            double gw = _results.Where(r => r.R > 0).Sum(r => r.R);
            double gl = -_results.Where(r => r.R < 0).Sum(r => r.R);
            double pf = gl > 0 ? gw / gl : (gw > 0 ? 999 : 0);
            int w = _results.Count(r => r.R > 0);
            int l = _results.Count(r => r.R < 0);
            Print("=== {0} === pairs={1} W/L={2}/{3} net={4:F2}R expectancy={5:F3}R PF={6:F2} avgMFE={7:F2}R avgMAE={8:F2}R worst={9:F2}R",
                title, _results.Count, w, l, _results.Sum(r => r.R), expectancy, pf,
                _results.Average(r => r.MfeR), _results.Average(r => r.MaeR), _results.Min(r => r.R));
        }

        private void PrintFinalReport()
        {
            PrintOverallReport("FINAL " + _results.Count + " PAIRS");
            foreach (var m in _markets)
                PrintSymbolReport(m.Symbol.Name);
            Print("ANALYZER finished. Use a NEW unseen sample before changing parameters.");
        }

        private void PrintHeartbeat()
        {
            Print("SIM HEARTBEAT completed={0}/{1}, open={2}/{3} | {4}",
                _results.Count, TargetPairs, _openPairs.Count, MaxSimultaneousPairs,
                string.Join("; ", _markets.Select(m => m.Symbol.Name + "=" +
                    (_notes.ContainsKey(m.Symbol.Name) ? _notes[m.Symbol.Name] : "ready"))));
        }

        private bool IsCorrelationBlocked(string symbolName)
        {
            bool isEur = symbolName.IndexOf("EURUSD", StringComparison.OrdinalIgnoreCase) >= 0;
            bool isGbp = symbolName.IndexOf("GBPUSD", StringComparison.OrdinalIgnoreCase) >= 0;
            if (!isEur && !isGbp) return false;

            string other = isEur ? "GBPUSD" : "EURUSD";
            return _openPairs.Any(p =>
                p.Market.Symbol.Name.IndexOf(other, StringComparison.OrdinalIgnoreCase) >= 0);
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
            for (int i = shift; i < shift + period; i++) mean += b.ClosePrices.Last(i);
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

        protected override void OnStop()
        {
            PrintOverallReport("STOPPED");
            Print("V6.3 MATH EDGE ANALYZER stopped. No broker orders were sent.");
            Timer.Stop();
        }
    }
}
