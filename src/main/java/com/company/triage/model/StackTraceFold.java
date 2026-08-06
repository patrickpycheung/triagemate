package com.company.triage.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * FND-88 — shortens a Java stack trace for a surface that has to render it as plain text.
 *
 * <p>The problem is the same one the UI has: an {@code e-log} evidence summary carries the
 * whole Sumo {@code _raw}, which for a real service is a 100–300 line stack trace, and
 * {@link DiagnosisReport#toSourcesNote()} writes it flat into a ServiceNow work note. The
 * assignment group then gets a comment several screens long with one useful sentence in it.
 *
 * <p><b>The rule is the same as the UI's</b> ({@code stackTraceHtml} in {@code index.html}) —
 * keep every line that carries meaning, elide only runs of framework plumbing:
 * <ul>
 *   <li>every non-frame line — the exception header, each {@code Caused by:} /
 *       {@code Suppressed:} / {@code ... N more}, any interleaved message text;</li>
 *   <li>the first two frames of every run (the throw site);</li>
 *   <li>every application frame — anything outside {@link #NOISE_PACKAGES};</li>
 *   <li>the last frame of every run (the entry point).</li>
 * </ul>
 *
 * <p><b>What is deliberately different from the UI.</b> The UI hides frames behind a
 * {@code <details>} and keeps the untouched original one click away. A work-note reader has
 * no expander and no link back to the Sumo row, so here the elision must be <em>stated</em>,
 * not merely available: every dropped run leaves a counted marker in its place, and
 * {@link #summarise} reports the totals so the caller can say plainly that what follows is
 * abbreviated. A reader who cannot tell that lines were removed would read a truncated trace
 * as a complete one, which is the failure mode this class exists to avoid — the note is
 * customer-visible and permanently retained, so an unmarked omission is worse than a long
 * comment.
 *
 * <p>Not applied to anything but stack traces: {@link #looksLikeStackTrace} gates it, and
 * ordinary one-sentence evidence passes through untouched.
 */
public final class StackTraceFold {

    private StackTraceFold() {}

    private static final Pattern FRAME = Pattern.compile("^\\s*at \\S");
    private static final Pattern FRAME_ANYWHERE = Pattern.compile("^\\s*at \\S", Pattern.MULTILINE);
    private static final Pattern CAUSE_ANYWHERE =
            Pattern.compile("^\\s*(?:Caused by|Suppressed):", Pattern.MULTILINE);
    private static final Pattern FRAME_PACKAGE = Pattern.compile("^\\s*at ([\\w$]+(?:\\.[\\w$]+)?)");

    /**
     * Third-party roots nobody triaging an incident reads frame-by-frame. A prefix list of
     * KNOWN third parties rather than an "is it ours?" test, deliberately: the app must fold
     * correctly for an estate whose own code lives under some other root, so an unrecognised
     * package counts as application code. That fails towards keeping a frame that did not
     * matter, which costs a line, rather than towards dropping the one that did.
     */
    private static final Pattern NOISE_PACKAGES = Pattern.compile(
            "^\\s*at (?:java|javax|jakarta|jdk|sun|com\\.sun|org\\.springframework|org\\.apache"
            + "|org\\.hibernate|org\\.eclipse|org\\.glassfish|org\\.junit|org\\.mockito|org\\.slf4j"
            + "|ch\\.qos|io\\.netty|io\\.micrometer|reactor|okhttp3|retrofit2|feign|com\\.zaxxer"
            + "|com\\.fasterxml|net\\.sf)\\.");

    /** A run shorter than this is left alone — a marker costs more than the lines it saves. */
    private static final int MIN_RUN_TO_ELIDE = 3;

    /**
     * Ceiling on the folded result. Frame elision alone does not bound the output: a trace
     * with a deep {@code Caused by:} chain can be almost all application frames and stay
     * enormous. When the cap bites, the HEAD and the TAIL are both kept — the head names
     * where it broke, the tail carries the deepest {@code Caused by:}, which is what actually
     * broke, and any single-ended cut loses one or the other.
     */
    private static final int MAX_LINES = 36;
    private static final int TAIL_LINES = 12;

    /** Longest single line kept; a serialised payload dumped into a message gets clipped. */
    private static final int MAX_LINE_CHARS = 400;

    /** The outcome of a fold: the shortened text, and enough counts to describe it honestly. */
    public record Folded(String text, int originalLines, int keptLines) {
        public int elidedLines() { return originalLines - keptLines; }
        public boolean shortened() { return elidedLines() > 0; }
    }

    /** Does this text look like a stack trace at all? Either signal suffices. */
    public static boolean looksLikeStackTrace(String text) {
        if (text == null || text.indexOf('\n') < 0) return false;
        return FRAME_ANYWHERE.matcher(text).find() || CAUSE_ANYWHERE.matcher(text).find();
    }

    /**
     * Fold {@code text} if it is a stack trace; otherwise return it unchanged with zero
     * elisions, so a caller can run everything through here without pre-checking.
     *
     * @param indent prefix for every line after the first — a work note renders the trace
     *               inside a bullet, and without this the continuation lines break the list.
     */
    public static Folded fold(String text, String indent) {
        if (!looksLikeStackTrace(text)) {
            int n = text == null ? 0 : text.split("\n", -1).length;
            return new Folded(text == null ? "" : text, n, n);
        }

        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        boolean[] keep = decideKeeps(lines);

        // Emit, gathering each contiguous dropped run into one counted marker.
        List<String> out = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!keep[i]) { pending.add(lines[i]); continue; }
            flush(pending, out);
            out.add(clip(deTab(lines[i])));
        }
        flush(pending, out);

        List<String> capped = applyCap(out);
        int kept = (int) capped.stream().filter(l -> !l.startsWith("…")).count();
        String joined = String.join("\n" + indent, capped);
        return new Folded(joined, lines.length, kept);
    }

    /**
     * Per-line keep/drop. Runs of frames are examined as a unit because "first two" and "last
     * one" are properties of the RUN, not of the text: a trace with three {@code Caused by:}
     * blocks has three throw sites and three entry points, and all six are worth seeing.
     */
    private static boolean[] decideKeeps(String[] lines) {
        boolean[] keep = new boolean[lines.length];
        java.util.Arrays.fill(keep, true);
        int i = 0;
        while (i < lines.length) {
            if (!FRAME.matcher(lines[i]).find()) { i++; continue; }
            int end = i;
            while (end < lines.length && FRAME.matcher(lines[end]).find()) end++;
            for (int j = i; j < end; j++) {
                boolean edge = (j < i + 2) || (j == end - 1);
                keep[j] = edge || !NOISE_PACKAGES.matcher(lines[j]).find();
            }
            i = end;
        }
        return keep;
    }

    private static void flush(List<String> pending, List<String> out) {
        if (pending.isEmpty()) return;
        if (pending.size() < MIN_RUN_TO_ELIDE) {
            pending.forEach(l -> out.add(clip(deTab(l))));
        } else {
            Set<String> packages = new LinkedHashSet<>();
            pending.forEach(l -> packages.add(framePackage(l)));
            List<String> shown = packages.stream().limit(2).toList();
            out.add("… %d framework frames elided (%s%s)".formatted(
                    pending.size(), String.join(", ", shown), packages.size() > 2 ? ", …" : ""));
        }
        pending.clear();
    }

    /** Head + tail with a stated gap, once elision alone has not brought it under the cap. */
    private static List<String> applyCap(List<String> out) {
        if (out.size() <= MAX_LINES) return out;
        int head = MAX_LINES - TAIL_LINES;
        int dropped = out.size() - MAX_LINES;
        List<String> capped = new ArrayList<>(out.subList(0, head));
        capped.add("… %d further lines elided (see the full log line in Sumo Logic)".formatted(dropped));
        capped.addAll(out.subList(out.size() - TAIL_LINES, out.size()));
        return capped;
    }

    /**
     * One line describing what was done to the text, or {@code null} when nothing was. Kept
     * separate from {@link #fold} so the caller decides where the disclosure goes; kept
     * mandatory in spirit, because a silently shortened trace reads as a complete one.
     */
    public static String summarise(Folded folded) {
        if (!folded.shortened()) return null;
        return "(stack trace abbreviated: %d of %d lines elided — full text in Sumo Logic)"
                .formatted(folded.elidedLines(), folded.originalLines());
    }

    /** The emitter's own leading tab; the rendering surface supplies its own indentation. */
    private static String deTab(String line) { return line.replaceFirst("^\\s+", ""); }

    private static String clip(String line) {
        return line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + " …" : line;
    }

    private static String framePackage(String line) {
        var m = FRAME_PACKAGE.matcher(line);
        return m.find() ? m.group(1) : "?";
    }
}
