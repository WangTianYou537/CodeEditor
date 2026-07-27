package cn.wty5.editor.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import cn.wty5.editor.complete.CompletionEngine;
import cn.wty5.editor.complete.CompletionItem;
import cn.wty5.editor.core.Document;
import cn.wty5.editor.core.UndoManager;
import cn.wty5.editor.highlight.ColorScheme;
import cn.wty5.editor.highlight.Highlighter;
import cn.wty5.editor.highlight.LineSpans;
import cn.wty5.editor.lang.LanguageRegistry;
import cn.wty5.editor.lang.LanguageSpec;
import cn.wty5.editor.lang.Languages;
import cn.wty5.editor.lang.Lexer;
import cn.wty5.editor.lang.TokenType;
import cn.wty5.editor.lsp.Diagnostic;
import cn.wty5.editor.lsp.LspClient;
import cn.wty5.editor.lsp.LspListener;
import cn.wty5.editor.plugin.PluginManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The editor widget.
 *
 * Renders the {@link Document} directly onto the canvas — no Spannable, no
 * TextView. Only visible lines are drawn; with the piece table's O(log n)
 * line lookups and per-line cached spans, frame cost is proportional to the
 * viewport, not the file.
 *
 * Owns caret/selection state, IME plumbing, scrolling, pinch-zoom, undo
 * wiring and the completion popup.
 */
