package com.coachai.android.ui.home

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Message
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.coachai.android.domain.InsightState
import com.coachai.android.domain.TaskStatus
import com.coachai.android.domain.TodayPlanState
import com.coachai.android.domain.UnavailableReason
import com.coachai.android.domain.greetingFor
import com.coachai.android.domain.progressFor
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val HOME_ASSET_URL =
    "file:///android_asset/home/CoachAI_Android_Home_Approval_Mockup.html"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HomeWebView(
    uiState: HomeUiState,
    lightTheme: Boolean,
    onNavigateToSchedule: () -> Unit,
    onNavigateToCoach: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestUiState by rememberUpdatedState(uiState)
    val latestNavigateToSchedule by rememberUpdatedState(onNavigateToSchedule)
    val latestNavigateToCoach by rememberUpdatedState(onNavigateToCoach)
    val latestRetry by rememberUpdatedState(onRetry)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var pageFailed by remember { mutableStateOf(false) }
    val assetUrl = "$HOME_ASSET_URL?theme=${if (lightTheme) "light" else "dark"}"
    val webBackground = if (lightTheme) Color.White else Color(0xFF08080A)

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize().background(webBackground),
    ) {
        AndroidView(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        factory = { context ->
            WebView(context).apply {
                webView = this
                visibility = View.INVISIBLE
                setBackgroundColor(if (lightTheme) AndroidColor.WHITE else AndroidColor.rgb(8, 8, 10))
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setOnLongClickListener { true }

                settings.apply {
                    javaScriptEnabled = true
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    domStorageEnabled = false
                    databaseEnabled = false
                    allowFileAccess = true
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    blockNetworkLoads = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    loadsImagesAutomatically = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    textZoom = 115
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }

                addJavascriptInterface(
                    HomeNavigationBridge(
                        onNavigateToSchedule = { latestNavigateToSchedule() },
                        onNavigateToCoach = { latestNavigateToCoach() },
                        onRetry = { latestRetry() },
                    ),
                    "CoachAIAndroid",
                )

                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?,
                    ): Boolean = false
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = request.url != Uri.parse(assetUrl)

                    override fun onPageFinished(view: WebView, url: String) {
                        if (url != assetUrl) return
                        installHomeRuntime(view) {
                            pageFailed = false
                            renderHomeState(view, latestUiState) {
                                pageReady = true
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            pageReady = false
                            pageFailed = true
                            view.visibility = View.VISIBLE
                            view.loadDataWithBaseURL(
                                null,
                                localErrorPage(error.description.toString(), lightTheme),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView,
                        handler: SslErrorHandler,
                        error: android.net.http.SslError,
                    ) {
                        handler.cancel()
                    }
                }

                loadUrl(assetUrl)
            }
        },
        update = { view ->
            webView = view
            if (pageReady) renderHomeState(view, uiState)
        },
        )

        if (!pageReady && !pageFailed) {
            HomeLoadingSkeleton(lightTheme = lightTheme)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                removeJavascriptInterface("CoachAIAndroid")
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }
}

@Composable
private fun HomeLoadingSkeleton(lightTheme: Boolean) {
    val block = if (lightTheme) Color(0xFFE9E6E2) else Color(0xFF242428)
    val card = if (lightTheme) Color(0xFFF5F3F0) else Color(0xFF171719)
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Spacer(Modifier.fillMaxWidth(.42f).height(24.dp).clip(RoundedCornerShape(8.dp)).background(block))
        Spacer(Modifier.height(10.dp))
        Spacer(Modifier.fillMaxWidth(.7f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(block))
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(22.dp)).background(card))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.weight(1f).height(132.dp).clip(RoundedCornerShape(22.dp)).background(card))
            Spacer(Modifier.weight(1f).height(132.dp).clip(RoundedCornerShape(22.dp)).background(card))
        }
        Spacer(Modifier.height(14.dp))
        Spacer(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(22.dp)).background(card))
    }
}

