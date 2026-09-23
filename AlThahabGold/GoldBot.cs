using System;
using System.Collections.Generic;
using System.Linq;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    // Research / DEMO tool. Trade signals have not been shown to be profitable.
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class الذهب : Robot
    {
        [Parameter("Demo ONLY (stop on live)", DefaultValue = true)]
        public bool DemoOnly { get; set; }

        [Parameter("Bot Label", DefaultValue = "AlThahab-Gold")]
        public string BotLabel { get; set; }

        [Parameter("Risk % per Trade", DefaultValue = 0.5, MinValue = 0.01, MaxValue = 2.0)]
        public double RiskPercent { get; set; }

        [Parameter("Max Trades / Day", DefaultValue = 15, MinValue = 1, MaxValue = 60)]
        public int MaxTrades { get; set; }

        [Parameter("Max Daily Loss %", DefaultValue = 2.0, MinValue = 0.5, MaxValue = 10.0)]
        public double MaxDailyLossPercent { get; set; }

        [Parameter("Stop after N Losses", DefaultValue = 3, MinValue = 1, MaxValue = 10)]
        public int MaxLosingStreak { get; set; }

        [Parameter("Cooldown Seconds", DefaultValue = 120, MinValue = 0, MaxValue = 1800)]
        public int CooldownSeconds { get; set; }

        [Parameter("Session Start UTC", DefaultValue = 7, MinValue = 0, MaxValue = 23)]
        public int StartHourUTC { get; set; }

        [Parameter("Session End UTC", DefaultValue = 17, MinValue = 1, MaxValue = 24)]
        public int EndHourUTC { get; set; }

        [Parameter("M1 Fast EMA", DefaultValue = 9, MinValue = 2)]
        public int FastPeriod { get; set; }

        [Parameter("M1 Slow EMA", DefaultValue = 21, MinValue = 3)]
        public int SlowPeriod { get; set; }

        [Parameter("M5 Fast EMA", DefaultValue = 20, MinValue = 2)]
        public int M5FastPeriod { get; set; }

        [Parameter("M5 Slow EMA", DefaultValue = 50, MinValue = 3)]
        public int M5SlowPeriod { get; set; }

        [Parameter("M1 RSI Period", DefaultValue = 7, MinValue = 2)]
        public int RsiPeriod { get; set; }

        [Parameter("M1 ATR Period", DefaultValue = 14, MinValue = 2)]
        public int AtrPeriod { get; set; }

        [Parameter("Tick Volume Lookback", DefaultValue = 20, MinValue = 5)]
        public int VolumeLookback { get; set; }

        [Parameter("Min Volume Ratio", DefaultValue = 1.05, MinValue = 0.25)]
        public double MinVolumeRatio { get; set; }

        [Parameter("Min Score", DefaultValue = 3.50, MinValue = 2.0, MaxValue = 5.0)]
        public double MinScore { get; set; }

        // GOLD quoted price units (USD per ounce) are translated into broker pips at order submission.
        [Parameter("Min M1 ATR (USD)", DefaultValue = 0.45, MinValue = 0.01)]
        public double MinAtrPrice { get; set; }

        [Parameter("Max M1 ATR (USD)", DefaultValue = 9.0, MinValue = 0.1)]
        public double MaxAtrPrice { get; set; }

        [Parameter("Gold Stop ATR Multiplier", DefaultValue = 1.8, MinValue = 0.2)]
        public double StopAtrMultiplier { get; set; }

        [Parameter("Minimum Stop (USD)", DefaultValue = 2.0, MinValue = 0.05)]
        public double MinStopPrice { get; set; }

        [Parameter("Maximum Stop (USD)", DefaultValue = 12.0, MinValue = 0.1)]
        public double MaxStopPrice { get; set; }

        [Parameter("Reward / Risk", DefaultValue = 1.4, MinValue = 0.5)]
        public double RewardRisk { get; set; }

        [Parameter("Max Spread (USD)", DefaultValue = 0.70, MinValue = 0.01)]
        public double MaxSpreadPrice { get; set; }

        // Estimated round-trip non-spread cost in GOLD quote USD, not an observed broker commission.
        [Parameter("Extra Cost (USD EST)", DefaultValue = 0.10, MinValue = 0.0)]
        public double ExtraCostPrice { get; set; }

        [Parameter("Max Estimated Cost / SL", DefaultValue = 0.22, MinValue = 0.01, MaxValue = 0.75)]
        public double MaxCostToStop { get; set; }

        [Parameter("Max Spread / ATR", DefaultValue = 0.45, MinValue = 0.02, MaxValue = 1.0)]
        public double MaxSpreadAtrRatio { get; set; }

        [Parameter("Max Candle Range / ATR", DefaultValue = 2.40, MinValue = 0.5, MaxValue = 10.0)]
        public double MaxCandleAtrRatio { get; set; }

        [Parameter("Break Even At (R)", DefaultValue = 1.0, MinValue = 0.2)]
        public double BreakEvenAtR { get; set; }

        [Parameter("Break Even Lock (R)", DefaultValue = 0.05, MinValue = 0)]
        public double BreakEvenLockR { get; set; }

        [Parameter("Time Exit (minutes)", DefaultValue = 12, MinValue = 1)]
        public int ExitAfterMinutes { get; set; }

        [Parameter("Diagnostic Logs", DefaultValue = true)]
        public bool DiagnosticLogs { get; set; }

        private ExponentialMovingAverage _fast;
        private ExponentialMovingAverage _slow;
        private ExponentialMovingAverage _m5fast;
        private ExponentialMovingAverage _m5slow;
        private RelativeStrengthIndex _rsi;
        private AverageTrueRange _atr;
        private Bars _m5bars;

        private DateTime _day;
        private DateTime _lastEntry = DateTime.MinValue;
        private int _tradesToday;
        private int _lossStreak;
        private int _closedToday;
        private int _winsToday;
        private double _realizedToday;
        private double _winDollars;
        private double _lossDollars;
        private double _startEquity;
        private bool _blocked;
        private int _barCount;
        private string _lastSkip = "";
        private int _lastLogBar = -100;

        private class EntryAudit
        {
            public double Spread;
            public double Quote;
            public double InitialStop;
            public double EstimatedExtra;
            public double Score;
            public string Trend;
        }

        private readonly Dictionary<int, EntryAudit> _audit = new Dictionary<int, EntryAudit>();
        private readonly Dictionary<int, string> _closeIntent = new Dictionary<int, string>();

        protected override void OnStart()
        {
            if (DemoOnly && Account.IsLive)
            {
                Print("SAFETY: الذهب is DEMO ONLY. Live account detected; refusing to trade.");
                Stop();
                return;
            }

            string goldSymbol = SymbolName.ToUpperInvariant();
            if (!goldSymbol.Contains("XAUUSD") && !goldSymbol.StartsWith("GOLD"))
            {
                Print("SETUP ERROR: Gold-only bot. Attach to the Fiper XAUUSD (or GOLD) symbol; found {0}.", SymbolName);
                Stop();
                return;
            }

            if (!TimeFrame.Equals(cAlgo.API.TimeFrame.Minute))
            {
                Print("SETUP ERROR: attach الذهب to XAUUSD M1; current timeframe is {0}.", TimeFrame);
                Stop();
                return;
            }

            if (FastPeriod >= SlowPeriod || M5FastPeriod >= M5SlowPeriod ||
                MinStopPrice > MaxStopPrice || MinAtrPrice >= MaxAtrPrice)
            {
                Print("SETUP ERROR: invalid EMA / stop / ATR ranges.");
                Stop();
                return;
            }

            _fast = Indicators.ExponentialMovingAverage(Bars.ClosePrices, FastPeriod);
            _slow = Indicators.ExponentialMovingAverage(Bars.ClosePrices, SlowPeriod);
            _rsi = Indicators.RelativeStrengthIndex(Bars.ClosePrices, RsiPeriod);
            _atr = Indicators.AverageTrueRange(AtrPeriod, MovingAverageType.Exponential);
            _m5bars = MarketData.GetBars(cAlgo.API.TimeFrame.Minute5, SymbolName);
            _m5fast = Indicators.ExponentialMovingAverage(_m5bars.ClosePrices, M5FastPeriod);
            _m5slow = Indicators.ExponentialMovingAverage(_m5bars.ClosePrices, M5SlowPeriod);

            _day = Server.Time.Date;
            RestoreToday();
            Positions.Closed += OnPositionClosed;
            Print("الذهب RUNNING | DEMO={0} | Symbol={1} M1 entries + M5 CLOSED-BAR trend | risk={2:F2}% | trades/day={3}",
                DemoOnly, SymbolName, RiskPercent, MaxTrades);
            Print("Gold costs in price USD: spread ceiling={0:F2} extra quote cost EST={1:F2}; max cost/stop={2:F2}. Confirm actual broker fees.",
                MaxSpreadPrice, ExtraCostPrice, MaxCostToStop);
            Print("Actual broker PipSize={0}, min volume units={1}. Do not force min lot.", Symbol.PipSize, Symbol.VolumeInUnitsMin);
            Print("Daily loss baseline inferred from current equity less today's bot realised and floating PnL; other account trades can affect equity.");
        }

        private void Skip(string message)
        {
            if (!DiagnosticLogs) return;
            if (_lastSkip != message || _barCount - _lastLogBar >= 5)
            {
                Print("WAIT: {0}", message);
                _lastSkip = message;
                _lastLogBar = _barCount;
            }
        }

        private void ResetDay()
        {
            if (Server.Time.Date == _day) return;
            _day = Server.Time.Date;
            _lastEntry = DateTime.MinValue;
            _blocked = false;
            RestoreToday();
            Print("NEW UTC DAY; recovered trading counters and estimated baseline.");
        }

        private void RestoreToday()
        {
            _tradesToday = 0;
            _lossStreak = 0;
            _closedToday = 0;
            _winsToday = 0;
            _realizedToday = 0;
            _winDollars = 0;
            _lossDollars = 0;

            var dailyEntries = History.FindAll(BotLabel, SymbolName)
                .Where(h => h.EntryTime.Date == _day)
                .GroupBy(h => h.PositionId)
                .OrderBy(g => g.Max(h => h.ClosingTime))
                .ToList();

            foreach (var group in dailyEntries)
            {
                double net = group.Sum(h => h.NetProfit);
                _tradesToday++;
                _closedToday++;
                _realizedToday += net;
                if (net < 0) { _lossStreak++; _lossDollars += -net; }
                else if (net > 0) { _winsToday++; _lossStreak = 0; _winDollars += net; }
                DateTime last = group.Max(h => h.EntryTime);
                if (last > _lastEntry) _lastEntry = last;
            }

            // Includes realised PnL of trades opened yesterday and closed today.
            // This is only a small approximation for positions that cross midnight;
            // the bot has a ten-minute time stop to limit such cases.
            var crossingDay = History.FindAll(BotLabel, SymbolName)
                .Where(h => h.EntryTime.Date != _day && h.ClosingTime.Date == _day)
                .ToList();
            _realizedToday += crossingDay.Sum(h => h.NetProfit);

            foreach (var p in Positions.FindAll(BotLabel, SymbolName))
                if (p.EntryTime.Date == _day)
                {
                    _tradesToday++;
                    if (p.EntryTime > _lastEntry) _lastEntry = p.EntryTime;
                }

            double floating = Positions.FindAll(BotLabel, SymbolName).Sum(p => p.NetProfit);
            _startEquity = Account.Equity - _realizedToday - floating;
            if (_startEquity <= 0) _startEquity = Account.Equity;

            if (_tradesToday >= MaxTrades || _lossStreak >= MaxLosingStreak)
                _blocked = true;
            Print("RECOVERED today opened={0}, closed={1}, win={2}, streak={3}, net={4:F2}, reference={5:F2}",
                _tradesToday, _closedToday, _winsToday, _lossStreak, _realizedToday, _startEquity);
        }

        private bool RiskBlocked()
        {
            if (_blocked) return true;
            double floatPnL = Positions.FindAll(BotLabel, SymbolName).Sum(p => p.NetProfit);
            double botDrawdown = -(_realizedToday + floatPnL);
            double equityDrawdown = _startEquity - Account.Equity;
            double allowed = Math.Max(1.0, _startEquity) * MaxDailyLossPercent / 100.0;

            if (_lossStreak >= MaxLosingStreak ||
                _tradesToday >= MaxTrades ||
                botDrawdown >= allowed ||
                equityDrawdown >= allowed)
            {
                _blocked = true;
                Print("DAY BLOCKED: trades={0}/{1}; lossStreak={2}/{3}; botLoss={4:F2}, acctEquityLoss={5:F2}, cap={6:F2}. No new orders.",
                    _tradesToday, MaxTrades, _lossStreak, MaxLosingStreak,
                    botDrawdown, equityDrawdown, allowed);
            }
            return _blocked;
        }

        protected override void OnBarClosed()
        {
            ResetDay();
            _barCount++;
            if (RiskBlocked()) { Skip("Daily risk/trade block."); return; }
            if (!Symbol.MarketHours.IsOpened()) { Skip("Market closed."); return; }

            int hour = Server.Time.Hour;
            bool inside = StartHourUTC < EndHourUTC
                ? hour >= StartHourUTC && hour < EndHourUTC
                : hour >= StartHourUTC || hour < EndHourUTC;
            if (!inside) { Skip("Outside configured trading session."); return; }

            if (Positions.FindAll(BotLabel, SymbolName).Length > 0)
            {
                Skip("Managing existing Gold position; no overlap.");
                return;
            }

            // Avoid overlapping with gold positions from other bots/manual trading on this account.
            bool otherGoldPosition = Positions.Any(p => p.SymbolName == SymbolName && p.Label != BotLabel);
            if (otherGoldPosition) { Skip("Another gold position is already open in the same account."); return; }
            if ((Server.Time - _lastEntry).TotalSeconds < CooldownSeconds) { Skip("Cooldown."); return; }

            if (Bars.Count < Math.Max(SlowPeriod, VolumeLookback) + 6 ||
                _m5bars.Count < M5SlowPeriod + 5)
            {
                Skip("Waiting for enough M1 and M5 historical candles.");
                return;
            }

            // All gold filters use PRICE distance in USD per oz instead of broker-defined pips.
            double spreadPrice = Symbol.Ask - Symbol.Bid;
            if (spreadPrice <= 0) { Skip("No valid gold bid/ask quote."); return; }
            if (spreadPrice > MaxSpreadPrice)
            {
                Skip(string.Format("Gold spread {0:F2} exceeds {1:F2} USD.", spreadPrice, MaxSpreadPrice));
                return;
            }

            double atrPrice = _atr.Result.Last(0);
            if (atrPrice < MinAtrPrice || atrPrice > MaxAtrPrice)
            {
                Skip(string.Format("Gold M1 ATR={0:F2}; permitted {1:F2}-{2:F2} USD.", atrPrice, MinAtrPrice, MaxAtrPrice));
                return;
            }
            if (spreadPrice / atrPrice > MaxSpreadAtrRatio)
            {
                Skip(string.Format("Gold spread-to-ATR ratio={0:F2}; limit={1:F2}.", spreadPrice / atrPrice, MaxSpreadAtrRatio));
                return;
            }
            double candleRange = Bars.HighPrices.Last(0) - Bars.LowPrices.Last(0);
            if (candleRange > atrPrice * MaxCandleAtrRatio)
            {
                Skip(string.Format("Abrupt M1 candle: range={0:F2} ATR={1:F2}.", candleRange, atrPrice));
                return;
            }

            double stopPrice = Math.Max(MinStopPrice, Math.Max(atrPrice * StopAtrMultiplier, spreadPrice * 4));
            if (stopPrice > MaxStopPrice)
            {
                Skip(string.Format("Required stop={0:F2} USD exceeds limit={1:F2}.", stopPrice, MaxStopPrice));
                return;
            }
            double costRatio = (spreadPrice + ExtraCostPrice) / stopPrice;
            if (costRatio > MaxCostToStop)
            {
                Skip(string.Format("Gold estimated cost/stop={0:F3}, limit={1:F3}, spread={2:F2} USD, SL={3:F2} USD.",
                    costRatio, MaxCostToStop, spreadPrice, stopPrice));
                return;
            }
            double stop = stopPrice / Symbol.PipSize;
            // M5 uses Last(1), the latest FULLY CLOSED five-minute candle.
            double trendFast = _m5fast.Result.Last(1);
            double trendSlow = _m5slow.Result.Last(1);
            double trendFastPrevious = _m5fast.Result.Last(2);
            bool m5Buy = trendFast > trendSlow && trendFast >= trendFastPrevious;
            bool m5Sell = trendFast < trendSlow && trendFast <= trendFastPrevious;

            double fast = _fast.Result.Last(0);
            double slow = _slow.Result.Last(0);
            double rsi = _rsi.Result.Last(0);
            double close = Bars.ClosePrices.Last(0);
            double open = Bars.OpenPrices.Last(0);
            double prevHigh = Bars.HighPrices.Last(1);
            double prevLow = Bars.LowPrices.Last(1);
            double volAvg = 0;
            for (int i = 1; i <= VolumeLookback; i++) volAvg += Bars.TickVolumes.Last(i);
            volAvg /= VolumeLookback;
            if (volAvg <= 0) { Skip("No tick-volume observations."); return; }
            double volRatio = Bars.TickVolumes.Last(0) / volAvg;
            if (volRatio < MinVolumeRatio)
            {
                Skip(string.Format("Low tick-volume ratio {0:F2} vs min {1:F2}.", volRatio, MinVolumeRatio));
                return;
            }

            bool upLtf = fast > slow;
            bool downLtf = fast < slow;
            bool buyRsi = rsi >= 52 && rsi <= 72;
            bool sellRsi = rsi >= 28 && rsi <= 48;
            bool breakoutBuy = close > prevHigh;
            bool breakoutSell = close < prevLow;
            bool impulseBuy = close > open && close > fast;
            bool impulseSell = close < open && close < fast;

            // Weighted evidence is a tunable heuristic, NOT a validated prediction.
            double buyScore = (m5Buy ? 1.25 : 0) + (upLtf ? 1.0 : 0) +
                (buyRsi ? 0.75 : 0) + (breakoutBuy ? 1.0 : 0) +
                (impulseBuy ? 0.5 : 0) + (volRatio >= 1.15 ? 0.5 : 0);
            double sellScore = (m5Sell ? 1.25 : 0) + (downLtf ? 1.0 : 0) +
                (sellRsi ? 0.75 : 0) + (breakoutSell ? 1.0 : 0) +
                (impulseSell ? 0.5 : 0) + (volRatio >= 1.15 ? 0.5 : 0);

            if (m5Buy && upLtf && buyRsi && buyScore >= MinScore && buyScore > sellScore)
                OpenTrade(TradeType.Buy, buyScore, stop, spreadPrice, "M5-UP");
            else if (m5Sell && downLtf && sellRsi && sellScore >= MinScore && sellScore > buyScore)
                OpenTrade(TradeType.Sell, sellScore, stop, spreadPrice, "M5-DOWN");
            else
                Skip(string.Format("No aligned M1/M5 signal: buy={0:F2}, sell={1:F2}, required={2:F2}, RSI={3:F1}, vol={4:F2}",
                    buyScore, sellScore, MinScore, rsi, volRatio));
        }

        private void OpenTrade(TradeType type, double score, double stopPips, double initialSpreadPrice, string trend)
        {
            if (RiskBlocked()) return;
            double spreadPrice = Symbol.Ask - Symbol.Bid;
            double stopPrice = stopPips * Symbol.PipSize;
            if (spreadPrice <= 0 || spreadPrice > MaxSpreadPrice || 
                (spreadPrice + ExtraCostPrice) / stopPrice > MaxCostToStop)
            {
                Skip("Gold quote/cost changed before execution.");
                return;
            }

            // Preserve a cost allowance inside the existing 0.5% equity risk budget.
            double effectiveRiskPct = RiskPercent * stopPrice / (stopPrice + spreadPrice + ExtraCostPrice);
            double units = Symbol.VolumeForProportionalRisk(
                ProportionalAmountType.Equity, effectiveRiskPct, stopPips, RoundingMode.Down);
            units = Symbol.NormalizeVolumeInUnits(units, RoundingMode.Down);
            if (units < Symbol.VolumeInUnitsMin)
            {
                Skip(string.Format("Trade below min volume for chosen risk: {0} < {1} units. No forced lot.",
                    units, Symbol.VolumeInUnitsMin));
                return;
            }
            if (units > Symbol.VolumeInUnitsMax) units = Symbol.VolumeInUnitsMax;

            double quote = type == TradeType.Buy ? Symbol.Ask : Symbol.Bid;
            var result = ExecuteMarketOrder(type, SymbolName, units, BotLabel, stopPips, stopPips * RewardRisk);
            if (!result.IsSuccessful || result.Position == null)
            {
                Print("ORDER_FAILED GOLD type={0} error={1}", type, result.Error);
                return;
            }

            _tradesToday++;
            _lastEntry = Server.Time;
            var p = result.Position;
            double adverseSlippage = (p.EntryPrice - quote) / Symbol.PipSize *
                (type == TradeType.Buy ? 1.0 : -1.0);
            _audit[p.Id] = new EntryAudit
            {
                Spread = spreadPrice, Quote = quote, InitialStop = stopPips,
                EstimatedExtra = ExtraCostPrice, Score = score, Trend = trend
            };
            Print("GOLD OPEN id={0} side={1} score={2:F2} M5trend={3} units={4} spreadUSD={5:F2} extraCostUSD_EST={6:F2} stopUSD={7:F2} targetUSD={8:F2} adverseSlippagePips={9:F2}",
                p.Id, type, score, trend, units, spreadPrice, ExtraCostPrice,
                stopPrice, stopPrice * RewardRisk, adverseSlippage);
        }

        protected override void OnTick()
        {
            ResetDay();
            RiskBlocked(); // Also block immediately if floating losses exceed limit.
            foreach (var p in Positions.FindAll(BotLabel, SymbolName))
            {
                if ((Server.Time - p.EntryTime).TotalMinutes >= ExitAfterMinutes)
                {
                    _closeIntent[p.Id] = "TIME_EXIT";
                    var closed = ClosePosition(p);
                    if (!closed.IsSuccessful)
                    {
                        _closeIntent.Remove(p.Id);
                        Print("TIME_EXIT_FAILED id={0} reason={1}", p.Id, closed.Error);
                    }
                    continue;
                }

                double originalStop;
                EntryAudit audit;
                if (_audit.TryGetValue(p.Id, out audit))
                    originalStop = audit.InitialStop;
                else if (p.TakeProfit.HasValue)
                    originalStop = Math.Abs(p.TakeProfit.Value - p.EntryPrice) / Symbol.PipSize / RewardRisk;
                else
                    originalStop = 0; // After restart, do not guess original stop.
                if (originalStop <= 0 || p.Pips < originalStop * BreakEvenAtR) continue;

                double newStop = p.EntryPrice + (p.TradeType == TradeType.Buy ? 1 : -1) *
                    originalStop * BreakEvenLockR * Symbol.PipSize;
                bool improved = !p.StopLoss.HasValue ||
                    (p.TradeType == TradeType.Buy && newStop - p.StopLoss.Value > 0.25 * Symbol.PipSize) ||
                    (p.TradeType == TradeType.Sell && p.StopLoss.Value - newStop > 0.25 * Symbol.PipSize);
                if (!improved) continue;

                // Do not spam invalid broker modifications when price is too close.
                if (p.TradeType == TradeType.Buy && Symbol.Bid - newStop < 1.0 * Symbol.PipSize) continue;
                if (p.TradeType == TradeType.Sell && newStop - Symbol.Ask < 1.0 * Symbol.PipSize) continue;
                var modified = p.ModifyStopLossPrice(Math.Round(newStop, Symbol.Digits));
                if (modified.IsSuccessful)
                    Print("MOVE_TO_BREAK_EVEN id={0} newSL={1:F5}", p.Id, newStop);
            }
        }

        private void OnPositionClosed(PositionClosedEventArgs args)
        {
            var p = args.Position;
            if (p.Label != BotLabel || p.SymbolName != SymbolName) return;
            if (Server.Time.Date == _day)
            {
                _realizedToday += p.NetProfit;
                _closedToday++;
                if (p.NetProfit > 0) { _winsToday++; _winDollars += p.NetProfit; _lossStreak = 0; }
                else if (p.NetProfit < 0) { _lossDollars += -p.NetProfit; _lossStreak++; }
            }

            string intent;
            string reason = args.Reason.ToString();
            if (_closeIntent.TryGetValue(p.Id, out intent))
            {
                reason += "+" + intent;
                _closeIntent.Remove(p.Id);
            }

            EntryAudit audit;
            if (_audit.TryGetValue(p.Id, out audit))
            {
                Print("GOLD CLOSED id={0} reason={1} durationSec={2:F0} entrySpreadUSD={3:F2} extraCostUSD_EST={4:F2} initialStopPips={5:F2} gross={6:F2} commissions={7:F2} swap={8:F2} net={9:F2} realisedPips={10:F1}",
                    p.Id, reason, (Server.Time - p.EntryTime).TotalSeconds,
                    audit.Spread, audit.EstimatedExtra, audit.InitialStop, p.GrossProfit,
                    p.Commissions, p.Swap, p.NetProfit, p.Pips);
                _audit.Remove(p.Id);
            }
            else
                Print("CLOSED id={0} reason={1} noEntryAuditAfterRestart gross={2:F2} commissions={3:F2} net={4:F2} pips={5:F1}",
                    p.Id, reason, p.GrossProfit, p.Commissions, p.NetProfit, p.Pips);

            if (_closedToday % 5 == 0)
                Print("SUMMARY {0} closed, {1} winners, net={2:F2}, realised profit factor={3:F2}, consecutive losses={4}. Diagnostic only, not proof of edge.",
                    _closedToday, _winsToday, _realizedToday,
                    _lossDollars > 0 ? _winDollars / _lossDollars : 0.0, _lossStreak);
            RiskBlocked();
        }

        protected override void OnError(Error error) { Print("BROKER_ERROR {0}", error.Code); }

        protected override void OnStop()
        {
            Positions.Closed -= OnPositionClosed;
            Print("الذهب stopped. Gold M1 research results require rigorous backtesting.");
        }
    }
}
