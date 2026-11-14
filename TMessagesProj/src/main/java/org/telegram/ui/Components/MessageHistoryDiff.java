/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui.Components;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;

import java.util.ArrayList;

/**
 * Word-level diff between two versions of a message text, rendered git-style:
 * added fragments get a green highlight, removed fragments are red, struck through and faded.
 */
public class MessageHistoryDiff {

    // Above this many DP cells the middle part is shown as a whole "removed + added" block.
    private static final int MAX_LCS_CELLS = 1_500_000;

    private static final int OP_EQUAL = 0;
    private static final int OP_REMOVED = 1;
    private static final int OP_ADDED = 2;

    public static CharSequence build(String oldText, String newText, int addedColor, int removedColor) {
        if (oldText == null) oldText = "";
        if (newText == null) newText = "";

        ArrayList<String> a = tokenize(oldText);
        ArrayList<String> b = tokenize(newText);

        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }

        ArrayList<int[]> ops = new ArrayList<>(); // {op, tokenIndex}
        for (int i = 0; i < prefix; i++) {
            ops.add(new int[]{OP_EQUAL, i});
        }
        diffMiddle(a, b, prefix, a.size() - suffix, prefix, b.size() - suffix, ops);
        for (int i = a.size() - suffix; i < a.size(); i++) {
            ops.add(new int[]{OP_EQUAL, i});
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        int addedBg = Color.argb(0x40, Color.red(addedColor), Color.green(addedColor), Color.blue(addedColor));
        int removedBg = Color.argb(0x26, Color.red(removedColor), Color.green(removedColor), Color.blue(removedColor));
        int removedFg = Color.argb(0xB3, Color.red(removedColor), Color.green(removedColor), Color.blue(removedColor));

        int i = 0;
        while (i < ops.size()) {
            int op = ops.get(i)[0];
            StringBuilder chunk = new StringBuilder();
            while (i < ops.size() && ops.get(i)[0] == op) {
                int idx = ops.get(i)[1];
                chunk.append(op == OP_ADDED ? b.get(idx) : a.get(idx));
                i++;
            }
            int start = sb.length();
            sb.append(chunk);
            int end = sb.length();
            if (op == OP_ADDED) {
                sb.setSpan(new BackgroundColorSpan(addedBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(addedColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (op == OP_REMOVED) {
                sb.setSpan(new BackgroundColorSpan(removedBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(removedFg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    private static void diffMiddle(ArrayList<String> a, ArrayList<String> b, int aStart, int aEnd, int bStart, int bEnd, ArrayList<int[]> ops) {
        int n = aEnd - aStart;
        int m = bEnd - bStart;
        if ((long) (n + 1) * (m + 1) > MAX_LCS_CELLS) {
            for (int i = aStart; i < aEnd; i++) ops.add(new int[]{OP_REMOVED, i});
            for (int j = bStart; j < bEnd; j++) ops.add(new int[]{OP_ADDED, j});
            return;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a.get(aStart + i).equals(b.get(bStart + j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a.get(aStart + i).equals(b.get(bStart + j))) {
                ops.add(new int[]{OP_EQUAL, aStart + i});
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new int[]{OP_REMOVED, aStart + i});
                i++;
            } else {
                ops.add(new int[]{OP_ADDED, bStart + j});
                j++;
            }
        }
        while (i < n) ops.add(new int[]{OP_REMOVED, aStart + i++});
        while (j < m) ops.add(new int[]{OP_ADDED, bStart + j++});
    }

    /** Splits text into runs of letters/digits, runs of whitespace, and single other code points. */
    private static ArrayList<String> tokenize(String text) {
        ArrayList<String> tokens = new ArrayList<>();
        int len = text.length();
        int i = 0;
        while (i < len) {
            int cp = text.codePointAt(i);
            int start = i;
            int type = tokenType(cp);
            i += Character.charCount(cp);
            if (type != 2) {
                while (i < len) {
                    int next = text.codePointAt(i);
                    if (tokenType(next) != type) break;
                    i += Character.charCount(next);
                }
            }
            tokens.add(text.substring(start, i));
        }
        return tokens;
    }

    private static int tokenType(int cp) {
        if (Character.isLetterOrDigit(cp) || cp == '_') return 0;
        if (Character.isWhitespace(cp)) return 1;
        return 2;
    }
}
