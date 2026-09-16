package org.telegram.ui.Components;

import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.text.style.ParagraphStyle;
import android.text.style.SuggestionSpan;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;

import java.util.HashMap;
import java.util.Map;

// 😡😡😡 some systems (especially samsung) put suggestions into the text
// using editable.commitText, this completely removes all of our spans
public class EditTextSuggestionsFix implements TextWatcher {

    private boolean ignore;

    private HashMap<Object, Pair<Integer, Integer>> beforeSpans;
    private int beforeSuggestionsCount;

    @Override
    public void beforeTextChanged(CharSequence charSequence, int start, int count, int after) {
        if (ignore) return;
        // ZG perf: onTextChanged below only ever uses what we save here when
        // `startIndex == 0 && beforeCount == afterCount` - the IME replacing the whole field
        // with an equal-length string. TextWatcher's contract gives us exactly those two values
        // up front (start == startIndex, count == beforeCount, after == afterCount), so apply the
        // same test here instead of doing three full getSpans() scans, a HashMap and one Pair per
        // span on every keystroke and then throwing the result away.
        if (start != 0 || count != after) {
            beforeSpans = null;
            beforeSuggestionsCount = 0;
            return;
        }
        beforeSpans = saveSpans(charSequence);
        beforeSuggestionsCount = charSequence instanceof Spannable ? ((Spannable) charSequence).getSpans(0, charSequence.length(), SuggestionSpan.class).length : 0;
    }

    @Override
    public void onTextChanged(CharSequence charSequence, int startIndex, int beforeCount, int afterCount) {
        if (ignore) return;
        // ZG perf: the cheap, allocation-free half of the condition is tested first so the
        // getSpans() scan below is skipped on an ordinary keystroke. getSpans() has no side
        // effects, so reordering the && chain cannot change which edits take the fix-up branch.
        if (beforeSpans == null || startIndex != 0 || beforeCount != afterCount) {
            return;
        }
        final int suggestionsCount = charSequence instanceof Spannable ? ((Spannable) charSequence).getSpans(0, charSequence.length(), SuggestionSpan.class).length : 0;
        if (suggestionsCount > 0 || beforeSuggestionsCount > 0) {
            ignore = true;
            applySpans(charSequence, beforeSpans);
            ignore = false;
        }
    }

    @Override
    public void afterTextChanged(Editable s) {

    }

    private static HashMap<Object, Pair<Integer, Integer>> saveSpans(CharSequence cs) {
        HashMap<Object, Pair<Integer, Integer>> m = new HashMap<>();
        if (!(cs instanceof Spannable)) return m;
        Spannable spannable = (Spannable) cs;
        CharacterStyle[] spans = spannable.getSpans(0, spannable.length(), CharacterStyle.class);
        ParagraphStyle[] spans2 = spannable.getSpans(0, spannable.length(), ParagraphStyle.class);
        if (spans != null && spans.length > 0) {
            for (int i = 0; i < spans.length; ++i) {
                CharacterStyle span = spans[i];
                if (span == null) continue;
                if (span instanceof SuggestionSpan) continue;
                m.put(span, new Pair<>(spannable.getSpanStart(span), spannable.getSpanEnd(span)));
            }
        }
        if (spans2 != null && spans2.length > 0) {
            for (int i = 0; i < spans2.length; ++i) {
                ParagraphStyle span = spans2[i];
                if (span == null) continue;
                if (span instanceof SuggestionSpan) continue;
                m.put(span, new Pair<>(spannable.getSpanStart(span), spannable.getSpanEnd(span)));
            }
        }
        return m;
    }

    private static void applySpans(CharSequence cs, HashMap<Object, Pair<Integer, Integer>> saved) {
        if (saved == null) return;
        if (!(cs instanceof Spannable)) return;
        Spannable spannable = (Spannable) cs;
        for (Map.Entry<Object, Pair<Integer, Integer>> e : saved.entrySet()) {
            if (spannable.getSpanStart(e.getKey()) != -1) continue;
            spannable.setSpan(e.getKey(), e.getValue().first, e.getValue().second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
