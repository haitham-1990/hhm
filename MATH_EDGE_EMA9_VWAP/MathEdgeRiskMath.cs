using System;

namespace cAlgo.Robots
{
    // Pure strategy/risk helpers: no trading API required, so these are independently testable.
    public static class MathEdgeRiskMath
    {
        public enum MarketCategory { Forex, Metal, Crypto, Index, Other }

        public static MarketCategory Classify(string symbolName)
        {
            string s = (symbolName ?? string.Empty).ToUpperInvariant();
            if (s.Contains("XAU") || s.Contains("XAG") || s.Contains("GOLD") || s.Contains("SILVER"))
                return MarketCategory.Metal;
            if (s.Contains("BTC") || s.Contains("ETH") || s.Contains("SOLUSD") || s.Contains("CRYPTO") ||
                s.Contains("XRP") || s.Contains("LTC"))
                return MarketCategory.Crypto;
            if (s.Contains("US30") || s.Contains("DJ30") || s.Contains("NAS") || s.Contains("USTEC") ||
                s.Contains("US100") || s.Contains("US500") || s.Contains("SPX") || s.Contains("GER40") ||
                s.Contains("DE40") || s.Contains("DAX") || s.Contains("UK100") || s.Contains("JP225") ||
                s.Contains("US2000") || s.Contains("AUS200"))
                return MarketCategory.Index;
            // Matches common currency pairs, including broker suffixes.
            string[] majors = { "EUR", "USD", "GBP", "JPY", "CHF", "AUD", "NZD", "CAD" };
            if (s.Length >= 6)
            {
                string a = s.Substring(0, 3);
                string b = s.Substring(3, 3);
                bool foundA = false;
                bool foundB = false;
                foreach (string m in majors)
                {
                    foundA |= a == m;
                    foundB |= b == m;
                }
                if (foundA && foundB && a != b)
                    return MarketCategory.Forex;
            }
            return MarketCategory.Other;
        }

        public static double ProfileRiskPercent(MarketCategory category, double requested)
        {
            double limit;
            switch (category)
            {
                case MarketCategory.Crypto: limit = 0.15; break;
                case MarketCategory.Metal:
                case MarketCategory.Index: limit = 0.25; break;
                default: limit = 0.35; break;
            }
            return Math.Min(requested, limit);
        }

        public static double ProfileDailyLossPercent(MarketCategory category, double requested)
        {
            double limit;
            switch (category)
            {
                case MarketCategory.Crypto: limit = 0.75; break;
                case MarketCategory.Metal:
                case MarketCategory.Index: limit = 1.0; break;
                default: limit = 1.5; break;
            }
            return Math.Min(requested, limit);
        }

        // A universal pip cap is meaningless for CFDs/metals/crypto because broker pip sizes vary.
        // The universal spread/ATR check remains mandatory for ALL categories.
        public static double ProfileSpreadCapPips(MarketCategory category, double customCapPips)
        {
            if (customCapPips > 0)
                return customCapPips;
            return category == MarketCategory.Forex ? 3.0 : double.PositiveInfinity;
        }

        public static bool SpreadAcceptable(double spreadPips, double atrPips,
            double maximumFraction, double absoluteMaxPips)
        {
            return IsFinitePositive(spreadPips) && IsFinitePositive(atrPips) &&
                IsFinitePositive(maximumFraction) && spreadPips <= atrPips * maximumFraction &&
                spreadPips <= absoluteMaxPips;
        }

        public static bool IsFinitePositive(double number)
        {
            return !double.IsNaN(number) && !double.IsInfinity(number) && number > 0;
        }

        public static DateTime VwapSessionDate(DateTime timeUtc, int resetHourUtc)
        {
            return timeUtc.AddHours(-resetHourUtc).Date;
        }

        public static bool ShouldExitOnEmaClose(bool positionIsBuy, double close, double ema)
        {
            return positionIsBuy ? close < ema : close > ema;
        }

        public static bool ConfirmedBuyCross(double lastEma, double lastVwap,
            double currentEma, double currentVwap, double currentClose)
        {
            return lastEma <= lastVwap && currentEma > currentVwap && currentClose > currentEma;
        }

        public static bool ConfirmedSellCross(double lastEma, double lastVwap,
            double currentEma, double currentVwap, double currentClose)
        {
            return lastEma >= lastVwap && currentEma < currentVwap && currentClose < currentEma;
        }

        // Do not allow broker minimum-volume rounding to silently exceed the cash risk cap.
        // Cash reserve accounts conservatively for spread, fees and imperfect estimates.
        public static double CashRiskAllowance(double equity, double perTradePercent,
            double cashRemainingByDailyRules, double utilisationFraction)
        {
            if (!IsFinitePositive(equity) || !IsFinitePositive(perTradePercent) ||
                !IsFinitePositive(utilisationFraction) || utilisationFraction > 1 ||
                !IsFinitePositive(cashRemainingByDailyRules))
                return 0.0;

            double tradeBudget = equity * perTradePercent / 100.0 * utilisationFraction;
            return Math.Max(0, Math.Min(tradeBudget, cashRemainingByDailyRules * utilisationFraction));
        }

        public static double RemainingDailyCash(double balanceReference,
            double maxLossPercent, double realizedPlusOpenProfit)
        {
            if (!IsFinitePositive(balanceReference) || !IsFinitePositive(maxLossPercent) ||
                double.IsNaN(realizedPlusOpenProfit) || double.IsInfinity(realizedPlusOpenProfit))
                return 0.0;

            return Math.Max(0.0,
                balanceReference * maxLossPercent / 100.0 + Math.Min(realizedPlusOpenProfit, 0.0));
        }

        public static bool EstimatedLossWithinBudget(double estimatedLossCash, double cashBudget)
        {
            return IsFinitePositive(estimatedLossCash) && IsFinitePositive(cashBudget) &&
                   estimatedLossCash <= cashBudget + 0.00000001;
        }

        public static bool DailyLossReached(double realized, double openPnl,
            double startingBalanceEstimate, double lossLimitPercent)
        {
            return realized + openPnl <= -Math.Max(startingBalanceEstimate, 1.0) * lossLimitPercent / 100.0;
        }
    }
}
