# MATH EDGE GOLD AI V1 — كود مصدر ومشروع cTrader

**حالة النسخة:** مصدر جاهز للبناء التجريبي، **لم يُجمّع ملف `.algo` ولم يُختبر داخل cTrader بعد**. لا تفترض جاهزيته للاستخدام قبل نجاح التجميع والاختبارات. هذا برنامج تداول تجريبي وليس ضمانًا للربح أو لتحديد خسارة لا يمكن تجاوزها في حالات الفجوات والانزلاق.

## المتطلبات

- cTrader الحديث يدعم cBots C# على حساب **Demo**.
- .NET 8 SDK (عند البناء الخارجي) واتصال للحصول على حزمة NuGet `cTrader.Automate` إصدار `1.0.21`.
- حساب تجريبي **USD** ورصيده/Equity قرب **300 USD** (انحراف افتراضي مسموح ±15%).
- رسم بياني لرمز **XAUUSD** أو رمز GOLD المسمى بهذا الشكل لدى الوسيط.
- وسيط يتيح حجم عقد أصغر أو يساوي حجم العقد المحسوب للمخاطرة؛ وإلا لا تُفتح الصفقة.

## طريقة التشغيل 1: ملف المصدر من cTrader Desktop

1. افتح `cTrader > Algo > cBots > New` وأنشئ بوت C# باسم `MATH_EDGE_GOLD_AI_V1`.
2. استبدل كود البوت بالكامل بمحتوى `src/MATH_EDGE_GOLD_AI_V1.cs` (ملف واحد يحوي كل الأصناف).
3. اضغط `Build`، وعالج أي فروق إصدار تظهر من سجل البناء. **لا تتجاهل أي خطأ**.
4. افتح حسابك التجريبي بالدولار، وضع البوت على **XAUUSD**.
5. تأكد من ضبط رصيد الحساب التجريبي حول 300 دولار؛ لا تغير المرجع إلى 10,000 بهدف تمرير ضوابط المخاطرة.
6. اختر `Enable DEMO orders = false` أولًا، ثم راجع السجلات وتشغيل المؤشرات.
7. اختبر Backtesting على بيانات Tick عند توافرها، ثم Forward Testing تجريبي دون أموال حقيقية.
8. بعد مراجعة البناء والاختبار، غيّر `Enable DEMO orders = true` للاختبار التنفيذي التجريبي فقط.
9. لتصدير `.algo` استخدم خيار **Export with source** من cTrader.

## طريقة التشغيل 2: بناء المشروع مباشرة

### Windows
شغّل `scripts\build.bat` بعد تثبيت .NET 8 SDK.

### macOS / Linux
شغّل `bash scripts/build.sh` بعد تثبيت .NET 8 SDK.

من سطر الأوامر مباشرة:

```bash
dotnet restore MATH_EDGE_GOLD_AI_V1.csproj
dotnet build MATH_EDGE_GOLD_AI_V1.csproj -c Release -p:IncludeSource=True
```

إذا نجح التجميع وتوليد `.algo`، سينسخه السكربت إلى جذر المشروع. **لا تضع ملفًا مزيفًا باسم `.algo`** عند فشل البناء.

### خيار GitHub Actions
يمكن رفع المشروع إلى مستودع GitHub خاص ثم تشغيل workflow `Build cTrader ALGO (manual)` من تبويب Actions. سيحاول تجميع `.algo` على Windows في السحابة ويرفعه كـ Artifact. لا يعني وجود workflow نجاح البناء قبل تشغيله.

## الإعدادات الافتراضية

| المعامل | القيمة |
|---|---|
| Reference capital USD | 300 |
| Risk per trade % | 1.0 **سقف مخاطرة نظري** |
| Risk sizing buffer | 80% من السقف المحسوب، أي حوالي 2.40 دولار عند Equity 300 |
| Enable DEMO orders | false، يحتاج تفعيلًا صريحًا |
| Daily loss stop | 1.5% من قيمة بداية يوم UTC |
| Weekly loss stop | 4% من بداية أسبوع UTC |
| Overall peak drawdown | 8% من أعلى Equity يتذكره المثيل |
| Consecutive losses | 3 => قفل حتى مراجعة يدوية |
| Timeframe | M5؛ M1 لتأكيد الدخول؛ M15/H1/H4 للاتجاه |
| Learning | تكيف إحصائي خفيف، لا يستخدم API خارجيًا |
| News Times UTC | فارغ: **لا توجد أخبار تلقائية**، أدرج المواعيد يدويًا |

*الحد الأعلى 1% يتعلق بالتقدير قبل إرسال الأمر، وليس حد خسارة مضمونًا: قد تتجاوز الخسارة ذلك عند الانزلاق، والفجوات، والعمولات. مقدار المخاطرة الفعلي يتوقف على رموز الوسيط والقيمة النقدية للنقطة والحجم الأدنى للعقود.*

## ما الذي يفعله الإصدار V1؟

