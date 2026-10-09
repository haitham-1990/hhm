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
import android.os.Build;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String NOOR_URL = "https://lms.moe.gov.om/teacher#networkfirst";
    private static final int REQUEST_PICK_PDF = 301;
    private static final int REQUEST_EXPORT_REPORT = 302;
    private static final int REQUEST_PICK_PLAN_PDF = 303;
    private static final int REQUEST_PICK_MATERIAL_PDF = 304;
    private static final int REQUEST_EXPORT_DIAGNOSTIC = 305;
    private static final String PREFS = "noor_helper";
    private static final String KEY_PDF_URI = "exercise_pdf_uri";
    private static final String KEY_PLAN_PDF_URI = "study_plan_pdf_uri";
    private static final String KEY_MATERIAL_PDF_URI = "subject_material_pdf_uri";
    private static final String KEY_AUTO_LAST_INDEX = "auto_last_index";
    private static final String KEY_AUTO_TARGET_INDEX = "auto_target_index";
    private static final String KEY_AUTO_PENDING_INDEX = "auto_pending_index";
    private static final String KEY_AUTO_ACTIVE = "auto_active";

    private WebView webView;
    private TextView status;
    private String currentTitle = "";
    private final List<String> currentOutcomes = new ArrayList<>();
    private LessonPreparation currentPreparation;
    private CurriculumLesson currentLesson;
    private AiPreparationClient aiClient;
    private GeneratedPreparationStore generatedStore;
    private DiscoveredCurriculumStore curriculumStore;
    private GeneratedPreparationStore.Entry currentDbEntry;
    private TextView databaseStatus;
    private volatile boolean databaseBuilding = false;
    private NoorLearningRecorder learningRecorder;
    private Button learnButton;
    private String pendingReport = "";
    private String pendingDiagnosticReport = "";
    private DiscoveryDiagnostics diagnostics;
    private volatile String lastLoadedUrl = "";
    private boolean guidedLearningWaiting = false;
    private String guidedFilledTitle = "";
    private Spinner autoStartSpinner;
    private Spinner autoTargetSpinner;
    private TextView autoProgress;
    private boolean autoActive = false;
    private boolean autoAwaitingSave = false;
    private int autoCurrentIndex = -1;
    private int autoTargetIndex = -1;
    private int autoPreparingIndex = -1;
    private final List<CurriculumLesson> autoLessons = new ArrayList<>();

    private Uri exercisePdfUri;
    private Uri studyPlanPdfUri;
    private Uri subjectMaterialPdfUri;
    private AiPreparationClient.SourceResult currentSourceResult;
    private final List<Bitmap> exerciseImages = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        aiClient = new AiPreparationClient(this);
        diagnostics = new DiscoveryDiagnostics(this);
        generatedStore = new GeneratedPreparationStore(this);
        curriculumStore = new DiscoveredCurriculumStore(this);
        learningRecorder = new NoorLearningRecorder(this);
        loadDiscoveredLessons();
        buildUi();
        loadSavedPdf();
        loadSavedSourcePdfs();
        updateDatabaseStatus();
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
        title.setText("نور الذكي - تشخيص شامل 1.0.5");
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
        status.setText("أرفق الخطة والمادة ثم اضغط تجهيز قاعدة البيانات. نور الذكي سيكتشف الوحدات والدروس ويقسم المادة ويخزنها بنفسه.");
        status.setTextSize(13);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(dp(12), dp(4), dp(12), dp(6));
        status.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(status);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout filesRow = new LinearLayout(this);
        filesRow.setOrientation(LinearLayout.HORIZONTAL);
        filesRow.setPadding(dp(4), dp(3), dp(4), dp(2));

        Button choosePlanPdf = makeButton("الخطة PDF");
        choosePlanPdf.setOnClickListener(v -> pickSourcePdf(REQUEST_PICK_PLAN_PDF));
        filesRow.addView(choosePlanPdf, weightedButton());

        Button chooseMaterialPdf = makeButton("المادة العلمية / التمارين");
        chooseMaterialPdf.setOnClickListener(v -> pickSourcePdf(REQUEST_PICK_MATERIAL_PDF));
        filesRow.addView(chooseMaterialPdf, weightedButton());
        root.addView(filesRow);

        Button buildDatabase = makeButton("تجهيز قاعدة البيانات بالذكاء الاصطناعي");
        buildDatabase.setTextSize(13);
        buildDatabase.setOnClickListener(v -> prepareGeneratedDatabase());
        LinearLayout.LayoutParams dbButtonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        dbButtonParams.setMargins(dp(6), dp(2), dp(6), dp(2));
        root.addView(buildDatabase, dbButtonParams);

        databaseStatus = new TextView(this);
        databaseStatus.setTextSize(12);
        databaseStatus.setTextColor(Color.DKGRAY);
        databaseStatus.setPadding(dp(12), dp(2), dp(12), dp(4));
        databaseStatus.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(databaseStatus);

        Button diagnosticReport = makeButton("حفظ التقرير الشامل (أرسله لي)");
        diagnosticReport.setTextSize(13);
        diagnosticReport.setOnClickListener(v -> exportDiagnosticReport());
        LinearLayout.LayoutParams diagnosticParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        diagnosticParams.setMargins(dp(6), dp(1), dp(6), dp(2));
        root.addView(diagnosticReport, diagnosticParams);

        TextView diagnosticHint = new TextView(this);
        diagnosticHint.setText("أكمل تجربتك كاملة داخل نور. عند أي مشكلة — الشجرة، اختيار الدرس، الحقول، الصور، التواريخ، الحصص أو الحفظ — احفظ التقرير الشامل وأرسله لي.");
        diagnosticHint.setTextSize(11);
        diagnosticHint.setTextColor(Color.DKGRAY);
        diagnosticHint.setPadding(dp(12), 0, dp(12), dp(4));
        diagnosticHint.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(diagnosticHint);

        LinearLayout autoStartRow = new LinearLayout(this);
        autoStartRow.setOrientation(LinearLayout.HORIZONTAL);
        autoStartRow.setGravity(Gravity.CENTER_VERTICAL);
        autoStartRow.setPadding(dp(4), dp(1), dp(4), dp(1));

        TextView startLabel = new TextView(this);
        startLabel.setText("ابدأ من:");
        startLabel.setTextSize(13);
        startLabel.setPadding(dp(6), 0, dp(6), 0);
        autoStartRow.addView(startLabel, new LinearLayout.LayoutParams(dp(82), dp(46)));

        autoStartSpinner = new Spinner(this);
        List<String> startLessonNames = new ArrayList<>();
        for (CurriculumLesson l : autoLessons) startLessonNames.add(l.displayName());
        ArrayAdapter<String> startLessonAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, startLessonNames);
        startLessonAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        autoStartSpinner.setAdapter(startLessonAdapter);
        autoStartRow.addView(autoStartSpinner, new LinearLayout.LayoutParams(0, dp(46), 1));
        root.addView(autoStartRow);

        LinearLayout autoTargetRow = new LinearLayout(this);
        autoTargetRow.setOrientation(LinearLayout.HORIZONTAL);
        autoTargetRow.setGravity(Gravity.CENTER_VERTICAL);
        autoTargetRow.setPadding(dp(4), dp(1), dp(4), dp(1));

        TextView untilLabel = new TextView(this);
        untilLabel.setText("حضّر حتى:");
        untilLabel.setTextSize(13);
        untilLabel.setPadding(dp(6), 0, dp(6), 0);
        autoTargetRow.addView(untilLabel, new LinearLayout.LayoutParams(dp(82), dp(46)));

        autoTargetSpinner = new Spinner(this);
        List<String> targetLessonNames = new ArrayList<>();
        for (CurriculumLesson l : autoLessons) targetLessonNames.add(l.displayName());
        ArrayAdapter<String> targetLessonAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, targetLessonNames);
        targetLessonAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        autoTargetSpinner.setAdapter(targetLessonAdapter);
        autoTargetRow.addView(autoTargetSpinner, new LinearLayout.LayoutParams(0, dp(46), 1));
        root.addView(autoTargetRow);

        autoProgress = new TextView(this);
        autoProgress.setTextSize(12);
        autoProgress.setTextColor(Color.DKGRAY);
        autoProgress.setPadding(dp(12), dp(2), dp(12), dp(3));
        autoProgress.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(autoProgress);

        Button autoStart = makeButton("ابدأ تلقائي");
        autoStart.setTextSize(14);
        autoStart.setOnClickListener(v -> startAutoRun(false));
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        startParams.setMargins(dp(6), dp(2), dp(6), dp(7));
        root.addView(autoStart, startParams);

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

    private String safeNoorLocation(String url) {
        try {
            Uri uri = Uri.parse(url == null ? "" : url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String fragment = uri.getFragment() == null ? "" : uri.getFragment();
            return path + (fragment.isEmpty() ? "" : "#" + fragment);
        } catch (Exception e) {
            return "";
        }
    }

    private void logAutoStage(String stage, CurriculumLesson lesson, JSONObject extra) {
        if (diagnostics == null) return;
        try {
            JSONObject o = extra == null ? new JSONObject() : extra;
            o.put("auto_active", autoActive);
            o.put("awaiting_save", autoAwaitingSave);
            o.put("current_index", autoCurrentIndex);
            o.put("target_index", autoTargetIndex);
            o.put("preparing_index", autoPreparingIndex);
            o.put("location", safeNoorLocation(webView == null ? "" : webView.getUrl()));
            if (lesson != null) {
                o.put("lesson_code", lesson.code);
                o.put("lesson_title", lesson.title);
                o.put("lesson_unit", lesson.unit);
            }
            diagnostics.log(stage, o);
        } catch (Exception ignored) {}
    }

    private void captureNoorDiagnosticSnapshot(String reason, CurriculumLesson lesson) {
        captureNoorDiagnosticSnapshot(reason, lesson, null);
    }

    private void captureNoorDiagnosticSnapshot(String reason, CurriculumLesson lesson, Runnable after) {
        if (diagnostics == null || webView == null || !isNoorPage()) {
            try {
                JSONObject o = new JSONObject();
                o.put("reason", reason == null ? "" : reason);
                o.put("noor_page", false);
                logAutoStage("noor_page_snapshot", lesson, o);
            } catch (Exception ignored) {}
            if (after != null) after.run();
            return;
        }

        webView.evaluateJavascript(noorDiagnosticSnapshotScript(), raw -> {
            try {
                JSONObject o = new JSONObject(decodeJsString(raw));
                o.put("reason", reason == null ? "" : reason);
                if (lesson != null) {
                    o.put("target_code", lesson.code);
                    o.put("target_title", lesson.title);
                    o.put("target_unit", lesson.unit);
                }
                logAutoStage("noor_page_snapshot", lesson, o);
            } catch (Exception e) {
                diagnostics.logException("noor_page_snapshot_parse_error", e);
            }
            if (after != null) after.run();
        });
    }

    private String noorDiagnosticSnapshotScript() {
        return "(function(){"
                + "function vis(e){try{var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}catch(x){return false;}}"
                + "function lim(s,n){s=(s||'').replace(/\\s+/g,' ').trim();return s.length>n?s.substring(0,n):s;}"
                + "function node(e){return {tag:(e.tagName||'').toLowerCase(),id:e.id||'',cls:lim(String(e.className||''),180),text:lim(e.innerText||e.textContent||'',180),visible:vis(e),expanded:e.getAttribute?e.getAttribute('aria-expanded')||'':'',selected:e.getAttribute?e.getAttribute('aria-selected')||'':''};}"
                + "var treeSelectors='.jstree-anchor,a[id$=_anchor],[role=treeitem],.tree-node,.treeview a';"
                + "var ta=[].slice.call(document.querySelectorAll(treeSelectors)),tree=[];"
                + "for(var i=0;i<ta.length&&tree.length<140;i++){tree.push(node(ta[i]));}"
                + "var roots=[].slice.call(document.querySelectorAll('.jstree,[role=tree],[class*=treeview],[class*=tree-view]')),rootInfo=[];"
                + "for(var r=0;r<roots.length&&r<20;r++){rootInfo.push(node(roots[r]));}"
                + "var fs=[].slice.call(document.querySelectorAll('input,select,textarea,iframe,[contenteditable=true]')),fields=[];"
                + "for(var f=0;f<fs.length&&fields.length<180;f++){var e=fs[f];fields.push({tag:(e.tagName||'').toLowerCase(),id:e.id||'',name:e.name||'',type:e.type||'',visible:vis(e),disabled:!!e.disabled,checked:!!e.checked,options:e.options?e.options.length:0});}"
                + "var vs=[].slice.call(document.querySelectorAll('.alert-danger,.alert-warning,.error,.help-block,.invalid-feedback,.field-validation-error,.validation-summary-errors,.has-error')),validation=[];"
                + "for(var v=0;v<vs.length&&validation.length<40;v++){var tx=lim(vs[v].innerText||vs[v].textContent||'',260);if(tx&&validation.indexOf(tx)<0)validation.push(tx);}"
                + "var bs=[].slice.call(document.querySelectorAll('button,input[type=submit],a.btn')),buttons=[];"
                + "for(var b=0;b<bs.length&&buttons.length<80;b++){if(vis(bs[b]))buttons.push(node(bs[b]));}"
                + "var title=document.getElementById('PreparationTitle');"
                + "var criteria=document.querySelectorAll('input[name^=\"Preparation[criteria]\"]');"
                + "return JSON.stringify({path:location.pathname||'',hash:location.hash||'',ready_state:document.readyState||'',page_title:document.title||'',tree_anchor_count:ta.length,tree_root_count:roots.length,tree_nodes:tree,tree_roots:rootInfo,field_count:fs.length,fields:fields,visible_buttons:buttons,validation_messages:validation,preparation_title_present:!!title,criteria_count:criteria.length,form_count:document.forms?document.forms.length:0});"
                + "})()";
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

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                try {
                    if (consoleMessage != null && autoActive
                            && (consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                            || consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING)) {
                        JSONObject o = new JSONObject();
                        o.put("level", String.valueOf(consoleMessage.messageLevel()));
                        o.put("message", consoleMessage.message() == null ? "" : consoleMessage.message());
                        o.put("source", consoleMessage.sourceId() == null ? "" : consoleMessage.sourceId());
                        o.put("line", consoleMessage.lineNumber());
                        logAutoStage("webview_console", currentLesson, o);
                    }
                } catch (Exception ignored) {}
                return super.onConsoleMessage(consoleMessage);
            }
        });
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
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (autoActive && isNoorUrl(url)) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("location", safeNoorLocation(url));
                        logAutoStage("webview_page_started", currentLesson, o);
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (autoActive && request != null && request.isForMainFrame()) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("location", safeNoorLocation(request.getUrl() == null ? "" : request.getUrl().toString()));
                        o.put("error_code", error == null ? 0 : error.getErrorCode());
                        o.put("description", error == null || error.getDescription() == null ? "" : error.getDescription().toString());
                        logAutoStage("webview_navigation_error", currentLesson, o);
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (autoActive && request != null && request.isForMainFrame()) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("location", safeNoorLocation(request.getUrl() == null ? "" : request.getUrl().toString()));
                        o.put("status_code", errorResponse == null ? 0 : errorResponse.getStatusCode());
                        o.put("reason", errorResponse == null ? "" : errorResponse.getReasonPhrase());
                        logAutoStage("webview_http_error", currentLesson, o);
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                lastLoadedUrl = url == null ? "" : url;
                if (autoActive && isNoorUrl(url)) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("location", safeNoorLocation(url));
                        logAutoStage("webview_page_finished", currentLesson, o);
                    } catch (Exception ignored) {}
                }
                if (!autoActive) {
                    int ready = generatedStore == null ? 0 : generatedReadyCount();
                    status.setText("نور مفتوح. قاعدة التحضير: " + ready + " / " + autoLessons.size()
                            + " درس. بعد اكتمالها اختر المدى واضغط «ابدأ تلقائي».");
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

    private CurriculumLesson findDiscoveredLesson(String title) {
        if (title == null || title.trim().isEmpty()) return null;
        for (CurriculumLesson lesson : autoLessons) {
            if (lessonMatchesNoorTitle(title, lesson)) return lesson;
        }
        return null;
    }

    private LessonPreparation findGeneratedPreparation(String title) {
        CurriculumLesson lesson = findDiscoveredLesson(title);
        if (lesson == null || generatedStore == null) return null;
        GeneratedPreparationStore.Entry entry = generatedStore.get(lesson.code);
        return entry == null ? null : entry.preparation;
    }

    private void loadDiscoveredLessons() {
        autoLessons.clear();
        if (curriculumStore == null) return;
        for (DiscoveredCurriculumStore.Entry e : curriculumStore.load()) {
            autoLessons.add(e.lesson);
        }
    }

    private void refreshLessonSpinners() {
        if (autoStartSpinner == null || autoTargetSpinner == null) return;
        List<String> names = new ArrayList<>();
        for (CurriculumLesson l : autoLessons) names.add(l.displayName());

        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        autoStartSpinner.setAdapter(a);

        ArrayAdapter<String> b = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        b.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        autoTargetSpinner.setAdapter(b);

        if (!autoLessons.isEmpty()) autoTargetSpinner.setSelection(autoLessons.size() - 1);
        updateDatabaseStatus();
    }

    private int generatedReadyCount() {
        int n = 0;
        for (CurriculumLesson lesson : autoLessons) {
            if (generatedStore.has(lesson.code) && LessonImageCache.has(this, lesson.code)) n++;
        }
        return n;
    }

    private void updateDatabaseStatus() {
        if (databaseStatus == null || generatedStore == null) return;
        int ready = generatedReadyCount();
        String plan = studyPlanPdfUri == null ? "غير مرفقة" : pdfName(studyPlanPdfUri);
        String material = subjectMaterialPdfUri == null ? "غير مرفقة" : pdfName(subjectMaterialPdfUri);
        String diagnosticState;
        if (diagnostics != null && diagnostics.hasReport()) {
            diagnosticState = databaseBuilding ? "يُسجّل الآن ويمكن حفظ نسخة جزئية" : "جاهز للحفظ";
        } else {
            diagnosticState = databaseBuilding ? "يُسجّل الآن" : "سيبدأ مع تجهيز القاعدة";
        }
        databaseStatus.setText("الخطة: " + plan
                + "\nالمادة: " + material
                + "\nالدروس المكتشفة: " + autoLessons.size()
                + "\nالتحاضير الجاهزة: " + ready + " / " + autoLessons.size()
                + "\nالتقرير الشامل: " + diagnosticState);
    }

    private void prepareGeneratedDatabase() {
        if (databaseBuilding) {
            toast("تجهيز قاعدة البيانات يعمل الآن.");
            return;
        }
        if (studyPlanPdfUri == null || subjectMaterialPdfUri == null) {
            new AlertDialog.Builder(this)
                    .setTitle("أرفق الملفين أولاً")
                    .setMessage("اختر ملف الخطة الدراسية PDF وملف المادة العلمية/التمارين PDF.")
                    .setPositiveButton("حسنًا", null)
                    .show();
            return;
        }

        boolean changedGenerated = generatedStore.bindSources(
                studyPlanPdfUri.toString(), subjectMaterialPdfUri.toString());
        boolean changedCatalog = curriculumStore.bindSources(
                studyPlanPdfUri.toString(), subjectMaterialPdfUri.toString());
        if (changedGenerated || changedCatalog) {
            LessonImageCache.clearAll(this);
            autoLessons.clear();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_AUTO_LAST_INDEX, -1)
                    .putInt(KEY_AUTO_PENDING_INDEX, 0)
                    .apply();
            refreshLessonSpinners();
        }

        diagnostics.startSession(
                pdfName(studyPlanPdfUri),
                pdfName(subjectMaterialPdfUri),
                changedGenerated || changedCatalog || !diagnostics.hasReport());
        try {
            JSONObject meta = new JSONObject();
            meta.put("source_changed", changedGenerated || changedCatalog);
            meta.put("cached_catalog_count", autoLessons.size());
            diagnostics.log("database_build_requested", meta);
        } catch (Exception ignored) {}

        databaseBuilding = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            Intent keepAlive = new Intent(this, AutoRunService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(keepAlive);
            else startService(keepAlive);
        } catch (Exception ignored) {}

        status.setText("أقرأ الملفين وأكتشف المنهج تلقائيًا...");
        updateDatabaseStatus();

        worker.execute(() -> {
            List<String> failed = new ArrayList<>();
            try {
                PdfCorpusIndex corpus = PdfCorpusIndex.build(this, studyPlanPdfUri, subjectMaterialPdfUri);
                try {
                    JSONObject meta = new JSONObject();
                    meta.put("plan_pages", corpus.planPageCount());
                    meta.put("material_pages", corpus.materialPageCount());
                    diagnostics.log("pdf_index_ready", meta);
                } catch (Exception ignored) {}

                List<DiscoveredCurriculumStore.Entry> catalog = curriculumStore.load();
                if (catalog.isEmpty()) {
                    runOnUiThread(() -> status.setText("الذكاء يحدد الصف والوحدات والدروس وترتيبها من الملفين..."));
                    catalog = aiClient.discoverCurriculum(corpus);
                    curriculumStore.save(catalog);
                    diagnostics.logLessons("catalog_saved", catalog);
                } else {
                    diagnostics.logLessons("catalog_loaded_from_store", catalog);
                }

                autoLessons.clear();
                for (DiscoveredCurriculumStore.Entry e : catalog) autoLessons.add(e.lesson);
                runOnUiThread(() -> {
                    refreshLessonSpinners();
                    status.setText("تم اكتشاف " + autoLessons.size() + " درسًا. أبدأ تقسيم المادة وبناء التحاضير...");
                });

                int total = catalog.size();
                for (int i = 0; i < total; i++) {
                    DiscoveredCurriculumStore.Entry entry = catalog.get(i);
                    CurriculumLesson lesson = entry.lesson;
                    DiscoveredCurriculumStore.Entry previous = i > 0 ? catalog.get(i - 1) : null;
                    DiscoveredCurriculumStore.Entry next = i + 1 < total ? catalog.get(i + 1) : null;

                    if (generatedStore.has(lesson.code) && LessonImageCache.has(this, lesson.code)) {
                        final int ready = generatedReadyCount();
                        runOnUiThread(() -> {
                            status.setText("قاعدة البيانات " + ready + " / " + total + " — أتجاوز الدرس المحفوظ...");
                            updateDatabaseStatus();
                        });
                        continue;
                    }

                    final int index = i;
                    runOnUiThread(() -> status.setText("أجهز الدرس " + (index + 1) + " من " + total
                            + ": " + lesson.displayName()));

                    try {
                        String title = lesson.noorCode() + " " + lesson.title;
                        String materialContext = corpus.materialRangeText(
                                entry.materialStartPage, entry.materialEndPage);
                        if (materialContext.trim().isEmpty()) {
                            throw new IllegalStateException("لم أجد محتوى صفحات الدرس التي اكتشفها الذكاء.");
                        }

                        StringBuilder planContext = new StringBuilder();
                        planContext.append(lesson.unit).append("\n")
                                .append("الدرس: ").append(title).append("\n")
                                .append("عدد الحصص: ").append(lesson.periods).append("\n")
                                .append("الفترة: ").append(lesson.periodStart).append(" إلى ").append(lesson.periodEnd);
                        for (String objective : lesson.objectives) {
                            planContext.append("\nمخرج: ").append(objective);
                        }

                        AiPreparationClient.SourceResult generated = generateFromSourcesWithRetry(
                                title, lesson.objectives, planContext.toString(), materialContext, lesson.code);

                        String nextTitle = next == null ? "" : next.lesson.noorCode() + " " + next.lesson.title;
                        int previousEnd = previous == null ? 0 : previous.materialEndPage;
                        int nextStart = next == null ? 0 : next.materialStartPage;
                        int savedImages = LessonImageCache.build(
                                this,
                                subjectMaterialPdfUri,
                                lesson.code,
                                title,
                                nextTitle,
                                entry.materialStartPage,
                                entry.materialEndPage,
                                previousEnd,
                                nextStart
                        );

                        List<Integer> materialPages = new ArrayList<>();
                        for (int pg = entry.materialStartPage;
                             pg > 0 && pg <= entry.materialEndPage; pg++) materialPages.add(pg);

                        generatedStore.save(
                                lesson.code,
                                title,
                                generated,
                                materialPages,
                                "تقسيم تلقائي: ص" + entry.materialStartPage + "–" + entry.materialEndPage
                        );

                        try {
                            JSONObject meta = new JSONObject();
                            meta.put("code", lesson.code);
                            meta.put("title", lesson.title);
                            meta.put("material_start_page", entry.materialStartPage);
                            meta.put("material_end_page", entry.materialEndPage);
                            meta.put("objectives_count", lesson.objectives.size());
                            meta.put("images_saved", savedImages);
                            diagnostics.log("lesson_build_success", meta);
                        } catch (Exception ignored) {}

                        final int ready = generatedReadyCount();
                        runOnUiThread(() -> {
                            status.setText("تم " + lesson.displayName() + " — القاعدة " + ready + " / " + total);
                            updateDatabaseStatus();
                        });
                    } catch (Exception lessonError) {
                        try {
                            JSONObject meta = new JSONObject();
                            meta.put("code", lesson.code);
                            meta.put("title", lesson.title);
                            meta.put("material_start_page", entry.materialStartPage);
                            meta.put("material_end_page", entry.materialEndPage);
                            meta.put("error", lessonError.getMessage() == null ? "" : lessonError.getMessage());
                            diagnostics.log("lesson_build_error", meta);
                        } catch (Exception ignored) {}
                        failed.add(lesson.displayName() + ": " + lessonError.getMessage());
                        final String shortError = lessonError.getMessage() == null
                                ? "خطأ غير معروف" : lessonError.getMessage();
                        runOnUiThread(() -> status.setText("تعذر " + lesson.displayName()
                                + " — سأكمل البقية. " + shortError));
                    }
                }
            } catch (Exception e) {
                diagnostics.logMessage("database_build_fatal_error", e.getMessage());
                failed.add("اكتشاف/فهرسة المنهج: " + e.getMessage());
            }

            runOnUiThread(() -> {
                databaseBuilding = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try { stopService(new Intent(this, AutoRunService.class)); } catch (Exception ignored) {}
                loadDiscoveredLessons();
                refreshLessonSpinners();
                int ready = generatedReadyCount();
                try {
                    JSONObject meta = new JSONObject();
                    meta.put("discovered_lessons", autoLessons.size());
                    meta.put("ready_lessons", ready);
                    meta.put("failed_count", failed.size());
                    JSONArray errors = new JSONArray();
                    for (String x : failed) errors.put(x);
                    meta.put("errors", errors);
                    diagnostics.log("database_build_summary", meta);
                } catch (Exception ignored) {}

                if (failed.isEmpty() && !autoLessons.isEmpty() && ready == autoLessons.size()) {
                    status.setText("القاعدة جاهزة: اكتشف " + autoLessons.size()
                            + " درسًا وجهزها كلها. اختر البداية والنهاية ثم ابدأ تلقائي.");
                    new AlertDialog.Builder(this)
                            .setTitle("اكتمل اكتشاف المنهج")
                            .setMessage("نور الذكي اكتشف " + autoLessons.size()
                                    + " درسًا من الملفين، قسم المادة، وجهز التحاضير والصور وخزنها محليًا.\n\nإذا لاحظت أن العدد أو التقسيم غير صحيح، احفظ تقرير التشخيص وارفعه لي.")
                            .setPositiveButton("ممتاز", null)
                            .setNeutralButton("حفظ تقرير التشخيص", (d, w) -> exportDiagnosticReport())
                            .show();
                } else {
                    StringBuilder msg = new StringBuilder();
                    msg.append("الدروس المكتشفة: ").append(autoLessons.size())
                            .append("\nالجاهز: ").append(ready);
                    if (!failed.isEmpty()) {
                        msg.append("\n\nملاحظات:");
                        for (int i = 0; i < Math.min(8, failed.size()); i++) msg.append("\n• ").append(failed.get(i));
                    }
                    msg.append("\n\nاضغط تجهيز قاعدة البيانات مرة أخرى لإعادة محاولة الناقص فقط.");
                    new AlertDialog.Builder(this)
                            .setTitle("اكتمل التجهيز مع ملاحظات")
                            .setMessage(msg.toString() + "\n\nاحفظ تقرير التشخيص وارفعه لي لأحدد موضع الخطأ.")
                            .setPositiveButton("حسنًا", null)
                            .setNeutralButton("حفظ تقرير التشخيص", (d, w) -> exportDiagnosticReport())
                            .show();
                }
            });
        });
    }


    private void exportDiagnosticReport() {
        if (diagnostics == null || !diagnostics.hasReport()) {
            toast("لا يوجد تقرير شامل بعد. شغّل «تجهيز قاعدة البيانات» أو ابدأ تجربة نور أولاً.");
            return;
        }

        CurriculumLesson exportLesson = autoCurrentIndex >= 0 && autoCurrentIndex < autoLessons.size()
                ? autoLessons.get(autoCurrentIndex) : currentLesson;
        captureNoorDiagnosticSnapshot("manual_report_export", exportLesson,
                () -> finalizeDiagnosticReportExport(exportLesson));
    }

    private void finalizeDiagnosticReportExport(CurriculumLesson exportLesson) {
        try {
            JSONObject snapshot = new JSONObject();
            snapshot.put("database_building", databaseBuilding);
            snapshot.put("discovered_lessons", autoLessons.size());
            snapshot.put("ready_lessons", generatedReadyCount());
            snapshot.put("plan_file", studyPlanPdfUri == null ? "" : pdfName(studyPlanPdfUri));
            snapshot.put("material_file", subjectMaterialPdfUri == null ? "" : pdfName(subjectMaterialPdfUri));
            snapshot.put("auto_active", autoActive);
            snapshot.put("auto_awaiting_save", autoAwaitingSave);
            snapshot.put("auto_current_index", autoCurrentIndex);
            snapshot.put("auto_target_index", autoTargetIndex);
            snapshot.put("last_completed_index",
                    getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_LAST_INDEX, -1));
            snapshot.put("pending_index",
                    getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_PENDING_INDEX, -1));
            snapshot.put("location", safeNoorLocation(webView == null ? "" : webView.getUrl()));
            if (exportLesson != null) {
                snapshot.put("current_lesson_code", exportLesson.code);
                snapshot.put("current_lesson_title", exportLesson.title);
            }
            diagnostics.log("manual_report_export", snapshot);
            if (curriculumStore != null) {
                diagnostics.logLessons("catalog_at_export", curriculumStore.load());
            }
        } catch (Exception e) {
            diagnostics.logException("manual_report_export_error", e);
        }

        pendingDiagnosticReport = diagnostics.exportReport();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "NoorSmart-Full-Diagnostic-1.0.5.json");
        startActivityForResult(intent, REQUEST_EXPORT_DIAGNOSTIC);
    }

    private void pickSourcePdf(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    private void loadSavedSourcePdfs() {
        String p = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PLAN_PDF_URI, "");
        String m = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MATERIAL_PDF_URI, "");
        try { studyPlanPdfUri = p.isEmpty() ? null : Uri.parse(p); } catch (Exception ignored) { studyPlanPdfUri = null; }
        try { subjectMaterialPdfUri = m.isEmpty() ? null : Uri.parse(m); } catch (Exception ignored) { subjectMaterialPdfUri = null; }
        if (subjectMaterialPdfUri != null) exercisePdfUri = subjectMaterialPdfUri;
    }

    private void showSourceFilesStatus() {
        String plan = studyPlanPdfUri == null ? "غير محددة" : pdfName(studyPlanPdfUri);
        String material = subjectMaterialPdfUri == null ? "غير محددة" : pdfName(subjectMaterialPdfUri);
        new AlertDialog.Builder(this)
                .setTitle("ملفات الذكاء")
                .setMessage("الخطة الدراسية: " + plan + "\n\nالمادة العلمية: " + material
                        + "\n\nفي التشغيل التلقائي سيُنشأ كل درس من هذين الملفين ثم يُحفظ الناتج محليًا.")
                .setPositiveButton("حسنًا", null)
                .show();
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
        if ((requestCode == REQUEST_PICK_PLAN_PDF || requestCode == REQUEST_PICK_MATERIAL_PDF)
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}

            if (requestCode == REQUEST_PICK_PLAN_PDF) {
                studyPlanPdfUri = uri;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PLAN_PDF_URI, uri.toString()).apply();
                status.setText("تم ربط الخطة الدراسية: " + pdfName(uri));
                toast("تم حفظ ملف الخطة.");
            } else {
                subjectMaterialPdfUri = uri;
                exercisePdfUri = uri;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_MATERIAL_PDF_URI, uri.toString())
                        .putString(KEY_PDF_URI, uri.toString())
                        .apply();
                status.setText("تم ربط المادة العلمية: " + pdfName(uri));
                toast("تم حفظ المادة العلمية وستستخدم أيضًا لصور الدرس.");
            }
            currentSourceResult = null;
            currentDbEntry = null;
            if (studyPlanPdfUri != null && subjectMaterialPdfUri != null) {
                boolean changedGenerated = generatedStore.bindSources(
                        studyPlanPdfUri.toString(), subjectMaterialPdfUri.toString());
                boolean changedCatalog = curriculumStore.bindSources(
                        studyPlanPdfUri.toString(), subjectMaterialPdfUri.toString());
                if (changedGenerated || changedCatalog) {
                    LessonImageCache.clearAll(this);
                    autoLessons.clear();
                    refreshLessonSpinners();
                }
            }
            updateDatabaseStatus();
            return;
        }

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

        if (requestCode == REQUEST_EXPORT_DIAGNOSTIC && resultCode == RESULT_OK
                && data != null && data.getData() != null && !pendingDiagnosticReport.isEmpty()) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null) {
                    out.write(pendingDiagnosticReport.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    toast("تم حفظ تقرير التشخيص.");
                    status.setText("تم حفظ تقرير التشخيص. أرسله لي وسأحدد مرحلة الخطأ بدقة.");
                }
            } catch (Exception e) {
                toast("تعذر حفظ تقرير التشخيص.");
            } finally {
                pendingDiagnosticReport = "";
            }
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

    private void restoreAutoUi() {
        int last = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_LAST_INDEX, -1);
        int pending = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_PENDING_INDEX, last + 1);
        int target = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_TARGET_INDEX, autoLessons.size() - 1);
        if (!autoLessons.isEmpty()) {
            int start = pending >= 0 ? pending : last + 1;
            start = Math.max(0, Math.min(autoLessons.size() - 1, start));
            target = Math.max(start, Math.min(autoLessons.size() - 1, target));
            if (autoStartSpinner != null) autoStartSpinner.setSelection(start);
            if (autoTargetSpinner != null) autoTargetSpinner.setSelection(target);
        }
        updateAutoProgressText(last);
    }

    private void updateAutoProgressText(int last) {
        if (autoProgress == null || autoLessons.isEmpty()) return;
        String completed = last >= 0 && last < autoLessons.size()
                ? autoLessons.get(last).displayName()
                : "لا يوجد";
        int next = last + 1;
        String nextText = next >= 0 && next < autoLessons.size()
                ? autoLessons.get(next).displayName()
                : "اكتمل الفصل";
        int target = autoTargetSpinner == null ? -1 : autoTargetSpinner.getSelectedItemPosition();
        String targetText = target >= 0 && target < autoLessons.size()
                ? autoLessons.get(target).displayName()
                : "غير محدد";
        int chosenStart = autoStartSpinner == null ? next : autoStartSpinner.getSelectedItemPosition();
        String startText = chosenStart >= 0 && chosenStart < autoLessons.size()
                ? autoLessons.get(chosenStart).displayName()
                : nextText;
        autoProgress.setText("آخر درس مكتمل: " + completed
                + "\nالبدء من: " + startText
                + "\nالتالي المحفوظ: " + nextText
                + "\nالتوقف عند: " + targetText);
    }

    private void startAutoRun(boolean resume) {
        if (!isNoorPage()) {
            toast("سجّل الدخول إلى نور أولاً.");
            return;
        }
        if (autoLessons.isEmpty()) {
            toast("قائمة الدروس غير متوفرة.");
            return;
        }

        int target = autoTargetSpinner.getSelectedItemPosition();
        int last = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_LAST_INDEX, -1);
        int pending = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_PENDING_INDEX, -1);
        int chosenStart = autoStartSpinner == null ? 0 : autoStartSpinner.getSelectedItemPosition();
        int start = chosenStart;
        if (resume && pending >= 0 && pending < autoLessons.size()) start = pending;

        if (!resume && start > 0) {
            // User explicitly says earlier lessons are already done (useful after installing an updated build).
            last = start - 1;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_AUTO_LAST_INDEX, last)
                    .putInt(KEY_AUTO_PENDING_INDEX, start)
                    .apply();
        }

        for (int i = start; i <= target && i < autoLessons.size(); i++) {
            if (!generatedStore.has(autoLessons.get(i).code)
                    || !LessonImageCache.has(this, autoLessons.get(i).code)) {
                new AlertDialog.Builder(this)
                        .setTitle("قاعدة البيانات غير مكتملة")
                        .setMessage("الدرس «" + autoLessons.get(i).displayName()
                                + "» غير مكتمل في قاعدة البيانات (التحضير/الصور). اضغط «تجهيز قاعدة البيانات» أولاً.")
                        .setPositiveButton("حسنًا", null)
                        .show();
                return;
            }
        }

        if (target < start) {
            final int selectedTarget = target;
            new AlertDialog.Builder(this)
                    .setTitle("الهدف قبل نقطة التقدم")
                    .setMessage("آخر درس مكتمل محفوظ بعد الدرس الذي اخترته. هل تريد تصفير التقدم والبدء من أول درس حتى «"
                            + autoLessons.get(selectedTarget).displayName() + "»؟")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("ابدأ من الأول", (d, w) -> {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putInt(KEY_AUTO_LAST_INDEX, -1)
                                .putInt(KEY_AUTO_PENDING_INDEX, 0)
                                .apply();
                        beginAutoRunAt(0, selectedTarget);
                    })
                    .show();
            return;
        }
        beginAutoRunAt(start, target);
    }

    private void beginAutoRunAt(int start, int target) {
        if (start < 0 || start >= autoLessons.size()) {
            toast("لا يوجد درس تالٍ للتحضير.");
            return;
        }
        autoActive = true;
        autoAwaitingSave = false;
        autoPreparingIndex = -1;
        autoCurrentIndex = start;
        autoTargetIndex = Math.max(start, Math.min(autoLessons.size() - 1, target));

        try {
            JSONObject meta = new JSONObject();
            meta.put("start_index", autoCurrentIndex);
            meta.put("target_index", autoTargetIndex);
            meta.put("lesson_count", autoLessons.size());
            meta.put("start_lesson", autoLessons.get(autoCurrentIndex).displayName());
            meta.put("target_lesson", autoLessons.get(autoTargetIndex).displayName());
            logAutoStage("auto_run_start", autoLessons.get(autoCurrentIndex), meta);
        } catch (Exception ignored) {}
        captureNoorDiagnosticSnapshot("auto_run_start", autoLessons.get(autoCurrentIndex));

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_ACTIVE, true)
                .putInt(KEY_AUTO_TARGET_INDEX, autoTargetIndex)
                .putInt(KEY_AUTO_PENDING_INDEX, autoCurrentIndex)
                .apply();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            Intent keepAlive = new Intent(this, AutoRunService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(keepAlive);
            else startService(keepAlive);
        } catch (Exception ignored) {}
        status.setText("بدأ التشغيل التلقائي — " + autoLessons.get(autoCurrentIndex).displayName());
        updateAutoProgressText(getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_LAST_INDEX, -1));
        handleAutoPage(webView.getUrl());
    }

    private void stopAutoRun(boolean completed) {
        CurriculumLesson stoppedLesson = autoCurrentIndex >= 0 && autoCurrentIndex < autoLessons.size()
                ? autoLessons.get(autoCurrentIndex) : null;
        try {
            JSONObject meta = new JSONObject();
            meta.put("completed", completed);
            meta.put("last_completed_index", getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_LAST_INDEX, -1));
            logAutoStage("auto_run_stop", stoppedLesson, meta);
        } catch (Exception ignored) {}
        captureNoorDiagnosticSnapshot(completed ? "auto_run_completed" : "auto_run_stopped", stoppedLesson);
        autoActive = false;
        autoAwaitingSave = false;
        autoPreparingIndex = -1;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { stopService(new Intent(this, AutoRunService.class)); } catch (Exception ignored) {}
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_ACTIVE, false)
                .apply();
        int last = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_LAST_INDEX, -1);
        updateAutoProgressText(last);
        if (completed) {
            status.setText("اكتمل التحضير حتى الدرس المحدد.");
            new AlertDialog.Builder(this)
                    .setTitle("اكتمل التشغيل")
                    .setMessage("تم الوصول إلى «" + autoLessons.get(Math.max(0, Math.min(last, autoLessons.size()-1))).displayName()
                            + "» وحفظ نقطة التقدم. يمكنك اختيار درس أبعد من القائمة والضغط «متابعة» لاحقًا.")
                    .setPositiveButton("حسنًا", null)
                    .show();
        } else {
            status.setText("تم إيقاف التشغيل. نقطة التقدم محفوظة ويمكنك الضغط «متابعة» لاحقًا.");
        }
    }

    private void stopAutoWithError(String message) {
        CurriculumLesson failingLesson = autoCurrentIndex >= 0 && autoCurrentIndex < autoLessons.size()
                ? autoLessons.get(autoCurrentIndex) : currentLesson;
        try {
            JSONObject meta = new JSONObject();
            meta.put("message", message == null ? "" : message);
            logAutoStage("auto_stop_error", failingLesson, meta);
        } catch (Exception ignored) {}
        captureNoorDiagnosticSnapshot("auto_stop_error: " + (message == null ? "" : message), failingLesson);
        autoActive = false;
        autoAwaitingSave = false;
        autoPreparingIndex = -1;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { stopService(new Intent(this, AutoRunService.class)); } catch (Exception ignored) {}
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_ACTIVE, false)
                .putInt(KEY_AUTO_PENDING_INDEX, autoCurrentIndex)
                .apply();
        int last = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO_LAST_INDEX, -1);
        updateAutoProgressText(last);
        status.setText("توقف التشغيل عند " + (autoCurrentIndex >= 0 && autoCurrentIndex < autoLessons.size()
                ? autoLessons.get(autoCurrentIndex).displayName() : "الدرس الحالي"));
        new AlertDialog.Builder(this)
                .setTitle("توقف آمن")
                .setMessage(message + "\n\nلم أسجل الدرس الحالي كمكتمل. بعد حل المشكلة اضغط «متابعة» وسيعيده من البداية.")
                .setPositiveButton("حسنًا", null)
                .show();
    }

    private void handleAutoPage(String url) {
        if (!autoActive || url == null) return;
        CurriculumLesson routeLesson = autoCurrentIndex >= 0 && autoCurrentIndex < autoLessons.size()
                ? autoLessons.get(autoCurrentIndex) : null;
        try {
            JSONObject meta = new JSONObject();
            meta.put("incoming_location", safeNoorLocation(url));
            logAutoStage("auto_page_route", routeLesson, meta);
        } catch (Exception ignored) {}
        if (autoCurrentIndex < 0 || autoCurrentIndex >= autoLessons.size()) {
            stopAutoWithError("فقدت رقم الدرس الحالي.");
            return;
        }

        if (autoAwaitingSave && (url.contains("/teacher/courses/preparations_index")
                || url.contains("/teacher/courses/browse_content"))) {
            markAutoLessonSaved();
            return;
        }

        if (url.contains("/teacher/courses/add_preparation")) {
            if (autoPreparingIndex != autoCurrentIndex) prepareAutoLesson(autoCurrentIndex);
            return;
        }

        if (url.contains("/teacher/courses/preparations_index")) {
            navigateToAddPreparation();
            return;
        }

        navigateToAddPreparation();
    }

    private void navigateToAddPreparation() {
        navigateToAddPreparation(0);
    }

    private void navigateToAddPreparation(int attempt) {
        if (!autoActive) return;

        String current = webView.getUrl() == null ? "" : webView.getUrl();
        if (current.contains("/teacher/courses/add_preparation")) {
            if (autoPreparingIndex != autoCurrentIndex) prepareAutoLesson(autoCurrentIndex);
            return;
        }

        // After a successful save Noor opens browse_content using the same opaque cid
        // as add_preparation. Reusing it is more reliable than hunting the side menu.
        if (current.contains("/teacher/courses/browse_content/")) {
            String next = current.replace("/teacher/courses/browse_content/", "/teacher/courses/add_preparation/");
            int hash = next.indexOf('#');
            if (hash >= 0) next = next.substring(0, hash);
            status.setText("تم حفظ الدرس — أفتح إضافة تحضير للدرس التالي...");
            webView.loadUrl(next);
            return;
        }

        status.setText(attempt == 0
                ? "أفتح قسم التحاضير ثم «إضافة تحضير»..."
                : "أنتظر قائمة التحاضير... محاولة " + (attempt + 1));

        String js = "(function(){"
                + "function n(s){return (s||'').replace(/\\s+/g,' ').trim();}"
                + "function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "var links=[].slice.call(document.querySelectorAll('a'));"
                + "for(var i=0;i<links.length;i++){var h=links[i].getAttribute('href')||'';"
                + "if(h.indexOf('/teacher/courses/add_preparation')>=0){links[i].click();return 'add_href';}}"
                + "var els=[].slice.call(document.querySelectorAll('a,button,[role=button],div,span,li'));"
                + "var add=null;for(var j=0;j<els.length;j++){if(!vis(els[j]))continue;var t=n(els[j].innerText||els[j].textContent);"
                + "if(t==='إضافة تحضير'){add=els[j];break;}}"
                + "if(add){add.click();return 'add_text';}"
                + "var prep=null;for(var k=0;k<els.length;k++){if(!vis(els[k]))continue;var p=n(els[k].innerText||els[k].textContent);"
                + "if(p==='التحاضير'){prep=els[k];break;}}"
                + "if(!prep){for(var z=0;z<els.length;z++){if(!vis(els[z]))continue;var p2=n(els[z].innerText||els[z].textContent);"
                + "if(p2==='تحضير الدروس'||p2.indexOf('تحضير الدروس')>=0){prep=els[z];break;}}}"
                + "if(prep){prep.click();return 'opened';}"
                + "return 'none';})()";

        webView.evaluateJavascript(js, raw -> {
            String v = raw == null ? "" : raw.replace("\"", "").trim();
            try {
                JSONObject meta = new JSONObject();
                meta.put("attempt", attempt);
                meta.put("js_result", v);
                logAutoStage("navigate_add_preparation_result",
                        autoCurrentIndex >= 0 && autoCurrentIndex < autoLessons.size() ? autoLessons.get(autoCurrentIndex) : null,
                        meta);
            } catch (Exception ignored) {}
            if (v.contains("add_")) return;

            if ((v.contains("opened") || v.contains("none")) && attempt < 8) {
                webView.postDelayed(() -> navigateToAddPreparation(attempt + 1), 650);
                return;
            }

            webView.postDelayed(() -> {
                if (!autoActive) return;
                String now = webView.getUrl() == null ? "" : webView.getUrl();
                if (!now.contains("/add_preparation")) {
                    stopAutoWithError("تعذر الوصول إلى «إضافة تحضير» بعد فتح قسم التحاضير عدة مرات.");
                }
            }, 900);
        });
    }

    private void prepareAutoLesson(int index) {
        if (!autoActive || index < 0 || index >= autoLessons.size()) return;
        autoPreparingIndex = index;
        CurriculumLesson lesson = autoLessons.get(index);
        currentLesson = lesson;
        currentDbEntry = generatedStore.get(lesson.code);
        currentSourceResult = null;
        currentPreparation = currentDbEntry == null ? null : currentDbEntry.preparation;
        currentTitle = lesson.noorCode() + " " + lesson.title;
        try {
            JSONObject meta = new JSONObject();
            meta.put("database_entry_present", currentDbEntry != null);
            meta.put("preparation_present", currentPreparation != null);
            logAutoStage("auto_lesson_prepare_begin", lesson, meta);
        } catch (Exception ignored) {}

        if (currentDbEntry == null || currentPreparation == null) {
            stopAutoWithError("الدرس «" + lesson.displayName()
                    + "» غير موجود في قاعدة البيانات. أعد تجهيز قاعدة البيانات.");
            return;
        }

        status.setText("الدرس " + (index + 1) + " من " + (autoTargetIndex + 1)
                + " — أستخدم التحضير المحفوظ وأختار «" + lesson.displayName() + "» من نور...");
        autoTreeStage(lesson, 0, 0);
    }


    private void autoTreeStage(CurriculumLesson lesson, int stage, int retry) {
        if (!autoActive || autoCurrentIndex < 0) return;

        List<String> path = new ArrayList<>();
        if (lesson.semester != null && !lesson.semester.trim().isEmpty()) {
            path.add(lesson.semester.trim());
        }
        if (lesson.unit != null && !lesson.unit.trim().isEmpty()) {
            path.add(lesson.unit.trim());
        }

        if (stage >= path.size()) {
            webView.evaluateJavascript(treeClickLessonScript(lesson), raw -> {
                int ok = parseJsInt(raw);
                try {
                    JSONObject meta = new JSONObject();
                    meta.put("stage", stage);
                    meta.put("retry", retry);
                    meta.put("result", ok);
                    meta.put("path_size", path.size());
                    logAutoStage("tree_lesson_click_result", lesson, meta);
                } catch (Exception ignored) {}
                if (ok <= 0) {
                    if (retry == 0 || retry >= 13) {
                        captureNoorDiagnosticSnapshot("tree_lesson_not_found_retry_" + retry, lesson);
                    }
                    if (retry < 14) {
                        webView.postDelayed(() -> autoTreeStage(lesson, Math.max(0, path.size() - 1), retry + 1), 700);
                    } else {
                        stopAutoWithError("لم أجد درس «" + lesson.displayName() + "» ظاهرًا في شجرة المنهج.");
                    }
                    return;
                }
                status.setText("تم اختيار " + lesson.displayName() + " — أنتظر نور ليحمّل العنوان والأهداف...");
                webView.postDelayed(() -> waitForAutoLessonForm(lesson, 0), 1400);
            });
            return;
        }

        String parent = stage == 0 ? "عرض الشجرة -" : path.get(stage - 1);
        String child = path.get(stage);
        boolean exactParent = stage > 0;

        webView.evaluateJavascript(treeEnsureChildScript(parent, child, exactParent), raw -> {
            int state = parseJsInt(raw);
            try {
                JSONObject meta = new JSONObject();
                meta.put("stage", stage);
                meta.put("retry", retry);
                meta.put("parent_query", parent);
                meta.put("child_query", child);
                meta.put("exact_parent", exactParent);
                meta.put("result", state);
                logAutoStage("tree_path_result", lesson, meta);
            } catch (Exception ignored) {}
            if (state <= 0 && (retry == 0 || retry >= 13)) {
                captureNoorDiagnosticSnapshot("tree_path_failure_stage_" + stage + "_retry_" + retry, lesson);
            }
            if (state == 2) {
                webView.postDelayed(() -> autoTreeStage(lesson, stage + 1, 0), 250);
            } else if (state == 1) {
                webView.postDelayed(() -> autoTreeStage(lesson, stage, retry + 1), 700);
            } else if (retry < 14) {
                webView.postDelayed(() -> autoTreeStage(lesson, stage, retry + 1), 700);
            } else {
                stopAutoWithError("لم أتمكن من فتح مسار الدرس «" + lesson.displayName() + "» في شجرة نور.");
            }
        });
    }


    private String treeEnsureChildScript(String parentQuery, String childQuery, boolean exactParent) {
        return "(function(){"
                + "function norm(s){return (s||'').replace(/[\\u064B-\\u065F\\u0670\\u0640]/g,'').replace(/[أإآ]/g,'ا').replace(/ى/g,'ي').replace(/\\s+/g,' ').trim();}"
                + "function visible(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "function sameish(a,b){a=norm(a);b=norm(b);if(!a||!b)return false;var ac=a.replace(/[\\s\\-–:]+/g,''),bc=b.replace(/[\\s\\-–:]+/g,'');return a===b||a.indexOf(b)>=0||b.indexOf(a)>=0||ac.indexOf(bc)>=0||bc.indexOf(ac)>=0;}"
                + "function openNode(anchor){var li=anchor&&anchor.closest?anchor.closest('li'):null;if(!li)return 2;"
                + "var cls=li.className||'';if(cls.indexOf('jstree-open')>=0)return 2;"
                + "var kids=li.children||[],ul=null,ocl=null;for(var k=0;k<kids.length;k++){var ch=kids[k];if(ch.tagName==='UL')ul=ch;if((ch.className||'').indexOf('jstree-ocl')>=0)ocl=ch;}"
                + "if(ul){var st=window.getComputedStyle?getComputedStyle(ul):null;if(!st||st.display!=='none')return 2;}"
                + "try{if(window.jQuery){var tr=jQuery(li).closest('.jstree');if(tr.length&&tr.jstree){tr.jstree('open_node',li);return 1;}}}catch(e){}"
                + "if(ocl&&ocl.click){ocl.click();return 1;}anchor.click();return 1;}"
                + "var anchors=[].slice.call(document.querySelectorAll('a[id$=_anchor],a')),cq=" + JSONObject.quote(childQuery) + ";"
                + "var child=null,bestLen=1e9;for(var i=0;i<anchors.length;i++){if(!visible(anchors[i]))continue;var t=anchors[i].innerText||anchors[i].textContent||'';if(sameish(t,cq)&&norm(t).length<bestLen){child=anchors[i];bestLen=norm(t).length;}}"
                + "if(child)return String(openNode(child));"
                + "var pq=" + JSONObject.quote(parentQuery) + ",parent=null,parentLen=1e9;"
                + "for(var j=0;j<anchors.length;j++){if(!visible(anchors[j]))continue;var tx=anchors[j].innerText||anchors[j].textContent||'';"
                + "var ok=" + (exactParent ? "norm(tx)===norm(pq)||sameish(tx,pq)" : "sameish(tx,pq)") + ";if(ok&&norm(tx).length<parentLen){parent=anchors[j];parentLen=norm(tx).length;}}"
                + "if(!parent)return '0';openNode(parent);return '1';})()";
    }


    private String treeClickLessonScript(CurriculumLesson lesson) {
        return "(function(){"
                + "function digits(s){var ar='٠١٢٣٤٥٦٧٨٩',o='';s=s||'';for(var i=0;i<s.length;i++){var k=ar.indexOf(s[i]);o+=k>=0?String(k):s[i];}return o;}"
                + "function norm(s){return digits((s||'').replace(/[\\u064B-\\u065F\\u0670\\u0640]/g,'').replace(/[أإآ]/g,'ا').replace(/ى/g,'ي').replace(/\\s+/g,' ').trim());}"
                + "function compact(s){return norm(s).replace(/[^\\p{L}\\p{N}]+/gu,'');}"
                + "function numCode(s){var m=norm(s).match(/([0-9]+)\\s*[-–]\\s*([0-9]+)/);return m?m[1]+'-'+m[2]:'';}"
                + "function rawCode(s){return compact(s);}"
                + "function words(s){var a=norm(s).replace(/[0-9]+\\s*[-–]\\s*[0-9]+/g,' ').split(/\\s+/),o=[];for(var i=0;i<a.length;i++){var w=a[i].replace(/[^\\p{L}\\p{N}]/gu,'');if(w.length>=2&&o.indexOf(w)<0)o.push(w);}return o;}"
                + "function textScore(raw,targetTitle){var t=norm(raw),cw=words(raw),tw=words(targetTitle),hits=0;for(var i=0;i<tw.length;i++){if(cw.indexOf(tw[i])>=0||t.indexOf(tw[i])>=0)hits++;}if(!tw.length)return 0;var r=hits/tw.length;var s=Math.round(r*320);var ct=compact(targetTitle),cr=compact(raw);if(ct&&cr.indexOf(ct)>=0)s+=360;return s;}"
                + "function sameish(a,b){a=norm(a);b=norm(b);if(!a||!b)return false;var ac=compact(a),bc=compact(b);return a===b||a.indexOf(b)>=0||b.indexOf(a)>=0||ac.indexOf(bc)>=0||bc.indexOf(ac)>=0;}"
                + "var targetCode=" + JSONObject.quote(lesson.noorCode()) + ",targetTitle=" + JSONObject.quote(lesson.title) + ",unitQ=" + JSONObject.quote(lesson.unit) + ";"
                + "var all=[].slice.call(document.querySelectorAll('a[id$=_anchor],a')),scope=document,bestUnit=null,unitLen=1e9;"
                + "if(unitQ){for(var u=0;u<all.length;u++){var ur=all[u].getBoundingClientRect();if(ur.width===0&&ur.height===0)continue;var ut=all[u].innerText||all[u].textContent||'';if(sameish(ut,unitQ)&&norm(ut).length<unitLen){bestUnit=all[u];unitLen=norm(ut).length;}}}"
                + "if(bestUnit&&bestUnit.closest){var li=bestUnit.closest('li');if(li)scope=li;}"
                + "var a=[].slice.call(scope.querySelectorAll?scope.querySelectorAll('a[id$=_anchor],a'):all),best=null,bestScore=-1;"
                + "var tc=numCode(targetCode),trc=rawCode(targetCode);"
                + "for(var i=0;i<a.length;i++){if(a[i]===bestUnit)continue;var r=a[i].getBoundingClientRect();if(r.width===0&&r.height===0)continue;var raw=a[i].innerText||a[i].textContent||'';if(!raw)continue;"
                + "var cc=numCode(raw),crc=rawCode(raw),score=textScore(raw,targetTitle);"
                + "if(tc&&cc===tc)score+=900;else if(trc&&crc.indexOf(trc)>=0)score+=650;"
                + "if(tc&&cc){var p=tc.split('-'),q=cc.split('-');if(p.length===2&&q.length===2&&p[0]===q[1]&&p[1]===q[0]&&score>=160)score+=120;}"
                + "if(score>bestScore){best=a[i];bestScore=score;}}"
                + "if(!best||bestScore<180)return '0';best.click();return '1';})()";
    }


    private boolean lessonMatchesNoorTitle(String foundTitle, CurriculumLesson lesson) {
        if (foundTitle == null || lesson == null) return false;
        String full = normalizeArabicTitle(foundTitle);
        String visibleCode = normalizeArabicTitle(lesson.noorCode());
        if (!visibleCode.isEmpty() && full.contains(visibleCode)) return true;
        String title = normalizeArabicTitle(lesson.title);
        return !title.isEmpty() && (full.contains(title) || title.contains(full));
    }


    private static String latinDigits(String s) {
        String ar = "٠١٢٣٤٥٦٧٨٩";
        StringBuilder out = new StringBuilder();
        if (s == null) return "";
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int k = ar.indexOf(ch);
            out.append(k >= 0 ? (char)('0' + k) : ch);
        }
        return out.toString();
    }

    private static String normalizeArabicTitle(String s) {
        if (s == null) return "";
        return latinDigits(s)
                .replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "")
                .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي')
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private void waitForAutoLessonForm(CurriculumLesson lesson, int attempt) {
        if (!autoActive) return;
        String js = "(function(){var t=document.getElementById('PreparationTitle');"
                + "var v=t?(t.value||''):'';"
                + "var xs=document.querySelectorAll('input'),n=0;for(var xi=0;xi<xs.length;xi++){if((xs[xi].name||'').indexOf('Preparation[criteria]')===0)n++;}"
                + "var ed=document.querySelectorAll('iframe,[contenteditable=true],textarea');"
                + "var sel=document.querySelectorAll('select');"
                + "var pub=document.querySelectorAll('input[id^=publishdate-]');"
                + "return JSON.stringify({title:v,titlePresent:!!t,criteria:n,editors:ed.length,selects:sel.length,publishRows:pub.length,readyState:document.readyState||''});})()";
        webView.evaluateJavascript(js, raw -> {
            boolean ready = false;
            String foundTitle = "";
            int criteria = 0;
            try {
                JSONObject o = new JSONObject(decodeJsString(raw));
                foundTitle = o.optString("title", "");
                criteria = o.optInt("criteria", 0);
                ready = lessonMatchesNoorTitle(foundTitle, lesson) && criteria > 0;
            } catch (Exception e) {
                diagnostics.logException("lesson_form_probe_parse_error", e);
            }

            if (ready || attempt == 0 || attempt % 5 == 0 || attempt >= 35) {
                try {
                    JSONObject meta = new JSONObject();
                    meta.put("attempt", attempt);
                    meta.put("found_title", foundTitle);
                    meta.put("criteria_count", criteria);
                    meta.put("title_matches", lessonMatchesNoorTitle(foundTitle, lesson));
                    meta.put("ready", ready);
                    logAutoStage("lesson_form_probe", lesson, meta);
                } catch (Exception ignored) {}
                if (!ready && (attempt == 0 || attempt >= 35)) {
                    captureNoorDiagnosticSnapshot("lesson_form_probe_attempt_" + attempt, lesson);
                }
            }

            if (!ready) {
                if (attempt < 36) {
                    if (attempt > 0 && attempt % 5 == 0) {
                        status.setText("نور تأخر في تحميل " + lesson.displayName()
                                + " — أعيد اختيار الدرس (محاولة " + (attempt / 5 + 1) + ")...");
                        webView.evaluateJavascript(treeClickLessonScript(lesson), ignored ->
                                webView.postDelayed(() -> waitForAutoLessonForm(lesson, attempt + 1), 1200));
                    } else {
                        status.setText("أنتظر عنوان وأهداف " + lesson.displayName()
                                + " — " + Math.min(30, attempt + 1) + "ث");
                        webView.postDelayed(() -> waitForAutoLessonForm(lesson, attempt + 1), 800);
                    }
                } else {
                    stopAutoWithError("نور لم يحمّل عنوان وأهداف الدرس «" + lesson.displayName()
                            + "» بعد إعادة اختياره عدة مرات.");
                }
                return;
            }

            currentTitle = foundTitle;
            currentDbEntry = generatedStore.get(lesson.code);
            if (currentDbEntry == null) {
                stopAutoWithError("قاعدة البيانات لا تحتوي على «" + lesson.displayName() + "».");
                return;
            }
            currentPreparation = currentDbEntry.preparation;
            fillAutoLessonContent(lesson);
        });
    }

    private void generateSourcePreparationAndFill(CurriculumLesson lesson, String foundTitle) {
        if (!autoActive) return;
        status.setText("أحلل الخطة والمادة للدرس " + lesson.displayName() + "...");

        final String lessonTitle = foundTitle;
        final List<String> noorOutcomes = new ArrayList<>(currentOutcomes);

        worker.execute(() -> {
            try {
                PdfLessonContext.Result context = PdfLessonContext.extract(
                        this, studyPlanPdfUri, subjectMaterialPdfUri, lessonTitle);
                runOnUiThread(() -> status.setText("وجدت سياق الدرس: " + context.summary() + " — أطلب التحضير من ذكاء خطوة..."));

                AiPreparationClient.SourceResult generated = generateFromSourcesWithRetry(
                        lessonTitle, noorOutcomes, context.planContext, context.materialContext, lesson.code);

                runOnUiThread(() -> {
                    if (!autoActive || autoCurrentIndex < 0 || autoCurrentIndex >= autoLessons.size()) return;
                    CurriculumLesson active = autoLessons.get(autoCurrentIndex);
                    if (!active.code.equals(lesson.code)) return;

                    currentSourceResult = generated;
                    currentPreparation = generated.preparation;
                    status.setText("تم إنشاء تحضير " + lesson.displayName() + " من الملفين — أعبئ نور...");
                    fillAutoLessonContent(lesson);
                });
            } catch (Exception e) {
                runOnUiThread(() -> stopAutoWithError("تعذر إنشاء تحضير «" + lesson.displayName()
                        + "» من الملفين: " + e.getMessage()));
            }
        });
    }

    private AiPreparationClient.SourceResult generateFromSourcesWithRetry(
            String title,
            List<String> outcomes,
            String planContext,
            String materialContext,
            String lessonCode) throws Exception {
        Exception last = null;
        final int attempts = 3;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (attempt > 1) {
                    try {
                        JSONObject meta = new JSONObject();
                        meta.put("code", lessonCode == null ? "" : lessonCode);
                        meta.put("title", title == null ? "" : title);
                        meta.put("attempt", attempt);
                        diagnostics.log("ai_request_retry", meta);
                    } catch (Exception ignored) {}
                }
                return aiClient.generateFromSources(title, outcomes, planContext, materialContext);
            } catch (Exception e) {
                last = e;
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean transientError =
                        msg.contains("Connection reset")
                        || msg.contains("timed out")
                        || msg.contains("timeout")
                        || msg.contains("HTTP 429")
                        || msg.contains("HTTP 500")
                        || msg.contains("HTTP 502")
                        || msg.contains("HTTP 503")
                        || msg.contains("HTTP 504")
                        || msg.contains("Unable to resolve host")
                        || msg.contains("failed to connect");
                if (!transientError || attempt >= attempts) break;

                try {
                    JSONObject meta = new JSONObject();
                    meta.put("code", lessonCode == null ? "" : lessonCode);
                    meta.put("title", title == null ? "" : title);
                    meta.put("attempt", attempt);
                    meta.put("error", msg);
                    diagnostics.log("ai_request_transient_error", meta);
                } catch (Exception ignored) {}

                try {
                    Thread.sleep(900L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw last == null ? new IllegalStateException("تعذر الاتصال بخدمة الذكاء.") : last;
    }

    private void fillAutoLessonContent(CurriculumLesson lesson) {
        if (!autoActive || currentPreparation == null) return;
        status.setText("أعبئ محتوى " + lesson.displayName() + "...");
        List<String> sourceStrategies = currentDbEntry != null && !currentDbEntry.strategies.isEmpty()
                ? currentDbEntry.strategies : lesson.strategies;
        List<String> sourceResources = currentDbEntry != null && !currentDbEntry.resources.isEmpty()
                ? currentDbEntry.resources : lesson.resources;
        String sourceLevel = currentDbEntry != null && currentDbEntry.level != null
                ? currentDbEntry.level : lesson.level;
        webView.evaluateJavascript(guidedContentScript(currentPreparation, lesson, sourceStrategies, sourceResources, sourceLevel), raw -> {
            int editors = 0;
            JSONObject fillResult = null;
            try {
                fillResult = new JSONObject(decodeJsString(raw));
                editors = fillResult.optInt("editors", 0);
                logAutoStage("content_fill_result", lesson, fillResult);
            } catch (Exception e) {
                diagnostics.logException("content_fill_parse_error", e);
            }
            if (editors < 4) {
                captureNoorDiagnosticSnapshot("content_fill_incomplete", lesson);
                stopAutoWithError("لم أستطع تعبئة جميع حقول محتوى الدرس.");
                return;
            }

            webView.evaluateJavascript(autoFlagsScript(), flagsRaw -> {
                try {
                    JSONObject flags = new JSONObject(decodeJsString(flagsRaw));
                    logAutoStage("preparation_flags_result", lesson, flags);
                } catch (Exception e) {
                    diagnostics.logException("preparation_flags_parse_error", e);
                }
                attachAutoPdf(lesson);
            });
        });
    }

    private String autoFlagsScript() {
        return "(function(){"
                + "function setCheck(id,on){var e=document.getElementById(id);if(!e)return 0;if(!!e.checked!==!!on)e.click();return !!e.checked===!!on?1:0;}"
                + "var a=setCheck('PreparationSemesterPlan',true);"
                + "var b=setCheck('PreparationAllowSharePreparations',true);"
                + "var c=setCheck('PreparationShowInWeeklyStudyPlan',true);"
                + "var g=setCheck('global',true);"
                + "return JSON.stringify({semester:a,share:b,weekly:c,global:g});})()";
    }

    private void attachAutoPdf(CurriculumLesson lesson) {
        if (!autoActive) return;
        status.setText("أضيف صور " + lesson.displayName() + " من قاعدة البيانات...");
        worker.execute(() -> {
            try {
                List<Bitmap> bitmaps = LessonImageCache.load(this, lesson.code);
                try {
                    JSONObject meta = new JSONObject();
                    meta.put("cached_images", bitmaps.size());
                    logAutoStage("lesson_images_loaded", lesson, meta);
                } catch (Exception ignored) {}
                if (bitmaps.isEmpty()) {
                    throw new IllegalStateException("صور الدرس غير موجودة في قاعدة البيانات.");
                }
                List<String> base64Images = new ArrayList<>();
                for (Bitmap bitmap : bitmaps) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 68, out);
                    base64Images.add(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                }
                runOnUiThread(() -> {
                    status.setText("صور الدرس جاهزة من القاعدة — أضيفها للتحضير...");
                    appendAutoImageAt(base64Images, 0, lesson);
                });
            } catch (Exception e) {
                runOnUiThread(() -> stopAutoWithError("تعذر تحميل صور الدرس من قاعدة البيانات: " + e.getMessage()));
            }
        });
    }


    private void appendAutoImageAt(List<String> images, int index, CurriculumLesson lesson) {
        if (!autoActive) return;
        if (index >= images.size()) {
            applyAutoSchedule(lesson);
            return;
        }
        String html = (index == 0 ? "<hr><p><strong>تمارين الدرس من المادة العلمية المرفقة</strong></p>" : "")
                + "<p><img src='data:image/jpeg;base64," + images.get(index)
                + "' style='max-width:100%;height:auto;display:block;margin:12px auto;' /></p>";
        String js = "(function(){" + baseHelpers()
                + "return appendToEditor('إجراءات سير الدرس'," + JSONObject.quote(html) + ");"
                + "})()";
        webView.evaluateJavascript(js, raw -> {
            int ok = parseJsInt(raw);
            try {
                JSONObject meta = new JSONObject();
                meta.put("image_index", index);
                meta.put("image_total", images.size());
                meta.put("result", ok);
                logAutoStage("lesson_image_append_result", lesson, meta);
            } catch (Exception ignored) {}
            if (ok > 0) {
                appendAutoImageAt(images, index + 1, lesson);
            } else {
                stopAutoWithError("لم أستطع إضافة صور PDF داخل «إجراءات سير الدرس».");
            }
        });
    }

    private void applyAutoSchedule(CurriculumLesson lesson) {
        List<String> dates = GenericSchedulePlanner.datesFor(lesson, autoLessons);
        if (dates.isEmpty()) {
            stopAutoWithError("لم أستطع حساب تواريخ النشر من الخطة.");
            return;
        }
        status.setText("أجهز " + dates.size() + " تاريخ/تواريخ نشر حسب الخطة...");
        try {
            JSONObject meta = new JSONObject();
            meta.put("dates", new JSONArray(dates));
            meta.put("date_count", dates.size());
            logAutoStage("schedule_dates_ready", lesson, meta);
        } catch (Exception ignored) {}
        ensurePublicationRows(lesson, dates, 0);
    }

    private void ensurePublicationRows(CurriculumLesson lesson, List<String> dates, int attempt) {
        if (!autoActive) return;
        String js = "(function(){"
                + "var es=[].slice.call(document.querySelectorAll('input[id^=publishdate-]')).filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;});"
                + "if(es.length>=" + dates.size() + ")return String(es.length);"
                + "var first=document.getElementById('publishdate-1'),global=document.getElementById('global');"
                + "var y1=first?first.getBoundingClientRect().top+window.scrollY:0,y2=global?global.getBoundingClientRect().top+window.scrollY:1e12;"
                + "var links=[].slice.call(document.querySelectorAll('a'));var best=null,dist=1e12;"
                + "for(var i=0;i<links.length;i++){var r=links[i].getBoundingClientRect();if(r.width===0&&r.height===0)continue;"
                + "var y=r.top+window.scrollY,t=(links[i].innerText||links[i].textContent||'').replace(/\\s+/g,' ').trim();"
                + "if(t!=='إضافة'||y>=y2)continue;var d=Math.abs(y-y1);if(d<dist){dist=d;best=links[i];}}"
                + "if(best){best.click();return '0';}return '-1';})()";
        webView.evaluateJavascript(js, raw -> {
            int count = parseJsInt(raw);
            if (attempt == 0 || attempt % 4 == 0 || count >= dates.size() || attempt >= 15) {
                try {
                    JSONObject meta = new JSONObject();
                    meta.put("attempt", attempt);
                    meta.put("rows_found", count);
                    meta.put("rows_needed", dates.size());
                    logAutoStage("publication_rows_probe", lesson, meta);
                } catch (Exception ignored) {}
            }
            if (count >= dates.size()) {
                configureAutoPublicationRow(lesson, dates, 0, 0);
            } else if (count == 0 && attempt < 16) {
                webView.postDelayed(() -> ensurePublicationRows(lesson, dates, attempt + 1), 650);
            } else if (count > 0 && count < dates.size() && attempt < 16) {
                webView.postDelayed(() -> ensurePublicationRows(lesson, dates, attempt + 1), 500);
            } else {
                stopAutoWithError("تعذر إنشاء العدد المطلوب من أسطر «تاريخ النشر». وجد التطبيق "
                        + Math.max(0, count) + " سطرًا بينما يحتاج " + dates.size() + ".");
            }
        });
    }

    private void configureAutoPublicationRow(CurriculumLesson lesson, List<String> dates, int index, int retry) {
        if (!autoActive) return;
        if (index >= dates.size()) {
            finalizeAutoLessonAndSave(lesson);
            return;
        }
        int row = index + 1;
        String date = dates.get(index);
        status.setText("أحدد تاريخ النشر " + date + " وأسبوع العمل (" + row + "/" + dates.size() + ")...");
        webView.evaluateJavascript(setAutoPublicationRowScript(row, date), raw -> {
            int ok = parseJsInt(raw);
            try {
                JSONObject meta = new JSONObject();
                meta.put("row", row);
                meta.put("date", date);
                meta.put("retry", retry);
                meta.put("result", ok);
                logAutoStage("publication_row_config_result", lesson, meta);
            } catch (Exception ignored) {}
            if (ok <= 0) {
                if (retry < 7) {
                    webView.postDelayed(() -> configureAutoPublicationRow(lesson, dates, index, retry + 1), 500);
                } else {
                    stopAutoWithError("تعذر تعيين تاريخ النشر/أسبوع العمل للتاريخ " + date + ".");
                }
                return;
            }
            webView.postDelayed(() -> selectAutoTimeslots(lesson, dates, index, 0), 1100);
        });
    }

    private String setAutoPublicationRowScript(int row, String date) {
        return "(function(){" + baseHelpers()
                + "var d=document.getElementById('publishdate-" + row + "');var w=document.getElementById('week_id-" + row + "');"
                + "if(!d||!w)return '0';d.value=" + JSONObject.quote(date) + ";fire(d);"
                + "var opts=[].slice.call(w.options||[]),target=null;"
                + "for(var i=0;i<opts.length;i++){var tx=opts[i].text||'';var ms=tx.match(/(20\\d{2}-\\d{2}-\\d{2})/g);"
                + "if(ms&&ms.length>=2&&" + JSONObject.quote(date) + ">=ms[0]&&" + JSONObject.quote(date) + "<=ms[1]){target=opts[i];break;}}"
                + "if(!target)return '0';w.value=target.value;fire(w);return '1';})()";
    }

    private void selectAutoTimeslots(CurriculumLesson lesson, List<String> dates, int index, int attempt) {
        if (!autoActive) return;
        int row = index + 1;
        String js = "(function(){var target='data[plane][date" + row + "][timeslot][]';"
                + "var a=[].slice.call(document.querySelectorAll('input[type=checkbox]')).filter(function(e){var r=e.getBoundingClientRect();return e.name===target&&r.width>0&&r.height>0;});"
                + "var n=0;for(var i=0;i<a.length;i++){if(!a[i].checked)a[i].click();if(a[i].checked)n++;}return String(n);})()";
        webView.evaluateJavascript(js, raw -> {
            int selected = parseJsInt(raw);
            if (attempt == 0 || selected > 0 || attempt >= 8) {
                try {
                    JSONObject meta = new JSONObject();
                    meta.put("row", row);
                    meta.put("date", dates.get(index));
                    meta.put("attempt", attempt);
                    meta.put("selected_timeslots", selected);
                    logAutoStage("timeslot_selection_result", lesson, meta);
                } catch (Exception ignored) {}
            }
            if (selected <= 0) {
                if (attempt < 9) {
                    status.setText("أنتظر نور ليحمّل حصص " + dates.get(index) + "...");
                    webView.postDelayed(() -> selectAutoTimeslots(lesson, dates, index, attempt + 1), 700);
                } else {
                    stopAutoWithError("لم تظهر حصص المادة للتاريخ " + dates.get(index) + ".");
                }
                return;
            }
            status.setText("تم تحديد " + selected + " حصة في " + dates.get(index) + ".");
            webView.postDelayed(() -> configureAutoPublicationRow(lesson, dates, index + 1, 0), 350);
        });
    }

    private void finalizeAutoLessonAndSave(CurriculumLesson lesson) {
        if (!autoActive) return;
        webView.evaluateJavascript(autoFlagsScript(), raw -> {
            try {
                JSONObject flags = new JSONObject(decodeJsString(raw));
                logAutoStage("final_flags_result", lesson, flags);
            } catch (Exception e) {
                diagnostics.logException("final_flags_parse_error", e);
            }
            status.setText("اكتمل " + lesson.displayName() + " — أحفظ في نور...");
            autoAwaitingSave = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_AUTO_PENDING_INDEX, autoCurrentIndex)
                    .apply();

            String js = "(function(){var t=document.getElementById('PreparationTitle');var f=t?t.form:null;"
                    + "if(!f)return '0';var s=f.querySelector('input[type=submit],button[type=submit]');"
                    + "if(!s)return '0';s.click();return '1';})()";
            webView.evaluateJavascript(js, saveRaw -> {
                int ok = parseJsInt(saveRaw);
                try {
                    JSONObject meta = new JSONObject();
                    meta.put("save_button_clicked", ok > 0);
                    meta.put("raw_result", saveRaw == null ? "" : saveRaw);
                    logAutoStage("save_submit_result", lesson, meta);
                } catch (Exception ignored) {}
                if (ok <= 0) {
                    captureNoorDiagnosticSnapshot("save_button_not_found", lesson);
                    autoAwaitingSave = false;
                    stopAutoWithError("لم أجد زر حفظ نموذج التحضير.");
                    return;
                }
                webView.postDelayed(() -> {
                    if (autoActive && autoAwaitingSave
                            && webView.getUrl() != null
                            && webView.getUrl().contains("/add_preparation")) {
                        autoAwaitingSave = false;
                        stopAutoWithError("نور لم يؤكد الحفظ خلال الوقت المتوقع. راجع الصفحة لمعرفة رسالة التحقق.");
                    }
                }, 12000);
            });
        });
    }

    private void markAutoLessonSaved() {
        if (!autoActive) return;
        int done = autoCurrentIndex;
        CurriculumLesson savedLesson = done >= 0 && done < autoLessons.size() ? autoLessons.get(done) : currentLesson;
        try {
            JSONObject meta = new JSONObject();
            meta.put("saved_index", done);
            meta.put("redirect_location", safeNoorLocation(webView == null ? "" : webView.getUrl()));
            logAutoStage("lesson_save_confirmed", savedLesson, meta);
        } catch (Exception ignored) {}
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_AUTO_LAST_INDEX, done)
                .putInt(KEY_AUTO_PENDING_INDEX, done + 1)
                .putInt(KEY_AUTO_TARGET_INDEX, autoTargetIndex)
                .apply();

        autoAwaitingSave = false;
        autoPreparingIndex = -1;
        currentSourceResult = null;
        currentDbEntry = null;
        currentPreparation = null;
        updateAutoProgressText(done);

        if (done >= autoTargetIndex) {
            stopAutoRun(true);
            return;
        }

        autoCurrentIndex = done + 1;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_AUTO_PENDING_INDEX, autoCurrentIndex)
                .apply();
        status.setText("تم حفظ " + autoLessons.get(done).displayName()
                + " — أنتقل إلى " + autoLessons.get(autoCurrentIndex).displayName() + "...");
        webView.postDelayed(this::navigateToAddPreparation, 700);
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
                    currentLesson = findDiscoveredLesson(title);
                    currentPreparation = findGeneratedPreparation(title);

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
                toast("لم أتعرف على درس من قاعدة المنهج المكتشفة. افتح إضافة تحضير واختر الدرس ثم اضغط «تعلم موجه» من جديد.");
            }
        });
    }

    private void fillGuidedLearningContent(String title, CurriculumLesson lesson, LessonPreparation prep) {
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

    private String guidedContentScript(LessonPreparation p, CurriculumLesson lesson) {
        List<String> strategies = lesson == null
                ? Arrays.asList("التعلم التعاوني", "التعلم بالاكتشاف", "التعلم المبني على حل المشكلات", "التعلم المتمايز")
                : lesson.strategies;
        List<String> resources = lesson == null
                ? Arrays.asList("الكتاب", "السبورة التقليدية", "جهاز عرض البيانات")
                : lesson.resources;
        String level = lesson == null ? "الفهم" : lesson.level;
        return guidedContentScript(p, lesson, strategies, resources, level);
    }

    private String guidedContentScript(LessonPreparation p, CurriculumLesson lesson,
                                       List<String> strategyList, List<String> resourceList, String level) {
        String strategies = new JSONArray(strategyList).toString();
        String resources = new JSONArray(resourceList).toString();

        return "(function(){" + baseHelpers()
                + "window.__khutwaNoorLearningMute=true;"
                + "try{"
                + "var oc=0,arr=objectiveBoxes();for(var i=0;i<arr.length;i++){if(!arr[i].checked)arr[i].click();if(arr[i].checked)oc++;}"
                + "var strategyCount=setMultiSelect('strategies'," + strategies + ");"
                + "var resourceCount=setMultiSelect('teaching_aids'," + resources + ");"
                + "var checks=strategyCount+resourceCount;"
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
                currentLesson = findDiscoveredLesson(currentTitle);
                currentPreparation = findGeneratedPreparation(currentTitle);

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
                    status.setText("تم التعرف على " + currentLesson.displayName() + " — "
                            + currentLesson.periods + " حصص.");
                } else {
                    msg.append("\n\nلم أتعرف على الدرس ضمن قاعدة المنهج المكتشفة.");
                    status.setText("تم الفحص، لكن الدرس غير موجود في قاعدة المنهج.");
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

        currentLesson = findDiscoveredLesson(currentTitle);
        currentPreparation = findGeneratedPreparation(currentTitle);

        if (currentPreparation != null) {
            status.setText("التحضير جاهز داخل التطبيق — لا يوجد استهلاك توكنات.");
            showPreparationPreview("قاعدة المنهج الجاهزة — 0 توكن");
            return;
        }

        status.setText("الدرس غير موجود في قاعدة البيانات المكتشفة. أعد تجهيز قاعدة البيانات.");
        toast("لا يوجد تحضير مخزن لهذا الدرس.");
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
                    currentPreparation = findGeneratedPreparation(title);
                    status.setText("تعذر اتصال AI ولا يوجد تحضير بديل خارج قاعدة البيانات.");
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

        currentLesson = findDiscoveredLesson(currentTitle);
        if (currentPreparation == null) {
            currentPreparation = findGeneratedPreparation(currentTitle);
            if (currentPreparation == null) {
                toast("لا يوجد تحضير مخزن لهذا الدرس.");
                return;
            }
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
        // Legacy manual-fill path. Automatic publishing chooses the work week
        // from the discovered publication date instead of any hardcoded week number.
        finishFill(baseResult, 0, 0);
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
                + "\nالحصص المحددة: " + sessionsSelected
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
                + "if(tx.indexOf(norm('الحصة'))<0)continue;"
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

    private String fillScript(LessonPreparation p, CurriculumLesson lesson) {
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
        String startDate = lesson == null ? "" : lesson.periodStart; // reserved for timetable-aware exact date in next step

        return "(function(){" + baseHelpers()
                + "var oc=0,arr=objectiveBoxes();for(var i=0;i<arr.length;i++){if(!arr[i].checked)arr[i].click();if(arr[i].checked)oc++;}"
                + "var strategyCount=setMultiSelect('strategies'," + strategies + ");"
                + "var resourceCount=setMultiSelect('teaching_aids'," + resources + ");"
                + "var checks=strategyCount+resourceCount;"
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
                + "function setMultiSelect(id,names){var el=document.getElementById(id);if(!el)return 0;var opts=[].slice.call(el.options||[]),count=0;for(var z=0;z<opts.length;z++)opts[z].selected=false;for(var a=0;a<names.length;a++){var q=norm(names[a]),best=null;for(var b=0;b<opts.length;b++){var tx=norm(opts[b].text||'');if(tx===q){best=opts[b];break;}if(!best&&tx.indexOf(q)>=0)best=opts[b];}if(best&&!best.selected){best.selected=true;count++;}}fire(el);if(window.jQuery){try{window.jQuery(el).trigger('chosen:updated');}catch(e){}}return count;}"
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
        try { stopService(new Intent(this, AutoRunService.class)); } catch (Exception ignored) {}
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