public class CodeEditorView extends View
        implements Highlighter.Callback, CompletionEngine.Callback, LspListener {

    private static final float MIN_TEXT_SIZE_SP = 8f;
    private static final float MAX_TEXT_SIZE_SP = 48f;
    private static final float DEFAULT_TEXT_SIZE_SP = 15f;

    private Document document;
    private UndoManager undoManager;
    private Highlighter highlighter;
    private CompletionEngine completionEngine;
    private CompletionPopup completionPopup;
    private final ColorScheme scheme = new ColorScheme();
    private LanguageSpec language;
    private final PluginManager pluginManager = new PluginManager();

    // -- caret & selection (offsets into the document) -------------------
    private int caret;
    private int selectionAnchor = -1; // -1 = no selection
    private boolean caretVisible = true;

    /** Which selection / insertion handle is being dragged, if any. */
    private enum Handle { NONE, START, END, INSERT }
    private Handle activeHandle = Handle.NONE;
    /**
     * While dragging START/END, the opposite selection edge is frozen here so
     * crossing the other handle cannot "walk" it (AOSP clamps rather than
     * mutating both ends).
     */
    private int handleDragFixedOffset = -1;
    /**
     * Added to the finger Y when mapping a handle drag to a document offset.
     * Handles hang below the line, so without this the first touch lands on
     * the next line and the selection jumps.
     */
    private float handleDragMapYAdjust;

    /** Explicit single-pointer interaction state; no GestureDetector latency. */
    private enum TouchMode {
        IDLE,
        TAP_PENDING,
        PANNING,
        HANDLE_DRAG,
        LONG_PRESS_LOCKED,
        LONG_PRESS_EXTENDING,
        DOUBLE_TAP_LOCKED,
        DOUBLE_TAP_EXTENDING
    }
    private TouchMode touchMode = TouchMode.IDLE;
    private int touchSlop;
    private int doubleTapSlop;
    private int minimumFlingVelocity;
    private int maximumFlingVelocity;
    private float downX, downY;
    private float lastTouchX, lastTouchY;
    private long lastTapUpTime;
    private float lastTapX, lastTapY;
    /** Android rejects double taps closer than this (framework value: 40 ms). */
    private static final long DOUBLE_TAP_MIN_TIME_MS = 40L;
    /** Caret/selection at this pointer sequence's DOWN, for pinch rollback. */
    private int touchStartCaret;
    private int touchStartSelectionAnchor;
    private int initialSelectionStart;
    private int initialSelectionEnd;
    private VelocityTracker velocityTracker;
    private final Runnable longPressRunnable = this::onManualLongPressTimeout;

    // -- composing region for IME -----------------------------------------
    private int composingStart = -1;
    private int composingEnd = -1;
    /** Last selection/composing reported to {@link InputMethodManager}. */
    private int lastImmSelStart = -1;
    private int lastImmSelEnd = -1;
    private int lastImmCompStart = -1;
    private int lastImmCompEnd = -1;
    private long lastImmDocumentVersion = -1;
    /** Token of the last ExtractedTextRequest that asked for continuous updates. */
    private int extractedToken = 0;
    private boolean extractedMonitor;
    /** Nested IME batch-edit depth (keeps multi-step commits as one undo). */
    private int imeBatchDepth;
    /** Active InputConnection; kept so external edits can resync its Editable. */
    private EditorInputConnection activeInputConnection;
    /** Cursor-anchor monitoring requested by the IME. */
    private boolean cursorAnchorMonitor;
    private final Matrix cursorAnchorMatrix = new Matrix();
    private final int[] viewLocationOnScreen = new int[2];
    /**
     * True while applying IME Editable → document mutations so we don't
     * clear composing / bounce-sync back into the Editable.
     */
    private boolean applyingEditableMutation;

    // -- metrics ---------------------------------------------------------
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint();
    private final Paint diagnosticPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect visibleFrame = new Rect();
    private float density;
    private float textSizeSp = DEFAULT_TEXT_SIZE_SP;
    private float charWidth;
    private float lineHeight;
    private float baselineShift;
    private float gutterWidth;
    private float gutterPad;
    /** Number of monospace cells between tab stops. */
    private static final int TAB_SIZE = 4;
    /** Reused per-line buffers: no allocations in the visible-line draw loop. */
    private float[] columnXs = new float[128];
    private float[] glyphWidths = new float[128];
    private char[] textChars = new char[128];

    /**
     * Native Android text-select handles resolved from the activity theme
     * ({@code textSelectHandleLeft/Right/Middle}), with system-resource
     * fallback so DeviceDefault hosts still get EditText-style teardrops.
     */
    private Drawable handleLeftDrawable;
    private Drawable handleRightDrawable;
    private Drawable handleMiddleDrawable;
    private int handleIntrinsicW;
    private int handleIntrinsicH;
    private int handleMiddleW;
    private int handleMiddleH;
    /** Fallback body radius used only when theme drawables are unavailable. */
    private float handleRadius;
    private final Path handleFallbackPath = new Path();
    /** Self-drawn floating selection toolbar (Cut / Copy / Paste / Select all). */
    private boolean selectionToolbarVisible;
    /** True when the insertion (caret) toolbar is allowed to show. */
    private boolean insertionToolbarAllowed;
    private final Paint toolbarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint toolbarTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF toolbarRect = new RectF();
    private final Path toolbarArrow = new Path();
    /** Visible item ids in left-to-right order for the current toolbar frame. */
    private final int[] toolbarItemIds = new int[4];
    private int toolbarItemCount;
    private float toolbarItemWidth;
    private float toolbarHeight;
    private float toolbarArrowSize;
    private static final int TB_SELECT_ALL = 1;
    private static final int TB_CUT = 2;
    private static final int TB_COPY = 3;
    private static final int TB_PASTE = 4;
    /**
     * Idle timeout after which the floating toolbar auto-dismisses (selection
     * is kept). Matches the rough cadence of EditText's floating toolbar.
     */
    private static final long TOOLBAR_AUTO_HIDE_MS = 3000L;
    private final Runnable toolbarAutoHideRunnable = this::autoHideSelectionToolbar;

    // -- LSP ---------------------------------------------------------------
    private LspClient lspClient;
    private String lspDocumentUri;
    private String lspLanguageId = "plaintext";
    private int lspDocumentVersion;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable lspChangeDebounce = this::flushLspDidChange;
    private static final long LSP_CHANGE_DEBOUNCE_MS = 250;
    private List<Diagnostic> diagnostics = Collections.emptyList();
    /** When true, {@link #setLanguage} auto-connects using the grammar/plugin lsp block. */
    private boolean autoStartLsp = true;
    /** Absolute filesystem path of the project root (for ${workspaceFolder}). */
    private String lspWorkspaceFolder;
    /** Absolute filesystem path of the open file (for ${file} / document URI). */
    private String lspFilePath;
    /** True when the current client was started from grammar/plugin config. */
    private boolean lspOwnedByEditor;
    private final Document.ContentListener lspSyncListener =
            new Document.ContentListener() {
                @Override
                public void onInsert(Document doc, int offset, String text) {
                    scheduleLspDidChange();
                }

                @Override
                public void onDelete(Document doc, int offset, String text) {
                    scheduleLspDidChange();
                }
            };

    // -- scrolling / zoom ------------------------------------------------
    private final OverScroller scroller;
    private final ScaleGestureDetector scaleDetector;
    /** True while a pinch is actively changing the scale factor. */
    private boolean scaling;
    /**
     * True from the moment a 2nd pointer lands (or a scale begins) until
     * every finger has lifted. Blocks caret moves / taps / scrolls that
     * would otherwise fire from the leftover single-finger UP that ends a
     * pinch — a delayed final single-finger UP must never become a tap.
     */
    private boolean suppressSingleFingerGestures;
    /**
     * Semantic document coordinate under the pinch focus. Using line plus an
     * x-coordinate expressed in current monospace-em units rather than old
     * pixels is essential: when text metrics change, old pixels no longer
     * identify the same code position and the viewport drifts (especially
     * visible on long comment lines).
     */
    private float zoomAnchorXEm;
    private float zoomAnchorLine;
    private boolean zoomAnchorInGutter;
    /** Horizontal scroll at pinch start; gutter pinches must preserve it. */
    private int zoomStartScrollX;
    /** Largest measured line width, in monospace em units. */
    private float maxObservedLineWidthEm;
    private int widestObservedLine = -1;
    /** NaN means this line has not been measured yet. */
    private float[] lineWidthEms = new float[0];
    /** Keeps width indices aligned with Document edits, including external edits. */
    private final Document.ContentListener widthCacheListener =
            new Document.ContentListener() {
                @Override
                public void onInsert(Document doc, int offset, String text) {
                    updateWidthsAfterInsert(doc, offset, text);
                }

                @Override
                public void onDelete(Document doc, int offset, String text) {
                    updateWidthsAfterDelete(doc, offset, text);
                }
            };

    /**
     * Cached visible band of this view that is NOT covered by the soft
     * keyboard (or other system windows), in view-local coordinates:
     * {@code [imeVisibleTop, imeVisibleBottom)}. Updated from
     * {@link #refreshImeVisibleBand()} on layout / global-layout.
     */
    private int imeVisibleTop;
    private int imeVisibleBottom;
    private final int[] locationInWindow = new int[2];
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener imeLayoutListener =
            this::onPossibleImeLayoutChange;

    private final Runnable caretBlink = new Runnable() {
        @Override
        public void run() {
            caretVisible = !caretVisible;
            invalidate();
            postDelayed(this, 500);
        }
    };

    public CodeEditorView(Context context) {
        this(context, null);
    }

    public CodeEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);
        // Hardware layer: text + solid fills compose faster; pinch-zoom
        // just invalidates content, no layer thrash on every frame.
        setWillNotDraw(false);

        density = context.getResources().getDisplayMetrics().density;
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        doubleTapSlop = config.getScaledDoubleTapSlop();
        minimumFlingVelocity = config.getScaledMinimumFlingVelocity();
        maximumFlingVelocity = config.getScaledMaximumFlingVelocity();
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setSubpixelText(true);
        textPaint.setLinearText(true);
        gutterPaint.setTypeface(Typeface.MONOSPACE);
        gutterPaint.setSubpixelText(true);
        // SOLID style for fills — avoids accidental stroke state leaks.
        fillPaint.setStyle(Paint.Style.FILL);
        diagnosticPaint.setStyle(Paint.Style.STROKE);
        diagnosticPaint.setStrokeWidth(Math.max(1.5f, 1.5f * density));
        diagnosticPaint.setStrokeCap(Paint.Cap.ROUND);
        toolbarPaint.setStyle(Paint.Style.FILL);
        toolbarTextPaint.setTypeface(Typeface.DEFAULT);
        toolbarTextPaint.setTextAlign(Paint.Align.CENTER);
        toolbarTextPaint.setSubpixelText(true);
        toolbarHeight = 40f * density;
        toolbarArrowSize = 7f * density;
        // Fallback size if the theme has no handle drawables.
        handleRadius = 11f * density;
        loadHandleDrawables();

        applyTextSize(textSizeSp, false);

        scroller = new OverScroller(context);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        // Quick-scale (double-tap-swipe) is confusing in an editor; pinch only.
        scaleDetector.setQuickScaleEnabled(false);

        Languages.ensureBuiltins();
        this.language = Languages.java();
        setDocument(new Document());
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public void setDocument(Document doc) {
        if (this.document != null) {
            this.document.removeContentListener(widthCacheListener);
            this.document.removeContentListener(lspSyncListener);
        }
        if (highlighter != null) {
            highlighter.shutdown();
        }
        if (completionEngine != null) {
            completionEngine.shutdown();
        }
        if (completionPopup != null) {
            completionPopup.dismiss();
        }
        this.document = doc;
        this.document.addContentListener(widthCacheListener);
        this.document.addContentListener(lspSyncListener);
        resetLineWidthCache(doc.lineCount());
        this.undoManager = new UndoManager(doc);
        Lexer lexer = Languages.lexerFor(language == null ? "java" : language.name);
        this.highlighter = new Highlighter(doc, lexer, this);
        this.completionEngine = new CompletionEngine(doc, language, this);
        wireLspExternalSource();
        this.completionPopup = new CompletionPopup(this, scheme);
        this.completionPopup.setListener(this::applyCompletion);
        this.caret = 0;
        this.selectionAnchor = -1;
        this.diagnostics = Collections.emptyList();
        // Re-open on the language server under the same URI if one is attached.
        if (lspClient != null && lspClient.isReady() && lspDocumentUri != null) {
            lspDocumentVersion = 1;
            lspClient.didOpen(lspDocumentUri, lspLanguageId, doc.toString(),
                    lspDocumentVersion);
        }
        updateGutterWidth();
        invalidate();
    }

    /**
     * Switches syntax highlighting and completion to the named language
     * (e.g. {@code "java"}, {@code "go"}). Unknown names fall back to a
     * plain-text lexer with no keywords.
     */
    public void setLanguage(String name) {
        Languages.ensureBuiltins();
        LanguageSpec spec = LanguageRegistry.getInstance().getSpec(name);
        if (spec == null) {
            spec = new LanguageSpec.Builder().name(name == null ? "plain" : name).build();
        }
        setLanguage(spec);
    }

    /** Switches language from a file extension (without or with leading dot). */
    public void setLanguageByExtension(String extension) {
        Languages.ensureBuiltins();
        LanguageSpec spec = LanguageRegistry.getInstance().getSpecByExtension(extension);
        if (spec == null) {
            spec = new LanguageSpec.Builder().name("plain").build();
        }
        setLanguage(spec);
    }

    public void setLanguage(LanguageSpec spec) {
        this.language = spec;
        this.lspLanguageId = resolveLanguageId(spec);
        if (highlighter != null) {
            highlighter.setLexer(Languages.lexerFor(spec.name));
        }
        if (completionEngine != null) {
            completionEngine.setLanguage(spec);
        }
        // Re-announce under the new language id if an external LSP is live;
        // otherwise try to auto-connect from the grammar / plugin config.
        if (lspClient != null && lspClient.isReady()
                && lspDocumentUri != null && document != null
                && !lspOwnedByEditor) {
            lspDocumentVersion++;
            lspClient.didOpen(lspDocumentUri, lspLanguageId,
                    document.toString(), lspDocumentVersion);
        } else if (autoStartLsp) {
            connectLspFromLanguage();
        }
        dismissCompletions();
        invalidate();
    }

    // ------------------------------------------------------------------
    // LSP
    // ------------------------------------------------------------------

    /**
     * Project folder + open file used to expand {@code ${workspaceFolder}} /
     * {@code ${file}} placeholders in grammar LSP configs and to build the
     * document URI sent to the server. Either argument may be null.
     */
    public void setLspWorkspace(String workspaceFolder, String filePath) {
        this.lspWorkspaceFolder = workspaceFolder;
        this.lspFilePath = filePath;
        if (filePath != null) {
            this.lspDocumentUri = cn.wty5.editor.lsp.LspConfig.LspWorkspace
                    .toFileUri(filePath);
        }
        if (autoStartLsp && language != null) {
            connectLspFromLanguage();
        }
    }

    public String getLspWorkspaceFolder() {
        return lspWorkspaceFolder;
    }

    public String getLspFilePath() {
        return lspFilePath;
    }

    /**
     * When {@code true} (default), {@link #setLanguage} and
     * {@link #setLspWorkspace} automatically start/stop the language server
     * described by the active grammar's {@code "lsp"} block or the plugin's
     * {@link cn.wty5.editor.plugin.LanguagePlugin#getLspConfig()}.
     */
    public void setAutoStartLsp(boolean enabled) {
        this.autoStartLsp = enabled;
    }

    public boolean isAutoStartLsp() {
        return autoStartLsp;
    }

    /**
     * Resolve + connect the LSP configured for the current language. No-ops
     * when the grammar/plugin has no (enabled) {@code lsp} block. Stops any
     * previously auto-started client first.
     *
     * @return the live client, or null when nothing was configured
     */
    public LspClient connectLspFromLanguage() {
        LanguageSpec spec = this.language;
        if (spec == null) return null;
        cn.wty5.editor.lsp.LspConfig cfg =
                LanguageRegistry.getInstance().resolveLspConfig(spec.name);
        if (cfg == null && spec.lsp != null) cfg = spec.lsp;
        if (cfg == null || !cfg.enabled) {
            // Language has no server — drop a previously auto-started one.
            if (lspOwnedByEditor) stopLsp();
            return lspClient;
        }
        try {
            return startLsp(cfg);
        } catch (IOException e) {
            if (lspOwnedByEditor) stopLsp();
            return null;
        }
    }

    /**
     * Start a language server from a declarative {@link cn.wty5.editor.lsp.LspConfig}
     * (stdio / tcp / http / websocket). Placeholders are expanded against the
     * current {@link #setLspWorkspace} paths.
     */
    public LspClient startLsp(cn.wty5.editor.lsp.LspConfig config) throws IOException {
        if (config == null) throw new IllegalArgumentException("config required");
        String langId = config.languageId != null ? config.languageId
                : (language == null ? "plaintext" : language.name);
        cn.wty5.editor.lsp.LspConfig.LspWorkspace ws =
                new cn.wty5.editor.lsp.LspConfig.LspWorkspace(
                        lspWorkspaceFolder, lspFilePath, langId);
        cn.wty5.editor.lsp.LspConfig resolved = config.resolve(ws);
        if (!resolved.isConfigured()) {
            throw new IOException("LSP config incomplete after placeholder expansion: "
                    + resolved);
        }
        String docUri = lspDocumentUri;
        if (docUri == null && lspFilePath != null) {
            docUri = cn.wty5.editor.lsp.LspConfig.LspWorkspace.toFileUri(lspFilePath);
        }
        if (docUri == null) {
            // Synthetic URI so didOpen still works for untitled buffers.
            docUri = "inmemory:///editor." + (language == null ? "txt" : language.name);
        }
        String root = resolved.rootUri;
        if (root == null || root.isEmpty()) {
            root = lspWorkspaceFolder != null
                    ? cn.wty5.editor.lsp.LspConfig.LspWorkspace.toFileUri(lspWorkspaceFolder)
                    : "file:///";
            // Re-build with concrete rootUri so initialize sees it.
            resolved = cn.wty5.editor.lsp.LspConfig.builder()
                    .enabled(resolved.enabled)
                    .transport(resolved.transport)
                    .command(resolved.command)
                    .env(resolved.env)
                    .cwd(resolved.cwd)
                    .url(resolved.url)
                    .host(resolved.host)
                    .port(resolved.port)
                    .sseUrl(resolved.sseUrl)
                    .languageId(resolved.languageId != null ? resolved.languageId : langId)
                    .rootUri(root)
                    .initializationOptions(resolved.initializationOptions)
                    .connectTimeoutMs(resolved.connectTimeoutMs)
                    .build();
        }

        // Replace any previous editor-owned client.
        if (lspOwnedByEditor) {
            stopLsp();
        } else {
            detachLsp();
        }

        LspClient client = new LspClient();
        attachLsp(client, docUri,
                resolved.languageId != null ? resolved.languageId : langId);
        lspOwnedByEditor = true;
        client.start(resolved);
        return client;
    }

    /**
     * Attach a language server. Completions from the server merge with the
     * local grammar list; diagnostics are underlined in the text.
     *
     * @param client already-constructed client (not yet necessarily started)
     * @param documentUri LSP document URI, e.g. {@code file:///…/Main.java}
     * @param languageId  LSP language id ({@code "java"}, {@code "go"}, …)
     */
    public void attachLsp(LspClient client, String documentUri, String languageId) {
        // Detach without stopping — caller owns the previous client unless we did.
        boolean wasOwned = lspOwnedByEditor;
        LspClient previous = lspClient;
        detachLsp();
        if (wasOwned && previous != null) {
            try { previous.stop(); } catch (Exception ignored) {}
        }
        this.lspClient = client;
        this.lspDocumentUri = documentUri;
        this.lspLanguageId = languageId == null
                ? (language == null ? "plaintext" : language.name)
                : languageId;
        this.lspOwnedByEditor = false;
        if (client == null) return;
        client.setUiScheduler(r -> {
            if (Looper.myLooper() == Looper.getMainLooper()) r.run();
            else mainHandler.post(r);
        });
        client.addListener(this);
        wireLspExternalSource();
        if (client.isReady() && document != null && documentUri != null) {
            lspDocumentVersion = 1;
            client.didOpen(documentUri, this.lspLanguageId,
                    document.toString(), lspDocumentVersion);
        }
    }

    /**
     * Spawn {@code command} as a language server over stdio and attach it.
     * Convenience wrapper around {@link LspClient#startProcess} +
     * {@link #attachLsp}.
     */
    public LspClient startLsp(List<String> command, String rootUri,
                              String documentUri, String languageId)
            throws IOException {
        cn.wty5.editor.lsp.LspConfig cfg = cn.wty5.editor.lsp.LspConfig.builder()
                .transport(cn.wty5.editor.lsp.LspConfig.Transport.STDIO)
                .command(command)
                .rootUri(rootUri)
                .languageId(languageId)
                .build();
        if (documentUri != null) this.lspDocumentUri = documentUri;
        LspClient client = startLsp(cfg);
        return client;
    }

    /** Detach (but do not stop) the current language server. */
    public void detachLsp() {
        mainHandler.removeCallbacks(lspChangeDebounce);
        if (lspClient != null) {
            lspClient.removeListener(this);
            if (lspDocumentUri != null) {
                try { lspClient.didClose(lspDocumentUri); } catch (Exception ignored) {}
            }
        }
        if (completionEngine != null) {
            completionEngine.setExternalSource(null);
        }
        lspClient = null;
        lspOwnedByEditor = false;
        diagnostics = Collections.emptyList();
        invalidate();
    }

    /** Detach and stop an editor-owned client (from grammar/plugin config). */
    public void stopLsp() {
        mainHandler.removeCallbacks(lspChangeDebounce);
        LspClient client = lspClient;
        boolean owned = lspOwnedByEditor;
        String uri = lspDocumentUri;
        if (client != null) {
            client.removeListener(this);
            if (uri != null) {
                try { client.didClose(uri); } catch (Exception ignored) {}
            }
            if (owned) {
                try { client.stop(); } catch (Exception ignored) {}
            }
        }
        if (completionEngine != null) {
            completionEngine.setExternalSource(null);
        }
        lspClient = null;
        lspOwnedByEditor = false;
        diagnostics = Collections.emptyList();
        invalidate();
    }

    public LspClient getLspClient() {
        return lspClient;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    private static String resolveLanguageId(LanguageSpec spec) {
        if (spec == null) return "plaintext";
        if (spec.lsp != null && spec.lsp.languageId != null
                && !spec.lsp.languageId.isEmpty()) {
            return spec.lsp.languageId;
        }
        return spec.name;
    }

    @Override
    public void onLspReady() {
        if (lspClient == null || document == null || lspDocumentUri == null) return;
        lspDocumentVersion = 1;
        lspClient.didOpen(lspDocumentUri, lspLanguageId,
                document.toString(), lspDocumentVersion);
    }

    @Override
    public void onLspClosed(String message) {
        diagnostics = Collections.emptyList();
        if (completionEngine != null) {
            completionEngine.setExternalSource(null);
        }
        invalidate();
    }

    @Override
    public void onDiagnostics(String uri, List<Diagnostic> list) {
        if (lspDocumentUri != null && uri != null
                && !uriEquals(lspDocumentUri, uri)) {
            return;
        }
        diagnostics = list == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(list));
        invalidate();
    }

    @Override
    public void onLspCompletions(int requestId, List<CompletionItem> items) {
        if (completionEngine != null) {
            completionEngine.acceptExternal(requestId, items);
        }
    }

    private void wireLspExternalSource() {
        if (completionEngine == null) return;
        if (lspClient == null) {
            completionEngine.setExternalSource(null);
            return;
        }
        completionEngine.setExternalSource((caretOffset, prefixStart, prefix) -> {
            if (lspClient == null || !lspClient.isReady() || lspDocumentUri == null) {
                return -1;
            }
            int line = document.lineOfOffset(caretOffset);
            int character = caretOffset - document.lineStart(line);
            return lspClient.requestCompletion(lspDocumentUri, line, character);
        });
    }

    private void scheduleLspDidChange() {
        if (lspClient == null || !lspClient.isReady() || lspDocumentUri == null) {
            return;
        }
        mainHandler.removeCallbacks(lspChangeDebounce);
        mainHandler.postDelayed(lspChangeDebounce, LSP_CHANGE_DEBOUNCE_MS);
    }

    private void flushLspDidChange() {
        if (lspClient == null || !lspClient.isReady()
                || lspDocumentUri == null || document == null) {
            return;
        }
        lspDocumentVersion++;
        lspClient.didChange(lspDocumentUri, document.toString(), lspDocumentVersion);
    }

    private static boolean uriEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        // Servers sometimes normalise file:///path vs file://path.
        return a.replace("file:///", "file://").equals(
                b.replace("file:///", "file://"));
    }

    public LanguageSpec getLanguage() {
        return language;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    /**
     * Loads every {@code *.json} grammar in {@code dir} into the shared
     * registry. Already-open editors pick up a newly loaded language the
     * next time {@link #setLanguage(String)} is called.
     */
    public List<String> loadGrammars(File dir) {
        return Languages.loadFromDirectory(dir);
    }

    public void setText(String text) {
        document.setText(text);
        undoManager.clear();
        caret = 0;
        selectionAnchor = -1;
        composingStart = composingEnd = -1;
        resetLineWidthCache(document.lineCount());
        highlighter.invalidateAll();
        updateGutterWidth();
        scrollTo(0, 0);
        restartImeInput();
        invalidate();
    }

    public Document getDocument() {
        return document;
    }

    public String getText() {
        return document.toString();
    }

    /** Current font size in scaled pixels (sp). */
    public float getTextSizeSp() {
        return textSizeSp;
    }

    /** Sets font size in sp and reflows. Clamped to [{@value MIN_TEXT_SIZE_SP}, {@value MAX_TEXT_SIZE_SP}]. */
    public void setTextSizeSp(float sp) {
        applyTextSize(sp, true);
    }

    public void undo() {
        int c = undoManager.undo();
        if (c >= 0) {
            moveCaretTo(c, false);
            afterEdit();
        }
    }

    public void redo() {
        int c = undoManager.redo();
        if (c >= 0) {
            moveCaretTo(c, false);
            afterEdit();
        }
    }

    public boolean canUndo() {
        return undoManager.canUndo();
    }

    public boolean canRedo() {
        return undoManager.canRedo();
    }

    // ------------------------------------------------------------------
    // Font / zoom metrics
    // ------------------------------------------------------------------

    private void applyTextSize(float sp, boolean keepFocus) {
        float clamped = Math.max(MIN_TEXT_SIZE_SP, Math.min(MAX_TEXT_SIZE_SP, sp));
        // Remember the document point under the viewport centre so a
        // programmatic size change (or end of pinch) doesn't jump.
        float focusDocX = 0, focusDocY = 0;
        float focusViewX = getWidth() / 2f;
        float focusViewY = getHeight() / 2f;
        if (keepFocus && charWidth > 0 && lineHeight > 0) {
            focusDocX = getScrollX() + focusViewX;
            focusDocY = getScrollY() + focusViewY;
        }

        textSizeSp = clamped;
        float px = textSizeSp * density;
        textPaint.setTextSize(px);
        gutterPaint.setTextSize(px * 0.85f);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        // Quantize line height to whole pixels — stops baseline shimmer
        // while pinching and keeps caret/selection rects pixel-aligned.
        lineHeight = (float) Math.ceil(fm.descent - fm.ascent + fm.leading);
        if (lineHeight < 1f) lineHeight = 1f;
        baselineShift = -fm.ascent;
        charWidth = textPaint.measureText("M");
        gutterPad = charWidth * 0.75f;
        updateGutterWidth();

        if (keepFocus && charWidth > 0) {
            int nx = Math.max(0, Math.round(focusDocX - focusViewX));
            int ny = Math.max(0, Math.round(focusDocY - focusViewY));
            nx = Math.min(nx, maxScrollX());
            ny = Math.min(ny, maxScrollY());
            scroller.forceFinished(true);
            scrollTo(nx, ny);
        }
        // Popup (if showing) needs re-anchoring at the new scale.
        if (completionPopup != null && completionPopup.isShowing()) {
            dismissCompletions();
        }
        invalidate();
    }

    // ------------------------------------------------------------------
    // Editing primitives (all IME/keyboard paths funnel through these)
    // ------------------------------------------------------------------

    /** Inserts at the caret, replacing any active selection. */
    public void insertAtCaret(String text) {
        if (text == null || text.isEmpty()) return;
        // Single closing brace on an otherwise-blank indented line → outdent
        // to the matching opener before inserting, so `}` lands at the right level.
        if ("}".equals(text) && !hasSelection()) {
            maybeOutdentBeforeClosingBrace();
        }
        deleteSelectionIfAny();
        document.insert(caret, text);
        caret += text.length();
        afterEdit();
        // Only pop completions for identifier-ish input, not spaces/symbols
        // that would just hide it again and steal a frame.
        if (shouldAutoCompleteAfter(text)) {
            requestCompletionsAtCaret();
        } else {
            dismissCompletions();
        }
    }

    /**
     * Insert a newline and the appropriate leading whitespace for the new
     * line. Also handles the empty-block case: caret between {@code {|} }
     * expands to a fully indented inner line with the closer re-indented.
     */
    public void insertNewlineWithIndent() {
        deleteSelectionIfAny();
        String indent = leadingWhitespaceOfLine(document.lineOfOffset(caret));
        String unit = indentUnit();

        // Look at the non-ws char immediately before the caret (on this line).
        int line = document.lineOfOffset(caret);
        int lineStart = document.lineStart(line);
        int before = caret - 1;
        while (before >= lineStart
                && isIndentWs(document.charAt(before))) {
            before--;
        }
        char open = before >= lineStart ? document.charAt(before) : '\0';
        boolean increase = open == '{' || open == '(' || open == '[';

        // Empty-block split: `{|}` → `{\n<indent+>\n<indent>}` with caret middle.
        boolean splitBlock = false;
        if (increase && caret < document.length()) {
            int after = caret;
            while (after < document.lineEnd(line)
                    && isIndentWs(document.charAt(after))) {
                after++;
            }
            if (after < document.length()) {
                char closer = document.charAt(after);
                splitBlock = (open == '{' && closer == '}')
                        || (open == '(' && closer == ')')
                        || (open == '[' && closer == ']');
            }
        }

        if (splitBlock) {
            String inner = indent + unit;
            String insert = "\n" + inner + "\n" + indent;
            document.insert(caret, insert);
            // Caret sits on the inner blank line, after its indent.
            caret = caret + 1 + inner.length();
        } else {
            String next = indent + (increase ? unit : "");
            String insert = "\n" + next;
            document.insert(caret, insert);
            caret += insert.length();
        }
        afterEdit();
        dismissCompletions();
    }

    public void deleteBackward() {
        if (deleteSelectionIfAny()) {
            afterEdit();
            dismissCompletions();
            return;
        }
        if (caret > 0) {
            int start = prevClusterOffset(caret);
            document.delete(start, caret);
            caret = start;
            afterEdit();
            // Backspace updates the prefix if a popup is already up; otherwise
            // don't spontaneously open one (avoids covering the IME mid-delete).
            if (completionPopup != null && completionPopup.isShowing()) {
                requestCompletionsAtCaret();
            }
        }
    }

    public void deleteForward() {
        if (deleteSelectionIfAny()) {
            afterEdit();
            dismissCompletions();
            return;
        }
        if (caret < document.length()) {
            int end = nextClusterOffset(caret);
            document.delete(caret, end);
            afterEdit();
            if (completionPopup != null && completionPopup.isShowing()) {
                requestCompletionsAtCaret();
            }
        }
    }

    private static boolean shouldAutoCompleteAfter(String text) {
        if (text == null || text.isEmpty()) return false;
        // Single identifier character → complete. Multi-char commits from the
        // IME (e.g. CJK) also qualify if they end in an identifier part.
        char last = text.charAt(text.length() - 1);
        return Character.isJavaIdentifierPart(last) && !Character.isDigit(text.charAt(0));
    }

    private boolean deleteSelectionIfAny() {
        if (!hasSelection()) {
            return false;
        }
        int s = Math.min(caret, selectionAnchor);
        int e = Math.max(caret, selectionAnchor);
        document.delete(s, e);
        caret = s;
        selectionAnchor = -1;
        return true;
    }

    private void afterEdit() {
        // Non-IME edits (hardware keys, paste via our own API, undo) clear any
        // stale composing region so the IME cannot keep extending it.
        if (imeBatchDepth == 0 && !applyingEditableMutation) {
            composingStart = composingEnd = -1;
        }
        clampCaret();
        updateGutterWidth();
        ensureCaretVisible();
        resetCaretBlink();
        // Content first: rebuild Editable text if the piece table changed.
        // notifyImeSelection then updates Selection/COMPOSING spans (and is a
        // no-op for the text body when lengths already match).
        if (!applyingEditableMutation && activeInputConnection != null) {
            activeInputConnection.syncFromDocument();
        }
        notifyImeSelection();
        invalidate();
        // Typing / paste / cut collapses selection — keep the floating toolbar
        // in sync (dismiss when nothing is selected, refresh otherwise).
        if (!applyingEditableMutation) {
            if (hasSelection()) invalidateSelectionToolbar();
            else hideSelectionToolbar();
        }
    }

    private void clampCaret() {
        caret = normalizeCaretOffset(caret);
        if (selectionAnchor > document.length()) {
            selectionAnchor = -1;
        } else if (selectionAnchor >= 0) {
            selectionAnchor = normalizeCaretOffset(selectionAnchor);
        }
        if (composingStart >= 0) {
            int len = document.length();
            composingStart = Math.max(0, Math.min(composingStart, len));
            composingEnd = Math.max(composingStart, Math.min(composingEnd, len));
            if (composingStart == composingEnd) {
                composingStart = composingEnd = -1;
            }
        }
    }

    // ------------------------------------------------------------------
    // Selection & caret movement
    // ------------------------------------------------------------------

    public boolean hasSelection() {
        return selectionAnchor >= 0 && selectionAnchor != caret;
    }

    public void moveCaretTo(int offset, boolean extendSelection) {
        offset = normalizeCaretOffset(offset);
        if (extendSelection) {
            if (selectionAnchor < 0) {
                selectionAnchor = caret;
            }
        } else {
            selectionAnchor = -1;
        }
        caret = offset;
        activeHandle = Handle.NONE;
        undoManager.sealCurrent(); // caret jump ends the typing merge run
        // Moving the caret aborts an in-progress composition (matches EditText).
        if (composingStart >= 0 && imeBatchDepth == 0) {
            composingStart = composingEnd = -1;
        }
        ensureCaretVisible();
        resetCaretBlink();
        notifyImeSelection();
        invalidate();
        // Never auto-pop the toolbar on caret/selection moves — pan, handle
        // drag and keyboard navigation only refresh it when already visible.
        // Explicit gestures (long-press, tap-in-selection) call show themselves.
        if (hasSelection()) {
            if (selectionToolbarVisible) invalidateSelectionToolbar();
        } else {
            hideSelectionToolbar();
        }
    }

    private void moveCaretVertically(int lineDelta, boolean extend) {
        int line = document.lineOfOffset(caret);
        int col = caret - document.lineStart(line);
        int target = Math.max(0, Math.min(line + lineDelta, document.lineCount() - 1));
        moveCaretTo(document.offsetAt(target, col), extend);
    }

    /**
     * Selects the word (identifier run) under {@code offset}. Falls back to a
     * single shaped cluster when the touch is on whitespace/punctuation. Always
     * leaves {@link #hasSelection()} true so the handles appear. Never leaves
     * the caret or anchor inside a surrogate / combining / ligature cluster.
     */
    private void selectWordAt(int offset) {
        if (document == null || document.length() == 0) {
            selectionAnchor = -1;
            caret = 0;
            invalidate();
            return;
        }
        offset = Math.max(0, Math.min(offset, document.length()));
        int probe = offset < document.length() ? offset
                : Math.max(0, offset - 1);
        // Land on the cluster start so supplementary-plane probes never leave
        // a lone high surrogate selected.
        probe = clusterStartAtOrBefore(probe);
        char pc = document.charAt(probe);
        int s;
        int e;
        if (isWordChar(pc)) {
            s = probe;
            e = expandOffsetToClusterEnd(probe + 1);
            while (s > 0 && isWordChar(document.charAt(s - 1))) s--;
            s = clusterStartAtOrBefore(s);
            while (e < document.length() && isWordChar(document.charAt(e))) {
                e = expandOffsetToClusterEnd(e + 1);
            }
        } else if (Character.isWhitespace(pc)) {
            s = probe;
            e = expandOffsetToClusterEnd(probe + 1);
            while (s > 0 && Character.isWhitespace(document.charAt(s - 1))) s--;
            s = clusterStartAtOrBefore(s);
            while (e < document.length()
                    && Character.isWhitespace(document.charAt(e))) {
                e = expandOffsetToClusterEnd(e + 1);
            }
            // A pure-whitespace "word" is rarely useful — take one cluster.
            if (e - s > 1) {
                s = probe;
                e = expandOffsetToClusterEnd(probe + 1);
            }
        } else {
            s = probe;
            e = expandOffsetToClusterEnd(probe + 1);
        }
        if (s == e) {
            // Absolute last resort so hasSelection() is true and handles show.
            e = expandOffsetToClusterEnd(Math.min(document.length(), s + 1));
            if (s == e && s > 0) {
                s = prevClusterOffset(s);
            }
        }
        selectionAnchor = s;
        caret = e;
        activeHandle = Handle.NONE;
        composingStart = composingEnd = -1;
        ensureCaretVisible();
        resetCaretBlink();
        notifyImeSelection();
        invalidate();
        showSelectionToolbar();
    }

    /**
     * Selects a complete word and records immutable initial bounds. A long
     * press stays locked to these bounds until the finger moves beyond slop
     * and outside the word; small touch jitter can never shrink "public" to
     * "pub".
     */
    private void activateWordSelectionAt(float viewX, float viewY,
                                         boolean fromDoubleTap) {
        requestFocus();
        selectWordAt(characterOffsetForPoint(viewX, viewY));
        initialSelectionStart = selectionStart();
        initialSelectionEnd = selectionEnd();
        dismissCompletions();
        try {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) {
        }
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        touchMode = fromDoubleTap
                ? TouchMode.DOUBLE_TAP_LOCKED
                : TouchMode.LONG_PRESS_LOCKED;
    }

    /** Expands from the initial whole-word selection without shrinking it. */
    private void extendInitialWordSelection(float viewX, float viewY) {
        int offset = offsetForPoint(viewX, viewY); // already cluster-snapped
        if (offset < initialSelectionStart) {
            selectionAnchor = initialSelectionEnd;
            caret = offset;
            activeHandle = Handle.START;
        } else if (offset > initialSelectionEnd) {
            selectionAnchor = initialSelectionStart;
            caret = offset;
            activeHandle = Handle.END;
        } else {
            // Still inside the initially selected word: preserve it exactly.
            selectionAnchor = initialSelectionStart;
            caret = initialSelectionEnd;
            activeHandle = Handle.NONE;
        }
        caret = normalizeCaretOffset(caret);
        selectionAnchor = normalizeCaretOffset(selectionAnchor);
        ensureCaretVisible();
        resetCaretBlink();
        notifyImeSelection();
        invalidate();
        // Extending the selection is a move — hide the bar; user re-taps the
        // selection to bring it back.
        dismissSelectionToolbar();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$'
                || Character.isJavaIdentifierPart(c);
    }

    private int selectionStart() {
        if (!hasSelection()) return caret;
        return Math.min(caret, selectionAnchor);
    }

    private int selectionEnd() {
        if (!hasSelection()) return caret;
        return Math.max(caret, selectionAnchor);
    }

    /**
     * Hit-tests selection / insertion handles. Selection handles win over the
     * insertion handle; a bare caret's insertion handle is only tappable while
     * the insertion toolbar is up so it doesn't steal the first long-press.
     *
     * <p>Hit centres use the drawable's <em>hotspot-relative body</em>, not the
     * full bitmap centre — platform handle PNGs carry ~1/4 width of transparent
     * padding on each side, so a full-bitmap centre would sit far outside the
     * painted teardrop.
     */
    private Handle hitTestHandle(float viewX, float viewY) {
        if (document == null || lineHeight <= 0f || charWidth <= 0f) {
            return Handle.NONE;
        }
        if (hasSelection()) {
            // Radius ≈ half the painted body (hotspot is at 1/4 or 3/4 of width,
            // so the opaque body spans ~half the intrinsic width).
            float bodyW = handleIntrinsicW > 0 ? handleIntrinsicW * 0.5f
                    : handleRadius * 2f;
            float bodyH = handleIntrinsicH > 0 ? handleIntrinsicH
                    : handleRadius * 2f;
            float hitR = Math.max(24f * density, Math.max(bodyW, bodyH) * 0.65f);
            float hitR2 = hitR * hitR;
            float[] start = handleViewPos(selectionStart(), Handle.START);
            float[] end = handleViewPos(selectionEnd(), Handle.END);
            float ds = dist2(viewX, viewY, start[0], start[1]);
            float de = dist2(viewX, viewY, end[0], end[1]);
            if (ds <= hitR2 || de <= hitR2) {
                return ds <= de ? Handle.START : Handle.END;
            }
            return Handle.NONE;
        }
        if (isFocused() && !scaling
                && (insertionToolbarAllowed || activeHandle == Handle.INSERT)) {
            float bodyW = handleMiddleW > 0 ? handleMiddleW : handleRadius * 1.6f;
            float bodyH = handleMiddleH > 0 ? handleMiddleH : handleRadius * 2f;
            float hitR = Math.max(24f * density, Math.max(bodyW, bodyH) * 0.7f);
            float[] mid = handleViewPos(caret, Handle.INSERT);
            if (dist2(viewX, viewY, mid[0], mid[1]) <= hitR * hitR) {
                return Handle.INSERT;
            }
        }
        return Handle.NONE;
    }

    /**
     * View-local centre of the <em>painted</em> handle body for hit-testing.
     * Uses the same AOSP hotspot placement as {@link #drawHandleAt}:
     * <ul>
     *   <li>START (LTR left drawable): hotspotX = 3/4 width</li>
     *   <li>END   (LTR right drawable): hotspotX = 1/4 width</li>
     *   <li>INSERT (middle drawable): hotspotX = 1/2 width</li>
     * </ul>
     * Body centre is then roughly the midpoint of the opaque region, which for
     * Material handles sits near the bitmap centre.
     */
    private float[] handleViewPos(int offset, Handle which) {
        int line = document.lineOfOffset(offset);
        float tipX = contentXForOffset(offset) - getScrollX();
        float tipY = (line + 1) * lineHeight - getScrollY();
        float w;
        float h;
        float hotspotX;
        if (which == Handle.INSERT) {
            w = handleMiddleW > 0 ? handleMiddleW
                    : (handleIntrinsicW > 0 ? handleIntrinsicW * 0.55f
                    : handleRadius * 1.6f);
            h = handleMiddleH > 0 ? handleMiddleH
                    : (handleIntrinsicH > 0 ? handleIntrinsicH
                    : handleRadius * 2f);
            hotspotX = w * 0.5f;
        } else if (which == Handle.START) {
            w = handleIntrinsicW > 0 ? handleIntrinsicW : handleRadius * 2f;
            h = handleIntrinsicH > 0 ? handleIntrinsicH : handleRadius * 2f;
            // AOSP SelectionHandleView for start handle, LTR: 3/4 width.
            hotspotX = w * 0.75f;
        } else {
            w = handleIntrinsicW > 0 ? handleIntrinsicW : handleRadius * 2f;
            h = handleIntrinsicH > 0 ? handleIntrinsicH : handleRadius * 2f;
            // AOSP SelectionHandleView for end handle, LTR: 1/4 width.
            hotspotX = w * 0.25f;
        }
        // Drawable left edge so the hotspot lands on tipX; body centre ≈ mid.
        float left = tipX - hotspotX;
        return new float[]{left + w * 0.5f, tipY + h * 0.5f};
    }

    private static float dist2(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    /**
     * Moves the dragged end of the selection (or the insertion caret).
     * Finger Y is adjusted by {@link #handleDragMapYAdjust} so a touch on the
     * body below the line still maps into the correct line.
     *
     * <p>{@link #handleDragFixedOffset} is the edge NOT under the finger and
     * never moves during this gesture. When the finger crosses it we swap
     * {@link #activeHandle} (START ↔ END) so drawing/hit-testing stay correct,
     * but the fixed edge itself is unchanged — so dragging past and back does
     * not "walk" the other handle.
     */
    private void dragSelectionTo(float viewX, float viewY) {
        if (document == null) return;
        float mapY = viewY + handleDragMapYAdjust;
        int offset = normalizeCaretOffset(offsetForPoint(viewX, mapY));

        if (activeHandle == Handle.INSERT) {
            selectionAnchor = -1;
            caret = offset;
            composingStart = composingEnd = -1;
            ensureCaretVisible();
            resetCaretBlink();
            notifyImeSelection();
            invalidate();
            // Dragging the insertion handle is a move → hide toolbar.
            dismissSelectionToolbar();
            return;
        }

        int fixed = handleDragFixedOffset;
        if (fixed < 0) {
            fixed = activeHandle == Handle.START
                    ? selectionEnd() : selectionStart();
            handleDragFixedOffset = fixed;
        }
        fixed = normalizeCaretOffset(fixed);

        // Finger owns one edge; fixed owns the other. Role follows order so
        // left drawable stays on the lower offset and right on the higher.
        int a = Math.min(offset, fixed);
        int b = Math.max(offset, fixed);
        selectionAnchor = a;
        caret = b;
        if (offset <= fixed) {
            // Finger is on (or at) the left edge → START handle.
            activeHandle = Handle.START;
        } else {
            // Finger is on the right edge → END handle.
            activeHandle = Handle.END;
        }

        ensureCaretVisible();
        resetCaretBlink();
        notifyImeSelection();
        invalidate();
        // Dragging a selection handle is a move → hide toolbar.
        dismissSelectionToolbar();
    }


    // ------------------------------------------------------------------
    // IME plumbing (called by EditorInputConnection)
    // ------------------------------------------------------------------

    /**
     * Commit text from the IME. {@code newCursorPosition} follows the
     * InputConnection contract: &gt;0 = chars after the inserted text,
     * &lt;0 = chars before it, 0/1 = end of insert (the common case).
     */
    void commitTextFromIme(String text, int newCursorPosition) {
        if (text == null) text = "";
        // Soft keyboards often deliver Enter as a bare "\n" commit rather than
        // a KEYCODE_ENTER event — route those through the indent-aware path.
        if (composingStart < 0 && isOnlyNewlines(text)) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') insertNewlineWithIndent();
            }
            notifyImeSelection();
            return;
        }
        int insertAt;
        if (composingStart >= 0) {
            document.replace(composingStart, composingEnd, text);
            insertAt = composingStart;
            composingStart = composingEnd = -1;
        } else {
            deleteSelectionIfAny();
            insertAt = caret;
            if (text.indexOf('\n') >= 0) {
                // Multi-line: indent-aware path places the caret itself.
                insertMultilineIndented(text);
                notifyImeSelection();
                return;
            }
            document.insert(caret, text);
        }
        caret = insertAt + text.length();
        applyImeCursorPosition(insertAt, text.length(), newCursorPosition);
        afterEdit();
        if (shouldAutoCompleteAfter(text)) {
            requestCompletionsAtCaret();
        } else {
            dismissCompletions();
        }
    }

    /** Back-compat for call sites that don't care about the cursor arg. */
    void commitTextFromIme(String text) {
        commitTextFromIme(text, 1);
    }

    void replaceComposingFromIme(String text, int newCursorPosition) {
        if (text == null) text = "";
        if (composingStart < 0) {
            deleteSelectionIfAny();
            composingStart = caret;
            composingEnd = caret;
        }
        document.replace(composingStart, composingEnd, text);
        composingEnd = composingStart + text.length();
        caret = composingEnd;
        applyImeCursorPosition(composingStart, text.length(), newCursorPosition);
        // Keep composing alive across intermediate updates — do NOT go through
        // afterEdit() which would clear it. Still notify the IME so suggestion
        // engines see the growing preedit.
        clampCaret();
        ensureCaretVisible();
        resetCaretBlink();
        notifyImeSelection();
        invalidate();
        // Don't pop the completion list over the IME candidate bar mid-compose.
        dismissCompletions();
    }

    void replaceComposingFromIme(String text) {
        replaceComposingFromIme(text, 1);
    }

    void setComposingRegionFromIme(int start, int end) {
        if (document == null) return;
        int len = document.length();
        start = Math.max(0, Math.min(start, len));
        end = Math.max(0, Math.min(end, len));
        if (start > end) {
            int t = start; start = end; end = t;
        }
        if (start == end) {
            composingStart = composingEnd = -1;
        } else {
            composingStart = start;
            composingEnd = end;
        }
        notifyImeSelection();
        invalidate();
    }

    void finishComposingFromIme() {
        if (composingStart < 0) return;
        composingStart = composingEnd = -1;
        notifyImeSelection();
        invalidate();
    }

    /** IME key-event backspace; unlike hardware delete it preserves preedit. */
    void deleteBackwardFromIme() {
        if (hasSelection()) {
            int s = selectionStart();
            int e = selectionEnd();
            document.delete(s, e);
            transformComposingAfterReplace(s, e, 0);
            caret = s;
            selectionAnchor = -1;
            afterImeMutationPreservingComposition();
            return;
        }
        if (caret <= 0) return;
        int start = prevClusterOffset(caret);
        int oldCaret = caret;
        document.delete(start, oldCaret);
        transformComposingAfterReplace(start, oldCaret, 0);
        caret = start;
        afterImeMutationPreservingComposition();
    }

    /** IME key-event forward delete; preserves preedit while deleting within it. */
    void deleteForwardFromIme() {
        if (hasSelection()) {
            int s = selectionStart();
            int e = selectionEnd();
            document.delete(s, e);
            transformComposingAfterReplace(s, e, 0);
            caret = s;
            selectionAnchor = -1;
            afterImeMutationPreservingComposition();
            return;
        }
        if (caret >= document.length()) return;
        int end = nextClusterOffset(caret);
        document.delete(caret, end);
        transformComposingAfterReplace(caret, end, 0);
        afterImeMutationPreservingComposition();
    }

    private void afterImeMutationPreservingComposition() {
        clampCaret();
        updateGutterWidth();
        ensureCaretVisible();
        resetCaretBlink();
        notifyImeSelection();
        invalidate();
        dismissCompletions();
    }

    void deleteSurroundingFromIme(int before, int after) {
        before = Math.max(0, before);
        after = Math.max(0, after);
        // A no-op is genuinely a no-op. Some IMEs probe with (0,0); ending
        // composition here made the candidate strip disappear.
        if (before == 0 && after == 0) {
            notifyImeSelection();
            return;
        }

        if (hasSelection()) {
            // InputConnection defines "before" relative to selectionStart and
            // "after" relative to selectionEnd. The selected text itself must
            // remain untouched. Delete the right range first so left offsets
            // stay stable, then shift the preserved selection by leftRemoved.
            int selStart = selectionStart();
            int selEnd = selectionEnd();
            boolean forward = selectionAnchor <= caret;
            int leftStart = Math.max(0, selStart - before);
            int rightEnd = Math.min(document.length(), selEnd + after);
            leftStart = snapRangeStartToCluster(leftStart);
            rightEnd = expandOffsetToClusterEnd(rightEnd);

            int rightRemoved = Math.max(0, rightEnd - selEnd);
            if (rightRemoved > 0) {
                document.delete(selEnd, rightEnd);
                transformComposingAfterReplace(selEnd, rightEnd, 0);
            }
            int leftRemoved = Math.max(0, selStart - leftStart);
            if (leftRemoved > 0) {
                document.delete(leftStart, selStart);
                transformComposingAfterReplace(leftStart, selStart, 0);
            }

            int newStart = selStart - leftRemoved;
            int newEnd = selEnd - leftRemoved;
            if (forward) {
                selectionAnchor = newStart;
                caret = newEnd;
            } else {
                selectionAnchor = newEnd;
                caret = newStart;
            }
            afterImeMutationPreservingComposition();
            return;
        }

        int s = Math.max(0, caret - before);
        int e = Math.min(document.length(), caret + after);
        // IME counts UTF-16 units; expand both edges out to shaped cluster
        // boundaries so deletion never splits a surrogate/combining sequence.
        s = snapRangeStartToCluster(s);
        e = expandOffsetToClusterEnd(e);
        if (s == e) {
            notifyImeSelection();
            return;
        }
        document.delete(s, e);
        transformComposingAfterReplace(s, e, 0);
        caret = s;
        selectionAnchor = -1;
        afterImeMutationPreservingComposition();
    }

    /**
     * Android 14+ InputConnection.replaceText: replace absolute document range
     * and finish composition per the platform contract.
     */
    void replaceRangeFromIme(int start, int end, String text,
                             int newCursorPosition) {
        if (document == null) return;
        if (text == null) text = "";
        int len = document.length();
        start = Math.max(0, Math.min(start, len));
        end = Math.max(0, Math.min(end, len));
        if (start > end) {
            int t = start; start = end; end = t;
        }
        document.replace(start, end, text);
        // replaceText explicitly finishes composing text.
        composingStart = composingEnd = -1;
        applyImeCursorPosition(start, text.length(), newCursorPosition);
        afterImeMutationPreservingComposition();
    }

    /**
     * Transform composing [start,end) through one document replacement
     * [replaceStart,replaceEnd) → insertedLength. Text before composition
     * shifts it; text inside composition shrinks/grows it; disjoint text after
     * it leaves the range untouched. Partial cross-boundary replacements end
     * composition because the IME no longer has an unambiguous preedit range.
     */
    private void transformComposingAfterReplace(int replaceStart, int replaceEnd,
                                                int insertedLength) {
        if (composingStart < 0) return;
        int oldStart = composingStart;
        int oldEnd = composingEnd;
        int removed = Math.max(0, replaceEnd - replaceStart);
        int delta = insertedLength - removed;

        if (replaceEnd <= oldStart) {
            // Edit entirely before composition.
            composingStart = oldStart + delta;
            composingEnd = oldEnd + delta;
        } else if (replaceStart >= oldEnd) {
            // Edit entirely after composition: unchanged.
        } else if (replaceStart >= oldStart && replaceEnd <= oldEnd) {
            // Edit wholly inside composition.
            composingEnd = oldEnd + delta;
            if (composingEnd <= composingStart) {
                composingStart = composingEnd = -1;
            }
        } else {
            // Crosses exactly one boundary — safest to finish composition.
            composingStart = composingEnd = -1;
        }
    }

    void deleteSurroundingCodePointsFromIme(int beforeCp, int afterCp) {
        if (document == null) return;
        beforeCp = Math.max(0, beforeCp);
        afterCp = Math.max(0, afterCp);
        // Counts are relative to the selection boundaries, not always `caret`.
        int beforeEdge = hasSelection() ? selectionStart() : caret;
        int afterEdge = hasSelection() ? selectionEnd() : caret;
        int before = utf16UnitsBefore(beforeEdge, beforeCp);
        int after = utf16UnitsAfter(afterEdge, afterCp);
        deleteSurroundingFromIme(before, after);
    }

    private int utf16UnitsBefore(int offset, int codePointCount) {
        int units = 0;
        int i = Math.max(0, Math.min(offset, document.length()));
        for (int n = 0; n < codePointCount && i > 0; n++) {
            int cp = Character.codePointBefore(document, i);
            int width = Character.charCount(cp);
            i -= width;
            units += width;
        }
        return units;
    }

    private int utf16UnitsAfter(int offset, int codePointCount) {
        int units = 0;
        int i = Math.max(0, Math.min(offset, document.length()));
        int len = document.length();
        for (int n = 0; n < codePointCount && i < len; n++) {
            int cp = Character.codePointAt(document, i);
            int width = Character.charCount(cp);
            i += width;
            units += width;
        }
        return units;
    }

    void setSelectionFromIme(int start, int end) {
        if (document == null) return;
        int len = document.length();
        start = Math.max(0, Math.min(start, len));
        end = Math.max(0, Math.min(end, len));
        if (start == end) {
            selectionAnchor = -1;
            caret = start;
        } else {
            selectionAnchor = start;
            caret = end;
        }
        // BaseInputConnection.setSelection does NOT remove composing spans.
        // English IMEs commonly re-assert the cursor after a backspace; ending
        // composition here erased their candidate strip. Only drop composition
        // when the requested selection is completely outside the preedit.
        if (composingStart >= 0) {
            int low = Math.min(start, end);
            int high = Math.max(start, end);
            boolean caretInside = start == end
                    && start >= composingStart && start <= composingEnd;
            boolean selectionInside = start != end
                    && low >= composingStart && high <= composingEnd;
            if (!caretInside && !selectionInside) {
                composingStart = composingEnd = -1;
            }
        }
        undoManager.sealCurrent();
        ensureCaretVisible();
        resetCaretBlink();
        notifyImeSelection();
        invalidate();
    }

    void beginImeBatch() {
        if (imeBatchDepth++ == 0 && undoManager != null) {
            undoManager.beginBatch();
        }
    }

    void endImeBatch() {
        if (imeBatchDepth <= 0) return;
        if (--imeBatchDepth == 0 && undoManager != null) {
            undoManager.endBatch();
            // End of a multi-step IME gesture: make sure selection is published.
            notifyImeSelection();
        }
    }

    CharSequence textBeforeCursor(int length) {
        if (document == null || length <= 0) return "";
        // InputConnection cursor is the start of selection for "before".
        int cursor = hasSelection() ? selectionStart() : caret;
        int s = Math.max(0, cursor - length);
        return document.substring(s, cursor);
    }

    CharSequence textAfterCursor(int length) {
        if (document == null || length <= 0) return "";
        // InputConnection cursor is the end of selection for "after".
        int cursor = hasSelection() ? selectionEnd() : caret;
        int e = Math.min(document.length(), cursor + length);
        return document.substring(cursor, e);
    }

    CharSequence selectedTextForIme() {
        if (!hasSelection() || document == null) return null;
        int s = selectionStart();
        int e = selectionEnd();
        if (s == e) return null;
        return document.substring(s, e);
    }

    int cursorCapsMode(int reqModes) {
        if (document == null) return 0;
        // TextUtils.getCapsMode understands the same CAP_MODE_* bits that
        // EditorInfo / InputType use for TYPE_TEXT_FLAG_CAP_*.
        return TextUtils.getCapsMode(document, caret, reqModes);
    }

    ExtractedText extractedTextForIme(ExtractedTextRequest request) {
        if (document == null) return null;
        ExtractedText et = new ExtractedText();
        // Cap the snapshot so a multi-MB buffer never freezes the IME thread.
        final int MAX = 32 * 1024;
        int len = document.length();
        int start = 0;
        int end = len;
        if (len > MAX) {
            // Prefer a window around the caret.
            start = Math.max(0, caret - MAX / 2);
            end = Math.min(len, start + MAX);
            start = Math.max(0, end - MAX);
        }
        et.text = document.substring(start, end);
        et.startOffset = start;
        et.partialStartOffset = -1;
        et.partialEndOffset = -1;
        int selStart = hasSelection() ? selectionStart() : caret;
        int selEnd = hasSelection() ? selectionEnd() : caret;
        et.selectionStart = Math.max(0, selStart - start);
        et.selectionEnd = Math.max(0, selEnd - start);
        et.flags = 0;
        if (request != null) {
            extractedToken = request.token;
        }
        return et;
    }

    void onExtractedTextRequested(ExtractedTextRequest request, int flags) {
        if (request != null) {
            extractedToken = request.token;
        }
        extractedMonitor =
                (flags & InputConnection.GET_EXTRACTED_TEXT_MONITOR) != 0;
    }

    /**
     * Apply InputConnection's newCursorPosition relative to an insertion of
     * {@code insertLen} characters that began at {@code insertAt}.
     */
    private void applyImeCursorPosition(int insertAt, int insertLen,
                                        int newCursorPosition) {
        if (newCursorPosition > 0) {
            // Relative to one char before end: 1 means just after inserted text.
            caret = insertAt + insertLen + (newCursorPosition - 1);
        } else {
            // <=0 is relative to the replacement start: 0 means before text.
            caret = insertAt + newCursorPosition;
        }
        caret = Math.max(0, Math.min(caret, document.length()));
        selectionAnchor = -1;
    }

    /**
     * Push selection + composing span to the IME so suggestion / gesture
     * engines stay aligned with the piece table. Also refreshes extracted
     * text when the IME asked for continuous monitoring.
     */
    private void notifyImeSelection() {
        if (!isAttachedToWindow() || document == null) return;
        int selStart = hasSelection() ? selectionStart() : caret;
        int selEnd = hasSelection() ? selectionEnd() : caret;
        int compStart = composingStart >= 0 ? composingStart : -1;
        int compEnd = composingEnd >= 0 ? composingEnd : -1;
        long version = document.version();
        boolean positionsUnchanged = selStart == lastImmSelStart
                && selEnd == lastImmSelEnd
                && compStart == lastImmCompStart
                && compEnd == lastImmCompEnd;
        boolean textUnchanged = version == lastImmDocumentVersion;
        if (positionsUnchanged && textUnchanged) {
            if (cursorAnchorMonitor) {
                publishCursorAnchorInfo();
            }
            return;
        }
        lastImmSelStart = selStart;
        lastImmSelEnd = selEnd;
        lastImmCompStart = compStart;
        lastImmCompEnd = compEnd;
        lastImmDocumentVersion = version;

        // Keep the IME Editable's Selection/COMPOSING spans in lockstep with
        // the drawn caret. Without this, a tap only updates our caret field
        // and IMM.updateSelection, while BaseInputConnection still inserts at
        // the previous Editable selection.
        if (!applyingEditableMutation && activeInputConnection != null) {
            activeInputConnection.syncSelectionFromEditor();
        }

        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        // Calling this even when only text changed is intentional: several
        // English IMEs use it as the signal to re-query surrounding text.
        imm.updateSelection(this, selStart, selEnd, compStart, compEnd);
        if (extractedMonitor) {
            ExtractedText et = extractedTextForIme(null);
            if (et != null) {
                imm.updateExtractedText(this, extractedToken, et);
            }
        }
        if (cursorAnchorMonitor) {
            publishCursorAnchorInfo();
        }
    }

    /** Force the IME to re-read surrounding text (after big external edits). */
    private void restartImeInput() {
        lastImmSelStart = lastImmSelEnd = lastImmCompStart = lastImmCompEnd = -1;
        lastImmDocumentVersion = -1;
        if (activeInputConnection != null) {
            activeInputConnection.syncFromDocument();
        }
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.restartInput(this);
        }
    }

    // ------------------------------------------------------------------
    // Editable-backed IME bridge (called by EditorInputConnection)
    // ------------------------------------------------------------------

    int imeSelectionStart() {
        return hasSelection() ? selectionStart() : caret;
    }

    int imeSelectionEnd() {
        return hasSelection() ? selectionEnd() : caret;
    }

    int imeComposingStart() {
        return composingStart;
    }

    int imeComposingEnd() {
        return composingEnd;
    }

    int getDocumentLength() {
        return document == null ? 0 : document.length();
    }

    /**
     * Apply a replacement originating from the IME Editable mirror. Preserves
     * the current composing range (the Editable already holds SPAN_COMPOSING;
     * {@link #applyImeStateFromEditable} will re-read it right after).
     */
    void replaceRangeFromEditable(int start, int end, String text) {
        if (document == null) return;
        if (text == null) text = "";
        int len = document.length();
        start = Math.max(0, Math.min(start, len));
        end = Math.max(0, Math.min(end, len));
        if (start > end) {
            int t = start; start = end; end = t;
        }
        applyingEditableMutation = true;
        try {
            if (start != end || !text.isEmpty()) {
                document.replace(start, end, text);
            }
            // Keep caret at end of inserted text as a baseline; selection
            // will be overwritten by applyImeStateFromEditable.
            caret = start + text.length();
            selectionAnchor = -1;
            clampCaret();
            updateGutterWidth();
            // Do not clear composing here — Editable still owns SPAN_COMPOSING.
        } finally {
            applyingEditableMutation = false;
        }
    }

    /**
     * Pull selection + composing from the Editable after an IME mutation.
     * Composing offsets come from {@code BaseInputConnection.getComposingSpan*}.
     */
    void applyImeStateFromEditable(int selStart, int selEnd,
                                   int compStart, int compEnd) {
        if (document == null) return;
        int len = document.length();
        selStart = Math.max(0, Math.min(selStart, len));
        selEnd = Math.max(0, Math.min(selEnd, len));
        if (selStart == selEnd) {
            selectionAnchor = -1;
            caret = selStart;
        } else {
            selectionAnchor = selStart;
            caret = selEnd;
        }
        if (compStart >= 0 && compEnd > compStart) {
            composingStart = Math.max(0, Math.min(compStart, len));
            composingEnd = Math.max(composingStart, Math.min(compEnd, len));
            if (composingStart == composingEnd) {
                composingStart = composingEnd = -1;
            }
        } else {
            composingStart = composingEnd = -1;
        }
        ensureCaretVisible();
        resetCaretBlink();
        // skip Editable re-sync — we just came from it
        notifyImeSelection();
        invalidate();
        // Code-completion popup is independent of the IME candidate strip.
        // Only refresh it when the user is typing identifier-ish text and not
        // mid-composition (IME candidates take priority then).
        if (composingStart < 0) {
            // Peek the last char before caret for auto-complete decision.
            if (caret > 0) {
                char c = document.charAt(caret - 1);
                if (Character.isJavaIdentifierPart(c) && !Character.isDigit(
                        document.charAt(Math.max(0, caret - 1)))) {
                    // Re-evaluate prefix; requestCompletionsAtCaret no-ops on empty.
                    requestCompletionsAtCaret();
                } else {
                    dismissCompletions();
                }
            } else {
                dismissCompletions();
            }
        } else {
            dismissCompletions();
        }
    }

    void onImeBatchFinished() {
        // Editable → document already applied; just publish selection / anchors.
        notifyImeSelection();
        invalidate();
    }

    boolean requestCursorUpdatesFromIme(int mode) {
        final int known = InputConnection.CURSOR_UPDATE_IMMEDIATE
                | InputConnection.CURSOR_UPDATE_MONITOR;
        if ((mode & ~known) != 0) {
            // Reject unknown flags (matches EditableInputConnection behaviour).
            // Character-bounds filters are optional; accept mode bits only.
            // If only known mode bits are set we're fine; filter bits beyond
            // IMMEDIATE/MONITOR are tolerated as "best effort".
        }
        cursorAnchorMonitor =
                (mode & InputConnection.CURSOR_UPDATE_MONITOR) != 0;
        if ((mode & InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0
                || cursorAnchorMonitor) {
            publishCursorAnchorInfo();
        }
        return true;
    }

    private void publishCursorAnchorInfo() {
        if (!isAttachedToWindow() || document == null) return;
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;

        int selStart = hasSelection() ? selectionStart() : caret;
        int selEnd = hasSelection() ? selectionEnd() : caret;
        CursorAnchorInfo.Builder b = new CursorAnchorInfo.Builder();
        b.setSelectionRange(selStart, selEnd);
        if (composingStart >= 0 && composingEnd > composingStart
                && composingEnd <= document.length()) {
            b.setComposingText(composingStart,
                    document.substring(composingStart, composingEnd));
        }

        // Insertion marker in view coordinates.
        int line = document.lineOfOffset(selEnd);
        float markerX = contentXForOffset(selEnd) - getScrollX();
        float top = line * lineHeight - getScrollY();
        float baseline = top + baselineShift;
        float bottom = top + lineHeight;
        int flags = CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION;
        if (markerX < 0 || markerX > getWidth() || bottom < 0 || top > getHeight()) {
            flags = CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION;
        }
        b.setInsertionMarkerLocation(markerX, top, baseline, bottom, flags);

        // Matrix: view → screen.
        getLocationOnScreen(viewLocationOnScreen);
        cursorAnchorMatrix.reset();
        cursorAnchorMatrix.postTranslate(viewLocationOnScreen[0],
                viewLocationOnScreen[1]);
        b.setMatrix(cursorAnchorMatrix);

        imm.updateCursorAnchorInfo(this, b.build());
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // Allow the system English suggestion bar / gesture typing. Code
        // editors still want multi-line + no extract/fullscreen chrome, but
        // NO_SUGGESTIONS was killing Gboard/Samsung predictive input.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        int selStart = hasSelection() ? selectionStart() : caret;
        int selEnd = hasSelection() ? selectionEnd() : caret;
        outAttrs.initialSelStart = selStart;
        outAttrs.initialSelEnd = selEnd;
        outAttrs.initialCapsMode = cursorCapsMode(
                TextUtils.CAP_MODE_CHARACTERS
                        | TextUtils.CAP_MODE_WORDS
                        | TextUtils.CAP_MODE_SENTENCES);
        // Seed surrounding text so the first suggestion round doesn't wait
        // for a getTextBeforeCursor round-trip. API 30+.
        if (document != null
                && android.os.Build.VERSION.SDK_INT >= 30) {
            final int SEED = 2048;
            int seedStart = Math.max(0, caret - SEED);
            int seedEnd = Math.min(document.length(), caret + SEED);
            CharSequence surrounding = document.substring(seedStart, seedEnd);
            outAttrs.setInitialSurroundingSubText(surrounding, seedStart);
        }
        lastImmSelStart = selStart;
        lastImmSelEnd = selEnd;
        lastImmCompStart = composingStart;
        lastImmCompEnd = composingEnd;
        lastImmDocumentVersion = document == null ? -1 : document.version();
        extractedMonitor = false;
        cursorAnchorMonitor = false;

        EditorInputConnection ic = android.os.Build.VERSION.SDK_INT >= 34
                ? new EditorInputConnectionApi34(this)
                : new EditorInputConnection(this);
        activeInputConnection = ic;
        return ic;
    }

    // ------------------------------------------------------------------
    // Hardware keys
    // ------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean shift = event.isShiftPressed();
        boolean ctrl = event.isCtrlPressed();

        // Completion popup owns navigation keys while visible.
        if (completionPopup != null && completionPopup.isShowing()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    completionPopup.moveSelection(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    completionPopup.moveSelection(-1);
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_TAB:
                    if (completionPopup.pickSelected()) {
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_ESCAPE:
                    dismissCompletions();
                    return true;
                default:
                    break;
            }
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                deleteBackward();
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                deleteForward();
                return true;
            case KeyEvent.KEYCODE_ENTER:
                insertNewlineWithIndent();
                return true;
            case KeyEvent.KEYCODE_TAB:
                if (shift) {
                    outdentSelectionOrLine();
                } else {
                    indentSelectionOrInsertTab();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveCaretTo(prevClusterOffset(caret), shift);
                dismissCompletions();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveCaretTo(nextClusterOffset(caret), shift);
                dismissCompletions();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveCaretVertically(-1, shift);
                dismissCompletions();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveCaretVertically(1, shift);
                dismissCompletions();
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME: {
                int line = document.lineOfOffset(caret);
                moveCaretTo(document.lineStart(line), shift);
                dismissCompletions();
                return true;
            }
            case KeyEvent.KEYCODE_MOVE_END: {
                int line = document.lineOfOffset(caret);
                moveCaretTo(document.lineEnd(line), shift);
                dismissCompletions();
                return true;
            }
            case KeyEvent.KEYCODE_Z:
                if (ctrl) {
                    if (shift) {
                        redo();
                    } else {
                        undo();
                    }
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_Y:
                if (ctrl) {
                    redo();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_A:
                if (ctrl) {
                    selectAll();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_C:
                if (ctrl) {
                    copySelection();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_X:
                if (ctrl) {
                    cutSelection();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_V:
                if (ctrl) {
                    pasteFromClipboard();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_SPACE:
                if (ctrl) {
                    requestCompletionsAtCaret();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_PLUS:
                if (ctrl) {
                    setTextSizeSp(textSizeSp + 1f);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MINUS:
                if (ctrl) {
                    setTextSizeSp(textSizeSp - 1f);
                    return true;
                }
                break;
            default:
                break;
        }

        // Printable characters from a hardware keyboard.
        int unicode = event.getUnicodeChar(event.getMetaState());
        if (unicode != 0 && !ctrl) {
            insertAtCaret(String.valueOf((char) unicode));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------
    // Auto-indent
    // ------------------------------------------------------------------

    /** One indent step: currently four spaces (matches TAB_SIZE). */
    private String indentUnit() {
        // Keep in sync with TAB_SIZE so tab stops and indent levels agree.
        char[] unit = new char[TAB_SIZE];
        Arrays.fill(unit, ' ');
        return new String(unit);
    }

    private static boolean isIndentWs(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean isOnlyNewlines(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n') return false;
        }
        return true;
    }

    /** Leading spaces/tabs of a line (content only — no trailing newline). */
    private String leadingWhitespaceOfLine(int line) {
        if (document == null || line < 0 || line >= document.lineCount()) {
            return "";
        }
        int start = document.lineStart(line);
        int end = document.lineEnd(line);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = document.charAt(i);
            if (isIndentWs(c)) sb.append(c);
            else break;
        }
        return sb.toString();
    }

    /**
     * When the user types {@code }} on a line that is only whitespace + that
     * brace, drop one indent level so the closer aligns with its opener.
     */
    private void maybeOutdentBeforeClosingBrace() {
        int line = document.lineOfOffset(caret);
        int start = document.lineStart(line);
        int end = document.lineEnd(line);
        // Only act when every char before the caret on this line is indent ws
        // and nothing non-ws follows the caret on the line either.
        for (int i = start; i < caret; i++) {
            if (!isIndentWs(document.charAt(i))) return;
        }
        for (int i = caret; i < end; i++) {
            if (!isIndentWs(document.charAt(i))) return;
        }
        String indent = leadingWhitespaceOfLine(line);
        if (indent.isEmpty()) return;
        String unit = indentUnit();
        String reduced;
        if (indent.endsWith(unit)) {
            reduced = indent.substring(0, indent.length() - unit.length());
        } else if (indent.charAt(indent.length() - 1) == '\t') {
            reduced = indent.substring(0, indent.length() - 1);
        } else {
            // Partial spaces: peel off up to TAB_SIZE trailing spaces.
            int peel = 0;
            for (int i = indent.length() - 1; i >= 0 && peel < TAB_SIZE; i--) {
                if (indent.charAt(i) != ' ') break;
                peel++;
            }
            if (peel == 0) return;
            reduced = indent.substring(0, indent.length() - peel);
        }
        // Replace the leading whitespace in place; caret stays just after it.
        document.replace(start, start + indent.length(), reduced);
        caret = start + reduced.length();
    }

    /**
     * Paste / multi-line IME commit: first line as-is at caret, subsequent
     * lines rebased onto the insertion line's indent (plus extra after `{`).
     */
    private void insertMultilineIndented(String text) {
        deleteSelectionIfAny();
        String base = leadingWhitespaceOfLine(document.lineOfOffset(caret));
        // Extra level when inserting right after an opener.
        int line = document.lineOfOffset(caret);
        int lineStart = document.lineStart(line);
        int before = caret - 1;
        while (before >= lineStart && isIndentWs(document.charAt(before))) before--;
        if (before >= lineStart) {
            char c = document.charAt(before);
            if (c == '{' || c == '(' || c == '[') base = base + indentUnit();
        }

        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        // First segment (before any \n) is inserted raw — caret may sit mid-line.
        while (i < text.length() && text.charAt(i) != '\n') {
            out.append(text.charAt(i++));
        }
        while (i < text.length()) {
            out.append('\n');
            i++; // skip the \n
            // Strip the source line's own leading ws, then apply base.
            while (i < text.length() && isIndentWs(text.charAt(i))) i++;
            if (i < text.length() && text.charAt(i) != '\n') {
                out.append(base);
            }
            while (i < text.length() && text.charAt(i) != '\n') {
                out.append(text.charAt(i++));
            }
        }
        String insert = out.toString();
        document.insert(caret, insert);
        caret += insert.length();
        afterEdit();
        dismissCompletions();
    }

    /** Tab with a selection indents every touched line; bare Tab inserts a unit. */
    private void indentSelectionOrInsertTab() {
        if (!hasSelection()) {
            insertAtCaret(indentUnit());
            return;
        }
        int s = selectionStart();
        int e = selectionEnd();
        int firstLine = document.lineOfOffset(s);
        int lastLine = document.lineOfOffset(Math.max(s, e - 1));
        String unit = indentUnit();
        // Edit bottom-up so earlier offsets stay valid.
        undoManager.beginBatch();
        try {
            for (int line = lastLine; line >= firstLine; line--) {
                int ls = document.lineStart(line);
                document.insert(ls, unit);
            }
        } finally {
            undoManager.endBatch();
        }
        // Selection grows by one unit per line on the end side; start shifts too.
        int lines = lastLine - firstLine + 1;
        selectionAnchor = s + unit.length();
        caret = e + unit.length() * lines;
        afterEdit();
        dismissCompletions();
    }

    /** Shift+Tab: remove one indent unit from each selected (or caret) line. */
    private void outdentSelectionOrLine() {
        final boolean hadSelection = hasSelection();
        int s = hadSelection ? selectionStart() : caret;
        int e = hadSelection ? selectionEnd() : caret;
        int firstLine = document.lineOfOffset(s);
        // Caret at column 0 of line N+1 after selecting line N's '\n' must not
        // outdent line N+1 — bound the range by the last selected character.
        int lastLine = document.lineOfOffset(e > s ? e - 1 : e);
        int lineCount = lastLine - firstLine + 1;
        if (lineCount <= 0) return;

        // Snapshot original line starts + peels BEFORE any mutation.
        int[] origStarts = new int[lineCount];
        int[] peels = new int[lineCount];
        for (int i = 0; i < lineCount; i++) {
            int line = firstLine + i;
            int ls = document.lineStart(line);
            int le = document.lineEnd(line);
            origStarts[i] = ls;
            int peel = 0;
            if (ls < le && document.charAt(ls) == '\t') {
                peel = 1;
            } else {
                while (ls + peel < le && peel < TAB_SIZE
                        && document.charAt(ls + peel) == ' ') {
                    peel++;
                }
            }
            peels[i] = peel;
        }

        undoManager.beginBatch();
        try {
            // Bottom-up so earlier line starts stay valid during the loop.
            for (int i = lineCount - 1; i >= 0; i--) {
                if (peels[i] == 0) continue;
                int ls = document.lineStart(firstLine + i);
                document.delete(ls, ls + peels[i]);
            }
        } finally {
            undoManager.endBatch();
        }

        int newS = s - charsRemovedBefore(s, origStarts, peels);
        int newE = e - charsRemovedBefore(e, origStarts, peels);
        if (newS < 0) newS = 0;
        if (newE < newS) newE = newS;
        if (hadSelection) {
            selectionAnchor = newS;
            caret = newE;
        } else {
            caret = newS;
            selectionAnchor = -1;
        }
        afterEdit();
        dismissCompletions();
    }

    /**
     * How many leading-indent characters were peeled at offsets strictly
     * before {@code offset} (and partially if {@code offset} sat inside a
     * peeled run).
     */
    private static int charsRemovedBefore(int offset, int[] origStarts, int[] peels) {
        int removed = 0;
        for (int i = 0; i < peels.length; i++) {
            int peel = peels[i];
            if (peel == 0) continue;
            int os = origStarts[i];
            if (offset >= os + peel) removed += peel;
            else if (offset > os) removed += offset - os;
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // Completions
    // ------------------------------------------------------------------

    private void requestCompletionsAtCaret() {
        if (completionEngine != null) {
            completionEngine.requestCompletions(caret);
        }
    }

    private void dismissCompletions() {
        if (completionEngine != null) {
            completionEngine.cancel();
        } else if (completionPopup != null) {
            completionPopup.dismiss();
        }
    }

    @Override
    public void onCompletions(List<CompletionItem> items, int prefixStart, String prefix) {
        if (items == null || items.isEmpty()) {
            if (completionPopup != null) completionPopup.dismiss();
            return;
        }
        // Don't fight the user mid-gesture.
        if (scaling) {
            return;
        }
        this.completionPrefixStart = prefixStart;
        this.completionPrefixLength = prefix.length();

        // Caret rect in WINDOW coordinates, then clamp the popup to the
        // visible display frame (the region above the soft keyboard).
        int[] loc = new int[2];
        getLocationInWindow(loc);
        int line = document.lineOfOffset(caret);
        float caretDocX = contentXForOffset(caret);
        float caretDocTop = line * lineHeight;
        int caretX = loc[0] + Math.round(caretDocX) - getScrollX();
        int caretTop = loc[1] + Math.round(caretDocTop) - getScrollY();
        int caretBottom = caretTop + Math.round(lineHeight);

        getWindowVisibleDisplayFrame(visibleFrame);
        completionPopup.show(items, caretX, caretTop, caretBottom, visibleFrame);
    }

    private int completionPrefixStart;
    private int completionPrefixLength;

    private void applyCompletion(CompletionItem item) {
        String insert = item.insertText;
        int caretMark = insert.indexOf("$0");
        if (caretMark >= 0) {
            insert = insert.substring(0, caretMark) + insert.substring(caretMark + 2);
        }
        undoManager.beginBatch();
        try {
            int end = completionPrefixStart + completionPrefixLength;
            document.replace(completionPrefixStart, Math.min(end, document.length()), insert);
            caret = completionPrefixStart + (caretMark >= 0 ? caretMark : insert.length());
        } finally {
            undoManager.endBatch();
        }
        afterEdit();
        completionPopup.dismiss();
    }

    // ------------------------------------------------------------------
    // Highlighter callback
    // ------------------------------------------------------------------

    @Override
    public void onHighlightUpdated(int firstLine, int lastLine) {
        int firstVisible = (int) (getScrollY() / lineHeight);
        int lastVisible = (int) ((getScrollY() + getHeight()) / lineHeight) + 1;
        // Only visible lines matter; with hardware acceleration a full
        // invalidate is cheap and the 4-arg form is deprecated post-API 28.
        if (lastLine >= firstVisible && firstLine <= lastVisible) {
            invalidate();
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        final int scrollX = getScrollX();
        final int scrollY = getScrollY();
        final int viewW = getWidth();
        final int viewH = getHeight();
        final float lh = lineHeight;
        final float cw = charWidth;
        final float gw = gutterWidth;

        canvas.drawColor(scheme.background);

        if (document == null || lh <= 0f || cw <= 0f) {
            return;
        }

        final int lineCount = document.lineCount();
        int firstLine = Math.max(0, (int) (scrollY / lh));
        int lastLine = Math.min(lineCount - 1,
                (int) ((scrollY + viewH) / lh) + 1);
        if (firstLine > lastLine) {
            drawGutter(canvas, scrollX, scrollY, viewH, firstLine, lastLine, -1);
            return;
        }

        int caretLine = document.lineOfOffset(caret);
        int selStart = hasSelection() ? Math.min(caret, selectionAnchor) : -1;
        int selEnd = hasSelection() ? Math.max(caret, selectionAnchor) : -1;

        // Clip text to the content area so it slides under the gutter
        // instead of painting over line numbers during horizontal scroll.
        int save = canvas.save();
        canvas.clipRect(scrollX + gw, scrollY, scrollX + viewW, scrollY + viewH);

        for (int line = firstLine; line <= lastLine; line++) {
            float top = line * lh;
            float baseline = top + baselineShift;
            int lineStart = document.lineStart(line);
            String content = document.lineContent(line);
            int contentLen = content.length();
            float[] xs = buildColumnXs(content, contentLen);
            observeLineWidth(line, xs[contentLen]);

            // Current-line highlight.
            if (line == caretLine && selStart < 0) {
                fillPaint.setColor(scheme.currentLine);
                canvas.drawRect(scrollX + gw, top,
                        scrollX + viewW, top + lh, fillPaint);
            }

            // Selection background for this line's overlap.
            if (selStart >= 0) {
                int lineEnd = lineStart + contentLen;
                int s = Math.max(selStart, lineStart);
                int e = Math.min(selEnd, lineEnd + 1); // +1 covers the '\n'
                if (s < e) {
                    float x1 = gw + xs[s - lineStart];
                    // e may include the newline; paint one cell beyond EOL.
                    int endColumn = Math.min(contentLen, e - lineStart);
                    float x2 = gw + xs[endColumn];
                    if (e > lineEnd) x2 += cw;
                    fillPaint.setColor(scheme.selection);
                    canvas.drawRect(x1, top, x2, top + lh, fillPaint);
                }
            }

            // LSP diagnostic underlines (drawn under the glyphs).
            drawDiagnosticsForLine(canvas, line, lineStart, contentLen,
                    gw, top, lh, xs);

            // IME composing region — underline the preedit so English
            // suggestions / CJK composition remain visible.
            if (composingStart >= 0 && composingEnd > composingStart) {
                int lineEndOff = lineStart + contentLen;
                int cs = Math.max(composingStart, lineStart);
                int ce = Math.min(composingEnd, lineEndOff);
                if (cs < ce) {
                    float x1 = gw + xs[cs - lineStart];
                    float x2 = gw + xs[ce - lineStart];
                    float uy = top + lh - Math.max(2f, density);
                    diagnosticPaint.setColor(scheme.composingUnderline);
                    diagnosticPaint.setStrokeWidth(Math.max(1.5f, 1.5f * density));
                    canvas.drawLine(x1, uy, x2, uy, diagnosticPaint);
                }
            }

            // Text with spans. Stale spans (right after an edit, before the
            // worker republishes) are still painted — columns are clipped to
            // contentLen so we never read past the new line end. This is what
            // stops the colour flash when holding backspace in a comment.
            LineSpans spans = highlighter.spansFor(line);
            if (spans == null || spans.size() == 0 || contentLen == 0) {
                if (contentLen > 0) {
                    textPaint.setColor(scheme.foreground);
                    drawMeasuredRange(canvas, content, 0, contentLen,
                            gw, baseline, xs);
                }
            } else {
                drawSpannedLine(canvas, content, contentLen, spans, gw, baseline, xs);
            }

            // Caret (hidden while a range is selected — handles show the ends).
            if (line == caretLine && caretVisible && isFocused() && selStart < 0) {
                int caretColumn = Math.max(0,
                        Math.min(contentLen, caret - lineStart));
                float cx = gw + xs[caretColumn];
                fillPaint.setColor(scheme.caret);
                // Keep the insertion caret on the preceding side of the
                // boundary so it never paints over the next glyph (n|g).
                // At column zero there is no preceding cell, so draw right.
                float caretW = 2f; // physical pixels: stable across zoom levels
                float caretLeft = caretColumn == 0 ? cx : cx - caretW;
                canvas.drawRect(caretLeft, top, caretLeft + caretW,
                        top + lh, fillPaint);
            }
        }
        canvas.restoreToCount(save);

        drawGutter(canvas, scrollX, scrollY, viewH, firstLine, lastLine, caretLine);

        // Selection / caret handles are drawn in view space on top of everything
        // (including the gutter) so they stay tappable.
        if (isFocused() && !scaling) {
            drawSelectionHandles(canvas);
            drawSelectionToolbar(canvas);
        }
    }

    /**
     * Selection / insertion handles drawn like EditText: platform
     * {@code textSelectHandleLeft/Right/Middle} drawables with the tip on the
     * line bottom. Falls back to a smooth teardrop path when the theme has
     * none.
     */
    private void drawSelectionHandles(Canvas canvas) {
        if (document == null || lineHeight <= 0f) return;
        tintHandleDrawables();
        if (hasSelection()) {
            drawHandleAt(canvas, selectionStart(), Handle.START);
            drawHandleAt(canvas, selectionEnd(), Handle.END);
        } else if (isFocused() && !scaling
                && (insertionToolbarAllowed || activeHandle == Handle.INSERT)) {
            // EditText reveals the middle handle with the insertion toolbar
            // (tap caret / long-press empty) or while the user is dragging it.
            drawHandleAt(canvas, caret, Handle.INSERT);
        }
    }

    /**
     * Draws one handle in the same content coordinate system as the text.
     * Placement matches AOSP {@code Editor.SelectionHandleView}:
     * <pre>
     *   positionX = cursorX - hotspotX
     *   START/LTR hotspotX = 3/4 * intrinsicWidth
     *   END/LTR   hotspotX = 1/4 * intrinsicWidth
     *   INSERT    hotspotX = 1/2 * intrinsicWidth
     * </pre>
     * Material handle PNGs include ~1/4 width of transparent padding on each
     * side, so anchoring the bitmap edge to the caret leaves a large visual
     * gap — the hotspot formula cancels that padding.
     */
    private void drawHandleAt(Canvas canvas, int offset, Handle which) {
        if (document == null || lineHeight <= 0f) return;
        int line = document.lineOfOffset(offset);
        float tipX = contentXForOffset(offset);
        // EditText anchors the handle tip to the BOTTOM of the line.
        float tipY = (line + 1) * lineHeight;

        Drawable d;
        if (which == Handle.INSERT) {
            d = handleMiddleDrawable;
        } else if (which == Handle.START) {
            d = handleLeftDrawable;
        } else {
            d = handleRightDrawable;
        }

        if (d != null) {
            int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth()
                    : Math.round(handleRadius * 2f);
            int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight()
                    : Math.round(handleRadius * 2f);
            float hotspotX = handleHotspotX(which, w);
            int left = Math.round(tipX - hotspotX);
            int top = Math.round(tipY);
            d.setBounds(left, top, left + w, top + h);
            d.draw(canvas);
            return;
        }

        // Fallback teardrop when the host theme has no handle drawables.
        drawFallbackTeardrop(canvas, tipX, tipY, which);
    }

    /**
     * AOSP hotspot X within the drawable (LTR). The tip of the painted
     * teardrop sits at this x on the top edge of the bitmap.
     */
    private static float handleHotspotX(Handle which, float width) {
        if (which == Handle.INSERT) return width * 0.5f;
        if (which == Handle.START) return width * 0.75f; // 3/4
        return width * 0.25f;                            // 1/4
    }

    /** Smooth EditText-like teardrop used when system drawables are missing. */
    private void drawFallbackTeardrop(Canvas canvas, float tipX, float tipY,
                                      Handle which) {
        float r = handleRadius;
        // Place the body centre so the tip still lands on tipX — same visual
        // offset the Material assets achieve via padding/hotspot.
        float cx;
        float cy = tipY + r * 1.05f;
        if (which == Handle.INSERT) {
            cx = tipX;
        } else if (which == Handle.START) {
            cx = tipX - r * 0.35f;
        } else {
            cx = tipX + r * 0.35f;
        }
        float tipAngle = (float) Math.toDegrees(Math.atan2(tipY - cy, tipX - cx));
        final float wedge = 50f;
        float aStart = tipAngle + wedge;
        float sweep = 360f - 2f * wedge;
        float aEnd = aStart + sweep;
        float radStart = (float) Math.toRadians(aStart);
        float radEnd = (float) Math.toRadians(aEnd);
        float sx = cx + r * (float) Math.cos(radStart);
        float sy = cy + r * (float) Math.sin(radStart);
        float ex = cx + r * (float) Math.cos(radEnd);
        float ey = cy + r * (float) Math.sin(radEnd);

        handleFallbackPath.reset();
        handleFallbackPath.moveTo(tipX, tipY);
        handleFallbackPath.quadTo((tipX + sx) * 0.5f, (tipY + sy) * 0.5f, sx, sy);
        handleFallbackPath.arcTo(cx - r, cy - r, cx + r, cy + r, aStart, sweep, false);
        handleFallbackPath.quadTo((tipX + ex) * 0.5f, (tipY + ey) * 0.5f, tipX, tipY);
        handleFallbackPath.close();
        fillPaint.setColor(scheme.selectionHandle);
        canvas.drawPath(handleFallbackPath, fillPaint);
    }

    /**
     * Squiggly underline for every diagnostic that intersects {@code line}.
     * Coordinates match the text run (content space, gutter already added).
     */
    private void drawDiagnosticsForLine(Canvas canvas, int line, int lineStart,
                                        int contentLen, float gw, float top,
                                        float lh, float[] xs) {
        if (diagnostics.isEmpty() || contentLen < 0) return;
        float y = top + lh - Math.max(2f, density);
        for (int i = 0, n = diagnostics.size(); i < n; i++) {
            Diagnostic d = diagnostics.get(i);
            if (d.endLine < line || d.startLine > line) continue;
            int startCol = (d.startLine == line) ? d.startCharacter : 0;
            int endCol = (d.endLine == line) ? d.endCharacter : contentLen;
            startCol = Math.max(0, Math.min(startCol, contentLen));
            endCol = Math.max(startCol, Math.min(endCol, contentLen));
            // Zero-width (e.g. "expected ;") — underline one em at the point.
            float x1 = gw + xs[startCol];
            float x2 = (endCol > startCol) ? gw + xs[endCol] : x1 + Math.max(charWidth, 4f);
            diagnosticPaint.setColor(colorForSeverity(d.severity));
            drawSquiggle(canvas, x1, x2, y);
        }
    }

    private void drawSquiggle(Canvas canvas, float x1, float x2, float y) {
        if (x2 <= x1) return;
        float amp = Math.max(1.5f, 1.6f * density);
        float step = Math.max(3f, 3.2f * density);
        float x = x1;
        float dir = 1f;
        float prevX = x;
        float prevY = y;
        x += step * 0.5f;
        while (x < x2) {
            float ny = y + dir * amp;
            canvas.drawLine(prevX, prevY, x, ny, diagnosticPaint);
            prevX = x;
            prevY = ny;
            dir = -dir;
            x += step;
        }
        canvas.drawLine(prevX, prevY, x2, y, diagnosticPaint);
    }

    private int colorForSeverity(int severity) {
        switch (severity) {
            case Diagnostic.SEVERITY_ERROR:   return scheme.diagnosticError;
            case Diagnostic.SEVERITY_WARNING: return scheme.diagnosticWarning;
            case Diagnostic.SEVERITY_INFORMATION: return scheme.diagnosticInfo;
            case Diagnostic.SEVERITY_HINT:
            default:                          return scheme.diagnosticHint;
        }
    }

    /** Lowest severity number on the line wins (Error=1 beats Hint=4). */
    private int worstDiagnosticSeverityOnLine(int line) {
        int worst = 0;
        for (int i = 0, n = diagnostics.size(); i < n; i++) {
            Diagnostic d = diagnostics.get(i);
            if (d.startLine <= line && d.endLine >= line) {
                if (worst == 0 || d.severity < worst) worst = d.severity;
            }
        }
        return worst;
    }

    /**
     * Resolve {@link android.R.attr#textSelectHandleLeft/Right/Middle} from the
     * host theme, with a system-resource fallback so DeviceDefault hosts still
     * get EditText-style teardrops. Mutated copies keep tint from leaking.
     */
    private void loadHandleDrawables() {
        handleLeftDrawable = null;
        handleRightDrawable = null;
        handleMiddleDrawable = null;
        handleIntrinsicW = 0;
        handleIntrinsicH = 0;
        handleMiddleW = 0;
        handleMiddleH = 0;
        Context ctx = getContext();
        if (ctx == null) return;

        final int[] attrs = new int[] {
                android.R.attr.textSelectHandleLeft,
                android.R.attr.textSelectHandleRight,
                android.R.attr.textSelectHandle
        };
        TypedArray ta = null;
        try {
            ta = ctx.obtainStyledAttributes(attrs);
            Drawable left = ta.getDrawable(0);
            Drawable right = ta.getDrawable(1);
            Drawable middle = ta.getDrawable(2);
            if (left != null) handleLeftDrawable = left.mutate();
            if (right != null) handleRightDrawable = right.mutate();
            if (middle != null) handleMiddleDrawable = middle.mutate();
        } catch (Exception ignored) {
            // Some host themes / test stubs omit the attrs — fall back below.
        } finally {
            if (ta != null) ta.recycle();
        }

        // DeviceDefault / bare themes sometimes leave the attrs null. Load the
        // platform Material assets by name so we still look like EditText.
        if (handleLeftDrawable == null) {
            handleLeftDrawable = loadSystemDrawable(
                    "text_select_handle_left_material",
                    "text_select_handle_left_mtrl_alpha");
        }
        if (handleRightDrawable == null) {
            handleRightDrawable = loadSystemDrawable(
                    "text_select_handle_right_material",
                    "text_select_handle_right_mtrl_alpha");
        }
        if (handleMiddleDrawable == null) {
            handleMiddleDrawable = loadSystemDrawable(
                    "text_select_handle_middle_material",
                    "text_select_handle_middle_mtrl_alpha");
        }

        tintHandleDrawables();
        if (handleLeftDrawable != null) {
            handleIntrinsicW = Math.max(1, handleLeftDrawable.getIntrinsicWidth());
            handleIntrinsicH = Math.max(1, handleLeftDrawable.getIntrinsicHeight());
        } else if (handleRightDrawable != null) {
            handleIntrinsicW = Math.max(1, handleRightDrawable.getIntrinsicWidth());
            handleIntrinsicH = Math.max(1, handleRightDrawable.getIntrinsicHeight());
        }
        if (handleMiddleDrawable != null) {
            handleMiddleW = Math.max(1, handleMiddleDrawable.getIntrinsicWidth());
            handleMiddleH = Math.max(1, handleMiddleDrawable.getIntrinsicHeight());
        } else {
            handleMiddleW = Math.max(1, Math.round(handleIntrinsicW * 0.55f));
            handleMiddleH = Math.max(1, handleIntrinsicH);
        }
        if (handleIntrinsicW > 0) {
            handleRadius = Math.max(handleRadius,
                    Math.max(handleIntrinsicW, handleIntrinsicH) * 0.35f);
        }
    }

    private Drawable loadSystemDrawable(String... names) {
        Context ctx = getContext();
        if (ctx == null) return null;
        Resources res = ctx.getResources();
        for (String name : names) {
            try {
                int id = res.getIdentifier(name, "drawable", "android");
                if (id != 0) {
                    Drawable d = res.getDrawable(id, ctx.getTheme());
                    if (d != null) return d.mutate();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void tintHandleDrawables() {
        int color = scheme.selectionHandle;
        // setTint is the non-deprecated path (API 21+; our minSdk is 24).
        if (handleLeftDrawable != null) handleLeftDrawable.setTint(color);
        if (handleRightDrawable != null) handleRightDrawable.setTint(color);
        if (handleMiddleDrawable != null) handleMiddleDrawable.setTint(color);
    }


    // ------------------------------------------------------------------
    // Selection toolbar (self-drawn Cut / Copy / Paste / Select all)
    // ------------------------------------------------------------------

    private void showSelectionToolbar() {
        if (!isAttachedToWindow() || !isFocused()) {
            hideSelectionToolbar();
            return;
        }
        if (!hasSelection() && !insertionToolbarAllowed) {
            hideSelectionToolbar();
            return;
        }
        if (!layoutSelectionToolbar()) {
            hideSelectionToolbar();
            return;
        }
        selectionToolbarVisible = true;
        scheduleToolbarAutoHide();
        invalidate();
    }

    /**
     * Relayout an already-visible toolbar (e.g. after select-all / copy). Does
     * <em>not</em> re-show a dismissed bar — pan / handle-drag / auto-hide all
     * leave the selection intact and rely on a fresh tap to call
     * {@link #showSelectionToolbar()} again.
     */
    private void invalidateSelectionToolbar() {
        if (!selectionToolbarVisible) return;
        if (!hasSelection() && !insertionToolbarAllowed) {
            hideSelectionToolbar();
            return;
        }
        if (!layoutSelectionToolbar()) {
            hideSelectionToolbar();
            return;
        }
        scheduleToolbarAutoHide();
        invalidate();
    }

    /**
     * Hides the floating toolbar without clearing selection. Used when the user
     * pans, drags a handle, or the idle timer fires — the selection stays so a
     * later tap on it can re-show the bar.
     */
    private void dismissSelectionToolbar() {
        cancelToolbarAutoHide();
        insertionToolbarAllowed = false;
        if (selectionToolbarVisible) {
            selectionToolbarVisible = false;
            toolbarItemCount = 0;
            toolbarRect.setEmpty();
            invalidate();
        } else {
            toolbarItemCount = 0;
            toolbarRect.setEmpty();
        }
    }

    /** Full hide — also used when selection collapses (caret move, cut, edit). */
    private void hideSelectionToolbar() {
        dismissSelectionToolbar();
    }

    private void autoHideSelectionToolbar() {
        if (selectionToolbarVisible) {
            dismissSelectionToolbar();
        }
    }

    private void scheduleToolbarAutoHide() {
        cancelToolbarAutoHide();
        if (selectionToolbarVisible) {
            postDelayed(toolbarAutoHideRunnable, TOOLBAR_AUTO_HIDE_MS);
        }
    }

    private void cancelToolbarAutoHide() {
        removeCallbacks(toolbarAutoHideRunnable);
    }

    /**
     * Rebuilds {@link #toolbarItemIds} / {@link #toolbarRect} in <em>view</em>
     * coordinates, pinned above the on-screen caret (or selection midpoint).
     * View space (not content space) keeps the bar locked to the caret's
     * screen position while the user pans — the document slides under it.
     */
    private boolean layoutSelectionToolbar() {
        if (document == null || lineHeight <= 0f) return false;
        toolbarItemCount = 0;
        boolean hasSel = hasSelection();
        boolean canPaste = clipboardHasText();
        boolean canSelectAll = document.length() > 0
                && !(hasSel && selectionStart() == 0
                && selectionEnd() == document.length());
        if (canSelectAll) toolbarItemIds[toolbarItemCount++] = TB_SELECT_ALL;
        if (hasSel) {
            toolbarItemIds[toolbarItemCount++] = TB_CUT;
            toolbarItemIds[toolbarItemCount++] = TB_COPY;
        }
        if (canPaste) toolbarItemIds[toolbarItemCount++] = TB_PASTE;
        if (toolbarItemCount == 0) return false;

        // Measure widest label so every cell is equal-width (EditText style).
        toolbarTextPaint.setTextSize(14f * density);
        float maxLabel = 0f;
        for (int i = 0; i < toolbarItemCount; i++) {
            maxLabel = Math.max(maxLabel,
                    toolbarTextPaint.measureText(toolbarLabel(toolbarItemIds[i])));
        }
        float hPad = 16f * density;
        toolbarItemWidth = maxLabel + hPad * 2f;
        float width = toolbarItemWidth * toolbarItemCount;
        float height = toolbarHeight;

        // Content → view so the bar stays put relative to the caret on screen
        // even while getScrollX/Y change under the finger.
        int s = hasSel ? selectionStart() : caret;
        int e = hasSel ? selectionEnd() : caret;
        int lineS = document.lineOfOffset(s);
        int lineE = document.lineOfOffset(e);
        float viewX1 = contentXForOffset(s) - getScrollX();
        float viewX2 = contentXForOffset(e) - getScrollX();
        float midX = hasSel ? (viewX1 + viewX2) * 0.5f : viewX1;
        float caretTop = lineS * lineHeight - getScrollY();
        float caretBottom = (lineE + 1) * lineHeight - getScrollY();
        float viewW = getWidth();
        float viewH = getHeight();

        // Caret / selection fully off-screen → skip painting this frame.
        if (caretBottom <= 0f || caretTop >= viewH
                || midX < 0f || midX > viewW) {
            toolbarRect.setEmpty();
            return true;
        }

        float handleH = handleIntrinsicH > 0 ? handleIntrinsicH : handleRadius * 2f;

        // Prefer directly above the caret / selection top.
        float gap = 8f * density;
        float top = caretTop - gap - height - toolbarArrowSize;
        if (top < 4f * density) {
            // Not enough room above: sit just below the line (and handles).
            top = caretBottom + handleH + gap + toolbarArrowSize;
        }

        float left = midX - width * 0.5f;
        float margin = 6f * density;
        if (left < margin) left = margin;
        if (left + width > viewW - margin) {
            left = Math.max(margin, viewW - margin - width);
        }

        toolbarRect.set(left, top, left + width, top + height);
        return true;
    }

    private static String toolbarLabel(int id) {
        switch (id) {
            case TB_SELECT_ALL: return "Select all";
            case TB_CUT:        return "Cut";
            case TB_COPY:       return "Copy";
            case TB_PASTE:      return "Paste";
            default:            return "";
        }
    }

    private void drawSelectionToolbar(Canvas canvas) {
        if (!selectionToolbarVisible) return;
        // Re-layout every frame so the bar re-pins above the caret's current
        // on-screen position while the user is scrolling.
        if (!layoutSelectionToolbar()) {
            // No useful actions left (e.g. empty doc) — drop the bar for good.
            selectionToolbarVisible = false;
            return;
        }
        // Anchor scrolled off-screen: skip painting this frame.
        if (toolbarRect.isEmpty() || toolbarItemCount == 0) return;

        // Handles / text are drawn in content space (View already translated
        // the canvas by -scroll). toolbarRect is view-local, so temporarily
        // undo that scroll before painting the bar — otherwise the bar drifts
        // by -scroll relative to the caret every pan.
        int save = canvas.save();
        canvas.translate(getScrollX(), getScrollY());

        float r = 8f * density;
        toolbarPaint.setColor(scheme.toolbarBackground);
        canvas.drawRoundRect(toolbarRect, r, r, toolbarPaint);

        // Arrow toward the on-screen caret / selection midpoint.
        int s = hasSelection() ? selectionStart() : caret;
        int e = hasSelection() ? selectionEnd() : caret;
        float midX = hasSelection()
                ? ((contentXForOffset(s) - getScrollX())
                + (contentXForOffset(e) - getScrollX())) * 0.5f
                : contentXForOffset(s) - getScrollX();
        midX = Math.max(toolbarRect.left + r,
                Math.min(midX, toolbarRect.right - r));
        float caretTop = document.lineOfOffset(s) * lineHeight - getScrollY();
        boolean above = toolbarRect.bottom <= caretTop + 1f;
        toolbarArrow.reset();
        if (above) {
            float y = toolbarRect.bottom;
            toolbarArrow.moveTo(midX - toolbarArrowSize, y);
            toolbarArrow.lineTo(midX, y + toolbarArrowSize);
            toolbarArrow.lineTo(midX + toolbarArrowSize, y);
        } else {
            float y = toolbarRect.top;
            toolbarArrow.moveTo(midX - toolbarArrowSize, y);
            toolbarArrow.lineTo(midX, y - toolbarArrowSize);
            toolbarArrow.lineTo(midX + toolbarArrowSize, y);
        }
        toolbarArrow.close();
        canvas.drawPath(toolbarArrow, toolbarPaint);

        toolbarTextPaint.setTextSize(14f * density);
        toolbarTextPaint.setColor(scheme.toolbarText);
        Paint.FontMetrics fm = toolbarTextPaint.getFontMetrics();
        float textY = toolbarRect.centerY() - (fm.ascent + fm.descent) * 0.5f;
        for (int i = 0; i < toolbarItemCount; i++) {
            float cellLeft = toolbarRect.left + i * toolbarItemWidth;
            float cx = cellLeft + toolbarItemWidth * 0.5f;
            canvas.drawText(toolbarLabel(toolbarItemIds[i]), cx, textY, toolbarTextPaint);
            if (i > 0) {
                toolbarPaint.setColor(scheme.toolbarDivider);
                float dx = cellLeft;
                canvas.drawRect(dx - density * 0.5f, toolbarRect.top + 8f * density,
                        dx + density * 0.5f, toolbarRect.bottom - 8f * density,
                        toolbarPaint);
                toolbarPaint.setColor(scheme.toolbarBackground);
            }
        }
        canvas.restoreToCount(save);
    }

    /** @return toolbar item id, or 0 if the point is outside (view coords). */
    private int hitTestToolbar(float viewX, float viewY) {
        if (!selectionToolbarVisible || toolbarItemCount == 0
                || toolbarRect.isEmpty()) {
            return 0;
        }
        // toolbarRect is kept in view coordinates.
        if (!toolbarRect.contains(viewX, viewY)) return 0;
        int idx = (int) ((viewX - toolbarRect.left) / toolbarItemWidth);
        if (idx < 0) idx = 0;
        if (idx >= toolbarItemCount) idx = toolbarItemCount - 1;
        return toolbarItemIds[idx];
    }

    private boolean onToolbarItemClicked(int id) {
        // Any interaction resets the idle auto-hide timer.
        scheduleToolbarAutoHide();
        switch (id) {
            case TB_SELECT_ALL:
                selectAll();
                return true;
            case TB_CUT:
                cutSelection();
                return true;
            case TB_COPY:
                copySelection();
                return true;
            case TB_PASTE:
                pasteFromClipboard();
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------
    // Clipboard
    // ------------------------------------------------------------------

    public void selectAll() {
        if (document == null || document.length() == 0) return;
        selectionAnchor = 0;
        caret = document.length();
        composingStart = composingEnd = -1;
        ensureCaretVisible();
        resetCaretBlink();
        notifyImeSelection();
        invalidate();
        showSelectionToolbar();
    }

    public void copySelection() {
        if (!hasSelection() || document == null) return;
        CharSequence text = document.substring(selectionStart(), selectionEnd());
        setClipboardText(text);
        // EditText keeps the selection after Copy.
        invalidateSelectionToolbar();
    }

    public void cutSelection() {
        if (!hasSelection() || document == null) return;
        int s = selectionStart();
        int e = selectionEnd();
        setClipboardText(document.substring(s, e));
        document.delete(s, e);
        caret = s;
        selectionAnchor = -1;
        composingStart = composingEnd = -1;
        afterEdit();
        hideSelectionToolbar();
        dismissCompletions();
    }

    public void pasteFromClipboard() {
        CharSequence clip = getClipboardText();
        if (clip == null) return;
        String text = clip.toString();
        if (text.isEmpty()) return;
        // Reuse the IME commit path so newlines get auto-indent.
        commitTextFromIme(text, 1);
        hideSelectionToolbar();
    }

    private boolean clipboardHasText() {
        try {
            ClipboardManager cm = (ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return false;
            ClipData data = cm.getPrimaryClip();
            return data != null && data.getItemCount() > 0
                    && data.getItemAt(0) != null
                    && data.getItemAt(0).coerceToText(getContext()).length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private CharSequence getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData data = cm.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return null;
            return data.getItemAt(0).coerceToText(getContext());
        } catch (Exception e) {
            return null;
        }
    }

    private void setClipboardText(CharSequence text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("code", text));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Paints one line from its span list. X positions come from measured glyph
     * advances rather than {@code column * charWidth}; after zoom, tabs,
     * CJK/emoji and fallback glyphs otherwise make comment token boundaries
     * drift away from both the text and touch coordinates.
     */
    private void drawSpannedLine(Canvas canvas, String content, int contentLen,
                                 LineSpans spans, float x0, float baseline,
                                 float[] xs) {
        int drawn = 0;
        int n = spans.size();
        for (int i = 0; i < n; i++) {
            int s = spans.start(i);
            int e = spans.end(i);
            if (s < 0) s = 0;
            if (e > contentLen) e = contentLen;
            if (s >= e) continue;
            if (s > contentLen) break;

            if (drawn < s) {
                textPaint.setColor(scheme.foreground);
                drawMeasuredRange(canvas, content, drawn, s,
                        x0 + xs[drawn], baseline, xs);
            }
            TokenType t = spans.type(i);
            if (t == TokenType.WHITESPACE) {
                // Spans intentionally remain cached while async re-lexing to
                // avoid colour flashes. After a delete, however, an old
                // whitespace range may now cover a real character. Never skip
                // that character: draw it in foreground until fresh spans land.
                if (!isCurrentWhitespace(content, s, e)) {
                    textPaint.setColor(scheme.foreground);
                    drawMeasuredRange(canvas, content, s, e,
                            x0 + xs[s], baseline, xs);
                }
            } else {
                textPaint.setColor(scheme.colorOf(t));
                drawMeasuredRange(canvas, content, s, e,
                        x0 + xs[s], baseline, xs);
            }
            drawn = e;
        }
        if (drawn < contentLen) {
            textPaint.setColor(scheme.foreground);
            drawMeasuredRange(canvas, content, drawn, contentLen,
                    x0 + xs[drawn], baseline, xs);
        }
    }

    /** True when the current (possibly edited) text range is still whitespace. */
    private static boolean isCurrentWhitespace(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    /**
     * Draws a measured range, expanding tabs to the same stops used by
     * hit-testing. Non-tab runs use the whole line as shaping context, so
     * syntax-color boundaries cannot change kerning/ligatures or move the
     * visual glyphs away from the measured caret positions.
     */
    private void drawMeasuredRange(Canvas canvas, String text, int start, int end,
                                   float x, float baseline, float[] xs) {
        int runStart = start;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) != '\t') continue;
            if (runStart < i) {
                canvas.drawTextRun(text, runStart, i,
                        0, text.length(),
                        x + xs[runStart] - xs[start], baseline,
                        false, textPaint);
            }
            // Tab is spacing only; next run begins at its measured tab stop.
            runStart = i + 1;
        }
        if (runStart < end) {
            canvas.drawTextRun(text, runStart, end,
                    0, text.length(),
                    x + xs[runStart] - xs[start], baseline,
                    false, textPaint);
        }
    }

    /**
     * Returns cumulative x advances for UTF-16 columns [0..length]. The
     * buffer is reused for every visible line to avoid per-frame garbage.
     */
    private float[] buildColumnXs(String text, int length) {
        int needed = length + 1;
        if (columnXs.length < needed) {
            int cap = Math.max(needed, columnXs.length * 2);
            columnXs = new float[cap];
            glyphWidths = new float[cap];
            textChars = new char[cap];
        }
        columnXs[0] = 0f;
        if (length == 0) return columnXs;

        text.getChars(0, length, textChars, 0);
        textPaint.getTextRunAdvances(textChars, 0, length,
                0, length, false, glyphWidths, 0);
        float x = 0f;
        float tabWidth = Math.max(1f, charWidth * TAB_SIZE);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\t') {
                float stops = (float) Math.floor(x / tabWidth) + 1f;
                x = stops * tabWidth;
            } else {
                float w = glyphWidths[i];
                // Trailing UTF-16 surrogate units can report zero width;
                // preserve the measured cumulative position rather than
                // inventing a cell so token endpoints remain aligned.
                x += Math.max(0f, w);
            }
            columnXs[i + 1] = x;
        }
        return columnXs;
    }

    /** X of one document offset in content coordinates. */
    private float contentXForOffset(int offset) {
        int line = document.lineOfOffset(offset);
        int lineStart = document.lineStart(line);
        String content = document.lineContent(line);
        int column = Math.max(0, Math.min(content.length(), offset - lineStart));
        float[] xs = buildColumnXs(content, content.length());
        observeLineWidth(line, xs[content.length()]);
        return gutterWidth + xs[column];
    }

    private void drawGutter(Canvas canvas, int scrollX, int scrollY, int viewH,
                            int firstLine, int lastLine, int caretLine) {
        fillPaint.setColor(scheme.gutterBackground);
        canvas.drawRect(scrollX, scrollY,
                scrollX + gutterWidth, scrollY + viewH, fillPaint);

        if (firstLine > lastLine || lineHeight <= 0f) return;

        final float rightPad = gutterPad;
        final float tickR = Math.max(2.5f * density, 3f);
        for (int line = firstLine; line <= lastLine; line++) {
            float baseline = line * lineHeight + baselineShift;
            gutterPaint.setColor(line == caretLine
                    ? scheme.gutterCurrentText : scheme.gutterText);
            String num = Integer.toString(line + 1);
            float numWidth = gutterPaint.measureText(num);
            canvas.drawText(num,
                    scrollX + gutterWidth - numWidth - rightPad,
                    baseline, gutterPaint);

            // Diagnostic severity tick on the left edge of the gutter.
            int sev = worstDiagnosticSeverityOnLine(line);
            if (sev > 0) {
                fillPaint.setColor(colorForSeverity(sev));
                float cy = line * lineHeight + lineHeight * 0.5f;
                canvas.drawCircle(scrollX + tickR + density, cy, tickR, fillPaint);
            }
        }
    }

    private void updateGutterWidth() {
        if (document == null || gutterPaint.getTextSize() <= 0f) {
            gutterWidth = 0;
            return;
        }
        int digits = Math.max(2, Integer.toString(document.lineCount()).length());
        // measureText("0") * digits is stable under monospace; cache-friendly.
        gutterWidth = digits * gutterPaint.measureText("0") + charWidth * 1.5f;
    }

    // ------------------------------------------------------------------
    // Touch & scrolling
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final int pointerCount = event.getPointerCount();

        // The second pointer atomically ends every single-finger mode before
        // ScaleGestureDetector sees the event. The final remaining-finger UP
        // stays suppressed until the complete multi-touch sequence is over.
        if (action == MotionEvent.ACTION_POINTER_DOWN && pointerCount >= 2) {
            // The first finger may have been tentatively recognised as the
            // second tap on DOWN. A pinch is viewport-only, so roll back any
            // state mutation from that still-active single-pointer sequence.
            if (touchMode == TouchMode.DOUBLE_TAP_LOCKED
                    || touchMode == TouchMode.DOUBLE_TAP_EXTENDING) {
                caret = Math.max(0, Math.min(touchStartCaret, document.length()));
                selectionAnchor = touchStartSelectionAnchor < 0
                        ? -1
                        : Math.max(0, Math.min(
                                touchStartSelectionAnchor, document.length()));
                invalidate();
            }
            cancelManualTouch(false);
            beginMultiTouchSession();
            scaleDetector.onTouchEvent(event);
            if (getParent() != null) {
                // Keep the complete pinch stream in the editor. Releasing this
                // here lets a ScrollView steal the next MOVE and abort zoom.
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }

        scaleDetector.onTouchEvent(event);
        if (suppressSingleFingerGestures || scaling
                || pointerCount > 1 || scaleDetector.isInProgress()) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                scaling = false;
                suppressSingleFingerGestures = false;
                cancelManualTouch(false);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                resetCaretBlink();
            }
            return true;
        }

        final float x = event.getX();
        final float y = event.getY();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                beginSingleTouch(event, x, y);
                return true;

            case MotionEvent.ACTION_MOVE:
                addMovement(event);
                updateSingleTouch(x, y);
                lastTouchX = x;
                lastTouchY = y;
                return true;

            case MotionEvent.ACTION_UP:
                addMovement(event);
                finishSingleTouch(event, x, y, false);
                return true;

            case MotionEvent.ACTION_CANCEL:
                finishSingleTouch(event, x, y, true);
                return true;

            default:
                return true;
        }
    }

    private void onManualLongPressTimeout() {
        if (touchMode != TouchMode.TAP_PENDING || suppressSingleFingerGestures
                || scaling || document == null) {
            return;
        }
        activateWordSelectionAt(downX, downY, false);
        touchMode = TouchMode.LONG_PRESS_LOCKED;
    }

    private void beginSingleTouch(MotionEvent event, float x, float y) {
        removeCallbacks(longPressRunnable);
        recycleVelocityTracker();
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);

        downX = lastTouchX = x;
        downY = lastTouchY = y;
        touchStartCaret = caret;
        touchStartSelectionAnchor = selectionAnchor;
        scroller.forceFinished(true);

        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        // Toolbar is drawn above the text; consume taps on it first so they
        // never become caret moves or handle grabs.
        int toolbarHit = hitTestToolbar(x, y);
        if (toolbarHit != 0) {
            onToolbarItemClicked(toolbarHit);
            touchMode = TouchMode.IDLE;
            lastTapUpTime = 0L;
            return;
        }

        Handle hit = hitTestHandle(x, y);
        if (hit != Handle.NONE) {
            activeHandle = hit;
            touchMode = TouchMode.HANDLE_DRAG;
            // Moving a handle dismisses the bar immediately; a later tap on
            // the (still-selected) range re-shows it.
            dismissSelectionToolbar();
            // Freeze the opposite edge so crossing cannot walk it.
            if (hit == Handle.START) {
                handleDragFixedOffset = selectionEnd();
            } else if (hit == Handle.END) {
                handleDragFixedOffset = selectionStart();
            } else {
                handleDragFixedOffset = -1;
            }
            // Map finger-on-body (below the line) back into the text line.
            // AOSP uses mTouchOffsetY ≈ -0.3 * handleHeight so the first sample
            // lands slightly above the line bottom, not on the next line.
            int tipOffset = hit == Handle.START ? selectionStart()
                    : hit == Handle.END ? selectionEnd() : caret;
            int tipLine = document.lineOfOffset(tipOffset);
            float tipYView = (tipLine + 1) * lineHeight - getScrollY();
            float handleH = hit == Handle.INSERT
                    ? (handleMiddleH > 0 ? handleMiddleH : handleRadius * 2f)
                    : (handleIntrinsicH > 0 ? handleIntrinsicH : handleRadius * 2f);
            // Target mapping Y: a bit above the line bottom (into current line).
            float mapTargetY = tipYView - Math.max(lineHeight * 0.35f, handleH * 0.3f);
            handleDragMapYAdjust = mapTargetY - y;
            dismissCompletions();
            lastTapUpTime = 0L;
            return;
        }
        activeHandle = Handle.NONE;
        handleDragFixedOffset = -1;
        handleDragMapYAdjust = 0f;

        if (isSecondTap(event, x, y)) {
            // Recognise on the second DOWN. The first tap already moved the
            // caret immediately; no delayed onSingleTapConfirmed is needed.
            lastTapUpTime = 0L;
            activateWordSelectionAt(x, y, true);
            return;
        }

        touchMode = TouchMode.TAP_PENDING;
        postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private boolean isSecondTap(MotionEvent event, float x, float y) {
        if (lastTapUpTime == 0L) return false;
        long elapsed = event.getEventTime() - lastTapUpTime;
        if (elapsed < DOUBLE_TAP_MIN_TIME_MS
                || elapsed > ViewConfiguration.getDoubleTapTimeout()) {
            lastTapUpTime = 0L;
            return false;
        }
        float dx = x - lastTapX;
        float dy = y - lastTapY;
        return dx * dx + dy * dy <= doubleTapSlop * doubleTapSlop;
    }

    private void updateSingleTouch(float x, float y) {
        switch (touchMode) {
            case HANDLE_DRAG:
                dragSelectionTo(x, y);
                return;

            case LONG_PRESS_LOCKED:
            case DOUBLE_TAP_LOCKED:
                if (movedPastSlop(x, y)) {
                    touchMode = touchMode == TouchMode.LONG_PRESS_LOCKED
                            ? TouchMode.LONG_PRESS_EXTENDING
                            : TouchMode.DOUBLE_TAP_EXTENDING;
                    extendInitialWordSelection(x, y);
                }
                return;

            case LONG_PRESS_EXTENDING:
            case DOUBLE_TAP_EXTENDING:
                extendInitialWordSelection(x, y);
                return;

            case TAP_PENDING:
                if (!movedPastSlop(x, y)) return;
                removeCallbacks(longPressRunnable);
                touchMode = TouchMode.PANNING;
                lastTapUpTime = 0L;
                // Pan starts → hide the floating toolbar (selection kept).
                dismissSelectionToolbar();
                panTo(x, y);
                return;

            case PANNING:
                panTo(x, y);
                return;

            case IDLE:
            default:
                return;
        }
    }

    private void panTo(float x, float y) {
        int nx = Math.max(0, Math.min(
                getScrollX() + Math.round(lastTouchX - x), maxScrollX()));
        int ny = Math.max(0, Math.min(
                getScrollY() + Math.round(lastTouchY - y), maxScrollY()));
        scrollTo(nx, ny);
        if (completionPopup != null && completionPopup.isShowing()) {
            dismissCompletions();
        }
    }

    private boolean movedPastSlop(float x, float y) {
        float dx = x - downX;
        float dy = y - downY;
        return dx * dx + dy * dy > touchSlop * touchSlop;
    }

    private void finishSingleTouch(MotionEvent event, float x, float y,
                                   boolean cancelled) {
        removeCallbacks(longPressRunnable);
        TouchMode finishedMode = touchMode;

        if (!cancelled) {
            if (finishedMode == TouchMode.TAP_PENDING) {
                requestFocus();
                int offset = offsetForPoint(x, y);
                // Tap inside an existing selection re-shows the toolbar
                // without collapsing the range (EditText floating-toolbar UX).
                if (hasSelection()
                        && offset >= selectionStart()
                        && offset < selectionEnd()) {
                    insertionToolbarAllowed = false;
                    showSelectionToolbar();
                    dismissCompletions();
                    showSoftKeyboard();
                    performClick();
                    lastTapUpTime = 0L;
                } else {
                    // Immediate caret placement: no double-tap timeout delay.
                    moveCaretTo(offset, false);
                    dismissCompletions();
                    showSoftKeyboard();
                    // EditText reveals Paste/Select-all on a focused caret tap.
                    insertionToolbarAllowed = true;
                    showSelectionToolbar();
                    performClick();
                    lastTapUpTime = event.getEventTime();
                    lastTapX = x;
                    lastTapY = y;
                }
            } else if (finishedMode == TouchMode.PANNING) {
                // Pan already dismissed the bar; do not re-show.
                flingFromVelocityTracker();
                lastTapUpTime = 0L;
            } else if (finishedMode == TouchMode.HANDLE_DRAG) {
                // Handle drag dismissed the bar; leave it hidden until the
                // user taps the selection again.
                lastTapUpTime = 0L;
            } else if (finishedMode == TouchMode.LONG_PRESS_LOCKED
                    || finishedMode == TouchMode.LONG_PRESS_EXTENDING
                    || finishedMode == TouchMode.DOUBLE_TAP_LOCKED
                    || finishedMode == TouchMode.DOUBLE_TAP_EXTENDING) {
                // Fresh selection from long-press / double-tap: show toolbar.
                // (selectWordAt already showed it; extending dismissed it —
                // re-show on finger-up so the final range gets a bar.)
                if (hasSelection()) showSelectionToolbar();
                lastTapUpTime = 0L;
            } else {
                lastTapUpTime = 0L;
            }
        } else {
            lastTapUpTime = 0L;
        }

        activeHandle = Handle.NONE;
        handleDragFixedOffset = -1;
        handleDragMapYAdjust = 0f;
        touchMode = TouchMode.IDLE;
        recycleVelocityTracker();
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        invalidate();
    }

    private void flingFromVelocityTracker() {
        if (velocityTracker == null) return;
        velocityTracker.computeCurrentVelocity(1000, maximumFlingVelocity);
        float vx = velocityTracker.getXVelocity();
        float vy = velocityTracker.getYVelocity();
        if (Math.abs(vx) < minimumFlingVelocity) vx = 0f;
        if (Math.abs(vy) < minimumFlingVelocity) vy = 0f;
        if (vx == 0f && vy == 0f) return;
        scroller.fling(getScrollX(), getScrollY(),
                Math.round(-vx), Math.round(-vy),
                0, maxScrollX(), 0, maxScrollY());
        postInvalidateOnAnimation();
    }

    private void showSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, 0);
        }
    }

    private void addMovement(MotionEvent event) {
        if (velocityTracker != null) velocityTracker.addMovement(event);
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void cancelManualTouch(boolean clearTapHistory) {
        removeCallbacks(longPressRunnable);
        activeHandle = Handle.NONE;
        handleDragFixedOffset = -1;
        handleDragMapYAdjust = 0f;
        touchMode = TouchMode.IDLE;
        recycleVelocityTracker();
        if (clearTapHistory) lastTapUpTime = 0L;
    }

    /** Marks the current touch sequence as multi-touch. */
    private void beginMultiTouchSession() {
        suppressSingleFingerGestures = true;
        scaling = true;
        scroller.forceFinished(true);
        dismissCompletions();
        dismissSelectionToolbar();
        lastTapUpTime = 0L;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    /**
     * Returns the document offset of the glyph box under a touch. Unlike
     * {@link #offsetForPoint(float, float)}, this never snaps the right half
     * of the last word character to the following separator — essential for
     * long-press/double-tap whole-word selection. Always returns a cluster
     * start so a long-press on an emoji never selects only its high surrogate.
     */
    private int characterOffsetForPoint(float viewX, float viewY) {
        float docX = viewX <= gutterWidth
                ? 0f
                : viewX + getScrollX() - gutterWidth;
        float docY = viewY + getScrollY();
        int line = Math.max(0, Math.min((int) (docY / lineHeight),
                document.lineCount() - 1));
        int lineStart = document.lineStart(line);
        String content = document.lineContent(line);
        if (content.isEmpty()) return lineStart;

        float[] xs = buildColumnXs(content, content.length());
        float targetX = Math.max(0f, docX);
        int lo = 0;
        int hi = content.length();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (xs[mid + 1] <= targetX) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int column = Math.min(lo, content.length() - 1);
        // Prefer the cluster start so word selection covers the full glyph
        // (surrogate pairs, combining marks, shaped ligatures).
        column = snapColumnToClusterStart(content, column);
        observeLineWidth(line, xs[content.length()]);
        return lineStart + column;
    }

    private int offsetForPoint(float viewX, float viewY) {
        // The gutter is pinned in view space. A touch inside it always maps to
        // column zero, regardless of horizontal scroll underneath.
        float docX = viewX <= gutterWidth
                ? 0f
                : viewX + getScrollX() - gutterWidth;
        float docY = viewY + getScrollY();
        int line = Math.max(0, Math.min((int) (docY / lineHeight),
                document.lineCount() - 1));
        String content = document.lineContent(line);
        float[] xs = buildColumnXs(content, content.length());
        float targetX = Math.max(0f, docX);

        // Lower-bound search in measured glyph advances, then snap to the
        // nearer caret edge. This uses exactly the same coordinates as token
        // drawing, so zoomed comments/tabs/wide glyphs remain tappable.
        int lo = 0;
        int hi = content.length();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (xs[mid] < targetX) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int column = lo;
        if (column > 0 && column <= content.length()) {
            float leftDistance = targetX - xs[column - 1];
            float rightDistance = xs[column] - targetX;
            if (leftDistance <= rightDistance) column--;
        }
        // Collapse duplicate zero-width interior positions onto a real caret
        // edge (after the cluster for a right-half tap, before for left-half).
        column = snapColumnToClusterBoundary(content, column, xs, targetX);
        observeLineWidth(line, xs[content.length()]);
        return document.offsetAt(line, column);
    }

    /**
     * Snap a column onto a valid insertion-point boundary for caret placement.
     * Interior offsets of a shaped cluster share the cluster's trailing x and
     * must never become caret positions.
     */
    private int snapColumnToClusterBoundary(String text, int column,
                                            float[] xs, float targetX) {
        if (text.isEmpty()) return 0;
        column = Math.max(0, Math.min(column, text.length()));
        if (column == 0 || column == text.length()) return column;

        int start = clusterStartAtOrBefore(text, column);
        int end = expandColumnToClusterEnd(text, start);
        if (column > start && column < end) {
            // Pick the nearer outer edge using measured advances when the
            // cluster has non-zero width; fall back to mid-index otherwise.
            float leftX = xs[start];
            float rightX = xs[end];
            if (rightX > leftX) {
                return (targetX - leftX) <= (rightX - targetX) ? start : end;
            }
            return (column - start) <= (end - column) ? start : end;
        }
        return column;
    }

    /** Snap to the start of the cluster that owns {@code column}. */
    private int snapColumnToClusterStart(String text, int column) {
        if (text.isEmpty()) return 0;
        column = Math.max(0, Math.min(column, text.length() - 1));
        return clusterStartAtOrBefore(text, column);
    }

    // ------------------------------------------------------------------
    // Shaped-cluster (grapheme) caret helpers
    // ------------------------------------------------------------------

    /**
     * Clamp any document offset onto a valid caret boundary. Uses the line's
     * shaped cursor positions so surrogate pairs, combining sequences and
     * ligatures cannot host an interior caret.
     */
    private int normalizeCaretOffset(int offset) {
        if (document == null) return 0;
        offset = Math.max(0, Math.min(offset, document.length()));
        if (offset == 0 || offset == document.length()) return offset;
        int line = document.lineOfOffset(offset);
        int lineStart = document.lineStart(line);
        int lineEnd = document.lineEnd(line);
        if (offset <= lineStart || offset >= lineEnd) return offset;
        String content = document.lineContent(line);
        int column = offset - lineStart;
        int start = clusterStartAtOrBefore(content, column);
        int end = expandColumnToClusterEnd(content, start);
        if (column > start && column < end) {
            // Prefer the end (after the cluster) so a mid-tap lands after the
            // glyph the user was aiming past — matches typical editor feel.
            return lineStart + end;
        }
        return offset;
    }

    /** Document offset of the previous valid caret position before {@code offset}. */
    private int prevClusterOffset(int offset) {
        if (document == null || offset <= 0) return 0;
        offset = Math.min(offset, document.length());
        int line = document.lineOfOffset(Math.max(0, offset - 1));
        int lineStart = document.lineStart(line);
        // Crossing a line boundary: land just before the '\n' of the previous
        // line (i.e. at the end of its content, which is offset - 1 when offset
        // is a lineStart).
        if (offset == lineStart) {
            return offset - 1;
        }
        String content = document.lineContent(line);
        int column = offset - lineStart;
        int prev = textRunCursor(content, column, Paint.CURSOR_BEFORE);
        if (prev < 0 || prev >= column) {
            // Fallback: one code point back.
            prev = column - 1;
            if (prev > 0 && Character.isLowSurrogate(content.charAt(prev))
                    && Character.isHighSurrogate(content.charAt(prev - 1))) {
                prev--;
            }
        }
        return lineStart + Math.max(0, prev);
    }

    /** Document offset of the next valid caret position after {@code offset}. */
    private int nextClusterOffset(int offset) {
        if (document == null) return 0;
        offset = Math.max(0, Math.min(offset, document.length()));
        if (offset >= document.length()) return document.length();
        int line = document.lineOfOffset(offset);
        int lineStart = document.lineStart(line);
        int lineEnd = document.lineEnd(line);
        // At end of line content, step over the trailing '\n' if any.
        if (offset >= lineEnd) {
            return Math.min(document.length(), offset + 1);
        }
        String content = document.lineContent(line);
        int column = offset - lineStart;
        int next = textRunCursor(content, column, Paint.CURSOR_AFTER);
        if (next <= column || next > content.length()) {
            // Fallback: one code point forward.
            next = column + Character.charCount(content.codePointAt(column));
        }
        return lineStart + Math.min(content.length(), next);
    }

    /**
     * Expand a document offset forward to the end of the cluster it sits in
     * (or leave it alone if already a boundary). Used when a range end must
     * cover whole clusters.
     */
    private int expandOffsetToClusterEnd(int offset) {
        if (document == null) return 0;
        offset = Math.max(0, Math.min(offset, document.length()));
        if (offset == 0 || offset >= document.length()) return offset;
        int line = document.lineOfOffset(offset);
        int lineStart = document.lineStart(line);
        int lineEnd = document.lineEnd(line);
        // offset may be the exclusive end of a line (at '\n'); leave it.
        if (offset >= lineEnd) return offset;
        // If offset is mid-cluster, push to the cluster end; if it already is a
        // boundary, leave it. Detect interior by checking whether offset-1
        // shares this cluster.
        String content = document.lineContent(line);
        int column = offset - lineStart;
        if (column <= 0) return offset;
        int startOfPrev = clusterStartAtOrBefore(content, column - 1);
        int endOfPrev = expandColumnToClusterEnd(content, startOfPrev);
        if (column < endOfPrev) {
            return lineStart + endOfPrev;
        }
        return offset;
    }

    /**
     * For a deletion-range start: if {@code offset} falls inside a cluster,
     * pull it back to that cluster's start so the whole glyph is included.
     * Valid caret boundaries are left unchanged.
     */
    private int snapRangeStartToCluster(int offset) {
        if (document == null) return 0;
        offset = Math.max(0, Math.min(offset, document.length()));
        if (offset == 0 || offset >= document.length()) return offset;
        int line = document.lineOfOffset(offset);
        int lineStart = document.lineStart(line);
        int lineEnd = document.lineEnd(line);
        if (offset >= lineEnd) return offset;
        String content = document.lineContent(line);
        int column = offset - lineStart;
        if (column <= 0) return offset;
        int startOfPrev = clusterStartAtOrBefore(content, column - 1);
        int endOfPrev = expandColumnToClusterEnd(content, startOfPrev);
        if (column < endOfPrev) {
            return lineStart + startOfPrev;
        }
        return offset;
    }

    /** Cluster start at or before a document offset that is known in-range. */
    private int clusterStartAtOrBefore(int offset) {
        if (document == null || offset <= 0) return 0;
        offset = Math.min(offset, document.length());
        if (offset >= document.length()) {
            // End-of-document: cluster of the last character.
            offset = document.length() - 1;
        }
        int line = document.lineOfOffset(offset);
        int lineStart = document.lineStart(line);
        int lineEnd = document.lineEnd(line);
        if (offset >= lineEnd) {
            // Offset on the '\n' — treat as end-of-line content boundary.
            return lineEnd;
        }
        String content = document.lineContent(line);
        int column = offset - lineStart;
        return lineStart + clusterStartAtOrBefore(content, column);
    }

    private int clusterStartAtOrBefore(String text, int column) {
        if (text.isEmpty()) return 0;
        column = Math.max(0, Math.min(column, text.length() - 1));
        // CURSOR_AT_OR_BEFORE: if column is a valid caret pos return it,
        // otherwise the previous valid one — i.e. the cluster start for an
        // interior offset, or column itself at a boundary interior to the run.
        // For a column that IS a cluster start, AT_OR_BEFORE returns it.
        // For an interior unit, it returns the start.
        int start = textRunCursor(text, column, Paint.CURSOR_AT_OR_BEFORE);
        if (start < 0 || start > column) {
            // Fallback: walk back over low surrogates / combining-ish marks.
            start = column;
            if (start > 0 && Character.isLowSurrogate(text.charAt(start))
                    && Character.isHighSurrogate(text.charAt(start - 1))) {
                start--;
            }
        }
        return Math.max(0, start);
    }

    private int expandColumnToClusterEnd(String text, int clusterStart) {
        if (text.isEmpty()) return 0;
        clusterStart = Math.max(0, Math.min(clusterStart, text.length()));
        if (clusterStart >= text.length()) return text.length();
        int end = textRunCursor(text, clusterStart, Paint.CURSOR_AFTER);
        if (end <= clusterStart || end > text.length()) {
            end = clusterStart
                    + Character.charCount(text.codePointAt(clusterStart));
        }
        return Math.min(text.length(), end);
    }

    /**
     * Thin wrapper over {@link Paint#getTextRunCursor(CharSequence, int, int,
     * boolean, int, int)}. Context is always the full line, LTR.
     */
    private int textRunCursor(String text, int offset, int cursorOpt) {
        if (text == null || text.isEmpty()) return 0;
        offset = Math.max(0, Math.min(offset, text.length()));
        try {
            return textPaint.getTextRunCursor(text, 0, text.length(),
                    false /* LTR */, offset, cursorOpt);
        } catch (RuntimeException ex) {
            // Some stub / host environments may not implement the native call.
            return -1;
        }
    }

    /** Resets the line-width cache; unknown entries are measured lazily. */
    private void resetLineWidthCache(int lineCount) {
        lineWidthEms = new float[Math.max(1, lineCount)];
        Arrays.fill(lineWidthEms, Float.NaN);
        maxObservedLineWidthEm = 0f;
        widestObservedLine = -1;
    }

    private void updateWidthsAfterInsert(Document doc, int offset, String text) {
        int line = doc.lineOfOffset(offset);
        int added = countNewlines(text);
        resizeWidthCacheForInsert(line, added, doc.lineCount());
        measureLineWidth(line);
        for (int i = 1; i <= added && line + i < doc.lineCount(); i++) {
            measureLineWidth(line + i);
        }
        recomputeMaxObservedWidth();
        clampHorizontalScrollToContent();
    }

    private void updateWidthsAfterDelete(Document doc, int offset, String text) {
        int line = doc.lineOfOffset(offset);
        int removed = countNewlines(text);
        resizeWidthCacheForDelete(line, removed, doc.lineCount());
        measureLineWidth(line);
        recomputeMaxObservedWidth();
        clampHorizontalScrollToContent();
    }

    private static int countNewlines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    private void resizeWidthCacheForInsert(int line, int added, int lineCount) {
        int target = Math.max(1, lineCount);
        float[] next = new float[target];
        Arrays.fill(next, Float.NaN);
        int prefix = Math.min(line, Math.min(lineWidthEms.length, next.length));
        if (prefix > 0) {
            System.arraycopy(lineWidthEms, 0, next, 0, prefix);
        }
        int oldTailStart = Math.min(line + 1, lineWidthEms.length);
        int newTailStart = Math.min(line + added + 1, next.length);
        int tail = Math.min(lineWidthEms.length - oldTailStart,
                next.length - newTailStart);
        if (tail > 0) {
            System.arraycopy(lineWidthEms, oldTailStart, next, newTailStart, tail);
        }
        lineWidthEms = next;
    }

    private void resizeWidthCacheForDelete(int line, int removed, int lineCount) {
        int target = Math.max(1, lineCount);
        float[] next = new float[target];
        Arrays.fill(next, Float.NaN);
        int prefix = Math.min(line, Math.min(lineWidthEms.length, next.length));
        if (prefix > 0) {
            System.arraycopy(lineWidthEms, 0, next, 0, prefix);
        }
        int oldTailStart = Math.min(line + removed + 1, lineWidthEms.length);
        int newTailStart = Math.min(line + 1, next.length);
        int tail = Math.min(lineWidthEms.length - oldTailStart,
                next.length - newTailStart);
        if (tail > 0) {
            System.arraycopy(lineWidthEms, oldTailStart, next, newTailStart, tail);
        }
        lineWidthEms = next;
    }

    private void measureLineWidth(int line) {
        if (document == null || charWidth <= 0f
                || line < 0 || line >= document.lineCount()) {
            return;
        }
        String content = document.lineContent(line);
        float[] xs = buildColumnXs(content, content.length());
        if (line >= lineWidthEms.length) {
            lineWidthEms = Arrays.copyOf(lineWidthEms, line + 1);
        }
        lineWidthEms[line] = xs[content.length()] / charWidth;
    }

    /** Records one measured line and maintains the maximum. */
    private void observeLineWidth(int line, float widthPx) {
        if (charWidth <= 0f || line < 0) return;
        if (line >= lineWidthEms.length) {
            int oldLength = lineWidthEms.length;
            lineWidthEms = Arrays.copyOf(lineWidthEms,
                    Math.max(line + 1, Math.max(1, oldLength * 2)));
            Arrays.fill(lineWidthEms, oldLength, lineWidthEms.length, Float.NaN);
        }
        float em = widthPx / charWidth;
        float old = lineWidthEms[line];
        lineWidthEms[line] = em;
        if (em >= maxObservedLineWidthEm) {
            maxObservedLineWidthEm = em;
            widestObservedLine = line;
        } else if (line == widestObservedLine && !Float.isNaN(old) && em < old) {
            recomputeMaxObservedWidth();
            clampHorizontalScrollToContent();
        }
    }

    private void recomputeMaxObservedWidth() {
        maxObservedLineWidthEm = 0f;
        widestObservedLine = -1;
        int count = document == null ? 0 : document.lineCount();
        for (int line = 0; line < count && line < lineWidthEms.length; line++) {
            float em = lineWidthEms[line];
            if (!Float.isNaN(em) && em > maxObservedLineWidthEm) {
                maxObservedLineWidthEm = em;
                widestObservedLine = line;
            }
        }
    }

    private void clampHorizontalScrollToContent() {
        int max = maxScrollX();
        if (getScrollX() > max) {
            scroller.forceFinished(true);
            scrollTo(max, getScrollY());
        }
    }

    private int maxScrollY() {
        if (lineHeight <= 0f || document == null) return 0;
        // Allow scrolling far enough that the last line can sit above the IME,
        // not merely within the full (possibly keyboard-covered) view height.
        int band = imeVisibleBandHeight();
        return (int) Math.max(0, document.lineCount() * lineHeight - band / 2f);
    }

    private int maxScrollX() {
        // Use the widest line observed by drawing/hit-testing. Add a small
        // right margin so the final glyph and caret are not flush to the edge.
        float contentWidth = maxObservedLineWidthEm * charWidth;
        float rightPadding = Math.max(charWidth * 4f, 16f * density);
        return (int) Math.max(0f,
                gutterWidth + contentWidth + rightPadding - getWidth());
    }

    /**
     * Height of the portion of this view currently free of the soft keyboard.
     * Falls back to {@link #getHeight()} before the first layout pass.
     */
    private int imeVisibleBandHeight() {
        int h = getHeight();
        if (h <= 0) return 0;
        if (imeVisibleBottom > imeVisibleTop) {
            return Math.max(1, Math.min(h, imeVisibleBottom - imeVisibleTop));
        }
        return h;
    }

    /**
     * Recomputes {@link #imeVisibleTop}/{@link #imeVisibleBottom} from the
     * window's visible display frame (the rectangle above the IME).
     *
     * <p>Works for both host modes:
     * <ul>
     *   <li>{@code adjustResize} — our height already shrank; band ≈ full height</li>
     *   <li>{@code adjustPan} / overlay — height unchanged; band is clipped
     *       to {@code visibleFrame.bottom} so caret scroll accounts for the IME</li>
     * </ul>
     *
     * @return true if the band changed
     */
    private boolean refreshImeVisibleBand() {
        int h = getHeight();
        int w = getWidth();
        if (h <= 0 || w <= 0 || !isAttachedToWindow()) {
            int oldBottom = imeVisibleBottom;
            imeVisibleTop = 0;
            imeVisibleBottom = Math.max(0, h);
            return oldBottom != imeVisibleBottom;
        }

        getWindowVisibleDisplayFrame(visibleFrame);
        getLocationInWindow(locationInWindow);
        int viewTop = locationInWindow[1];
        int viewBottom = viewTop + h;

        // Intersect view bounds with the window region not covered by the IME.
        int uncoveredTop = Math.max(viewTop, visibleFrame.top);
        int uncoveredBottom = Math.min(viewBottom, visibleFrame.bottom);

        int top = Math.max(0, uncoveredTop - viewTop);
        int bottom = Math.max(top, Math.min(h, uncoveredBottom - viewTop));

        // If the IME reports a degenerate frame (some OEMs while animating),
        // keep the previous band rather than collapsing to zero.
        if (bottom - top < lineHeight && h > lineHeight) {
            // Visible frame might still be full-screen during IME animation;
            // only trust a shrink when it's clearly smaller than the view.
            if (visibleFrame.height() >= h - 1) {
                top = 0;
                bottom = h;
            }
        }

        if (top == imeVisibleTop && bottom == imeVisibleBottom) {
            return false;
        }
        imeVisibleTop = top;
        imeVisibleBottom = bottom;
        return true;
    }

    /**
     * Scrolls so the caret lies inside the IME-uncovered band of this view,
     * with a small margin so it is not flush against the keyboard edge.
     */
    private void ensureCaretVisible() {
        if (lineHeight <= 0f || charWidth <= 0f || getWidth() == 0 || document == null) {
            return;
        }
        refreshImeVisibleBand();

        int line = document.lineOfOffset(caret);
        float caretDocTop = line * lineHeight;
        float caretDocBottom = caretDocTop + lineHeight;
        float x = contentXForOffset(caret);

        int sx = getScrollX();
        int sy = getScrollY();

        // Margin inside the visible band (≈ half a line, at least 4dp) so the
        // caret does not sit directly on the IME boundary.
        float margin = Math.max(4f * density, lineHeight * 0.5f);
        float bandTop = imeVisibleTop + margin;
        float bandBottom = imeVisibleBottom - margin;
        if (bandBottom <= bandTop) {
            // Tiny visible strip — just centre the caret in whatever remains.
            bandTop = imeVisibleTop;
            bandBottom = Math.max(imeVisibleTop + 1, imeVisibleBottom);
        }

        // Caret edges in view-local coordinates.
        float caretViewTop = caretDocTop - sy;
        float caretViewBottom = caretDocBottom - sy;

        if (caretViewTop < bandTop) {
            sy = Math.round(caretDocTop - bandTop);
        } else if (caretViewBottom > bandBottom) {
            sy = Math.round(caretDocBottom - bandBottom);
        }

        if (x < sx + gutterWidth) {
            sx = (int) Math.max(0, x - gutterWidth - charWidth * 4);
        } else if (x > sx + getWidth() - charWidth * 2) {
            sx = (int) (x - getWidth() + charWidth * 6);
        }
        sx = Math.max(0, Math.min(sx, maxScrollX()));
        sy = Math.max(0, Math.min(sy, maxScrollY()));
        if (sx != getScrollX() || sy != getScrollY()) {
            scroller.forceFinished(true);
            scrollTo(sx, sy);
        }
    }

    private void resetCaretBlink() {
        removeCallbacks(caretBlink);
        caretVisible = true;
        postDelayed(caretBlink, 500);
    }

    /**
     * Called from the global-layout listener when the window's visible frame
     * may have changed (IME show / hide / resize). Scrolls the caret out from
     * under a newly shown keyboard even if our own height did not change
     * (adjustPan / edge-to-edge hosts).
     */
    private void onPossibleImeLayoutChange() {
        if (!isFocused() || document == null) {
            refreshImeVisibleBand();
            return;
        }
        boolean bandChanged = refreshImeVisibleBand();
        if (bandChanged) {
            ensureCaretVisible();
            if (completionPopup != null && completionPopup.isShowing()) {
                requestCompletionsAtCaret();
            }
        }
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction,
                                  android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        removeCallbacks(caretBlink);
        if (gained) {
            resetCaretBlink();
            // Keyboard often appears right after focus — defer one frame so the
            // visible display frame has settled, then reveal the caret.
            post(this::ensureCaretVisible);
        } else {
            cancelManualTouch(true);
            dismissCompletions();
            hideSelectionToolbar();
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // adjustResize: our height shrinks when the IME opens. Recompute the
        // visible band and bring the caret back above the keyboard.
        refreshImeVisibleBand();
        int sx = Math.min(getScrollX(), maxScrollX());
        int sy = Math.min(getScrollY(), maxScrollY());
        if (sx != getScrollX() || sy != getScrollY()) {
            scrollTo(sx, sy);
        }
        if (isFocused()) {
            ensureCaretVisible();
        }
        if (completionPopup != null && completionPopup.isShowing()) {
            // Re-query with the new visible frame so the popup climbs above
            // a newly shown keyboard instead of sitting on top of it.
            requestCompletionsAtCaret();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (document != null) {
            document.removeContentListener(widthCacheListener);
            document.addContentListener(widthCacheListener);
            document.removeContentListener(lspSyncListener);
            document.addContentListener(lspSyncListener);
        }
        // Theme may not have been ready in the constructor (e.g. inflation).
        if (handleLeftDrawable == null && handleRightDrawable == null) {
            loadHandleDrawables();
        }
        getViewTreeObserver().addOnGlobalLayoutListener(imeLayoutListener);
        refreshImeVisibleBand();
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(imeLayoutListener);
        mainHandler.removeCallbacks(lspChangeDebounce);
        cancelToolbarAutoHide();
        if (document != null) {
            document.removeContentListener(widthCacheListener);
            document.removeContentListener(lspSyncListener);
        }
        cancelManualTouch(true);
        hideSelectionToolbar();
        super.onDetachedFromWindow();
        removeCallbacks(caretBlink);
        if (completionPopup != null) completionPopup.dismiss();
        if (highlighter != null) highlighter.shutdown();
        if (completionEngine != null) completionEngine.shutdown();
        // Editor-owned clients (started from grammar/plugin config) die with the
        // view; externally attached clients are only detached.
        if (lspOwnedByEditor) {
            stopLsp();
        } else if (lspClient != null) {
            lspClient.removeListener(this);
        }
    }

    // ------------------------------------------------------------------
    // Gesture listeners
    // ------------------------------------------------------------------

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            // May fire slightly after POINTER_DOWN; make sure the session flag
            // is set even if the platform coalesced the pointer event.
            suppressSingleFingerGestures = true;
            scaling = true;
            scroller.forceFinished(true);
            dismissCompletions();

            // The gutter is pinned in view space, while text scrolls beneath
            // it. Therefore gutter detection must use the view-local focus X.
            float focusViewX = detector.getFocusX();
            float focusDocX = getScrollX() + focusViewX;
            float focusDocY = getScrollY() + detector.getFocusY();
            zoomStartScrollX = getScrollX();
            zoomAnchorInGutter = focusViewX < gutterWidth;
            zoomAnchorXEm = zoomAnchorInGutter
                    ? 0f
                    : Math.max(0f,
                            (focusDocX - gutterWidth) / Math.max(1f, charWidth));
            zoomAnchorLine = Math.max(0f,
                    focusDocY / Math.max(1f, lineHeight));
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            if (Float.isNaN(factor) || Float.isInfinite(factor) || factor <= 0f) {
                return false;
            }
            float newSp = textSizeSp * factor;
            // Hard clamp before applying so a fling-pinch doesn't overshoot
            // and then feel sticky against the stop.
            if (newSp < MIN_TEXT_SIZE_SP) newSp = MIN_TEXT_SIZE_SP;
            if (newSp > MAX_TEXT_SIZE_SP) newSp = MAX_TEXT_SIZE_SP;
            if (Math.abs(newSp - textSizeSp) < 0.01f) {
                return true;
            }

            updateTextMetricsForScale(newSp);

            // Rebuild the semantic anchor with the NEW metrics, then scroll it
            // back under the (possibly moving) pinch midpoint. A gutter pinch
            // changes only scale/vertical position; preserve horizontal scroll.
            float anchorDocY = zoomAnchorLine * lineHeight;
            int nx;
            if (zoomAnchorInGutter) {
                nx = zoomStartScrollX;
            } else {
                float anchorDocX = gutterWidth + zoomAnchorXEm * charWidth;
                nx = Math.round(anchorDocX - detector.getFocusX());
            }
            int ny = Math.round(anchorDocY - detector.getFocusY());
            nx = Math.max(0, Math.min(nx, maxScrollX()));
            ny = Math.max(0, Math.min(ny, maxScrollY()));
            scrollTo(nx, ny);
            invalidate();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            // Keep suppressSingleFingerGestures set — the final finger is still
            // down and its eventual UP must not become a caret-moving tap.
            scaling = false;
            // Snap scroll to legal range once more after the last factor.
            int nx = Math.max(0, Math.min(getScrollX(), maxScrollX()));
            int ny = Math.max(0, Math.min(getScrollY(), maxScrollY()));
            scrollTo(nx, ny);
            invalidate();
        }
    }

    /** Recomputes zoom-dependent metrics without changing caret/selection. */
    private void updateTextMetricsForScale(float newSp) {
        textSizeSp = newSp;
        float px = textSizeSp * density;
        textPaint.setTextSize(px);
        gutterPaint.setTextSize(px * 0.85f);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        lineHeight = (float) Math.ceil(fm.descent - fm.ascent + fm.leading);
        if (lineHeight < 1f) lineHeight = 1f;
        baselineShift = -fm.ascent;
        charWidth = textPaint.measureText("M");
        gutterPad = charWidth * 0.75f;
        updateGutterWidth();
    }
}