private class HomeNavigationBridge(
    private val onNavigateToSchedule: () -> Unit,
    private val onNavigateToCoach: () -> Unit,
    private val onRetry: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun openSchedule() {
        mainHandler.post(onNavigateToSchedule)
    }

    @JavascriptInterface
    fun openCoach() {
        mainHandler.post(onNavigateToCoach)
    }

    @JavascriptInterface
    fun retryHome() {
        mainHandler.post(onRetry)
    }
}

private fun installHomeRuntime(webView: WebView, onInstalled: () -> Unit) {
    webView.evaluateJavascript(HOME_RUNTIME_SCRIPT) { onInstalled() }
}

private fun renderHomeState(
    webView: WebView,
    uiState: HomeUiState,
    onRendered: () -> Unit = {},
) {
    val payload = uiState.toJson().toString()
    webView.evaluateJavascript("window.CoachAIHome.render($payload);") {
        webView.visibility = View.VISIBLE
        onRendered()
    }
}

private fun HomeUiState.toJson(): JSONObject = when (this) {
    HomeUiState.Loading -> JSONObject()
        .put("date", "Today")
        .put("greeting", "Loading your day…")
        .put("subtitle", "Getting today ready for you.")
        .put("displayName", JSONObject.NULL)
        .put("quote", JSONObject().put("text", "A clear day starts with one small step.").put("source", "Zen Maestro"))
        .put("streakDays", JSONObject.NULL)
        .put("progress", unavailableJson("Loading progress", "Waiting for today's plan."))
        .put("schedule", unavailableJson("Loading schedule", "Waiting for today's plan."))
        .put("insight", unavailableJson("Loading insight", "Looking for a useful pattern in your day."))

    is HomeUiState.Ready -> {
        val snapshot = snapshot
        val displayName = snapshot.displayName?.takeIf(String::isNotBlank)
        val firstName = displayName?.trim()?.substringBefore(' ')?.takeIf(String::isNotBlank)
        val greeting = greetingFor(localTime)
        JSONObject()
            .put("date", snapshot.localDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH)))
            .put("greeting", firstName?.let { "$greeting, $it." } ?: "$greeting.")
            .put(
                "subtitle",
                if (snapshot.planState is TodayPlanState.Available) {
                    "You have a clear plan. Keep the next step small."
                } else {
                    "Start with one important task, then build around it."
                },
            )
            .put("displayName", displayName ?: JSONObject.NULL)
            .put(
                "quote",
                JSONObject()
                    .put("text", quote.text)
                    .put("source", quote.attribution ?: quote.category.label),
            )
            .put("streakDays", snapshot.streakDays ?: JSONObject.NULL)
            .put("progress", progressJson(snapshot.planState))
            .put("schedule", scheduleJson(snapshot.planState))
            .put("insight", insightJson(snapshot.insightState))
    }
}

private fun progressJson(planState: TodayPlanState): JSONObject {
    val summary = progressFor(planState)
    if (summary != null) {
        return JSONObject()
            .put("available", true)
            .put("percent", summary.completionPercent)
            .put("completed", summary.completed)
            .put("total", summary.total)
            .put("inProgress", summary.inProgress)
            .put("pending", summary.pending)
            .put("failed", summary.failed)
    }
    val (title, detail) = when (planState) {
        TodayPlanState.Loading -> "Loading progress" to "Waiting for today's plan."
        TodayPlanState.NoPlan -> "Your day is open" to "Add your first task when you're ready."
        TodayPlanState.EmptyPlan -> "Ready for your first task" to "Add something important and Zen Maestro will help you make room for it."
        is TodayPlanState.Unavailable -> "Progress unavailable" to planState.reason.userMessage()
        is TodayPlanState.Available -> "No progress yet" to "Complete your first task to start today's progress."
    }
    val retryable = planState is TodayPlanState.Unavailable &&
        planState.reason == UnavailableReason.NETWORK_ERROR
    return unavailableJson(title, detail, retryable)
}

