package com.khutwa.noorhelper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String NOOR_URL = "https://lms.moe.gov.om/teacher#networkfirst";
    private static final int REQUEST_PICK_PDF = 301;
    private static final int REQUEST_EXPORT_REPORT = 302;
    private static final String PREFS = "noor_helper";
    private static final String KEY_PDF_URI = "exercise_pdf_uri";
    private static final String KEY_AUTO_LAST_INDEX = "auto_last_index";
    private static final String KEY_AUTO_TARGET_INDEX = "auto_target_index";
    private static final String KEY_AUTO_PENDING_INDEX = "auto_pending_index";
    private static final String KEY_AUTO_ACTIVE = "auto_active";

    private WebView webView;
    private TextView status;
    private String currentTitle = "";
    private final List<String> currentOutcomes = new ArrayList<>();
    private LessonPreparation currentPreparation;
    private Grade9Curriculum.Lesson currentLesson;
    private AiPreparationClient aiClient;
    private NoorLearningRecorder learningRecorder;
    private Button learnButton;
    private String pendingReport = "";
    private volatile String lastLoadedUrl = "";
    private boolean guidedLearningWaiting = false;
    private String guidedFilledTitle = "";
    private Spinner autoTargetSpinner;
    private TextView autoProgress;
    private boolean autoActive = false;
    private boolean autoAwaitingSave = false;
    private int autoCurrentIndex = -1;
    private int autoTargetIndex = -1;
    private final List<Grade9Curriculum.Lesson> autoLessons = Grade9Curriculum.allLessons();

    private Uri exercisePdfUri;
    private final List<Bitmap> exerciseImages = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        aiClient = new AiPreparationClient(this);
        learningRecorder = new NoorLearningRecorder(this);
        buildUi();
        loadSavedPdf();
        restoreAutoUi();
        configureWebView();
        webView.loadUrl(NOOR_URL);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView title = new TextView(this);
        title.setText("مساعد نور - التاسع 0.6.0");
        title.setTextSize(18);
        title.setTextColor(Color.rgb(25, 25, 25));
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));

        Button clear = new Button(this);
        clear.setText("خروج");
        clear.setOnClickListener(v -> confirmClearSession());
        top.addView(clear, new LinearLayout.LayoutParams(dp(82), dp(44)));
        root.addView(top);

        status = new TextView(this);
        status.setText("اختر درس الصف التاسع في نور ثم اضغط «تجهيز كامل». التحضير الأساسي جاهز داخل التطبيق بدون توكنات.");
        status.setTextSize(13);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(dp(12), dp(4), dp(12), dp(6));
        root.addView(status);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(4), dp(3), dp(4), dp(2));

        Button scan = makeButton("فحص");
        scan.setOnClickListener(v -> scanLesson(true));
        actions.addView(scan, weightedButton());

        Button select = makeButton("كل الأهداف");
        select.setOnClickListener(v -> selectAllObjectives());
        actions.addView(select, weightedButton());

        Button prepare = makeButton("تجهيز كامل");
        prepare.setOnClickListener(v -> generatePreview());
        actions.addView(prepare, weightedButton());

        Button fill = makeButton("تعبئة");
        fill.setOnClickListener(v -> fillPreparation());
        actions.addView(fill, weightedButton());

        root.addView(actions);

        LinearLayout pdfActions = new LinearLayout(this);
        pdfActions.setOrientation(LinearLayout.HORIZONTAL);
        pdfActions.setPadding(dp(4), dp(1), dp(4), dp(6));

        Button choosePdf = makeButton("اختيار PDF التمارين");
        choosePdf.setOnClickListener(v -> pickPdf());
        pdfActions.addView(choosePdf, weightedButton());

        Button extractPdf = makeButton("تمارين هذا الدرس");
        extractPdf.setOnClickListener(v -> extractExercises(false));
        pdfActions.addView(extractPdf, weightedButton());

        Button aiImprove = makeButton("تحسين AI");
        aiImprove.setOnClickListener(v -> generateAiPreview());
        pdfActions.addView(aiImprove, weightedButton());

        root.addView(pdfActions);

        LinearLayout learningActions = new LinearLayout(this);
        learningActions.setOrientation(LinearLayout.HORIZONTAL);
        learningActions.setPadding(dp(4), dp(1), dp(4), dp(6));

        learnButton = makeButton(learningRecorder.isActive() ? "إيقاف التعلم" : "تعلم موجه");
        learnButton.setOnClickListener(v -> {
            if (learningRecorder.isActive()) stopNoorLearning();
            else startNoorLearning();
        });
        learningActions.addView(learnButton, weightedButton());

        Button testLearning = makeButton("اختبار التعلم");
        testLearning.setOnClickListener(v -> testNoorLearning());
        learningActions.addView(testLearning, weightedButton());

        Button exportLearning = makeButton("تقرير نور");
        exportLearning.setOnClickListener(v -> exportNoorReport());
        learningActions.addView(exportLearning, weightedButton());

        root.addView(learningActions);

        LinearLayout autoTargetRow = new LinearLayout(this);
        autoTargetRow.setOrientation(LinearLayout.HORIZONTAL);
        autoTargetRow.setGravity(Gravity.CENTER_VERTICAL);
        autoTargetRow.setPadding(dp(4), dp(2), dp(4), dp(2));

        TextView untilLabel = new TextView(this);
        untilLabel.setText("حضّر حتى:");
        untilLabel.setTextSize(13);
        untilLabel.setPadding(dp(6), 0, dp(6), 0);
        autoTargetRow.addView(untilLabel, new LinearLayout.LayoutParams(dp(82), dp(48)));

        autoTargetSpinner = new Spinner(this);
        List<String> lessonNames = new ArrayList<>();
        for (Grade9Curriculum.Lesson l : autoLessons) lessonNames.add(l.displayName());
        ArrayAdapter<String> lessonAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, lessonNames);
        lessonAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        autoTargetSpinner.setAdapter(lessonAdapter);
        autoTargetRow.addView(autoTargetSpinner, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(autoTargetRow);

        autoProgress = new TextView(this);
        autoProgress.setTextSize(12);
        autoProgress.setTextColor(Color.DKGRAY);
        autoProgress.setPadding(dp(12), dp(2), dp(12), dp(4));
        autoProgress.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(autoProgress);

        LinearLayout autoButtons = new LinearLayout(this);
        autoButtons.setOrientation(LinearLayout.HORIZONTAL);
        autoButtons.setPadding(dp(4), dp(1), dp(4), dp(6));

        Button autoStart = makeButton("ابدأ تلقائي");
        autoStart.setOnClickListener(v -> startAutoRun(false));
        autoButtons.addView(autoStart, weightedButton());

        Button autoResume = makeButton("متابعة");
        autoResume.setOnClickListener(v -> startAutoRun(true));
        autoButtons.addView(autoResume, weightedButton());

        Button autoStop = makeButton("إيقاف");
        autoStop.setOnClickListener(v -> stopAutoRun(false));
        autoButtons.addView(autoStop, weightedButton());

        root.addView(autoButtons);
        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new LearningBridge(), "KhutwaNoorBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                lastLoadedUrl = url == null ? "" : url;
                if (!autoActive) {
                    String pdf = exercisePdfUri == null ? "لا يوجد PDF مرتبط." : "PDF التمارين مرتبط: " + pdfName(exercisePdfUri);
                    status.setText("نور مفتوح. اختر درس الصف التاسع أو استخدم التشغيل التلقائي. " + pdf);
                }
                if (learningRecorder != null && learningRecorder.isActive() && isNoorUrl(url)) {
                    webView.postDelayed(() -> {
                        injectLearningScript();
                        if (guidedLearningWaiting) watchForGuidedLesson(0);
                    }, 650);
                }
                if (autoActive && isNoorUrl(url)) {
                    webView.postDelayed(() -> handleAutoPage(url), 850);
                }
            }
        });
    }

    private void loadSavedPdf() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PDF_URI, "");
        if (!saved.isEmpty()) {
            try {
                exercisePdfUri = Uri.parse(saved);
                status.setText("PDF التمارين مرتبط: " + pdfName(exercisePdfUri));
            } catch (Exception ignored) {
                exercisePdfUri = null;
            }
        }
    }

    private void pickPdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_PDF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_PDF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            exercisePdfUri = uri;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PDF_URI, uri.toString()).apply();
            status.setText("تم ربط ملف التمارين: " + pdfName(uri));
            toast("تم حفظ ملف التمارين لهذا التطبيق.");
            return;
        }

        if (requestCode == REQUEST_EXPORT_REPORT && resultCode == RESULT_OK
                && data != null && data.getData() != null && !pendingReport.isEmpty()) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null) {
                    out.write(pendingReport.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    toast("تم حفظ تقرير تعلم نور.");
                    status.setText("تم تصدير تقرير نور. يمكنك إرساله لي لفهم المنصة بدقة.");
                }
            } catch (Exception e) {
                toast("تعذر حفظ تقرير نور.");
            } finally {
                pendingReport = "";
            }
        }
    }

    private void startNoorLearning() {
        if (!isNoorPage()) {
            toast("افتح منصة نور أولاً.");
            return;
        }
        learningRecorder.startSession(webView.getUrl());
        guidedLearningWaiting = true;
        guidedFilledTitle = "";
        if (learnButton != null) learnButton.setText("إيقاف التعلم");
        injectLearningScript();
        status.setText("التعلم الموجه يعمل: اختر درسًا من شجرة نور فقط، وسأملأ محتوى التحضير تلقائيًا.");
        new AlertDialog.Builder(this)
                .setTitle("التعلم الموجه")
                .setMessage("1) اختر درسًا واحدًا من شجرة نور.\n\n"
                        + "2) التطبيق سيملأ تلقائيًا الأهداف والمستويات والاستراتيجيات والمصادر والمفاهيم والتهيئة والإجراءات والتقويمين والملاحظة الأسبوعية.\n\n"
                        + "3) بعد أن تظهر رسالة «تم تجهيز المحتوى»، نفّذ أنت فقط: تاريخ النشر ← أسبوع العمل ← تحديد الحصص ← تعميم التحضير ← حفظ.\n\n"
                        + "لا تحتاج أن تكتب شرحًا أو تعبئ حقول التحضير يدويًا.")
                .setPositiveButton("ابدأ", (d, w) -> watchForGuidedLesson(0))
                .show();
    }

    private void stopNoorLearning() {
        if (!learningRecorder.isActive()) return;
        if (isNoorPage()) {
            webView.evaluateJavascript("(function(){try{if(window.__khutwaNoorLearningStop)window.__khutwaNoorLearningStop();}catch(e){}return 'ok';})()", raw -> {});
        }
        learningRecorder.stopSession(webView.getUrl());
        guidedLearningWaiting = false;
        if (learnButton != null) learnButton.setText("تعلم موجه");
        status.setText("تم إيقاف تعلم نور. تم تسجيل " + learningRecorder.eventCount() + " حدثًا محليًا.");
        new AlertDialog.Builder(this)
                .setTitle("تم حفظ جلسة التعلم")
                .setMessage("تم تسجيل " + learningRecorder.eventCount() + " حدثًا. اضغط «تقرير نور» لحفظ ملف JSON وإرساله لي، أو «اختبار التعلم» لفحص العناصر التي تعلمها التطبيق.")
                .setPositiveButton("حسنًا", null)
                .show();
    }

    private void watchForGuidedLesson(int attempt) {
        if (!guidedLearningWaiting || learningRecorder == null || !learningRecorder.isActive() || !isNoorPage()) return;

        webView.evaluateJavascript(scanScript(), raw -> {
            try {
                JSONObject obj = new JSONObject(decodeJsString(raw));
                String title = obj.optString("title", "").trim();
                int editorCount = obj.optInt("editorCount", 0);

                currentOutcomes.clear();
                JSONArray arr = obj.optJSONArray("outcomes");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) currentOutcomes.add(arr.optString(i));
                }

                if (!title.isEmpty()) {
                    currentTitle = title;
                    currentLesson = Grade9Curriculum.find(title);
                    currentPreparation = Grade9PreparationBank.get(title);

                    if (currentLesson != null && currentPreparation != null && !title.equals(guidedFilledTitle)) {
                        if (editorCount < 4 && attempt < 20) {
                            status.setText("تعرفت على " + currentLesson.code + " — أنتظر حقول التحضير حتى تكتمل...");
                            webView.postDelayed(() -> watchForGuidedLesson(attempt + 1), 700);
                            return;
                        }
                        fillGuidedLearningContent(title, currentLesson, currentPreparation);
                        return;
                    }
                }
            } catch (Exception ignored) {}

            if (guidedLearningWaiting && attempt < 120) {
                status.setText("التعلم الموجه يعمل — اختر الدرس من شجرة نور.");
                webView.postDelayed(() -> watchForGuidedLesson(attempt + 1), 800);
            } else if (guidedLearningWaiting) {
                toast("لم أتعرف على درس من الصف التاسع. افتح إضافة تحضير واختر الدرس ثم اضغط «تعلم موجه» من جديد.");
            }
        });
    }

    private void fillGuidedLearningContent(String title, Grade9Curriculum.Lesson lesson, LessonPreparation prep) {
        guidedFilledTitle = title;
        status.setText("تعرفت على " + lesson.code + " — أملأ محتوى التحضير تلقائيًا...");
        webView.evaluateJavascript(guidedContentScript(prep, lesson), raw -> {
            int filled = 0;
            int objectives = 0;
            int checks = 0;
            int levels = 0;
            try {
                JSONObject result = new JSONObject(decodeJsString(raw));
                filled = result.optInt("editors", 0);
                objectives = result.optInt("objectives", 0);
                checks = result.optInt("checks", 0);
                levels = result.optInt("levels", 0);
            } catch (Exception ignored) {}

            if (filled < 4) {
                guidedFilledTitle = "";
                status.setText("بعض حقول التحضير لم تظهر بعد؛ سأعيد المحاولة...");
                webView.postDelayed(() -> watchForGuidedLesson(0), 900);
                return;
            }

            guidedLearningWaiting = false;
            status.setText("تم تجهيز محتوى " + lesson.code + ". الآن نفّذ يدويًا: التاريخ ← الأسبوع ← الحصص ← التعميم ← حفظ.");
            final String summary = "تمت تعبئة المحتوى تلقائيًا بدون توكنات:\n"
                    + "الأهداف: " + objectives
                    + "\nالاستراتيجيات/المصادر: " + checks
                    + "\nالمستويات: " + levels
                    + "\nحقول المحتوى: " + filled
                    + "\n\nالآن لا تكتب أي شرح. نفّذ يدويًا فقط:\n"
                    + "1. تاريخ النشر\n"
                    + "2. أسبوع العمل\n"
                    + "3. انتظر ظهور الحصص وحددها\n"
                    + "4. تعميم التحضير على كافة الجداول\n"
                    + "5. حفظ\n\n"
                    + "بعد نجاح الحفظ اضغط «إيقاف التعلم»، ثم «تقرير نور».";
            new AlertDialog.Builder(this)
                    .setTitle("المحتوى جاهز")
                    .setMessage(summary)
                    .setPositiveButton("أكمل الخطوات", null)
                    .show();
        });
    }

    private String guidedContentScript(LessonPreparation p, Grade9Curriculum.Lesson lesson) {
        List<String> strategyList = lesson == null
                ? Arrays.asList("التعلم التعاوني", "التعلم بالاكتشاف", "التعلم المبني على حل المشكلات", "التعلم المتمايز")
                : lesson.strategies;
        List<String> resourceList = lesson == null
                ? Arrays.asList("الكتاب", "السبورة التقليدية", "جهاز عرض البيانات")
                : lesson.resources;

        String strategies = new JSONArray(strategyList).toString();
        String resources = new JSONArray(resourceList).toString();
        String level = lesson == null ? "الفهم" : lesson.level;

        return "(function(){" + baseHelpers()
                + "window.__khutwaNoorLearningMute=true;"
                + "try{"
                + "var oc=0,arr=objectiveBoxes();for(var i=0;i<arr.length;i++){if(!arr[i].checked)arr[i].click();if(arr[i].checked)oc++;}"
                + "var checks=0;function checkNames(names){for(var a=0;a<names.length;a++){var target=norm(names[a]);var cbs=[].slice.call(document.querySelectorAll('input[type=checkbox]'));for(var b=0;b<cbs.length;b++){var tx=norm(labelText(cbs[b]));if(tx&&tx.indexOf(target)>=0){if(!cbs[b].checked)cbs[b].click();if(cbs[b].checked)checks++;break;}}}}"
                + "checkNames(" + strategies + ");checkNames(" + resources + ");"
                + "var levels=setLevels(" + JSONObject.quote(level) + ");"
                + "var ed=0;ed+=setEditor('المفاهيم'," + JSONObject.quote(toHtml(p.concepts)) + ");"
                + "ed+=setEditor('التهيئة'," + JSONObject.quote(toHtml(p.intro)) + ");"
                + "ed+=setEditor('إجراءات سير الدرس'," + JSONObject.quote(toHtml(p.procedures)) + ");"
                + "ed+=setEditor('التقويم التكويني'," + JSONObject.quote(toHtml(p.formative)) + ");"
                + "ed+=setEditor('التقويم الختامي'," + JSONObject.quote(toHtml(p.summative)) + ");"
                + "ed+=setEditor('ملاحظات ضمن خطة الدراسة الأسبوعية'," + JSONObject.quote(toHtml(p.weeklyNote)) + ");"
                + "disableByLabel('نشر التحضير للطلبة في خطة الدراسة الأسبوعية');disableByLabel('السماح للمعلمين بنسخ و استخدام تحضيري');"
                + "return JSON.stringify({objectives:oc,checks:checks,levels:levels,editors:ed});"
                + "}finally{window.__khutwaNoorLearningMute=false;}"
                + "})()";
    }

    private void injectLearningScript() {
        if (!isNoorPage() || learningRecorder == null || !learningRecorder.isActive()) return;
        webView.evaluateJavascript(learningScript(), raw -> {});
    }

    private void exportNoorReport() {
        if (learningRecorder == null || learningRecorder.eventCount() == 0) {
            toast("لا توجد جلسة تعلم مسجلة بعد.");
            return;
        }

        if (!isNoorPage()) {
            launchReportSave();
            return;
        }

        webView.evaluateJavascript(pageSnapshotScript(), raw -> {
            try {
                String json = decodeJsString(raw);
                learningRecorder.appendSnapshot(json);
            } catch (Exception ignored) {}
            launchReportSave();
        });
    }

    private void launchReportSave() {
        pendingReport = learningRecorder.buildReport(webView.getUrl());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "Noor-Learning-Report.json");
        startActivityForResult(intent, REQUEST_EXPORT_REPORT);
    }

    private void testNoorLearning() {
        if (!isNoorPage()) {
            toast("افتح صفحة في نور أولاً.");
            return;
        }
        JSONArray learned = learningRecorder.learnedDescriptors();
        if (learned.length() == 0) {
            toast("ابدأ «تعلم نور» ونفّذ خطوات يدوية أولاً.");
            return;
        }

        status.setText("أختبر العناصر التي تعلمها التطبيق دون الضغط عليها...");
        webView.evaluateJavascript(testLearnedScript(learned), raw -> {
            try {
                JSONObject result = new JSONObject(decodeJsString(raw));
                int total = result.optInt("total", 0);
                int found = result.optInt("found", 0);
                JSONArray missing = result.optJSONArray("missing");
                StringBuilder msg = new StringBuilder();
                msg.append("تم العثور على ").append(found).append(" من ").append(total).append(" عنصرًا تعلمها التطبيق.");
                if (missing != null && missing.length() > 0) {
                    msg.append("\n\nأمثلة على العناصر غير الموجودة الآن:");
                    for (int i = 0; i < Math.min(8, missing.length()); i++) {
                        msg.append("\n• ").append(missing.optString(i));
                    }
                }
                status.setText("اختبار التعلم: " + found + "/" + total + " عنصر موجود.");
                new AlertDialog.Builder(this)
                        .setTitle("اختبار تعلم نور")
                        .setMessage(msg.toString())
                        .setPositiveButton("حسنًا", null)
                        .show();
            } catch (Exception e) {
                toast("تعذر تحليل اختبار التعلم.");
            }
        });
    }

    private boolean isNoorUrl(String url) {
        try {
            Uri uri = Uri.parse(url == null ? "" : url);
            String host = uri.getHost();
            return host != null && (host.equals("lms.moe.gov.om") || host.endsWith(".lms.moe.gov.om"));
        } catch (Exception e) {
            return false;
        }
    }

    private final class LearningBridge {
        @JavascriptInterface
        public void record(String json) {
            if (learningRecorder == null || !learningRecorder.isActive()) return;
            if (!isNoorUrl(lastLoadedUrl)) return;
            learningRecorder.record(json);
            int count = learningRecorder.eventCount();
            if (count % 10 == 0) {
                runOnUiThread(() -> status.setText("تعلم نور يعمل — تم تسجيل " + count + " حدثًا."));
            }
        }
    }

    private String learningScript() {
        return "(function(){"
                + "if(window.__khutwaNoorLearningInstalled){window.__khutwaNoorLearningActive=true;return 'active';}"
                + "window.__khutwaNoorLearningInstalled=true;window.__khutwaNoorLearningActive=true;"
                + "function norm(s){return (s||'').replace(/[\\u064B-\\u065F\\u0670]/g,'').replace(/\\s+/g,' ').trim();}"
                + "function safeUrl(u){try{var x=new URL(u,location.href);return x.origin+x.pathname;}catch(e){return '';}}"
                + "function send(o){try{if(!window.__khutwaNoorLearningActive||window.__khutwaNoorLearningMute||!window.KhutwaNoorBridge)return;o.url=location.origin+location.pathname;o.time=Date.now();window.KhutwaNoorBridge.record(JSON.stringify(o));}catch(e){}}"
                + "function labelFor(el){if(!el)return '';var t='';if(el.id){var ls=document.querySelectorAll('label');for(var i=0;i<ls.length;i++){if(ls[i].htmlFor===el.id){t=ls[i].innerText||ls[i].textContent||'';break;}}}if(!t&&el.closest&&el.closest('label'))t=el.closest('label').innerText||el.closest('label').textContent||'';if(!t&&el.parentElement)t=el.parentElement.innerText||el.parentElement.textContent||'';return norm(t).substring(0,180);}"
                + "function cssPath(el){try{if(!el||!el.tagName)return '';if(el.id)return '#'+CSS.escape(el.id);var p=[],n=el;while(n&&n.nodeType===1&&p.length<5){var tag=n.tagName.toLowerCase(),i=1,s=n;while((s=s.previousElementSibling))if(s.tagName===n.tagName)i++;p.unshift(tag+':nth-of-type('+i+')');n=n.parentElement;}return p.join('>');}catch(e){return '';}}"
                + "function desc(el){if(!el||!el.tagName)return null;var tag=el.tagName.toUpperCase(),type=(el.type||'').toLowerCase();var d={tag:tag,id:el.id||'',name:el.name||'',type:type,placeholder:el.placeholder||'',label:labelFor(el),text:norm(el.innerText||el.textContent||'').substring(0,180),path:cssPath(el)};if(tag==='SELECT'){d.value=el.value||'';d.selectedText=el.options&&el.selectedIndex>=0?norm(el.options[el.selectedIndex].text):'';}else if(type==='checkbox'||type==='radio'){d.checked=!!el.checked;d.value=el.value||'';}else if(type==='date'||type==='number'){d.value=el.value||'';}else if(type==='password'){d.value_redacted=true;}else if('value' in el&&el.value){d.value_length=String(el.value).length;}return d;}"
                + "document.addEventListener('click',function(e){var el=e.target&&e.target.closest?e.target.closest('button,a,input,select,label,[role=button]'):e.target;send({kind:'click',element:desc(el)});},true);"
                + "document.addEventListener('change',function(e){send({kind:'change',element:desc(e.target)});},true);"
                + "document.addEventListener('focusin',function(e){var el=e.target;if(el&&/^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName))send({kind:'focus',element:desc(el)});},true);"
                + "var mo=new MutationObserver(function(ms){var added=[];for(var i=0;i<ms.length;i++){var ns=ms[i].addedNodes||[];for(var j=0;j<ns.length;j++){var n=ns[j];if(!n||n.nodeType!==1)continue;var els=[];if(n.matches&&n.matches('input,select,textarea,button'))els.push(n);if(n.querySelectorAll){var q=n.querySelectorAll('input,select,textarea,button');for(var k=0;k<q.length&&els.length<12;k++)els.push(q[k]);}for(var z=0;z<els.length&&added.length<12;z++){var d=desc(els[z]);if(d)added.push(d);}}}if(added.length)send({kind:'dom_added',count:added.length,elements:added});});"
                + "try{mo.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}"
                + "var oldFetch=window.fetch;if(oldFetch&&!window.__khutwaFetchWrapped){window.__khutwaFetchWrapped=true;window.fetch=function(){try{var a=arguments[0],m=(arguments[1]&&arguments[1].method)||'GET',u=typeof a==='string'?a:(a&&a.url)||'';send({kind:'fetch',method:m,url_path:safeUrl(u)});}catch(e){}return oldFetch.apply(this,arguments);};}"
                + "if(window.XMLHttpRequest&&!window.__khutwaXhrWrapped){window.__khutwaXhrWrapped=true;var op=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){try{send({kind:'xhr',method:m||'GET',url_path:safeUrl(u)});}catch(e){}return op.apply(this,arguments);};}"
                + "function route(){send({kind:'route',route:location.pathname+location.hash});}window.addEventListener('hashchange',route);window.addEventListener('popstate',route);"
                + "window.__khutwaNoorLearningStop=function(){window.__khutwaNoorLearningActive=false;try{mo.disconnect();}catch(e){}};"
                + "send({kind:'page_ready',title:document.title,controls:document.querySelectorAll('input,select,textarea,button').length});"
                + "return 'installed';"
                + "})()";
    }

    private String pageSnapshotScript() {
        return "(function(){"
                + "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}"
                + "function lab(el){if(!el)return '';if(el.id){var ls=document.querySelectorAll('label');for(var i=0;i<ls.length;i++){if(ls[i].htmlFor===el.id)return norm(ls[i].innerText||ls[i].textContent).substring(0,160);}}return el.closest&&el.closest('label')?norm(el.closest('label').innerText||el.closest('label').textContent).substring(0,160):'';}"
                + "var els=[].slice.call(document.querySelectorAll('input,select,textarea,button,a'));var out=[];"
                + "for(var i=0;i<els.length&&out.length<300;i++){var e=els[i],r=e.getBoundingClientRect();if(r.width===0&&r.height===0)continue;var type=(e.type||'').toLowerCase();var o={tag:e.tagName,id:e.id||'',name:e.name||'',type:type,label:lab(e),placeholder:e.placeholder||'',text:norm(e.innerText||e.textContent||'').substring(0,160)};if(e.tagName==='SELECT'){o.selectedText=e.options&&e.selectedIndex>=0?norm(e.options[e.selectedIndex].text):'';o.optionCount=e.options?e.options.length:0;}else if(type==='checkbox'||type==='radio'){o.checked=!!e.checked;}else if(type==='date'||type==='number'){o.value=e.value||'';}else if(type==='password'){o.redacted=true;}out.push(o);}"
                + "return JSON.stringify({url:location.origin+location.pathname,title:document.title,visible_controls:out});"
                + "})()";
    }

    private String testLearnedScript(JSONArray descriptors) {
        return "(function(){"
                + "var ds=" + descriptors.toString() + ";"
                + "function norm(s){return (s||'').replace(/[\\u064B-\\u065F\\u0670]/g,'').replace(/\\s+/g,' ').trim();}"
                + "function lab(el){if(!el)return '';if(el.id){var ls=document.querySelectorAll('label');for(var i=0;i<ls.length;i++){if(ls[i].htmlFor===el.id)return norm(ls[i].innerText||ls[i].textContent);}}return el.closest&&el.closest('label')?norm(el.closest('label').innerText||el.closest('label').textContent):'';}"
                + "function find(d){var el=null;try{if(d.id)el=document.getElementById(d.id);if(!el&&d.name){var ns=document.getElementsByName(d.name);for(var z=0;z<ns.length;z++){if(!d.tag||ns[z].tagName===d.tag){el=ns[z];break;}}}if(!el&&d.path)el=document.querySelector(d.path);}catch(e){}if(el)return el;var all=[].slice.call(document.querySelectorAll((d.tag||'*').toLowerCase()));for(var i=0;i<all.length;i++){var e=all[i],l=lab(e),p=norm(e.placeholder||''),t=norm(e.innerText||e.textContent||'');if(d.label&&l.indexOf(norm(d.label))>=0)return e;if(d.placeholder&&p.indexOf(norm(d.placeholder))>=0)return e;if(d.text&&t&&t.indexOf(norm(d.text))>=0)return e;}return null;}"
                + "var found=0,missing=[];for(var i=0;i<ds.length;i++){if(find(ds[i]))found++;else if(missing.length<20)missing.push(ds[i].label||ds[i].text||ds[i].name||ds[i].id||ds[i].tag);}"
                + "return JSON.stringify({total:ds.length,found:found,missing:missing});"
                + "})()";
    }

    private String pdfName(Uri uri) {
        if (uri == null) return "";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String s = uri.getLastPathSegment();
        return s == null ? "تمارين.pdf" : s;
    }

    private boolean isNoorPage() {
        Uri uri = Uri.parse(webView.getUrl() == null ? "" : webView.getUrl());
        String host = uri.getHost();
        return host != null && (host.equals("lms.moe.gov.om") || host.endsWith(".lms.moe.gov.om"));
    }

    private void scanLesson(boolean showDialog) {
        if (!isNoorPage()) {
            toast("ارجع إلى صفحة منصة نور أولاً.");
            return;
        }
        status.setText("أفحص الدرس والحقول...");
        webView.evaluateJavascript(scanScript(), raw -> {
            try {
                String jsonText = decodeJsString(raw);
                JSONObject obj = new JSONObject(jsonText);
                currentTitle = obj.optString("title", "").trim();
                currentOutcomes.clear();
                JSONArray arr = obj.optJSONArray("outcomes");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) currentOutcomes.add(arr.optString(i));
                }

                int editorCount = obj.optInt("editorCount", 0);
                currentLesson = Grade9Curriculum.find(currentTitle);
                currentPreparation = Grade9PreparationBank.get(currentTitle);

                StringBuilder msg = new StringBuilder();
                msg.append("العنوان: ").append(currentTitle.isEmpty() ? "لم يُكتشف" : currentTitle)
                        .append("\nالأهداف المكتشفة في نور: ").append(currentOutcomes.size())
                        .append("\nمحررات النص المكتشفة: ").append(editorCount)
                        .append("\nPDF التمارين: ").append(exercisePdfUri == null ? "غير مرتبط" : pdfName(exercisePdfUri));

                if (currentLesson != null) {
                    msg.append("\n\nمطابقة الخطة الرسمية: نعم")
                            .append("\n").append(currentLesson.planSummary())
                            .append("\nالأهداف الرسمية المحفوظة: ").append(currentLesson.objectives.size())
                            .append("\nالتحضير الجاهز: ").append(currentPreparation == null ? "غير متوفر" : "متوفر - صفر توكن");
                    status.setText("تم التعرف على " + currentLesson.code + " — "
                            + currentLesson.periods + " حصص — الأسبوع " + currentLesson.weekStart);
                } else {
                    msg.append("\n\nلم أتعرف على الدرس ضمن خطة الصف التاسع المحفوظة.");
                    status.setText("تم الفحص، لكن الدرس غير موجود في قاعدة الصف التاسع.");
                }

                if (showDialog) new AlertDialog.Builder(this)
                        .setTitle("نتيجة الفحص")
                        .setMessage(msg.toString())
                        .setPositiveButton("حسنًا", null)
                        .show();
            } catch (Exception e) {
                status.setText("تعذر قراءة الصفحة.");
                toast("تعذر تحليل صفحة التحضير. افتح إضافة التحضير واختر الدرس ثم أعد المحاولة.");
            }
        });
    }

    private void selectAllObjectives() {
        if (!isNoorPage()) {
            toast("افتح صفحة التحضير في نور أولاً.");
            return;
        }
        webView.evaluateJavascript(selectObjectivesScript(), raw -> {
            String count = raw == null ? "0" : raw.replace("\"", "");
            status.setText("تم تحديد الأهداف الموجودة: " + count);
            toast("تم تحديد " + count + " من أهداف الدرس.");
            scanLesson(false);
        });
    }

    private void generatePreview() {
        if (currentTitle.isEmpty()) {
            scanLesson(false);
            toast("بعد ظهور اسم الدرس اضغط «تجهيز كامل» مرة أخرى.");
            return;
        }

        currentLesson = Grade9Curriculum.find(currentTitle);
        currentPreparation = Grade9PreparationBank.get(currentTitle);

        if (currentPreparation != null) {
            status.setText("التحضير جاهز داخل التطبيق — لا يوجد استهلاك توكنات.");
            showPreparationPreview("قاعدة الصف التاسع الجاهزة — 0 توكن");
            return;
        }

        currentPreparation = LessonGenerator.generate(currentTitle, currentOutcomes);
        status.setText("الدرس غير موجود في بنك التاسع؛ استخدمت قالبًا محليًا احتياطيًا.");
        showPreparationPreview("قالب محلي احتياطي — 0 توكن");
    }

    private void generateAiPreview() {
        if (currentTitle.isEmpty()) {
            scanLesson(false);
            toast("بعد ظهور اسم الدرس اضغط «تحسين AI» مرة أخرى.");
            return;
        }

        LessonPreparation cached = aiClient.getCached(currentTitle);
        if (cached != null) {
            currentPreparation = cached;
            status.setText("نسخة AI محفوظة محليًا — لا يوجد استهلاك جديد.");
            showPreparationPreview("تحسين AI محفوظ محليًا");
            return;
        }

        status.setText("أحسن التحضير بالذكاء الاصطناعي مرة واحدة فقط...");
        final String title = currentTitle;
        final List<String> outcomes = currentLesson != null
                ? new ArrayList<>(currentLesson.objectives)
                : new ArrayList<>(currentOutcomes);

        worker.execute(() -> {
            try {
                LessonPreparation generated = aiClient.generateAndCache(title, outcomes);
                runOnUiThread(() -> {
                    if (!title.equals(currentTitle)) return;
                    currentPreparation = generated;
                    status.setText("تم حفظ نسخة AI على الجهاز؛ إعادة فتح الدرس لن تستهلك توكنات جديدة.");
                    showPreparationPreview("تحسين AI اختياري — محفوظ محليًا");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    currentPreparation = Grade9PreparationBank.get(title);
                    if (currentPreparation == null) currentPreparation = LessonGenerator.generate(title, outcomes);
                    status.setText("تعذر اتصال AI؛ بقي التحضير الجاهز داخل التطبيق.");
                    showPreparationPreview("التحضير المحلي — 0 توكن");
                });
            }
        });
    }

    private void showPreparationPreview(String source) {
        TextView preview = new TextView(this);
        String plan = currentLesson == null ? "" : "\n\nالخطة الرسمية:\n" + currentLesson.planSummary()
                + "\nالمستوى المقترح: " + currentLesson.level;
        preview.setText("المصدر: " + source + plan + "\n\n" + currentPreparation.preview());
        preview.setTextSize(15);
        preview.setPadding(dp(20), dp(10), dp(20), dp(10));
        preview.setTextDirection(View.TEXT_DIRECTION_RTL);
        preview.setMovementMethod(new ScrollingMovementMethod());

        new AlertDialog.Builder(this)
                .setTitle("معاينة التحضير")
                .setView(preview)
                .setNegativeButton("إغلاق", null)
                .setPositiveButton("تعبئة في نور", (d, w) -> fillPreparation())
                .show();
    }

    private void fillPreparation() {
        if (!isNoorPage()) {
            toast("افتح صفحة التحضير في نور أولاً.");
            return;
        }
        if (currentTitle.isEmpty()) {
            scanLesson(false);
            toast("تم تشغيل الفحص. بعد ظهور الدرس اضغط تعبئة مرة أخرى.");
            return;
        }

        currentLesson = Grade9Curriculum.find(currentTitle);
        if (currentPreparation == null) {
            currentPreparation = Grade9PreparationBank.get(currentTitle);
            if (currentPreparation == null) currentPreparation = LessonGenerator.generate(currentTitle, currentOutcomes);
        }

        status.setText("أعبئ التحضير وأضع تاريخ النشر...");
        webView.evaluateJavascript(fillScript(currentPreparation, currentLesson), raw -> {
            try {
                String jsonText = decodeJsString(raw);
                JSONObject result = new JSONObject(jsonText);
                applyWeekThenSessions(result);
            } catch (Exception e) {
                status.setText("انتهت محاولة التعبئة. راجع الحقول قبل الحفظ.");
                toast("تمت تعبئة المحتوى، لكن تعذر بدء تسلسل الأسبوع والحصص.");
            }
        });
    }

    private void applyWeekThenSessions(JSONObject baseResult) {
        if (currentLesson == null) {
            finishFill(baseResult, 0, 0);
            return;
        }

        final int week = currentLesson.weekStart;
        status.setText("تم تاريخ النشر. أختار الآن أسبوع العمل " + week + "...");
        webView.postDelayed(() -> webView.evaluateJavascript(setWeekOnlyScript(week), raw -> {
            int weekSet = parseJsInt(raw);
            if (weekSet <= 0) {
                status.setText("تعذر اختيار أسبوع العمل تلقائيًا. راجع القائمة.");
                finishFill(baseResult, 0, 0);
                return;
            }

            status.setText("تم اختيار أسبوع العمل. أنتظر نور ليحمّل حصص الأسبوع...");
            selectSessionsWithRetry(baseResult, weekSet, 0);
        }), 450);
    }

    private void selectSessionsWithRetry(JSONObject baseResult, int weekSet, int attempt) {
        long delay = attempt == 0 ? 1200L : 750L;
        webView.postDelayed(() -> webView.evaluateJavascript(selectVisibleMathSessionsScript(), raw -> {
            int selected = 0;
            int found = 0;
            try {
                String jsonText = decodeJsString(raw);
                JSONObject r = new JSONObject(jsonText);
                selected = r.optInt("selected", 0);
                found = r.optInt("found", 0);
            } catch (Exception ignored) {}

            if (found == 0 && attempt < 4) {
                status.setText("نور ما زال يحمّل الحصص... محاولة " + (attempt + 2) + " من 5");
                selectSessionsWithRetry(baseResult, weekSet, attempt + 1);
                return;
            }

            finishFill(baseResult, weekSet, selected);
        }), delay);
    }

    private void finishFill(JSONObject result, int weekSet, int sessionsSelected) {
        int objectives = result.optInt("objectives", 0);
        int editors = result.optInt("editors", 0);
        int checks = result.optInt("checks", 0);
        int levels = result.optInt("levels", 0);
        int dateSet = result.optInt("date", 0);
        int allTables = result.optInt("allTables", 0);

        String scheduleInfo = "\nتاريخ النشر: " + (dateSet > 0 ? "تم" : "راجع")
                + "\nأسبوع العمل: " + (weekSet > 0 ? "تم" : "راجع")
                + "\nحصص الرياضيات المحددة: " + sessionsSelected
                + "\nالتعميم على الجداول: " + (allTables > 0 ? "مفعّل" : "راجع");

        if (exercisePdfUri != null) {
            status.setText("تم التاريخ والأسبوع وتحديد " + sessionsSelected + " حصة. أجهز الآن تمارين الدرس...");
            extractExercises(true);
        } else {
            status.setText("اكتملت التعبئة — تم تحديد " + sessionsSelected + " حصة. راجع ثم احفظ.");
            new AlertDialog.Builder(this)
                    .setTitle("تمت التعبئة")
                    .setMessage("الأهداف: " + objectives
                            + "\nالاستراتيجيات/المصادر: " + checks
                            + "\nالمستويات: " + levels
                            + "\nحقول النص: " + editors
                            + scheduleInfo
                            + "\n\nراجع النتيجة ثم اضغط حفظ في نور بنفسك.")
                    .setPositiveButton("مراجعة", null)
                    .show();
        }
    }

    private int parseJsInt(String raw) {
        try {
            if (raw == null || "null".equals(raw)) return 0;
            return Integer.parseInt(raw.replace("\"", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String setWeekOnlyScript(int week) {
        return "(function(){" + baseHelpers()
                + "return setWorkWeek(" + week + ");"
                + "})()";
    }

    private String selectVisibleMathSessionsScript() {
        return "(function(){" + baseHelpers()
                + "var w=findText('أسبوع العمل'),b=findText('تعميم التحضير على كافة الجداول');"
                + "var y1=w?pos(w):-1,y2=b?pos(b):1e12;"
                + "var cbs=[].slice.call(document.querySelectorAll('input[type=checkbox]'));"
                + "var found=0,selected=0;"
                + "for(var i=0;i<cbs.length;i++){var cb=cbs[i],r=cb.getBoundingClientRect();"
                + "if(r.width===0&&r.height===0)continue;var y=r.top+window.scrollY;"
                + "if(y1>=0&&y<=y1+40)continue;if(y<=y1||y>=y2)continue;"
                + "var tx=norm(labelText(cb));if(!tx&&cb.parentElement)tx=norm(cb.parentElement.innerText||cb.parentElement.textContent||'');"
                + "if(tx.indexOf(norm('الرياضيات'))<0||tx.indexOf(norm('الحصة'))<0)continue;"
                + "found++;if(!cb.checked)cb.click();if(cb.checked)selected++;}"
                + "return JSON.stringify({found:found,selected:selected});"
                + "})()";
    }

    private void extractExercises(boolean afterFill) {
        if (exercisePdfUri == null) {
            new AlertDialog.Builder(this)
                    .setTitle("ملف التمارين")
                    .setMessage("اختر ملف PDF الذي يحتوي تمارين المنهج أولاً. سيُحفظ اختياره على الجهاز.")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("اختيار PDF", (d, w) -> pickPdf())
                    .show();
            return;
        }
        if (currentTitle.isEmpty()) {
            scanLesson(false);
            toast("أفحص عنوان الدرس. اضغط «تمارين هذا الدرس» مرة أخرى بعد ظهور العنوان.");
            return;
        }

        status.setText("أبحث داخل " + pdfName(exercisePdfUri) + " عن: " + currentTitle);
        Uri uri = exercisePdfUri;
        String title = currentTitle;

        worker.execute(() -> {
            try {
                PdfExerciseExtractor.ExtractResult result = PdfExerciseExtractor.extract(this, uri, title);
                runOnUiThread(() -> showExercisePreview(result, afterFill));
            } catch (Exception e) {
                runOnUiThread(() -> showAutoExtractFailure(e.getMessage()));
            }
        });
    }

    private void showAutoExtractFailure(String reason) {
        status.setText("لم تنجح المطابقة التلقائية للتمارين.");
        new AlertDialog.Builder(this)
                .setTitle("تعذر تحديد صفحات الدرس")
                .setMessage((reason == null ? "تعذر قراءة عنوان الدرس داخل الملف." : reason)
                        + "\n\nقد يكون الـPDF مصورًا أو فهرسته مختلفة. يمكنك تحديد صفحات الدرس يدويًا، وبعدها سيقتطعها التطبيق ويرفقها بالطريقة نفسها.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تحديد الصفحات", (d, w) -> showManualRangeDialog())
                .show();
    }

    private void showManualRangeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        EditText start = new EditText(this);
        start.setHint("صفحة البداية");
        start.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(start);

        EditText end = new EditText(this);
        end.setHint("صفحة النهاية");
        end.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(end);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("صفحات تمارين الدرس")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("استخراج", null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int s = Integer.parseInt(start.getText().toString().trim());
                int e = Integer.parseInt(end.getText().toString().trim());
                if (s <= 0 || e < s) throw new NumberFormatException();
                dialog.dismiss();
                status.setText("أستخرج الصفحات " + s + "–" + e + "...");
                worker.execute(() -> {
                    try {
                        PdfExerciseExtractor.ExtractResult result = PdfExerciseExtractor.extractRange(this, exercisePdfUri, s, e);
                        runOnUiThread(() -> showExercisePreview(result, false));
                    } catch (Exception ex) {
                        runOnUiThread(() -> toast("تعذر استخراج الصفحات: " + ex.getMessage()));
                    }
                });
            } catch (NumberFormatException ex) {
                toast("أدخل أرقام صفحات صحيحة.");
            }
        }));
        dialog.show();
    }

    private void showExercisePreview(PdfExerciseExtractor.ExtractResult result, boolean afterFill) {
        recycleExerciseImages();
        exerciseImages.addAll(result.images);
        status.setText("وجدت تمارين الدرس في الصفحات " + result.startPage + "–" + result.endPage + ". راجعها قبل الإرفاق.");

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView info = new TextView(this);
        info.setText("الدرس: " + currentTitle
                + "\nالصفحات: " + result.startPage + "–" + result.endPage
                + "\nالطريقة: " + (result.automatic ? "خريطة جاهزة/مطابقة تلقائية" : "تحديد يدوي")
                + "\n" + result.matchedText);
        info.setTextSize(14);
        info.setTextDirection(View.TEXT_DIRECTION_RTL);
        list.addView(info);

        for (Bitmap bitmap : exerciseImages) {
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setImageBitmap(bitmap);
            image.setPadding(0, dp(8), 0, dp(8));
            list.addView(image, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        scroll.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("معاينة تمارين الدرس")
                .setView(scroll)
                .setNegativeButton("إلغاء", null)
                .setNeutralButton("صفحات أخرى", (d, w) -> showManualRangeDialog())
                .setPositiveButton(afterFill ? "إرفاق وإكمال التحضير" : "إرفاق في الإجراءات", (d, w) -> appendExerciseImages())
                .show();
    }

    private void appendExerciseImages() {
        if (exerciseImages.isEmpty()) {
            toast("لا توجد صور تمارين جاهزة.");
            return;
        }
        status.setText("أجهز صور التمارين للإرفاق داخل إجراءات الدرس...");
        worker.execute(() -> {
            List<String> base64Images = new ArrayList<>();
            try {
                for (Bitmap bitmap : exerciseImages) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
                    base64Images.add(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                }
                runOnUiThread(() -> appendImageAt(base64Images, 0));
            } catch (Exception e) {
                runOnUiThread(() -> toast("تعذر تجهيز صور التمارين."));
            }
        });
    }

    private void appendImageAt(List<String> images, int index) {
        if (index >= images.size()) {
            status.setText("تم إرفاق تمارين الدرس داخل إجراءات سير الدرس — راجعها ثم احفظ في نور.");
            new AlertDialog.Builder(this)
                    .setTitle("تم إرفاق التمارين")
                    .setMessage("أضيف الجزء المستخرج من ملف PDF إلى قسم «إجراءات سير الدرس / الأنشطة التدريسية».\n\nراجع الصور والنص داخل نور، ثم اضغط «حفظ» بنفسك.")
                    .setPositiveButton("مراجعة", null)
                    .show();
            return;
        }

        String html = (index == 0 ? "<hr><p><strong>تمارين الدرس من ملف المنهج</strong></p>" : "")
                + "<p><img src=\"data:image/jpeg;base64," + images.get(index)
                + "\" style=\"max-width:100%;height:auto;display:block;margin:12px auto;\" /></p>";

        String js = "(function(){" + baseHelpers()
                + "return appendToEditor('إجراءات سير الدرس'," + JSONObject.quote(html) + ");"
                + "})()";

        webView.evaluateJavascript(js, raw -> {
            String v = raw == null ? "0" : raw.replace("\"", "");
            if ("1".equals(v)) {
                appendImageAt(images, index + 1);
            } else {
                status.setText("تعذر الوصول إلى محرر إجراءات الدرس.");
                toast("لم أتمكن من إرفاق الصور تلقائيًا. جرّب فتح قسم إجراءات الدرس ثم أعد المحاولة.");
            }
        });
    }

    private void recycleExerciseImages() {
        for (Bitmap b : exerciseImages) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        exerciseImages.clear();
    }

    private void confirmClearSession() {
        new AlertDialog.Builder(this)
                .setTitle("تسجيل الخروج")
                .setMessage("سيتم مسح جلسة نور من هذا التطبيق فقط. ملف التمارين المختار سيبقى محفوظًا ما لم تغيّره.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("مسح الجلسة", (d, w) -> {
                    CookieManager.getInstance().removeAllCookies(value -> {
                        CookieManager.getInstance().flush();
                        webView.clearCache(true);
                        webView.loadUrl(NOOR_URL);
                    });
                })
                .show();
    }

    private String scanScript() {
        return "(function(){" + baseHelpers()
                + "var title='';"
                + "var tl=findText('العنوان');"
                + "if(tl){var ti=nearestAfter(tl,'input[type=text],input:not([type]),textarea',500);if(ti)title=(ti.value||'').trim();}"
                + "if(!title){var ins=[].slice.call(document.querySelectorAll('input[type=text]'));for(var i=0;i<ins.length;i++){var v=(ins[i].value||'').trim();if(/^[0-9٠-٩]+\\s*[-–]\\s*[0-9٠-٩]+/.test(v)){title=v;break;}}}"
                + "var os=objectiveBoxes();var outcomes=[];for(var j=0;j<os.length;j++){var t=labelText(os[j]);if(t)outcomes.push(t);}"
                + "var editors=visibleEditors().length;"
                + "return JSON.stringify({title:title,outcomes:outcomes,editorCount:editors,url:location.href});"
                + "})()";
    }

    private String selectObjectivesScript() {
        return "(function(){" + baseHelpers()
                + "var arr=objectiveBoxes(),n=0;for(var i=0;i<arr.length;i++){if(!arr[i].checked){arr[i].click();}if(arr[i].checked)n++;}return String(n);"
                + "})()";
    }

    private String fillScript(LessonPreparation p, Grade9Curriculum.Lesson lesson) {
        List<String> strategyList = lesson == null
                ? Arrays.asList("التعلم التعاوني", "التعلم بالاكتشاف", "التعلم المبني على حل المشكلات", "التعلم المتمايز")
                : lesson.strategies;
        List<String> resourceList = lesson == null
                ? Arrays.asList("الكتاب", "السبورة التقليدية", "جهاز عرض البيانات")
                : lesson.resources;

        String strategies = new JSONArray(strategyList).toString();
        String resources = new JSONArray(resourceList).toString();
        String level = lesson == null ? "الفهم" : lesson.level;
        int periods = lesson == null ? 0 : lesson.periods;
        int week = lesson == null ? 0 : lesson.weekStart;
        String startDate = lesson == null ? "" : lesson.periodStart; // reserved for timetable-aware exact date in next step

        return "(function(){" + baseHelpers()
                + "var oc=0,arr=objectiveBoxes();for(var i=0;i<arr.length;i++){if(!arr[i].checked)arr[i].click();if(arr[i].checked)oc++;}"
                + "var checks=0;function checkNames(names){for(var a=0;a<names.length;a++){var target=norm(names[a]);var cbs=[].slice.call(document.querySelectorAll('input[type=checkbox]'));for(var b=0;b<cbs.length;b++){var tx=norm(labelText(cbs[b]));if(tx&&tx.indexOf(target)>=0){if(!cbs[b].checked)cbs[b].click();if(cbs[b].checked)checks++;break;}}}}"
                + "checkNames(" + strategies + ");checkNames(" + resources + ");"
                + "var levels=setLevels(" + JSONObject.quote(level) + ");"
                + "var ed=0;ed+=setEditor('المفاهيم'," + JSONObject.quote(toHtml(p.concepts)) + ");"
                + "ed+=setEditor('التهيئة'," + JSONObject.quote(toHtml(p.intro)) + ");"
                + "ed+=setEditor('إجراءات سير الدرس'," + JSONObject.quote(toHtml(p.procedures)) + ");"
                + "ed+=setEditor('التقويم التكويني'," + JSONObject.quote(toHtml(p.formative)) + ");"
                + "ed+=setEditor('التقويم الختامي'," + JSONObject.quote(toHtml(p.summative)) + ");"
                + "ed+=setEditor('ملاحظات ضمن خطة الدراسة الأسبوعية'," + JSONObject.quote(toHtml(p.weeklyNote)) + ");"
                + "var allTables=enableByLabel('تعميم التحضير على كافة الجداول');"
                + "var dateSet=setPublicationDate(" + JSONObject.quote(startDate) + ");"
                + "disableByLabel('نشر التحضير للطلبة في خطة الدراسة الأسبوعية');disableByLabel('السماح للمعلمين بنسخ و استخدام تحضيري');"
                + "return JSON.stringify({objectives:oc,checks:checks,levels:levels,editors:ed,date:dateSet,allTables:allTables});"
                + "})()";
    }

    private String baseHelpers() {
        return "function norm(s){return (s||'').replace(/[\\u064B-\\u065F\\u0670]/g,'').replace(/\\s+/g,' ').trim();}"
                + "function findText(t){var q=norm(t),els=[].slice.call(document.querySelectorAll('label,legend,h1,h2,h3,h4,h5,strong,span,div,p'));var best=null,score=1e9;for(var i=0;i<els.length;i++){var x=norm(els[i].innerText||els[i].textContent);if(!x||x.indexOf(q)<0)continue;var r=els[i].getBoundingClientRect();if(r.width===0&&r.height===0)continue;var sc=x.length-q.length;if(sc<score){score=sc;best=els[i];}}return best;}"
                + "function nearestAfter(anchor,sel,max){if(!anchor)return null;var ar=anchor.getBoundingClientRect(),cs=[].slice.call(document.querySelectorAll(sel)),best=null,d=1e9;for(var i=0;i<cs.length;i++){var r=cs[i].getBoundingClientRect();if(r.width===0&&r.height===0)continue;var dy=r.top-ar.bottom;if(dy>=-20&&dy<(max||700)&&dy<d){d=dy;best=cs[i];}}return best;}"
                + "function fire(el){try{el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));if(window.jQuery){window.jQuery(el).trigger('chosen:updated').trigger('change');}}catch(e){}}"
                + "function labelText(cb){if(!cb)return '';var t='';if(cb.id){var ls=[].slice.call(document.querySelectorAll('label'));for(var z=0;z<ls.length;z++){if(ls[z].htmlFor===cb.id){t=ls[z].innerText||ls[z].textContent||'';break;}}}if(!t&&cb.closest('label'))t=cb.closest('label').innerText||cb.closest('label').textContent||'';if(!t){var p=cb.parentElement;if(p)t=p.innerText||p.textContent||'';}return norm(t).replace(/^[-–•\\s]+/,'');}"
                + "function pos(el){if(!el)return -1;var r=el.getBoundingClientRect();return r.top+window.scrollY;}"
                + "function objectiveBoxes(){var a=findText('المخرجات التعليمية'),b=findText('الاستراتيجيات');var y1=pos(a),y2=pos(b);var all=[].slice.call(document.querySelectorAll('input[type=checkbox]'));var out=[];for(var i=0;i<all.length;i++){var r=all[i].getBoundingClientRect(),y=r.top+window.scrollY;if(y1>=0&&y2>y1&&y>y1-10&&y<y2-5){var tx=labelText(all[i]);if(tx&&tx.length>4)out.push(all[i]);}}if(out.length===0){for(var j=0;j<all.length;j++){var tx2=labelText(all[j]);if(/(يحدد|يحدّد|يجري|يطبق|يطبّق|يتعامل|الطلاب|الطالب)/.test(tx2))out.push(all[j]);}}return out;}"
                + "function visibleEditors(){var a=[].slice.call(document.querySelectorAll('iframe,[contenteditable=true],textarea'));return a.filter(function(e){var r=e.getBoundingClientRect();return r.width>20&&r.height>20;});}"
                + "function findEditor(label){var a=findText(label);if(!a)return null;var ar=a.getBoundingClientRect(),cs=visibleEditors(),best=null,d=1e9;for(var i=0;i<cs.length;i++){var r=cs[i].getBoundingClientRect(),dy=r.top-ar.bottom;if(dy>=-30&&dy<650&&dy<d){d=dy;best=cs[i];}}return best;}"
                + "function setEditor(label,html){var best=findEditor(label);if(!best)return 0;try{if(best.tagName==='IFRAME'){var doc=best.contentDocument||best.contentWindow.document;if(doc&&doc.body){doc.body.innerHTML=html;fire(doc.body);return 1;}}if(best.getAttribute('contenteditable')==='true'){best.innerHTML=html;fire(best);return 1;}if(best.tagName==='TEXTAREA'){best.value=html.replace(/<br\\s*\\/?\\s*>/gi,'\\n').replace(/<[^>]+>/g,'');fire(best);return 1;}}catch(e){}return 0;}"
                + "function appendToEditor(label,html){var best=findEditor(label);if(!best)return 0;try{if(best.tagName==='IFRAME'){var doc=best.contentDocument||best.contentWindow.document;if(doc&&doc.body){doc.body.insertAdjacentHTML('beforeend',html);fire(doc.body);return 1;}}if(best.getAttribute('contenteditable')==='true'){best.insertAdjacentHTML('beforeend',html);fire(best);return 1;}if(best.tagName==='TEXTAREA'){best.value+='\\nتمارين الدرس مرفقة كصور في النسخة المرئية.';fire(best);return 1;}}catch(e){}return 0;}"
                + "function setLevels(target){var n=0,q=norm(target),sels=[].slice.call(document.querySelectorAll('select'));for(var s=0;s<sels.length;s++){var opts=[].slice.call(sels[s].options||[]);for(var o=0;o<opts.length;o++){if(norm(opts[o].text).indexOf(q)>=0){if(sels[s].multiple){opts[o].selected=true;}else{sels[s].value=opts[o].value;}fire(sels[s]);n++;break;}}}return n;}"
                + "function setControlValue(el,val){if(!el)return 0;try{if(el.tagName==='SELECT'){var opts=[].slice.call(el.options||[]),q=norm(val);for(var i=0;i<opts.length;i++){if(norm(opts[i].text)===q||norm(opts[i].text).indexOf(q)>=0||String(opts[i].value)===String(val)){el.value=opts[i].value;fire(el);return 1;}}return 0;}el.value=val;fire(el);return 1;}catch(e){return 0;}}"
                + "function setLabeledValue(labels,val){for(var i=0;i<labels.length;i++){var a=findText(labels[i]);if(!a)continue;var el=nearestAfter(a,'select,input[type=number],input[type=text],input:not([type])',450);if(setControlValue(el,val))return 1;}return 0;}"
                + "function setSelectWeek(el,n){if(!el)return 0;var names=['','الأول','الثاني','الثالث','الرابع','الخامس','السادس','السابع','الثامن','التاسع','العاشر','الحادي عشر','الثاني عشر','الثالث عشر','الرابع عشر','الخامس عشر','السادس عشر','السابع عشر','الثامن عشر','التاسع عشر'];var opts=[].slice.call(el.options||[]);for(var i=0;i<opts.length;i++){var tx=norm(opts[i].text),v=String(opts[i].value||'');if(tx.indexOf(String(n))>=0||(names[n]&&tx.indexOf(norm(names[n]))>=0)||v===String(n)){el.value=opts[i].value;fire(el);return 1;}}if(el.options&&el.options.length>n){el.selectedIndex=n;fire(el);return 1;}return 0;}"
                + "function setWorkWeek(n){var labels=['أسبوع العمل','الأسبوع'];for(var i=0;i<labels.length;i++){var a=findText(labels[i]);if(!a)continue;var el=nearestAfter(a,'select',450);if(setSelectWeek(el,n))return 1;}return 0;}"
                + "function setPublicationDate(date){if(!date)return 0;var done=0;var section=findText('أضف تاريخ النشر لكل فصل');var start=section?pos(section):-1;var inputs=[].slice.call(document.querySelectorAll('input[type=date],input[placeholder*=\\\"تاريخ النشر\\\"],input[type=text]'));for(var i=0;i<inputs.length;i++){var el=inputs[i],r=el.getBoundingClientRect();if(r.width===0&&r.height===0)continue;var y=r.top+window.scrollY;var ph=norm(el.getAttribute('placeholder')||''),nm=norm(el.getAttribute('name')||''),id=norm(el.id||'');var likely=el.type==='date'||ph.indexOf(norm('تاريخ النشر'))>=0||nm.indexOf('publish')>=0||id.indexOf('publish')>=0;if(!likely)continue;if(start>=0&&y<start-20)continue;try{el.value=date;fire(el);done++;}catch(e){}}if(done>0)return done;var a=findText('تاريخ النشر');if(a){var el2=nearestAfter(a,'input[type=date],input[type=text]',500);if(el2){el2.value=date;fire(el2);return 1;}}return 0;}"
                + "function enableByLabel(name){var q=norm(name),cbs=[].slice.call(document.querySelectorAll('input[type=checkbox]'));for(var i=0;i<cbs.length;i++){if(norm(labelText(cbs[i])).indexOf(q)>=0){if(!cbs[i].checked)cbs[i].click();return cbs[i].checked?1:0;}}return 0;}"
                + "function disableByLabel(name){var q=norm(name),cbs=[].slice.call(document.querySelectorAll('input[type=checkbox]'));for(var i=0;i<cbs.length;i++){if(norm(labelText(cbs[i])).indexOf(q)>=0){if(cbs[i].checked)cbs[i].click();return cbs[i].checked?0:1;}}return 0;}";
    }

    private static String toHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    private String decodeJsString(String raw) throws JSONException {
        if (raw == null || "null".equals(raw)) return "{}";
        JSONArray wrapper = new JSONArray("[" + raw + "]");
        return wrapper.getString(0);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        recycleExerciseImages();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
