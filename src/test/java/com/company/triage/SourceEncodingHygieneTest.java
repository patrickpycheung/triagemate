package com.company.triage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FND-85: no source file may contain a NUL byte.
 *
 * <p>This is not style policing — a NUL byte makes GNU {@code grep}, {@code rg} and most
 * code-search tools classify the file as <em>binary</em> and skip it entirely, returning
 * <b>exit 0 with no matches and no warning</b>. Every symbol in that file becomes invisible
 * to a repo-wide search.
 *
 * <p>It had already caused real harm before it was found. A single raw NUL sat inside the
 * {@code firstDocId} sentinel in {@code DeterministicDiagnosisEngine} — the largest
 * orchestration file in the project — where the author had plainly meant the Java escape
 * {@code "\0none"} and typed the byte itself. A repo-wide grep for
 * {@code resolutionCode}/{@code resolutionNotes} consequently reported that <em>nothing</em>
 * read those fields, when {@code DeterministicDiagnosisEngine} reads {@code resolutionCode}.
 * Two independent readers reached that same wrong conclusion from the same silent grep, in
 * one session, and one of them wrote it into a design document as a verified fact.
 *
 * <p>Any dead-code sweep, rename, or impact analysis run against this repo is unsound while
 * such a byte exists, so the guard is cheap insurance against a whole class of silent
 * wrong answers.
 */
class SourceEncodingHygieneTest {

    /**
     * Text sources only. Binary resources (icons, images) contain NUL bytes by nature and
     * grep is <em>right</em> to skip them — the defect is a NUL in a file a human expects
     * to be searchable.
     */
    private static final List<String> TEXT_SUFFIXES =
            List.of(".java", ".yml", ".yaml", ".properties", ".html", ".json", ".xml", ".md", ".txt");

    @Test
    void noSourceFileContainsANulByte() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path root : List.of(Path.of("src/main"), Path.of("src/test"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path p : paths.filter(Files::isRegularFile)
                        .filter(SourceEncodingHygieneTest::isTextSource).toList()) {
                    byte[] bytes = Files.readAllBytes(p);
                    int at = indexOfNul(bytes);
                    if (at >= 0) {
                        offenders.add("%s (offset %d, line %d)"
                                .formatted(p, at, lineOf(bytes, at)));
                    }
                }
            }
        }

        assertThat(offenders)
                .withFailMessage("""
                        Source file(s) contain a NUL byte, which makes grep/rg treat them as \
                        binary and SILENTLY skip them (exit 0, no matches, no warning) — every \
                        symbol inside becomes invisible to code search:
                        %s
                        Fix: use the Java escape "\\0" rather than a literal NUL byte.""",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    private static boolean isTextSource(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return TEXT_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static int indexOfNul(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) return i;
        }
        return -1;
    }

    private static int lineOf(byte[] bytes, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (bytes[i] == '\n') line++;
        }
        return line;
    }
}