private fun scheduleJson(planState: TodayPlanState): JSONObject {
    if (planState is TodayPlanState.Available && planState.tasks.isNotEmpty()) {
        val tasks = planState.tasks
            .sortedWith(compareBy<com.coachai.android.domain.HomeTask> { it.status == TaskStatus.COMPLETED }.thenBy { it.scheduledStart ?: "99:99" })
            .take(3)
        return JSONObject()
            .put("available", true)
            .put(
                "tasks",
                JSONArray().apply {
                    tasks.forEach { task ->
                        put(
                            JSONObject()
                                .put("time", task.scheduledStart?.take(5) ?: "—")
                                .put("title", task.title)
                                .put("meta", "${task.estimatedMinutes} min · Priority ${task.priority}")
                                .put("status", task.status.webValue)
                                .put("statusLabel", task.status.webLabel),
                        )
                    }
                },
            )
    }
    val (title, detail) = when (planState) {
        TodayPlanState.Loading -> "Loading schedule" to "Waiting for today's plan."
        TodayPlanState.NoPlan -> "Nothing planned yet" to "Add a task or plan ahead from Schedule."
        TodayPlanState.EmptyPlan -> "Your schedule is clear" to "Add a task when you're ready to shape the day."
        is TodayPlanState.Unavailable -> "Schedule unavailable" to planState.reason.userMessage()
        is TodayPlanState.Available -> "No scheduled tasks" to "Your day has room for something meaningful."
    }
    val retryable = planState is TodayPlanState.Unavailable &&
        planState.reason == UnavailableReason.NETWORK_ERROR
    return unavailableJson(title, detail, retryable)
}

private fun insightJson(state: InsightState): JSONObject = when (state) {
    is InsightState.Available -> JSONObject()
        .put("available", true)
        .put("title", state.title)
        .put("detail", state.evidence)
    is InsightState.OnboardingSuggestion -> JSONObject()
        .put("available", true)
        .put("title", state.title)
        .put("detail", state.detail)
    InsightState.Unavailable -> unavailableJson(
        "Smart insights aren't available yet",
        "Complete a few scheduled tasks to unlock a reliable personal insight.",
    )
}

private fun unavailableJson(title: String, detail: String, retryable: Boolean = false) = JSONObject()
    .put("available", false)
    .put("title", title)
    .put("detail", detail)
    .put("retryable", retryable)

private val TaskStatus.webValue: String
    get() = when (this) {
        TaskStatus.COMPLETED -> "done"
        TaskStatus.IN_PROGRESS -> "live"
        TaskStatus.PENDING -> "pending"
        TaskStatus.FAILED -> "failed"
    }

private val TaskStatus.webLabel: String
    get() = when (this) {
        TaskStatus.COMPLETED -> "Done"
        TaskStatus.IN_PROGRESS -> "Active"
        TaskStatus.PENDING -> "Pending"
        TaskStatus.FAILED -> "Failed"
    }

private fun UnavailableReason.userMessage(): String = when (this) {
    UnavailableReason.API_NOT_CONFIGURED -> "Today's plan isn't available right now. Try again shortly."
    UnavailableReason.AUTHENTICATION_REQUIRED -> "Your session has expired. Sign in again to see your day."
    UnavailableReason.NETWORK_ERROR -> "Couldn't refresh your day. Check your connection and try again."
    UnavailableReason.INVALID_RESPONSE -> "We couldn't display today's plan. Try refreshing it."
}