- يرفض الحسابات الحقيقية (`Account.IsLive`).
- يحلل M1/M5/M15/H1/H4 دون النظر إلى الشموع المستقبلية، مع مراعاة الشمعة الأخيرة المكتملة.
- يبحث عن 3 نماذج: Trend Pullback، Momentum Breakout، Mean Reversion.
- يحدد ظروف السوق عبر ADX، ويستخدم EMA / RSI / ATR / مستويات الاختراق.
- يخصص حجم الصفقة عبر `VolumeForFixedRisk` ثم يتحقق من `AmountRisked` والهامش، ويرفض الأحجام الأعلى من الميزانية.
- يحتفظ بإحصاءات التعلم على مستوى الاستراتيجية وحالة السوق ويعدل درجات الإشارات **ضمن نطاق ضيق** بعد 12 صفقة مغلقة في المجموعة نفسها؛ تحديثه من صافي أرباح وخسائر الصفقات المغلقة فقط.
- يضبط وقف خسارة وهدفًا عند فتح الصفقة، ويحاول الوصول إلى نقطة التعادل وإدارة الوقف الزاحف.
- يمنع فتح مركز ثانٍ في الرمز نفسه، ويمنع المضاعفات وGrid.
- يوقف الإدخالات الجديدة عند حدود المخاطرة حتى مراجعة حالة الأمان.
- يسمح بإدخال مواعيد أخبار UTC يدويًا بشكل: `2026-11-12 13:30,2026-12-16 19:00` **(أمثلة تنسيق لا تمثل مواعيد أخبار مؤكدة)**.

## القيود بصراحة

1. **هذا ليس نموذجًا عصبيًا أو ذكاء اصطناعي يتنبأ بسعر الذهب**؛ هو نظام *تعلم تكيفي إحصائي* محدود آمن نسبيًا على مستوى أوزان الاستراتيجيات. يحتاج بيانات تداول لاحقة ليتعلم.
2. البيانات التاريخية الفعلية ونتائج الأرباح والاختبار الأمامي **غير متاحة** في بيئة إنشاء المشروع. لا توجد إحصاءات أداء حقيقية حاليًا.
3. الفلترة الإخبارية تتطلب أوقاتًا تدخلها يدويًا؛ لا توجد خدمة تقويم حيّة أو اتصال بالوسيط الآن.
4. حدود خسارة اليوم/الأسبوع تعتمد على رصيد Equity عند بدء الجلسة وتخزين محلي لمثيل البوت؛ لا تمثل مراقبًا مستقلًا لكل حساب أو كل روبوتات الحساب.
5. `LocalStorageScope.Instance` يُحفظ محليًا لكل مثيل؛ وفي cTrader Cloud قد لا يستمر التخزين بين التشغيلات. استخدم جهازًا محليًا إن أردت استمرار التعلم.
6. قد تكون أحجام تداول الذهب الدنيا لدى بعض الوسطاء كبيرة جدًا لحساب 300 دولار؛ في هذه الحالة **الصواب هو عدم التداول**.
7. لا يوجد ضمان ربح ولا ضمان لسقف خسارة أثناء الانزلاق والفجوات، ولا تتوفر أوامر وقف حماية مطلقة.

## خطة الاختبار قبل قبول V1

- [ ] Compile succeeds with `0 errors` and produces a usable `.algo`.
- [ ] Run on Demo USD $300 and XAUUSD symbol.
- [ ] Reject account IsLive and symbols other than XAUUSD/GOLDUSD/GOLD.
- [ ] Reject min contract size when its theoretical risk exceeds the budget.
- [ ] Confirm orders have valid stop loss and take profit.
- [ ] Simulate bad quotes/spread spikes, no historical data, errors from broker, disconnects.
- [ ] Backtest at least multiple market regimes with fees and spread sensitivity.
- [ ] Conduct chronological walk-forward testing and demo forward test.
- [ ] Test restart/recovery and daily/weekly hard lock semantics.
- [ ] Compare learner enabled vs learner disabled out of sample.

## مصادر API الرسمية

- [cTrader Automate build](https://help.ctrader.com/ctrader-algo/documentation/all-algos/compiling/)
- [cTrader cBots creation](https://help.ctrader.com/ctrader-algo/creating-and-running-a-cbot/)
- [Risk sizing](https://help.ctrader.com/ctrader-algo/references/MarketData/Symbols/Symbol/VolumeForFixedRisk/)
- [Local Storage](https://help.ctrader.com/ctrader-algo/guides/local-storage/)
- [Position protection modifications](https://help.ctrader.com/ctrader-algo/references/General/Robot/ModifyPosition/)

## استكشاف الأخطاء

- No `.algo`: تأكد من تنزيل .NET SDK وليس Runtime فقط، وأن NuGet متاح، وافحص سجل `dotnet build`.
- Too many no-trade signals: اطلع على سجل الرفض، وتأكد من بيانات M15/H1/H4 الكافية وأن الحساب متوافق مع المرجع، وتحقق من الحد الأدنى للحجم والسبريد.
- Risk locked: لا تحذف السجلات لإعادة التداول تلقائيًا؛ راجع سبب الحد ثم استخدم مثيلًا تجريبيًا جديدًا ضمن اختبار آمن.
- Cloud: لا تعتمد على استمرار LocalStorage للبيانات عبر التشغيلات السحابية.