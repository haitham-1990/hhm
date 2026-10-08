package com.khutwa.noorhelper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {
    private static final String NOOR_URL = "https://lms.moe.gov.om/teacher#networkfirst";

    private WebView webView;
    private TextView status;
    private String currentTitle = "";
    private final List<String> currentOutcomes = new ArrayList<>();
    private LessonPreparation currentPreparation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
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
        title.setText("مساعد نور التجريبي 0.1.0");
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
        status.setText("سجّل الدخول في نور، ثم افتح إضافة التحضير واختر الدرس من شجرة المنهج.");
        status.setTextSize(13);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(dp(12), dp(4), dp(12), dp(6));
        root.addView(status);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(4), dp(4), dp(4), dp(6));

        Button scan = makeButton("فحص");
        scan.setOnClickListener(v -> scanLesson(true));
        actions.addView(scan, weightedButton());

        Button select = makeButton("كل الأهداف");
        select.setOnClickListener(v -> selectAllObjectives());
        actions.addView(select, weightedButton());

        Button prepare = makeButton("تجهيز");
        prepare.setOnClickListener(v -> generatePreview());
        actions.addView(prepare, weightedButton());

        Button fill = makeButton("تعبئة");
        fill.setOnClickListener(v -> fillPreparation());
        actions.addView(fill, weightedButton());

        root.addView(actions);
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1);
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
                status.setText("نور مفتوح. اختر الدرس ثم استخدم أزرار المساعد أسفل الشاشة.");
            }
        });
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
                        + "\nمحررات النص المكتشفة: " + editorCount;
                status.setText("تم الفحص: " + currentOutcomes.size() + " أهداف");
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
            toast("اضغط تجهيز مرة أخرى بعد اكتمال الفحص.");
            return;
        }
        currentPreparation = LessonGenerator.generate(currentTitle, currentOutcomes);
        TextView preview = new TextView(this);
        preview.setText(currentPreparation.preview());
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
        if (currentPreparation == null) currentPreparation = LessonGenerator.generate(currentTitle, currentOutcomes);
        status.setText("أعبئ التحضير داخل نور...");
        webView.evaluateJavascript(fillScript(currentPreparation), raw -> {
            try {
                String jsonText = decodeJsString(raw);
                JSONObject result = new JSONObject(jsonText);
                int objectives = result.optInt("objectives", 0);
                int editors = result.optInt("editors", 0);
                int checks = result.optInt("checks", 0);
                int levels = result.optInt("levels", 0);
                status.setText("اكتملت التعبئة المبدئية — راجع ثم اضغط حفظ في نور.");
                new AlertDialog.Builder(this)
                        .setTitle("تمت التعبئة")
                        .setMessage("الأهداف: " + objectives
                                + "\nالاستراتيجيات/المصادر: " + checks
                                + "\nالمستويات: " + levels
                                + "\nحقول النص: " + editors
                                + "\n\nراجع الحقول داخل نور، ثم اضغط زر حفظ بنفسك. التطبيق التجريبي لا يضغط حفظ تلقائيًا.")
                        .setPositiveButton("مراجعة", null)
                        .show();
            } catch (Exception e) {
                status.setText("انتهت محاولة التعبئة. راجع الحقول قبل الحفظ.");
                toast("تمت المحاولة، لكن بعض الحقول قد تحتاج تعبئة يدوية في هذه النسخة التجريبية.");
            }
        });
    }

    private void confirmClearSession() {
        new AlertDialog.Builder(this)
                .setTitle("تسجيل الخروج")
                .setMessage("سيتم مسح جلسة نور من هذا التطبيق فقط. لا يتم حفظ كلمة المرور بواسطة مساعد نور.")
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
                + "return JSON.stringify({title:title,outcomes:outcomes,editorCount:editors,url:location.href});" + "})()";
    }

    private String selectObjectivesScript() {
        return "(function(){" + baseHelpers()
                + "var arr=objectiveBoxes(),n=0;for(var i=0;i<arr.length;i++){if(!arr[i].checked){arr[i].click();}if(arr[i].checked)n++;}return String(n);" + "})()";
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
                + "return JSON.stringify({objectives:oc,checks:checks,levels:levels,editors:ed});" + "})()";
    }

    private String baseHelpers() {
        return "function norm(s){return (s||'').replace(/[\\u064B-\\u065F\\u0670]/g,'').replace(/\\s+/g,' ').trim();}"
                + "function findText(t){var q=norm(t),els=[].slice.call(document.querySelectorAll('label,legend,h1,h2,h3,h4,h5,strong,span,div,p'));var best=null,score=1e9;for(var i=0;i<els.length;i++){var x=norm(els[i].innerText||els[i].textContent);if(!x||x.indexOf(q)<0)continue;var r=els[i].getBoundingClientRect();if(r.width===0&&r.height===0)continue;var sc=x.length-q.length;if(sc<score){score=sc;best=els[i];}}return best;}"
                + "function nearestAfter(anchor,sel,max){if(!anchor)return null;var ar=anchor.getBoundingClientRect(),cs=[].slice.call(document.querySelectorAll(sel)),best=null,d=1e9;for(var i=0;i<cs.length;i++){var r=cs[i].getBoundingClientRect();if(r.width===0&&r.height===0)continue;var dy=r.top-ar.bottom;if(dy>=-20&&dy<(max||700)&&dy<d){d=dy;best=cs[i];}}return best;}"
                + "function labelText(cb){if(!cb)return '';var t='';if(cb.id){var ls=[].slice.call(document.querySelectorAll('label'));for(var z=0;z<ls.length;z++){if(ls[z].htmlFor===cb.id){t=ls[z].innerText||ls[z].textContent||'';break;}}}if(!t&&cb.closest('label'))t=cb.closest('label').innerText||cb.closest('label').textContent||'';if(!t){var p=cb.parentElement;if(p)t=p.innerText||p.textContent||'';}return norm(t).replace(/^[-–•\\s]+/,'');}"
                + "function pos(el){if(!el)return -1;var r=el.getBoundingClientRect();return r.top+window.scrollY;}"
                + "function objectiveBoxes(){var a=findText('المخرجات التعليمية'),b=findText('الاستراتيجيات');var y1=pos(a),y2=pos(b);var all=[].slice.call(document.querySelectorAll('input[type=checkbox]'));var out=[];for(var i=0;i<all.length;i++){var r=all[i].getBoundingClientRect(),y=r.top+window.scrollY;if(y1>=0&&y2>y1&&y>y1-10&&y<y2-5){var tx=labelText(all[i]);if(tx&&tx.length>4)out.push(all[i]);}}if(out.length===0){for(var j=0;j<all.length;j++){var tx2=labelText(all[j]);if(/(يحدد|يحدّد|يجري|يطبق|يطبّق|يتعامل|الطلاب|الطالب)/.test(tx2))out.push(all[j]);}}return out;}"
                + "function visibleEditors(){var a=[].slice.call(document.querySelectorAll('iframe,[contenteditable=true],textarea'));return a.filter(function(e){var r=e.getBoundingClientRect();return r.width>20&&r.height>20;});}"
                + "function setEditor(label,html){var a=findText(label);if(!a)return 0;var ar=a.getBoundingClientRect(),cs=visibleEditors(),best=null,d=1e9;for(var i=0;i<cs.length;i++){var r=cs[i].getBoundingClientRect(),dy=r.top-ar.bottom;if(dy>=-30&&dy<650&&dy<d){d=dy;best=cs[i];}}if(!best)return 0;try{if(best.tagName==='IFRAME'){var doc=best.contentDocument||best.contentWindow.document;if(doc&&doc.body){doc.body.innerHTML=html;doc.body.dispatchEvent(new Event('input',{bubbles:true}));doc.body.dispatchEvent(new Event('change',{bubbles:true}));return 1;}}if(best.getAttribute('contenteditable')==='true'){best.innerHTML=html;best.dispatchEvent(new Event('input',{bubbles:true}));best.dispatchEvent(new Event('change',{bubbles:true}));return 1;}if(best.tagName==='TEXTAREA'){best.value=html.replace(/<br\\s*\\/?\\s*>/gi,'\\n').replace(/<[^>]+>/g,'');best.dispatchEvent(new Event('input',{bubbles:true}));best.dispatchEvent(new Event('change',{bubbles:true}));return 1;}}catch(e){}return 0;}"
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
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
