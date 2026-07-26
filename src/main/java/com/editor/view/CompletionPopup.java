package com.editor.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.PopupWindow;

import com.editor.complete.CompletionItem;
import com.editor.highlight.ColorScheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Completion list rendered by a lightweight custom view inside a
 * {@link PopupWindow} anchored at the caret. Keyboard (via the editor) and
 * touch both drive the selection; the editor applies the chosen item.
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
    private int selected;
    private Listener listener;

    public CompletionPopup(CodeEditorView editor, ColorScheme scheme) {
        this.editor = editor;
        this.listView = new ListView(editor.getContext(), scheme);
        this.window = new PopupWindow(listView,
                (int) (editor.getResources().getDisplayMetrics().density * 260), 0);
        this.window.setOutsideTouchable(true);
        // Don't steal focus: the editor keeps receiving key events.
        this.window.setFocusable(false);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isShowing() {
        return window.isShowing();
    }

    public void show(List<CompletionItem> newItems, int anchorX, int anchorY) {
        items.clear();
        items.addAll(newItems);
        selected = 0;
        int rows = Math.min(items.size(), MAX_VISIBLE);
        int height = rows * listView.rowHeight() + listView.getPaddingTop()
                + listView.getPaddingBottom();
        window.setHeight(height);
        listView.scrollOffset = 0;
        if (window.isShowing()) {
            window.update(anchorX, anchorY, window.getWidth(), height);
        } else {
            window.showAtLocation(editor, android.view.Gravity.NO_GRAVITY, anchorX, anchorY);
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

    // ------------------------------------------------------------------

    /** Minimal internal list: draws rows, handles taps and fling-less drag. */
    private final class ListView extends View {

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint();
        private final ColorScheme scheme;
        private final float density;
        int scrollOffset; // pixels
        private float lastTouchY;
        private boolean dragging;

        ListView(Context context, ColorScheme scheme) {
            super(context);
            this.scheme = scheme;
            this.density = context.getResources().getDisplayMetrics().density;
            textPaint.setTextSize(15 * density);
            textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
            textPaint.setColor(scheme.completionText);
            detailPaint.setTextSize(11 * density);
            detailPaint.setColor(scheme.completionDetail);
            setPadding(dp(4), dp(4), dp(4), dp(4));
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
            int viewH = MAX_VISIBLE * rowHeight();
            if (top < 0) {
                scrollOffset += top;
            } else if (bottom > viewH) {
                scrollOffset += bottom - viewH;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            bgPaint.setColor(scheme.completionBackground);
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()),
                    dp(6), dp(6), bgPaint);

            int rh = rowHeight();
            canvas.save();
            canvas.translate(0, -scrollOffset);
            for (int i = 0; i < items.size(); i++) {
                int top = getPaddingTop() + i * rh;
                if (top + rh - scrollOffset < 0 || top - scrollOffset > getHeight()) {
                    continue;
                }
                CompletionItem item = items.get(i);
                if (i == selected) {
                    bgPaint.setColor(scheme.completionSelected);
                    canvas.drawRoundRect(new RectF(dp(2), top, getWidth() - dp(2), top + rh),
                            dp(4), dp(4), bgPaint);
                }
                float baseline = top + rh / 2f + textPaint.getTextSize() / 2.8f;
                canvas.drawText(kindGlyph(item.kind) + "  " + item.label,
                        getPaddingLeft() + dp(4), baseline, textPaint);
                float dw = detailPaint.measureText(item.detail);
                canvas.drawText(item.detail,
                        getWidth() - getPaddingRight() - dw - dp(4), baseline, detailPaint);
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
                    lastTouchY = event.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = lastTouchY - event.getY();
                    if (Math.abs(dy) > dp(6)) {
                        dragging = true;
                    }
                    if (dragging) {
                        int maxScroll = Math.max(0,
                                items.size() * rowHeight() - MAX_VISIBLE * rowHeight());
                        scrollOffset = Math.max(0,
                                Math.min(maxScroll, scrollOffset + (int) dy));
                        lastTouchY = event.getY();
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        int index = (int) ((event.getY() - getPaddingTop() + scrollOffset)
                                / rowHeight());
                        if (index >= 0 && index < items.size()) {
                            selected = index;
                            pickSelected();
                        }
                    }
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }
    }
}
