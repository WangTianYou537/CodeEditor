package com.editor.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import com.editor.complete.CompletionItem;
import com.editor.highlight.ColorScheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Completion list rendered by a lightweight custom view inside a
 * {@link PopupWindow} anchored near the caret.
 *
 * <p>Positioning is constrained to the window's <em>visible display frame</em>
 * (the area above the soft keyboard). The popup is flipped above the caret
 * when there isn't enough room below, so it never sits on top of the IME —
 * which used to intercept taps meant for the keyboard.
 */
public final class CompletionPopup {

    public interface Listener {
        void onItemPicked(CompletionItem item);
    }

    private static final int MAX_VISIBLE = 6;

    private final CodeEditorView editor;
    private final PopupWindow window;
    private final ListView listView;
    private final List<CompletionItem> items = new ArrayList<>();
    private final float density;
    private int selected;
    private Listener listener;

    public CompletionPopup(CodeEditorView editor, ColorScheme scheme) {
        this.editor = editor;
        this.density = editor.getResources().getDisplayMetrics().density;
        this.listView = new ListView(editor.getContext(), scheme);
        int width = (int) (density * 260);
        this.window = new PopupWindow(listView, width, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Transparent window background required for outside-touch dismiss.
        this.window.setBackgroundDrawable(new ColorDrawable(0x00000000));
        this.window.setOutsideTouchable(true);
        this.window.setTouchable(true);
        // Don't steal focus: the editor (and IME) keep receiving key events.
        this.window.setFocusable(false);
        // Let the IME keep its size; we position ourselves around it instead
        // of letting the popup push/overlap the keyboard.
        this.window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        this.window.setClippingEnabled(true);
        this.window.setElevation(8 * density);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isShowing() {
        return window.isShowing();
    }

    /**
     * @param caretX      caret x in window coordinates
     * @param caretTop    top of the caret line in window coordinates
     * @param caretBottom bottom of the caret line in window coordinates
     * @param visibleFrame area of the window not covered by the IME
     *                     (from {@code View.getWindowVisibleDisplayFrame})
     */
    public void show(List<CompletionItem> newItems,
                     int caretX, int caretTop, int caretBottom,
                     Rect visibleFrame) {
        items.clear();
        items.addAll(newItems);
        selected = 0;
        listView.scrollOffset = 0;

        int rows = Math.min(items.size(), MAX_VISIBLE);
        int height = rows * listView.rowHeight() + listView.getPaddingTop()
                + listView.getPaddingBottom();
        int width = window.getWidth() > 0
                ? window.getWidth()
                : (int) (density * 260);

        // Horizontal: keep the popup inside the visible frame.
        int x = caretX;
        if (x + width > visibleFrame.right - dp(4)) {
            x = visibleFrame.right - width - dp(4);
        }
        if (x < visibleFrame.left + dp(4)) {
            x = visibleFrame.left + dp(4);
        }

        // Vertical: prefer below the caret; flip above if it would collide
        // with the IME / bottom edge. Never place the popup over the keyboard.
        int gap = dp(2);
        int yBelow = caretBottom + gap;
        int yAbove = caretTop - height - gap;
        int y;
        boolean fitsBelow = yBelow + height <= visibleFrame.bottom - dp(4);
        boolean fitsAbove = yAbove >= visibleFrame.top + dp(4);
        if (fitsBelow) {
            y = yBelow;
        } else if (fitsAbove) {
            y = yAbove;
        } else {
            // Neither side fits fully — pin inside the visible frame, as close
            // to the caret as possible, still entirely above the IME.
            y = Math.max(visibleFrame.top + dp(4),
                    Math.min(yBelow, visibleFrame.bottom - height - dp(4)));
        }

        // Final clamp so a very tall list can't spill under the IME.
        int maxHeight = Math.max(listView.rowHeight(),
                visibleFrame.bottom - y - dp(4));
        if (height > maxHeight) {
            height = maxHeight - (maxHeight % listView.rowHeight());
            height = Math.max(listView.rowHeight(), height);
        }

        if (window.isShowing()) {
            window.update(x, y, width, height);
        } else {
            window.setWidth(width);
            window.setHeight(height);
            window.showAtLocation(editor, Gravity.NO_GRAVITY, x, y);
        }
        listView.invalidate();
    }

    public void dismiss() {
        if (window.isShowing()) {
            window.dismiss();
        }
        items.clear();
    }

    // -- keyboard navigation, called by the editor ----------------------

    public void moveSelection(int delta) {
        if (items.isEmpty()) {
            return;
        }
        selected = (selected + delta + items.size()) % items.size();
        listView.ensureVisible(selected);
        listView.invalidate();
    }

    public boolean pickSelected() {
        if (selected < 0 || selected >= items.size()) {
            return false;
        }
        if (listener != null) {
            listener.onItemPicked(items.get(selected));
        }
        return true;
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    // ------------------------------------------------------------------

    /** Minimal internal list: draws rows, handles taps and drag-scroll. */
    private final class ListView extends View {

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ColorScheme scheme;
        private final float density;
        private final RectF roundRect = new RectF();
        int scrollOffset; // pixels
        private float lastTouchY;
        private float downX, downY;
        private boolean dragging;
        /** Ignore taps that look like they slid in from the IME region. */
        private static final float TAP_SLOP_DP = 12f;

        ListView(Context context, ColorScheme scheme) {
            super(context);
            this.scheme = scheme;
            this.density = context.getResources().getDisplayMetrics().density;
            textPaint.setTextSize(14 * density);
            textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
            textPaint.setColor(scheme.completionText);
            detailPaint.setTextSize(11 * density);
            detailPaint.setColor(scheme.completionDetail);
            setPadding(dp(4), dp(4), dp(4), dp(4));
            // Don't let the list steal the next editor key event after a tap.
            setFocusable(false);
            setClickable(true);
        }

        int rowHeight() {
            return dp(36);
        }

        private int dp(float v) {
            return (int) (v * density + 0.5f);
        }

        void ensureVisible(int index) {
            int top = index * rowHeight() - scrollOffset;
            int bottom = top + rowHeight();
            int viewH = Math.max(getHeight(), MAX_VISIBLE * rowHeight());
            if (top < 0) {
                scrollOffset += top;
            } else if (bottom > viewH) {
                scrollOffset += bottom - viewH;
            }
            if (scrollOffset < 0) scrollOffset = 0;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            roundRect.set(0, 0, getWidth(), getHeight());
            bgPaint.setColor(scheme.completionBackground);
            canvas.drawRoundRect(roundRect, dp(6), dp(6), bgPaint);

            int rh = rowHeight();
            int viewH = getHeight();
            canvas.save();
            canvas.clipRect(0, 0, getWidth(), viewH);
            canvas.translate(0, -scrollOffset);
            for (int i = 0; i < items.size(); i++) {
                int top = getPaddingTop() + i * rh;
                if (top + rh - scrollOffset < 0 || top - scrollOffset > viewH) {
                    continue;
                }
                CompletionItem item = items.get(i);
                if (i == selected) {
                    bgPaint.setColor(scheme.completionSelected);
                    roundRect.set(dp(2), top, getWidth() - dp(2), top + rh);
                    canvas.drawRoundRect(roundRect, dp(4), dp(4), bgPaint);
                }
                float baseline = top + rh / 2f + textPaint.getTextSize() / 2.8f;
                canvas.drawText(kindGlyph(item.kind) + "  " + item.label,
                        getPaddingLeft() + dp(4), baseline, textPaint);
                if (item.detail != null && !item.detail.isEmpty()) {
                    float dw = detailPaint.measureText(item.detail);
                    canvas.drawText(item.detail,
                            getWidth() - getPaddingRight() - dw - dp(4),
                            baseline, detailPaint);
                }
            }
            canvas.restore();
        }

        private String kindGlyph(CompletionItem.Kind kind) {
            switch (kind) {
                case KEYWORD:    return "k";
                case TYPE:       return "T";
                case SNIPPET:    return "▸";
                case IDENTIFIER:
                default:         return "a";
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = lastTouchY = event.getY();
                    dragging = false;
                    // Consume so the event doesn't fall through to the IME
                    // or editor underneath.
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = lastTouchY - event.getY();
                    if (Math.abs(event.getY() - downY) > dp(TAP_SLOP_DP)
                            || Math.abs(event.getX() - downX) > dp(TAP_SLOP_DP)) {
                        dragging = true;
                    }
                    if (dragging) {
                        int maxScroll = Math.max(0,
                                items.size() * rowHeight()
                                        - Math.max(getHeight(), MAX_VISIBLE * rowHeight()));
                        scrollOffset = Math.max(0,
                                Math.min(maxScroll, scrollOffset + (int) dy));
                        lastTouchY = event.getY();
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        // Require the finger to stay roughly where it landed
                        // so a key-press that grazes the popup edge is ignored.
                        float slop = dp(TAP_SLOP_DP);
                        if (Math.abs(event.getX() - downX) <= slop
                                && Math.abs(event.getY() - downY) <= slop) {
                            int index = (int) ((event.getY() - getPaddingTop()
                                    + scrollOffset) / rowHeight());
                            if (index >= 0 && index < items.size()) {
                                selected = index;
                                pickSelected();
                            }
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }
    }
}
