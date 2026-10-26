using System;
using cAlgo.Robots;
using static cAlgo.Robots.MathEdgeRiskMath;

int passed = 0;

void Check(bool good, string name)
{
    if (!good)
        throw new InvalidOperationException("FAILED: " + name);
    Console.WriteLine("PASS " + name);
    passed++;
}

Check(Classify("EURUSD") == MarketCategory.Forex, "EURUSD classified as FX");
Check(Classify("GBPJPY.pro") == MarketCategory.Forex, "broker suffix FX classification");
Check(Classify("XAUUSD") == MarketCategory.Metal, "gold market");
Check(Classify("SILVER") == MarketCategory.Metal, "silver market");
Check(Classify("BTCUSD") == MarketCategory.Crypto, "bitcoin market");
Check(Classify("ETHUSD") == MarketCategory.Crypto, "ethereum market");
Check(Classify("NAS100") == MarketCategory.Index, "Nasdaq index market");
Check(Classify("US30.cash") == MarketCategory.Index, "Dow index market");
Check(Classify("SLV") == MarketCategory.Other, "unlisted ETF not misclassified");

Check(ProfileRiskPercent(MarketCategory.Forex, 0.5) == 0.35, "forex risk cap");
Check(ProfileRiskPercent(MarketCategory.Metal, 0.5) == 0.25, "metal risk cap");
Check(ProfileRiskPercent(MarketCategory.Index, 0.5) == 0.25, "index risk cap");
Check(ProfileRiskPercent(MarketCategory.Crypto, 0.5) == 0.15, "crypto risk cap");
Check(ProfileRiskPercent(MarketCategory.Forex, 0.1) == 0.1, "lower manual risk preserved");
Check(ProfileDailyLossPercent(MarketCategory.Crypto, 2) == 0.75, "crypto daily symbol cap");

Check(ProfileSpreadCapPips(MarketCategory.Forex, 0) == 3.0, "FX absolute auto spread cap");
Check(double.IsPositiveInfinity(ProfileSpreadCapPips(MarketCategory.Metal, 0)), "metals use relative spread filter by default");
Check(ProfileSpreadCapPips(MarketCategory.Metal, 17.0) == 17.0, "custom absolute spread cap enforced");
Check(SpreadAcceptable(2, 10, 0.3, double.PositiveInfinity), "relative spread filter accepts 20%");
Check(!SpreadAcceptable(4, 10, 0.3, double.PositiveInfinity), "relative spread filter blocks 40%");
Check(!SpreadAcceptable(3.1, 25, 0.3, 3), "absolute forex spread filter");
Check(!SpreadAcceptable(double.NaN, 10, 0.3, 3), "NaN spread rejected");
Check(!SpreadAcceptable(0, 10, 0.3, 3), "zero spread guarded");
Check(!SpreadAcceptable(1, 0, 0.3, 3), "zero ATR guarded");

DateTime atReset = new DateTime(2026, 10, 26, 13, 59, 0, DateTimeKind.Utc);
Check(VwapSessionDate(atReset, 14) == new DateTime(2026, 10, 25), "reset UTC hour before boundary");
Check(VwapSessionDate(atReset.AddMinutes(1), 14) == new DateTime(2026, 10, 26), "reset UTC hour after boundary");
Check(VwapSessionDate(atReset, 0) == new DateTime(2026, 10, 26), "midnight UTC session");

Check(ConfirmedBuyCross(99, 100, 101, 100, 102), "confirmed EMA crossing up");
Check(!ConfirmedBuyCross(99, 100, 101, 100, 100.5), "buy confirmation requires close over EMA");
Check(ConfirmedSellCross(101, 100, 99, 100, 98), "confirmed EMA crossing down");
Check(!ConfirmedSellCross(101, 100, 99, 100, 99.5), "sell confirmation requires close below EMA");

Check(ShouldExitOnEmaClose(true, 99, 100), "buy exits below EMA");
Check(!ShouldExitOnEmaClose(true, 101, 100), "buy holds above EMA");
Check(ShouldExitOnEmaClose(false, 101, 100), "sell exits above EMA");
Check(!ShouldExitOnEmaClose(false, 99, 100), "sell holds below EMA");

Check(DailyLossReached(-70, -85, 10000, 1.5), "aggregated losses reach portfolio stop");
Check(!DailyLossReached(-70, -75, 10000, 1.5), "loss below stop does not block");
Check(DailyLossReached(-150, 0, 10000, 1.5), "realized only triggers loss stop");

// Regression: real-world small-equity gold case (~$277 equity, minimum one ounce).
// 0.25% nominal risk is only ~0.69 USD, BEFORE 15% risk reserve.
// A broker's 1-ounce minimum could risk multiple dollars => must reject.
double smallAccountBudget = CashRiskAllowance(277.34, 0.25, 100.0, 0.85);
Check(smallAccountBudget > 0.58 && smallAccountBudget < 0.60,
    "small-account 0.25% risk budget with reserve");
Check(!EstimatedLossWithinBudget(3.31, smallAccountBudget),
    "broker 1 oz minimum must be blocked if it risks $3.31");
Check(!EstimatedLossWithinBudget(1.10, smallAccountBudget),
    "even $1.10 minimum is too large for small-account target");
Check(EstimatedLossWithinBudget(0.42, smallAccountBudget),
    "sufficiently small trade can pass the risk ceiling");
Check(!EstimatedLossWithinBudget(double.NaN, smallAccountBudget),
    "unavailable estimated loss must block the order");
Check(!EstimatedLossWithinBudget(0.42, 0),
    "exhausted cash budget must block all new orders");
Check(RemainingDailyCash(277.34, 1.0, -6.18) == 0,
    "daily loss already exceeded blocks further trading");
Check(RemainingDailyCash(1000, 1.5, -10) == 5,
    "remaining portfolio allowance reflects existing losses");
Check(CashRiskAllowance(1000, 0.25, 1, 0.85) == 0.85,
    "smaller remaining daily allowance overrides per-trade budget");
Check(CashRiskAllowance(1000, 0.25, 5, 0.85) == 2.125,
    "per-trade allowance dominates when daily remaining is large");
Check(CashRiskAllowance(1000, 0.25, 5, 1.5) == 0,
    "invalid utilization ratio fails closed");

Console.WriteLine($"ALL {passed} checks passed");