private fun localErrorPage(detail: String, lightTheme: Boolean): String {
    @Suppress("UNUSED_VARIABLE") val ignoredTechnicalDetail = detail
    val background = if (lightTheme) "#faf9f7" else "#08080a"
    val text = if (lightTheme) "#111111" else "#ededef"
    val card = if (lightTheme) "#ffffff" else "#18181b"
    val border = if (lightTheme) "#e2ddd8" else "#303036"
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{height:100%;margin:0;background:$background;color:$text;font-family:sans-serif}
        body{display:grid;place-items:center;padding:24px;box-sizing:border-box}.card{max-width:420px;padding:24px;
        border:1px solid $border;border-radius:17px;background:$card}h1{font-size:20px}p{color:$text;line-height:1.5}</style>
        </head><body><div class="card"><h1>Home couldn't open</h1><p>Close and reopen Zen Maestro. Your saved plan is safe.</p></div></body></html>
    """.trimIndent()
}

private val HOME_RUNTIME_SCRIPT = """
    (function () {
      if (window.CoachAIHome) return;

      var style = document.createElement('style');
      style.textContent = "" +
        "@font-face{font-family:FigtreeLocal;src:url('file:///android_asset/home/fonts/figtree_variable.ttf') format('truetype');font-style:normal;font-weight:100 900;font-display:swap;}" +
        "html,body,button,a{font-family:FigtreeLocal,Arial,sans-serif!important;}" +
        ".status-bar,.gesture-bar{display:none!important;}" +
        ".device{height:100vh!important;min-height:100vh!important;overflow:hidden!important;}" +
        ".top-app-bar{position:fixed!important;top:0;left:0;right:0;z-index:4;}" +
        ".sample-label{display:none!important;}" +
        ".scroll-content{position:fixed!important;top:56px;bottom:0;left:0;right:0;height:auto!important;overflow-anchor:none!important;padding-bottom:28px!important;}" +
        ".bottom-nav{position:fixed!important;bottom:0!important;z-index:5;}" +
        ".sample-label{color:var(--muted);}" +
        ".unavailable{padding:13px 6px 10px;}" +
        ".unavailable strong{display:block;color:var(--text);font-size:12px;font-weight:750;}" +
        ".unavailable p{margin:4px 0 0;color:var(--text);font-size:10px;line-height:1.45;}" +
        ".retry-button{margin-top:10px;padding:7px 11px;border:1px solid var(--accent);border-radius:10px;background:transparent;color:var(--accent);font-size:10px;font-weight:750;}" +
        ".state.failed{border-color:rgba(217,72,72,.30);background:rgba(217,72,72,.08);color:#d94848;}" +
        "a,button{-webkit-tap-highlight-color:transparent;}";
      document.head.appendChild(style);
      document.body.classList.add('android-host');
      if ('scrollRestoration' in history) history.scrollRestoration = 'manual';

      document.addEventListener('click', function (event) {
        if (event.target.closest('a,button')) event.preventDefault();
      }, true);

      function query(selector) { return document.querySelector(selector); }
      function node(tag, className, text) {
        var result = document.createElement(tag);
        if (className) result.className = className;
        if (text !== undefined && text !== null) result.textContent = String(text);
        return result;
      }
      function unavailable(container, data) {
        container.replaceChildren();
        var box = node('div', 'unavailable');
        box.appendChild(node('strong', '', data.title));
        box.appendChild(node('p', '', data.detail));
        if (data.retryable) {
          var retry = node('button', 'retry-button', 'Try again');
          retry.setAttribute('type', 'button');
          retry.addEventListener('click', function (event) {
            event.preventDefault();
            event.stopPropagation();
            if (window.CoachAIAndroid) window.CoachAIAndroid.retryHome();
          });
          box.appendChild(retry);
        }
        container.appendChild(box);
      }
      function displayTime(value) {
        var parts = String(value).split(':');
        if (parts.length !== 2) return value;
        var hour = Number(parts[0]);
        if (!Number.isFinite(hour)) return value;
        var suffix = hour >= 12 ? 'PM' : 'AM';
        var displayHour = hour % 12 || 12;
        return displayHour + ':' + parts[1] + ' ' + suffix;
      }

      window.CoachAIHome = {
        render: function (data) {
          var date = query('.date');
          date.replaceChildren(node('i'));
          date.appendChild(document.createTextNode(data.date));
          query('h1').textContent = data.greeting;
          query('.subtitle').textContent = data.subtitle;

          var avatar = query('.avatar');
          if (data.displayName) {
            avatar.textContent = data.displayName.split(/\s+/).map(function (part) { return part.charAt(0); }).slice(0, 2).join('').toUpperCase();
            avatar.setAttribute('aria-label', data.displayName);
          } else {
            avatar.textContent = '•';
            avatar.setAttribute('aria-label', 'Profile unavailable');
          }

          var quote = query('.quote');
          quote.replaceChildren();
          quote.appendChild(node('span', 'quote-mark', '“'));
          var quoteCopy = node('div', '', data.quote.text);
          quoteCopy.appendChild(node('cite', '', data.quote.source));
          quote.appendChild(quoteCopy);

          var streak = query('.streak');
          if (data.streakDays === null || data.streakDays === undefined) {
            streak.style.display = 'none';
          } else {
            streak.style.display = '';
            streak.textContent = '↗ ' + data.streakDays + '-day streak';
          }

          var progress = query('.progress-content');
          if (!data.progress.available) {
            unavailable(progress, data.progress);
          } else {
            progress.replaceChildren();
            var ring = node('div', 'ring');
            ring.style.setProperty('--value', data.progress.percent);
            ring.appendChild(node('strong', '', data.progress.percent + '%'));
            progress.appendChild(ring);
            var summary = node('div', 'progress-summary');
            summary.appendChild(node('strong', '', data.progress.completed + ' of ' + data.progress.total + ' done'));
            var open = data.progress.inProgress + data.progress.pending;
            summary.appendChild(node('p', '', open === 0 ? 'Everything in today\'s plan is complete.' : (open === 1 ? 'One task is still open today.' : open + ' tasks are still open today.')));
            var chips = node('div', 'status-chips');
            if (data.progress.inProgress > 0) chips.appendChild(node('span', '', data.progress.inProgress + ' active'));
            if (data.progress.pending > 0) chips.appendChild(node('span', '', data.progress.pending + ' pending'));
            if (data.progress.failed > 0) chips.appendChild(node('span', '', data.progress.failed + ' failed'));
            summary.appendChild(chips);
            progress.appendChild(summary);
          }

          var taskList = query('.task-list');
          if (!data.schedule.available) {
            unavailable(taskList, data.schedule);
          } else {
            taskList.replaceChildren();
            data.schedule.tasks.forEach(function (task) {
              var row = node('div', 'task');
              row.appendChild(node('span', 'time', displayTime(task.time)));
              var copy = node('div', 'task-copy');
              copy.appendChild(node('div', 'task-title', task.title));
              copy.appendChild(node('div', 'task-meta', task.meta));
              row.appendChild(copy);
              row.appendChild(node('span', 'state ' + task.status, task.statusLabel));
              taskList.appendChild(row);
            });
          }

          query('.insight h2').textContent = data.insight.title;
          query('.insight p:last-child').textContent = data.insight.detail;

          document.querySelectorAll('.action,.nav-item,.open-link').forEach(function (item) {
            var label = item.textContent.trim().toLowerCase();
            var destination = item.dataset.destination || '';
            var opensSchedule = destination === 'schedule' || label === 'schedule' || label.indexOf('see all') === 0;
            var opensCoach = destination === 'coach' || label === 'coach' || label === 'ai coach';
            var isAvailable = opensSchedule || opensCoach;
            item.setAttribute('aria-disabled', isAvailable ? 'false' : 'true');
            item.setAttribute('tabindex', isAvailable ? '0' : '-1');
            if (opensSchedule && item.dataset.coachaiScheduleBound !== 'true') {
              item.dataset.coachaiScheduleBound = 'true';
              item.addEventListener('click', function (event) {
                event.preventDefault();
                event.stopPropagation();
                if (window.CoachAIAndroid) window.CoachAIAndroid.openSchedule();
              });
            }
            if (opensCoach && item.dataset.coachaiCoachBound !== 'true') {
              item.dataset.coachaiCoachBound = 'true';
              item.addEventListener('click', function (event) {
                event.preventDefault();
                event.stopPropagation();
                if (window.CoachAIAndroid) window.CoachAIAndroid.openCoach();
              });
            }
          });

          if (!this.initialScrollApplied) {
            this.initialScrollApplied = true;
            var scrollContent = query('.scroll-content');
            var hero = query('.hero');
            var resetScroll = function () {
              scrollContent.scrollTop = 0;
              if (hero) hero.scrollIntoView({block: 'start'});
            };
            window.requestAnimationFrame(resetScroll);
            window.setTimeout(resetScroll, 100);
            window.setTimeout(resetScroll, 500);
            window.setTimeout(resetScroll, 1500);
          }
        }
      };
    })();
""".trimIndent()
