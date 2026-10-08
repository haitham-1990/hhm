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
import android.webkit.CookieManager;
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
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String NOOR_URL = "https://lms.moe.gov.om/teacher#networkfirst";
    private static final int REQUEST_PICK_PDF = 301;
    private static final String PREFS = "noor_helper";
    private static final String KEY_PDF_URI = "exercise_pdf_uri";

    private WebView webView;
    private TextView status;
    private String currentTitle = "";
    private final List<String> currentOutcomes = new ArrayList<>();
    private LessonPreparation currentPreparation;\n    private AiPreparationClient aiClient;

    private Uri exercisePdfUri;
    private final List<Bitmap> exerciseImages = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        buildUi();
        loadSavedPdf();
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
        title.setText("مساعد نور الشخصي 0.3.0");
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
        status.setText("سجّل الدخول في نور، ثم اختر الدرس. يمكنك ربط ملف PDF للتمارين من الأسفل.");
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

        Button prepare = makeButton("تجهيز AI");
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

        root.addView(pdfActions);
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
                String pdf = exercisePdfUri == null ? "لا يوجد PDF مرتبط." : "PDF التمارين مرتبط: " + pdfName(exercisePdfUri);
                status.setText("نور مفتوح. اختر الدرس ثم استخدم أزرار المساعد. " + pdf);
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
        }
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
                currentPreparation = null;
                String msg = "العنوان: " + (currentTitle.isEmpty() ? "لم يُكتشف" : currentTitle)
                        + "\nالأهداف المكتشفة: " + currentOutcomes.size()
                        + "\nمحررات النص المكتشفة: " + editorCount
                        + "\nPDF التمارين: " + (exercisePdfUri == null ? "غير مرتبط" : pdfName(exercisePdfUri));
                status.setText("تم الفحص: " + currentOutcomes.size() + " أهداف — " + currentTitle);
                if (showDialog) new AlertDialog.Builder(this)
                        .setTitle("نتيجة الفحص")
                        .setMessage(msg)
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
            toast("اضغط تجهيز AI مرة أخرى بعد اكتمال الفحص.");
            return;
        }

        LessonPreparation cached = aiClient.getCached(currentTitle);
        if (cached != null) {
            currentPreparation = cached;
            status.setText("التحضير الذكي محفوظ لهذا الدرس — بدون طلب AI جديد.");
            showPreparationPreview("من الذاكرة المحلية — صفر استهلاك جديد");
            return;
        }

        status.setText("أجهز التحضير بالذكاء الاصطناعي لأول مرة لهذا الدرس...");
        final String title = currentTitle;
        final List<String> outcomes = new ArrayList<>(currentOutcomes);

        worker.execute(() -> {
            try {
                LessonPreparation generated = aiClient.generateAndCache(title, outcomes);
                runOnUiThread(() -> {
                    if (!title.equals(currentTitle)) return;
                    currentPreparation = generated;
                    status.setText("تم إنشاء التحضير وحفظه محليًا. المرات القادمة لن تحتاج طلب AI جديد.");
                    showPreparationPreview("ذكاء اصطناعي — تم التخزين للاستخدام القادم");
                });
            } catch (Exception e) {
                LessonPreparation fallback = LessonGenerator.generate(title, outcomes);
                runOnUiThread(() -> {
                    if (!title.equals(currentTitle)) return;
                    currentPreparation = fallback;
                    status.setText("تعذر اتصال الذكاء؛ استخدمت التحضير المحلي المؤقت.");
                    showPreparationPreview("قالب محلي مؤقت — تعذر الاتصال بالذكاء");
                });
            }
        });
    }

    private void showPreparationPreview(String source) {
        TextView preview = new TextView(this);
        preview.setText("المصدر: " + source + "\n\n" + currentPreparation.preview());
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
        if (currentPreparation == null) {
            currentPreparation = aiClient.getCached(currentTitle);
            if (currentPreparation == null) currentPreparation = LessonGenerator.generate(currentTitle, currentOutcomes);
        }
        status.setText("أعبئ التحضير داخل نور...");
        webView.evaluateJavascript(fillScript(currentPreparation), raw -> {
            try {
                String jsonText = decodeJsString(raw);
                JSONObject result = new JSONObject(jsonText);
                int objectives = result.optInt("objectives", 0);
                int editors = result.optInt("editors", 0);
                int checks = result.optInt("checks", 0);
                int levels = result.optInt("levels", 0);

                if (exercisePdfUri != null) {
                    status.setText("تمت تعبئة النص. أبحث الآن عن تمارين الدرس داخل PDF...");
                    extractExercises(true);
                } else {
                    status.setText("اكتملت التعبئة المبدئية — راجع ثم اضغط حفظ في نور.");
                    new AlertDialog.Builder(this)
                            .setTitle("تمت التعبئة")
                            .setMessage("الأهداف: " + objectives
                                    + "\nالاستراتيجيات/المصادر: " + checks
                                    + "\nالمستويات: " + levels
                                    + "\nحقول النص: " + editors
                                    + "\n\nلا يوجد ملف تمارين مرتبط. راجع الحقول ثم اضغط حفظ في نور بنفسك.")
                            .setPositiveButton("مراجعة", null)
                            .show();
                }
            } catch (Exception e) {
                status.setText("انتهت محاولة التعبئة. راجع الحقول قبل الحفظ.");
                toast("تمت المحاولة، لكن بعض الحقول قد تحتاج تعبئة يدوية في هذه النسخة التجريبية.");
            }
        });
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
                + "\nالطريقة: " + (result.automatic ? "خريطة جاهزة/مطابقة تلقائية" : "تحديد يدوي")\n                + "\n" + result.matchedText);
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

    private String fillScript(LessonPreparation p) {
        String strategies = new JSONArray(Arrays.asList(
                "التعلم التعاوني", "التعلم بالاكتشاف", "التعلم المبني على حل المشكلات", "العصف الذهني", "التعلم المتمايز"
        )).toString();
        String resources = new JSONArray(Arrays.asList(
                "الكتاب", "السبورة التقليدية", "جهاز عرض البيانات"
        )).toString();

        return "(function(){" + baseHelpers()
                + "var oc=0,arr=objectiveBoxes();for(var i=0;i<arr.length;i++){if(!arr[i].checked)arr[i].click();if(arr[i].checked)oc++;}"
                + "var checks=0;function checkNames(names){for(var a=0;a<names.length;a++){var target=norm(names[a]);var cbs=[].slice.call(document.querySelectorAll('input[type=checkbox]'));for(var b=0;b<cbs.length;b++){var tx=norm(labelText(cbs[b]));if(tx&&tx.indexOf(target)>=0){if(!cbs[b].checked)cbs[b].click();if(cbs[b].checked)checks++;break;}}}}"
                + "checkNames(" + strategies + ");checkNames(" + resources + ");"
                + "var levels=0;var sels=[].slice.call(document.querySelectorAll('select'));for(var s=0;s<sels.length;s++){var opts=[].slice.call(sels[s].options||[]);for(var o=0;o<opts.length;o++){if(norm(opts[o].text).indexOf(norm('الفهم'))>=0){if(sels[s].multiple){opts[o].selected=true;}else{sels[s].value=opts[o].value;}sels[s].dispatchEvent(new Event('change',{bubbles:true}));if(window.jQuery){try{window.jQuery(sels[s]).trigger('chosen:updated').trigger('change');}catch(e){}}levels++;break;}}}"
                + "var ed=0;ed+=setEditor('المفاهيم'," + JSONObject.quote(toHtml(p.concepts)) + ");"
                + "ed+=setEditor('التهيئة'," + JSONObject.quote(toHtml(p.intro)) + ");"
                + "ed+=setEditor('إجراءات سير الدرس'," + JSONObject.quote(toHtml(p.procedures)) + ");"
                + "ed+=setEditor('التقويم التكويني'," + JSONObject.quote(toHtml(p.formative)) + ");"
                + "ed+=setEditor('التقويم الختامي'," + JSONObject.quote(toHtml(p.summative)) + ");"
                + "ed+=setEditor('ملاحظات ضمن خطة الدراسة الأسبوعية'," + JSONObject.quote(toHtml(p.weeklyNote)) + ");"
                + "disableByLabel('نشر التحضير للطلبة في خطة الدراسة الأسبوعية');disableByLabel('السماح للمعلمين بنسخ و استخدام تحضيري');"
                + "return JSON.stringify({objectives:oc,checks:checks,levels:levels,editors:ed});"
                + "})()";
    }

    private String baseHelpers() {
        return "function norm(s){return (s||'').replace(/[\\u064B-\\u065F\\u0670]/g,'').replace(/\\s+/g,' ').trim();}"
                + "function findText(t){var q=norm(t),els=[].slice.call(document.querySelectorAll('label,legend,h1,h2,h3,h4,h5,strong,span,div,p'));var best=null,score=1e9;for(var i=0;i<els.length;i++){var x=norm(els[i].innerText||els[i].textContent);if(!x||x.indexOf(q)<0)continue;var r=els[i].getBoundingClientRect();if(r.width===0&&r.height===0)continue;var sc=x.length-q.length;if(sc<score){score=sc;best=els[i];}}return best;}"
                + "function nearestAfter(anchor,sel,max){if(!anchor)return null;var ar=anchor.getBoundingClientRect(),cs=[].slice.call(document.querySelectorAll(sel)),best=null,d=1e9;for(var i=0;i<cs.length;i++){var r=cs[i].getBoundingClientRect();if(r.width===0&&r.height===0)continue;var dy=r.top-ar.bottom;if(dy>=-20&&dy<(max||700)&&dy<d){d=dy;best=cs[i];}}return best;}"
                + "function labelText(cb){if(!cb)return '';var t='';if(cb.id){var ls=[].slice.call(document.querySelectorAll('label'));for(var z=0;z<ls.length;z++){if(ls[z].htmlFor===cb.id){t=ls[z].innerText||ls[z].textContent||'';break;}}}if(!t&&cb.closest('label'))t=cb.closest('label').innerText||cb.closest('label').textContent||'';if(!t){var p=cb.parentElement;if(p)t=p.innerText||p.textContent||'';}return norm(t).replace(/^[-–•\\s]+/,'');}"
                + "function pos(el){if(!el)return -1;var r=el.getBoundingClientRect();return r.top+window.scrollY;}"
                + "function objectiveBoxes(){var a=findText('المخرجات التعليمية'),b=findText('الاستراتيجيات');var y1=pos(a),y2=pos(b);var all=[].slice.call(document.querySelectorAll('input[type=checkbox]'));var out=[];for(var i=0;i<all.length;i++){var r=all[i].getBoundingClientRect(),y=r.top+window.scrollY;if(y1>=0&&y2>y1&&y>y1-10&&y<y2-5){var tx=labelText(all[i]);if(tx&&tx.length>4)out.push(all[i]);}}if(out.length===0){for(var j=0;j<all.length;j++){var tx2=labelText(all[j]);if(/(يحدد|يحدّد|يجري|يطبق|يطبّق|يتعامل|الطلاب|الطالب)/.test(tx2))out.push(all[j]);}}return out;}"
                + "function visibleEditors(){var a=[].slice.call(document.querySelectorAll('iframe,[contenteditable=true],textarea'));return a.filter(function(e){var r=e.getBoundingClientRect();return r.width>20&&r.height>20;});}"
                + "function findEditor(label){var a=findText(label);if(!a)return null;var ar=a.getBoundingClientRect(),cs=visibleEditors(),best=null,d=1e9;for(var i=0;i<cs.length;i++){var r=cs[i].getBoundingClientRect(),dy=r.top-ar.bottom;if(dy>=-30&&dy<650&&dy<d){d=dy;best=cs[i];}}return best;}"
                + "function setEditor(label,html){var best=findEditor(label);if(!best)return 0;try{if(best.tagName==='IFRAME'){var doc=best.contentDocument||best.contentWindow.document;if(doc&&doc.body){doc.body.innerHTML=html;doc.body.dispatchEvent(new Event('input',{bubbles:true}));doc.body.dispatchEvent(new Event('change',{bubbles:true}));return 1;}}if(best.getAttribute('contenteditable')==='true'){best.innerHTML=html;best.dispatchEvent(new Event('input',{bubbles:true}));best.dispatchEvent(new Event('change',{bubbles:true}));return 1;}if(best.tagName==='TEXTAREA'){best.value=html.replace(/<br\\s*\\/?\\s*>/gi,'\\n').replace(/<[^>]+>/g,'');best.dispatchEvent(new Event('input',{bubbles:true}));best.dispatchEvent(new Event('change',{bubbles:true}));return 1;}}catch(e){}return 0;}"
                + "function appendToEditor(label,html){var best=findEditor(label);if(!best)return 0;try{if(best.tagName==='IFRAME'){var doc=best.contentDocument||best.contentWindow.document;if(doc&&doc.body){doc.body.insertAdjacentHTML('beforeend',html);doc.body.dispatchEvent(new Event('input',{bubbles:true}));doc.body.dispatchEvent(new Event('change',{bubbles:true}));return 1;}}if(best.getAttribute('contenteditable')==='true'){best.insertAdjacentHTML('beforeend',html);best.dispatchEvent(new Event('input',{bubbles:true}));best.dispatchEvent(new Event('change',{bubbles:true}));return 1;}if(best.tagName==='TEXTAREA'){best.value+='\\nتمارين الدرس مرفقة كصور في النسخة المرئية.';best.dispatchEvent(new Event('input',{bubbles:true}));best.dispatchEvent(new Event('change',{bubbles:true}));return 1;}}catch(e){}return 0;}"
                + "function disableByLabel(name){var q=norm(name),cbs=[].slice.call(document.querySelectorAll('input[type=checkbox]'));for(var i=0;i<cbs.length;i++){if(norm(labelText(cbs[i])).indexOf(q)>=0&&cbs[i].checked)cbs[i].click();}}";
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
