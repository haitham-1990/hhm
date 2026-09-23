using System;
using System.Collections.Generic;
using System.Linq;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    // SAQR: experimental multi-asset M1 DEMO cBot. No established trading edge.
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class SAQRMulti : Robot
    {
        [Parameter("Demo ONLY - block live", DefaultValue = true, Group = "Safety")]
        public bool DemoOnly { get; set; }

        [Parameter("Bot label", DefaultValue = "SAQR-Multi-V1", Group = "Safety")]
        public string BotLabel { get; set; }

        [Parameter("Risk per trade (%)", DefaultValue = 0.5, MinValue = 0.01, MaxValue = 2.0, Group = "Safety")]
        public double RiskPercent { get; set; }

        [Parameter("Daily loss threshold (%)", DefaultValue = 2.0, MinValue = 0.5, MaxValue = 10.0, Group = "Safety")]
        public double MaxDailyLossPercent { get; set; }

        [Parameter("Max trades per UTC day", DefaultValue = 15, MinValue = 1, MaxValue = 60, Group = "Safety")]
        public int MaxTradesDaily { get; set; }

        [Parameter("Stop after consecutive losses", DefaultValue = 3, MinValue = 1, MaxValue = 10, Group = "Safety")]
        public int MaxLosingStreak { get; set; }

        [Parameter("Cooldown seconds", DefaultValue = 120, MinValue = 0, Group = "Safety")]
        public int CooldownSeconds { get; set; }

        [Parameter("Block if other tracked positions", DefaultValue = true, Group = "Safety")]
        public bool BlockOtherPositions { get; set; }

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

        [Parameter("UTC session start hour", DefaultValue = 0, MinValue = 0, MaxValue = 23, Group = "Session")]
        public int StartHour { get; set; }

        [Parameter("UTC session end hour", DefaultValue = 24, MinValue = 1, MaxValue = 24, Group = "Session")]
        public int EndHour { get; set; }

        [Parameter("M1 fast EMA", DefaultValue = 9, MinValue = 2, Group = "Signals")]
        public int M1FastPeriod { get; set; }

        [Parameter("M1 slow EMA", DefaultValue = 21, MinValue = 3, Group = "Signals")]
        public int M1SlowPeriod { get; set; }

        [Parameter("M5 fast EMA", DefaultValue = 20, MinValue = 2, Group = "Signals")]
        public int M5FastPeriod { get; set; }

        [Parameter("M5 slow EMA", DefaultValue = 50, MinValue = 3, Group = "Signals")]
        public int M5SlowPeriod { get; set; }

        [Parameter("RSI period", DefaultValue = 7, MinValue = 2, Group = "Signals")]
        public int RsiPeriod { get; set; }

        [Parameter("Volume lookback", DefaultValue = 20, MinValue = 5, Group = "Signals")]
        public int VolumeLookback { get; set; }

        [Parameter("Min signal score", DefaultValue = 3.5, MinValue = 2, MaxValue = 5.5, Group = "Signals")]
        public double MinSignalScore { get; set; }

        [Parameter("Min tick volume ratio", DefaultValue = 1.0, MinValue = 0.1, Group = "Signals")]
        public double MinVolumeRatio { get; set; }

        [Parameter("FX max spread pips", DefaultValue = 1.25, MinValue = 0.1, Group = "FX")]
        public double FxMaxSpread { get; set; }

        [Parameter("FX reserve cost pips EST", DefaultValue = 0.15, MinValue = 0, Group = "FX")]
        public double FxReserveCost { get; set; }

        [Parameter("FX minimum ATR pips", DefaultValue = 0.7, MinValue = 0.1, Group = "FX")]
        public double FxMinAtr { get; set; }

        [Parameter("FX maximum ATR pips", DefaultValue = 8.0, MinValue = 0.5, Group = "FX")]
        public double FxMaxAtr { get; set; }

        [Parameter("FX minimum stop pips", DefaultValue = 4.0, MinValue = 0.5, Group = "FX")]
        public double FxMinStop { get; set; }

        [Parameter("FX maximum stop pips", DefaultValue = 14.0, MinValue = 1, Group = "FX")]
        public double FxMaxStop { get; set; }

        [Parameter("FX stop ATR multiplier", DefaultValue = 1.4, MinValue = 0.5, Group = "FX")]
        public double FxStopAtr { get; set; }

        [Parameter("FX reward/risk", DefaultValue = 1.45, MinValue = 0.5, Group = "FX")]
        public double FxRr { get; set; }

        [Parameter("Gold max spread USD/oz", DefaultValue = 0.70, MinValue = 0.01, Group = "Gold")]
        public double GoldMaxSpread { get; set; }

        [Parameter("Gold reserve cost USD EST", DefaultValue = 0.10, MinValue = 0, Group = "Gold")]
        public double GoldReserveCost { get; set; }

        [Parameter("Gold minimum ATR USD", DefaultValue = 0.45, MinValue = 0.01, Group = "Gold")]
        public double GoldMinAtr { get; set; }

        [Parameter("Gold maximum ATR USD", DefaultValue = 9.0, MinValue = 0.2, Group = "Gold")]
        public double GoldMaxAtr { get; set; }

        [Parameter("Gold minimum stop USD", DefaultValue = 2.0, MinValue = 0.05, Group = "Gold")]
        public double GoldMinStop { get; set; }

        [Parameter("Gold maximum stop USD", DefaultValue = 12.0, MinValue = 0.2, Group = "Gold")]
        public double GoldMaxStop { get; set; }

        [Parameter("Gold stop ATR multiplier", DefaultValue = 1.8, MinValue = 0.5, Group = "Gold")]
        public double GoldStopAtr { get; set; }

        [Parameter("Gold reward/risk", DefaultValue = 1.4, MinValue = 0.5, Group = "Gold")]
        public double GoldRr { get; set; }

        [Parameter("Max estimated cost/stop", DefaultValue = 0.20, MinValue = 0.01, MaxValue = 0.75, Group = "Execution")]
        public double MaxCostToStop { get; set; }

        [Parameter("Max spread/ATR", DefaultValue = 0.45, MinValue = 0.01, MaxValue = 1.0, Group = "Execution")]
        public double MaxSpreadToAtr { get; set; }

        [Parameter("Max M1 candle/ATR", DefaultValue = 2.5, MinValue = 0.5, Group = "Execution")]
        public double MaxCandleToAtr { get; set; }

        [Parameter("Break-even after R", DefaultValue = 1.0, MinValue = 0.2, Group = "Exits")]
        public double BreakEvenAtR { get; set; }

        [Parameter("Break-even lock R", DefaultValue = 0.05, MinValue = 0, Group = "Exits")]
        public double BreakEvenLockR { get; set; }

        [Parameter("FX max minutes", DefaultValue = 10, MinValue = 1, Group = "Exits")]
        public int FxMaxMinutes { get; set; }

        [Parameter("Gold max minutes", DefaultValue = 12, MinValue = 1, Group = "Exits")]
        public int GoldMaxMinutes { get; set; }

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
            public DateTime LastExaminedM1 = DateTime.MinValue;
        }

        private sealed class Signal
        {
            public MarketInfo Market;
            public TradeType Side;
            public double Score;
            public double Quality;
            public double StopPips;
            public double RiskReward;
            public double CostToStop;
        }

        private sealed class TradeAudit
        {
            public double InitialStopPips;
            public double RiskReward;
            public double SpreadAtEntryPips;
            public double ExtraEstimatedPips;
            public double SignalScore;
        }

        private readonly List<MarketInfo> _markets = new List<MarketInfo>();
        private readonly Dictionary<int, TradeAudit> _audits = new Dictionary<int, TradeAudit>();
        private readonly Dictionary<int, string> _manualExitReasons = new Dictionary<int, string>();

        private DateTime _utcDay;
        private DateTime _lastEntry = DateTime.MinValue;
        private double _referenceEquity;
        private double _botNetToday;
        private double _positiveToday;
        private double _negativeToday;
        private int _entriesToday;
        private int _closedToday;
        private int _winsToday;
        private int _consecutiveLosses;
        private bool _blocked;
        private int _diagnosticTick;

        protected override void OnStart()
        {
            if (DemoOnly && Account.IsLive)
            {
                Print("SAQR safety block: DemoOnly=true on a LIVE account. No trading.");
                Stop();
                return;
            }
            if (M1FastPeriod >= M1SlowPeriod || M5FastPeriod >= M5SlowPeriod ||
                FxMinAtr >= FxMaxAtr || GoldMinAtr >= GoldMaxAtr ||
                FxMinStop > FxMaxStop || GoldMinStop > GoldMaxStop ||
                string.IsNullOrWhiteSpace(BotLabel))
            {
                Print("SAQR setup invalid: review EMA, ATR, stop ranges and bot label.");
                Stop();
                return;
            }
            AddMarket(EnableEurUsd, EurUsdName, false);
            AddMarket(EnableGbpUsd, GbpUsdName, false);
            AddMarket(EnableUsdJpy, UsdJpyName, false);
            AddMarket(EnableGold, GoldName, true);
            if (_markets.Count == 0)
            {
                Print("SAQR error: no enabled broker symbols could be loaded. Check Market symbol names.");
                Stop();
                return;
            }
            _utcDay = Server.Time.Date;
            RecoverState();
            Positions.Closed += Closed;
            Timer.Start(10);
            Print("SAQR ON | DEMO={0} markets={1} M1 closed candles with closed M5 direction. ONE total SAQR position max. Risk={2:F2}%",
                DemoOnly, string.Join(",", _markets.Select(x => x.Symbol.Name)), RiskPercent);
            Print("SAQR trading session UTC={0}:00 to {1}:00 (defaults cover all 24 hours; trades only when each broker market is OPEN).", StartHour, EndHour);
            Print("SAQR: estimated extra fees are PLACEHOLDERS; check broker actual commissions. Daily equity baseline reconstructed, not an enforceable liquidation cap.");
        }

        private void AddMarket(bool enabled, string name, bool gold)
        {
            if (!enabled || string.IsNullOrWhiteSpace(name)) return;
            name = name.Trim();
            try
            {
                if (_markets.Any(x => x.Symbol.Name == name)) return;
                Symbol sym = Symbols.GetSymbol(name);
                if (sym == null)
                {
                    Print("SAQR skipped missing broker symbol {0}. Check symbol suffix in parameters.", name);
                    return;
                }
                Bars m1 = MarketData.GetBars(cAlgo.API.TimeFrame.Minute, name);
                Bars m5 = MarketData.GetBars(cAlgo.API.TimeFrame.Minute5, name);
                _markets.Add(new MarketInfo
                {
                    Symbol = sym,
                    M1 = m1,
                    M5 = m5,
                    M1Fast = Indicators.ExponentialMovingAverage(m1.ClosePrices, M1FastPeriod),
                    M1Slow = Indicators.ExponentialMovingAverage(m1.ClosePrices, M1SlowPeriod),
                    M5Fast = Indicators.ExponentialMovingAverage(m5.ClosePrices, M5FastPeriod),
                    M5Slow = Indicators.ExponentialMovingAverage(m5.ClosePrices, M5SlowPeriod),
                    Rsi = Indicators.RelativeStrengthIndex(m1.ClosePrices, RsiPeriod),
                    Gold = gold
                });
                Print("SAQR tracking {0} as {1}, pipSize={2} volumeMin={3}",
                    name, gold ? "GOLD" : "FX", sym.PipSize, sym.VolumeInUnitsMin);
            }
            catch (Exception ex)
            {
                Print("SAQR could not initialize {0}: {1}", name, ex.Message);
            }
        }

        private Position[] OpenBotPositions()
        {
            return Positions.FindAll(BotLabel);
        }

        private void RecoverState()
        {
            _entriesToday = 0;
            _closedToday = 0;
            _winsToday = 0;
            _consecutiveLosses = 0;
            _botNetToday = 0;
            _positiveToday = 0;
            _negativeToday = 0;
            var tracked = new HashSet<string>(_markets.Select(x => x.Symbol.Name));
            var history = History.FindAll(BotLabel)
                .Where(h => tracked.Contains(h.SymbolName))
                .ToList();
            var entries = history.Where(h => h.EntryTime.Date == _utcDay)
                .GroupBy(h => h.PositionId)
                .ToList();
            _entriesToday = entries.Count;
            foreach (var trade in entries.Select(g => new
            {
                Amount = g.Sum(h => h.NetProfit),
                Open = g.Max(h => h.EntryTime),
                Closed = g.Max(h => h.ClosingTime)
            }).OrderBy(g => g.Closed))
            {
                if (trade.Open > _lastEntry) _lastEntry = trade.Open;
            }
            var closes = history.Where(h => h.ClosingTime.Date == _utcDay)
                .GroupBy(h => h.PositionId)
                .Select(g => new { Amount = g.Sum(h => h.NetProfit), Closed = g.Max(h => h.ClosingTime) })
                .OrderBy(g => g.Closed).ToList();
            foreach (var x in closes)
            {
                _closedToday++;
                _botNetToday += x.Amount;
                if (x.Amount > 0)
                {
                    _winsToday++;
                    _positiveToday += x.Amount;
                    _consecutiveLosses = 0;
                }
                else if (x.Amount < 0)
                {
                    _negativeToday += -x.Amount;
                    _consecutiveLosses++;
                }
            }
            foreach (var p in OpenBotPositions())
            {
                if (!tracked.Contains(p.SymbolName)) continue;
                if (p.EntryTime.Date == _utcDay) _entriesToday++;
                if (p.EntryTime > _lastEntry) _lastEntry = p.EntryTime;
            }
            double floating = OpenBotPositions()
                .Where(p => tracked.Contains(p.SymbolName)).Sum(p => p.NetProfit);
            _referenceEquity = Account.Equity - _botNetToday - floating;
            if (_referenceEquity <= 0) _referenceEquity = Account.Equity;
            _blocked = _entriesToday >= MaxTradesDaily || _consecutiveLosses >= MaxLosingStreak;
            Print("SAQR RESTORED todayEntries={0} closed={1} wins={2} net={3:F2} lossStreak={4}, referenceEquity={5:F2}",
                _entriesToday, _closedToday, _winsToday, _botNetToday,
                _consecutiveLosses, _referenceEquity);
        }

        private void ResetUTCDate()
        {
            if (Server.Time.Date == _utcDay) return;
            _utcDay = Server.Time.Date;
            _lastEntry = DateTime.MinValue;
            _blocked = false;
            RecoverState();
        }

        private bool RiskBlocked()
        {
            if (_blocked) return true;
            double floating = OpenBotPositions().Sum(p => p.NetProfit);
            double estimatedBotLoss = -(_botNetToday + floating);
            double accountEquityDrop = _referenceEquity - Account.Equity;
            double threshold = _referenceEquity * MaxDailyLossPercent / 100.0;
            if (_entriesToday >= MaxTradesDaily || _consecutiveLosses >= MaxLosingStreak ||
                estimatedBotLoss >= threshold || accountEquityDrop >= threshold)
            {
                _blocked = true;
                Print("SAQR DAY BLOCKED new entries: trades={0}/{1}, lossStreak={2}/{3}, estimatedBotLoss={4:F2}, equityDrop={5:F2}, cap={6:F2}",
                    _entriesToday, MaxTradesDaily, _consecutiveLosses,
                    MaxLosingStreak, estimatedBotLoss, accountEquityDrop, threshold);
            }
            return _blocked;
        }

        private bool TradingHours()
        {
            int h = Server.Time.Hour;
            return StartHour < EndHour
                ? h >= StartHour && h < EndHour
                : h >= StartHour || h < EndHour;
        }

        protected override void OnTimer()
        {
            ResetUTCDate();
            MaintainOpenPositions();
            if (RiskBlocked() || !TradingHours() || OpenBotPositions().Length > 0) return;
            if ((Server.Time - _lastEntry).TotalSeconds < CooldownSeconds) return;
            if (BlockOtherPositions && Positions.Any(p =>
                _markets.Any(m => m.Symbol.Name == p.SymbolName) && p.Label != BotLabel))
            {
                if (DiagnosticLogs && _diagnosticTick++ % 6 == 0)
                    Print("SAQR waits: external/manual position in tracked markets; avoiding overlapping account risk.");
                return;
            }

            // Rank only independent valid signals from closed M1 + M5 candles.
            // One new SAQR position may be created across all symbols per pass.
            var candidates = new List<Signal>();
            foreach (var market in _markets)
            {
                if (!market.Symbol.MarketHours.IsOpened()) continue;
                Signal candidate = Evaluate(market);
                if (candidate != null) candidates.Add(candidate);
            }
            if (candidates.Count == 0)
            {
                if (DiagnosticLogs && _diagnosticTick++ % 30 == 0)
                    Print("SAQR scanner: no qualifying entries. activeMarkets={0}", _markets.Count);
                return;
            }
            var selected = candidates.OrderByDescending(c => c.Quality)
                .ThenBy(c => c.CostToStop).First();
            Print("SAQR ranked {0} signals; selected {1} {2}, score={3:F2}, quality={4:F2}, cost/stop={5:F3}",
                candidates.Count, selected.Market.Symbol.Name,
                selected.Side, selected.Score, selected.Quality, selected.CostToStop);
            ExecuteSignal(selected);
        }

        private Signal Evaluate(MarketInfo market)
        {
            var s = market.Symbol;
            var b = market.M1;
            var five = market.M5;
            if (b.Count < Math.Max(100, VolumeLookback + 8) || five.Count < M5SlowPeriod + 8)
                return null;

            // In OnTimer, Last(0) can be the still-forming candle.
            // Only the latest COMPLETELY CLOSED M1/M5 candles are scored.
            DateTime barDate = b.OpenTimes.Last(1);
            if (barDate <= market.LastExaminedM1) return null;
            market.LastExaminedM1 = barDate;
            if ((Server.Time - barDate).TotalSeconds > 160 ||
                (Server.Time - five.OpenTimes.Last(1)).TotalMinutes > 12)
                return null;

            double spreadPrice = s.Ask - s.Bid;
            double pip = s.PipSize;
            if (pip <= 0 || spreadPrice <= 0) return null;
            double spreadPips = spreadPrice / pip;
            double atrPrice = ClosedAtr(b, 14, 75);
            if (atrPrice <= 0) return null;
            double stopPrice, costPrice, rr;
            if (market.Gold)
            {
                if (spreadPrice > GoldMaxSpread || atrPrice < GoldMinAtr || atrPrice > GoldMaxAtr)
                    return null;
                stopPrice = Math.Max(GoldMinStop,
                    Math.Max(atrPrice * GoldStopAtr, spreadPrice * 4));
                if (stopPrice > GoldMaxStop) return null;
                costPrice = spreadPrice + GoldReserveCost;
                rr = GoldRr;
            }
            else
            {
                double atrPips = atrPrice / pip;
                if (spreadPips > FxMaxSpread || atrPips < FxMinAtr || atrPips > FxMaxAtr)
                    return null;
                stopPrice = Math.Max(FxMinStop * pip,
                    Math.Max(atrPrice * FxStopAtr, spreadPrice * 3));
                if (stopPrice / pip > FxMaxStop) return null;
                costPrice = spreadPrice + FxReserveCost * pip;
                rr = FxRr;
            }
            double costRatio = costPrice / stopPrice;
            if (costRatio > MaxCostToStop || spreadPrice / atrPrice > MaxSpreadToAtr)
                return null;
            if (b.HighPrices.Last(1) - b.LowPrices.Last(1) > MaxCandleToAtr * atrPrice)
                return null;

            bool m5Up = market.M5Fast.Result.Last(1) > market.M5Slow.Result.Last(1) &&
                market.M5Fast.Result.Last(1) > market.M5Fast.Result.Last(2);
            bool m5Down = market.M5Fast.Result.Last(1) < market.M5Slow.Result.Last(1) &&
                market.M5Fast.Result.Last(1) < market.M5Fast.Result.Last(2);
            double fast = market.M1Fast.Result.Last(1);
            double slow = market.M1Slow.Result.Last(1);
            double close = b.ClosePrices.Last(1);
            double open = b.OpenPrices.Last(1);
            double rsi = market.Rsi.Result.Last(1);
            double volMean = 0;
            for (int i = 2; i < VolumeLookback + 2; i++)
                volMean += b.TickVolumes.Last(i);
            volMean /= VolumeLookback;
            if (volMean <= 0) return null;
            double volume = b.TickVolumes.Last(1) / volMean;
            if (volume < MinVolumeRatio) return null;

            bool up = fast > slow;
            bool down = fast < slow;
            bool buyRsi = rsi >= 52 && rsi <= 72;
            bool sellRsi = rsi >= 28 && rsi <= 48;
            bool boost = volume >= 1.2;
            bool trendStrong = Math.Abs(fast - slow) >= 0.25 * atrPrice;
            double buyScore = (m5Up ? 1.25 : 0) + (up ? 1 : 0) +
                (buyRsi ? 0.75 : 0) + (close > b.HighPrices.Last(2) ? 1 : 0) +
                (close > open && close > fast ? 0.5 : 0) +
                (boost ? 0.5 : 0) + (trendStrong ? 0.25 : 0);
            double sellScore = (m5Down ? 1.25 : 0) + (down ? 1 : 0) +
                (sellRsi ? 0.75 : 0) + (close < b.LowPrices.Last(2) ? 1 : 0) +
                (close < open && close < fast ? 0.5 : 0) +
                (boost ? 0.5 : 0) + (trendStrong ? 0.25 : 0);

            TradeType side;
            double score;
            if (m5Up && up && buyRsi && buyScore >= MinSignalScore && buyScore > sellScore)
            {
                side = TradeType.Buy;
                score = buyScore;
            }
            else if (m5Down && down && sellRsi && sellScore >= MinSignalScore && sellScore > buyScore)
            {
                side = TradeType.Sell;
                score = sellScore;
            }
            else
                return null;

            // Relative ranking is heuristic, not predicted expected return.
            double quality = score - costRatio * 2 + 0.1 * Math.Min(2, volume - 1);
            return new Signal
            {
                Market = market,
                Side = side,
                Score = score,
                Quality = quality,
                StopPips = stopPrice / pip,
                RiskReward = rr,
                CostToStop = costRatio
            };
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

        private void ExecuteSignal(Signal c)
        {
            if (RiskBlocked() || OpenBotPositions().Length > 0) return;
            var s = c.Market.Symbol;
            if (!s.MarketHours.IsOpened()) return;
            double currentSpread = (s.Ask - s.Bid) / s.PipSize;
            double reserve = c.Market.Gold ? GoldReserveCost / s.PipSize : FxReserveCost;
            double costRatio = (currentSpread + reserve) / c.StopPips;
            bool spreadExceeded = c.Market.Gold
                ? currentSpread * s.PipSize > GoldMaxSpread
                : currentSpread > FxMaxSpread;
            if (currentSpread <= 0 || spreadExceeded || costRatio > MaxCostToStop)
            {
                Print("SAQR skip {0}: live spread/cost worsened before order.", s.Name);
                return;
            }

            // Account for quoted spread and estimated extra round trip costs in position sizing.
            // Broker actual slippage/fees and gaps can exceed the intended risk budget.
            double adjustedRiskPct = RiskPercent * c.StopPips /
                (c.StopPips + currentSpread + reserve);
            double units = s.VolumeForProportionalRisk(
                ProportionalAmountType.Equity, adjustedRiskPct, c.StopPips, RoundingMode.Down);
            units = s.NormalizeVolumeInUnits(units, RoundingMode.Down);
            if (units < s.VolumeInUnitsMin)
            {
                Print("SAQR skip {0}: calculated volume={1} below min={2}; NOT increasing risk to force a trade.",
                    s.Name, units, s.VolumeInUnitsMin);
                return;
            }
            if (units > s.VolumeInUnitsMax) units = s.VolumeInUnitsMax;

            double quote = c.Side == TradeType.Buy ? s.Ask : s.Bid;
            var result = ExecuteMarketOrder(c.Side, s.Name, units, BotLabel,
                c.StopPips, c.StopPips * c.RiskReward);
            if (!result.IsSuccessful || result.Position == null)
            {
                Print("SAQR ORDER FAILED {0} {1} error={2}", s.Name, c.Side, result.Error);
                return;
            }
            var p = result.Position;
            _lastEntry = Server.Time;
            _entriesToday++;
            _audits[p.Id] = new TradeAudit
            {
                InitialStopPips = c.StopPips,
                RiskReward = c.RiskReward,
                SpreadAtEntryPips = currentSpread,
                ExtraEstimatedPips = reserve,
                SignalScore = c.Score
            };
            double slippagePips = (p.EntryPrice - quote) / s.PipSize *
                (c.Side == TradeType.Buy ? 1.0 : -1.0);
            Print("SAQR OPEN id={0} symbol={1} side={2} units={3} score={4:F2} spread={5:F2}p reserveEST={6:F2}p SL={7:F2}p TP={8:F2}p adverseSlip={9:F2}p",
                p.Id, s.Name, c.Side, units, c.Score,
                currentSpread, reserve, c.StopPips, c.StopPips * c.RiskReward, slippagePips);
        }

        private void MaintainOpenPositions()
        {
            foreach (var p in OpenBotPositions())
            {
                var market = _markets.FirstOrDefault(m => m.Symbol.Name == p.SymbolName);
                if (market == null) continue;
                Symbol s = market.Symbol;
                int duration = market.Gold ? GoldMaxMinutes : FxMaxMinutes;
                if ((Server.Time - p.EntryTime).TotalMinutes >= duration)
                {
                    _manualExitReasons[p.Id] = "TIME_LIMIT";
                    var close = ClosePosition(p);
                    if (!close.IsSuccessful)
                    {
                        _manualExitReasons.Remove(p.Id);
                        Print("SAQR TIME CLOSE FAILED id={0} error={1}", p.Id, close.Error);
                    }
                    continue;
                }
                TradeAudit audit;
                double originalStop = 0;
                if (_audits.TryGetValue(p.Id, out audit))
                    originalStop = audit.InitialStopPips;
                else if (p.TakeProfit.HasValue)
                {
                    double rr = market.Gold ? GoldRr : FxRr;
                    originalStop = Math.Abs(p.TakeProfit.Value - p.EntryPrice) / s.PipSize / rr;
                }
                if (originalStop <= 0 || p.Pips < originalStop * BreakEvenAtR) continue;

                double delta = originalStop * BreakEvenLockR * s.PipSize;
                double stopPrice = p.EntryPrice + (p.TradeType == TradeType.Buy ? 1 : -1) * delta;
                bool improved = !p.StopLoss.HasValue ||
                    (p.TradeType == TradeType.Buy && stopPrice - p.StopLoss.Value > 0.25 * s.PipSize) ||
                    (p.TradeType == TradeType.Sell && p.StopLoss.Value - stopPrice > 0.25 * s.PipSize);
                if (!improved) continue;
                if (p.TradeType == TradeType.Buy && s.Bid - stopPrice < 2 * s.PipSize) continue;
                if (p.TradeType == TradeType.Sell && stopPrice - s.Ask < 2 * s.PipSize) continue;

                var mod = p.ModifyStopLossPrice(Math.Round(stopPrice, s.Digits));
                if (mod.IsSuccessful)
                    Print("SAQR BREAK EVEN stop adjusted id={0} symbol={1} newStop={2}",
                        p.Id, p.SymbolName, stopPrice);
            }
        }

        private void Closed(PositionClosedEventArgs e)
        {
            Position p = e.Position;
            if (p.Label != BotLabel || !_markets.Any(m => m.Symbol.Name == p.SymbolName))
                return;
            if (Server.Time.Date == _utcDay)
            {
                _closedToday++;
                _botNetToday += p.NetProfit;
                if (p.NetProfit > 0)
                {
                    _winsToday++;
                    _positiveToday += p.NetProfit;
                    _consecutiveLosses = 0;
                }
                else if (p.NetProfit < 0)
                {
                    _negativeToday += -p.NetProfit;
                    _consecutiveLosses++;
                }
            }
            string reason = e.Reason.ToString();
            string requested;
            if (_manualExitReasons.TryGetValue(p.Id, out requested))
            {
                reason += "+BOT_" + requested;
                _manualExitReasons.Remove(p.Id);
            }
            TradeAudit audit;
            if (_audits.TryGetValue(p.Id, out audit))
            {
                Print("SAQR CLOSE id={0} {1} actualReason={2} durationSec={3:F0} spreadEntry={4:F2}p costReserveEST={5:F2}p stopInitial={6:F2}p score={7:F2} gross={8:F2} commission={9:F2} swap={10:F2} NET={11:F2} pips={12:F1}",
                    p.Id, p.SymbolName, reason, (Server.Time - p.EntryTime).TotalSeconds,
                    audit.SpreadAtEntryPips, audit.ExtraEstimatedPips, audit.InitialStopPips,
                    audit.SignalScore, p.GrossProfit, p.Commissions, p.Swap, p.NetProfit, p.Pips);
                _audits.Remove(p.Id);
            }
            else
                Print("SAQR CLOSE id={0} {1} reason={2} entryAuditUnavailable=restart NET={3:F2} commission={4:F2} pips={5:F1}",
                    p.Id, p.SymbolName, reason, p.NetProfit, p.Commissions, p.Pips);
            if (_closedToday % 5 == 0)
                Print("SAQR SUMMARY closed={0}, wins={1}, net={2:F2}, profitFactor={3:F2}, consecutiveLosses={4}; not a profitability prediction.",
                    _closedToday, _winsToday, _botNetToday,
                    _negativeToday > 0 ? _positiveToday / _negativeToday : 0.0,
                    _consecutiveLosses);
            RiskBlocked();
        }

        protected override void OnError(Error error)
        {
            Print("SAQR cTrader error {0}", error.Code);
        }

        protected override void OnStop()
        {
            Positions.Closed -= Closed;
            Timer.Stop();
            Print("SAQR stopped.");
        }
    }
}
