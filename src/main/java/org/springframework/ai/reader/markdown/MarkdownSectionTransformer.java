/*
 * Copyright ID.on GmbH.
 */
package org.springframework.ai.reader.markdown;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splittet Markdown an Überschriften-Grenzen und erzeugt pro Abschnitt ein
 * Spring-AI Document.
 *
 * Eigenschaften: - unterstützt ATX-Headings (#, ##, ###, ...) - unterstützt
 * Setext-Headings (=== / ---) - ignoriert Headings innerhalb fenced code blocks
 * (``` / ~~~) - behandelt YAML front matter am Dokumentanfang als Präambel -
 * ergänzt Header-Metadaten (h1..h6, header_path, section_title, section_level,
 * start_line, end_line) - optionaler zweiter Split großer Abschnitte mit
 * TokenTextSplitter
 *
 * Gedacht für rohe Markdown-Inhalte, z.B. direkt aus einer .md-Datei gelesen.
 */
public final class MarkdownSectionTransformer implements DocumentTransformer {

    private static final Pattern ATX_HEADING
            = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");

    private static final Pattern SETEXT_H1
            = Pattern.compile("^ {0,3}=+[ \\t]*$");

    private static final Pattern SETEXT_H2
            = Pattern.compile("^ {0,3}-+[ \\t]*$");

    private static final Pattern FENCE_MARKER
            = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");

    private static final Pattern FENCE_CLOSE
            = Pattern.compile("^ {0,3}(`{3,}|~{3,})[ \\t]*$");

    private final TokenTextSplitter secondarySplitter;
    private final boolean includeHeadingLineInChunk;
    private final boolean emitPreamble;

    public MarkdownSectionTransformer() {
        this(null, true, true);
    }

    public MarkdownSectionTransformer(TokenTextSplitter secondarySplitter) {
        this(secondarySplitter, true, true);
    }

    public MarkdownSectionTransformer(
            TokenTextSplitter secondarySplitter,
            boolean includeHeadingLineInChunk,
            boolean emitPreamble
    ) {
        this.secondarySplitter = secondarySplitter;
        this.includeHeadingLineInChunk = includeHeadingLineInChunk;
        this.emitPreamble = emitPreamble;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>();

        for (Document source : documents) {
            if (source == null) {
                continue;
            }

            if (!source.isText() || source.getText() == null || source.getText().isBlank()) {
                result.add(source);
                continue;
            }

            result.addAll(splitSingleDocument(source));
        }

        return result;
    }

    private List<Document> splitSingleDocument(Document source) {
        String markdown = normalize(source.getText());
        List<String> lines = Arrays.asList(markdown.split("\n", -1));
        List<ParsedSection> sections = parseSections(lines);

        List<Document> output = new ArrayList<>();
        int sectionIndex = 1;

        for (ParsedSection section : sections) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (source.getMetadata() != null) {
                metadata.putAll(source.getMetadata());
            }

            metadata.put("parent_document_id", source.getId());
            metadata.put("section_index", sectionIndex);
            metadata.put("section_level", section.level());
            metadata.put("start_line", section.startLine());
            metadata.put("end_line", section.endLine());
            metadata.put("chunk_kind", section.level() == 0 ? "markdown_preamble" : "markdown_section");

            if (section.title() != null && !section.title().isBlank()) {
                metadata.put("section_title", section.title());
            }

            String headerPath = buildHeaderPath(section.headers());
            if (!headerPath.isBlank()) {
                metadata.put("header_path", headerPath);
            }

            for (int i = 0; i < section.headers().length; i++) {
                if (section.headers()[i] != null && !section.headers()[i].isBlank()) {
                    metadata.put("h" + (i + 1), section.headers()[i]);
                }
            }

            Document base = Document.builder()
                    .id(source.getId() + "#sec-" + sectionIndex)
                    .text(section.content())
                    .metadata(metadata)
                    .score(source.getScore())
                    .build();

            output.addAll(applySecondarySplit(base));
            sectionIndex++;
        }

        return output;
    }

    private List<Document> applySecondarySplit(Document base) {
        if (this.secondarySplitter == null) {
            return List.of(base);
        }

        List<Document> splitDocs = this.secondarySplitter.apply(List.of(base));
        if (splitDocs == null || splitDocs.isEmpty()) {
            return List.of(base);
        }

        if (splitDocs.size() == 1 && Objects.equals(splitDocs.get(0).getText(), base.getText())) {
            return List.of(base);
        }

        List<Document> rebuilt = new ArrayList<>();
        int chunkCount = splitDocs.size();

        for (int i = 0; i < splitDocs.size(); i++) {
            Document splitDoc = splitDocs.get(i);
            if (splitDoc.getText() == null || splitDoc.getText().isBlank()) {
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>(base.getMetadata());
            metadata.put("section_chunk_index", i + 1);
            metadata.put("section_chunk_count", chunkCount);

            rebuilt.add(Document.builder()
                    .id(base.getId() + "-chunk-" + (i + 1))
                    .text(splitDoc.getText())
                    .metadata(metadata)
                    .score(base.getScore())
                    .build());
        }

        return rebuilt.isEmpty() ? List.of(base) : rebuilt;
    }

    private List<ParsedSection> parseSections(List<String> lines) {
        List<ParsedSection> sections = new ArrayList<>();

        String[] headerStack = new String[6];
        List<String> buffer = new ArrayList<>();

        String currentTitle = null;
        int currentLevel = 0;
        int currentStartLine = 1;

        boolean inFence = false;
        FenceInfo openFence = null;

        int i = 0;

        int yamlEnd = findYamlFrontMatterEnd(lines);
        if (yamlEnd >= 0) {
            for (int j = 0; j <= yamlEnd; j++) {
                buffer.add(lines.get(j));
            }
            i = yamlEnd + 1;
        }

        while (i < lines.size()) {
            String line = lines.get(i);

            if (inFence) {
                buffer.add(line);

                if (isFenceClose(line, openFence)) {
                    inFence = false;
                    openFence = null;
                }

                i++;
                continue;
            }

            HeadingMatch heading = detectHeading(lines, i);
            if (heading != null) {
                addSectionIfPresent(
                        sections,
                        buffer,
                        currentTitle,
                        currentLevel,
                        headerStack,
                        currentStartLine,
                        i
                );

                buffer = new ArrayList<>();

                updateHeaderStack(headerStack, heading.level(), heading.title());
                currentTitle = heading.title();
                currentLevel = heading.level();
                currentStartLine = i + 1;

                if (this.includeHeadingLineInChunk) {
                    buffer.addAll(heading.rawLines());
                }

                i += heading.consumedLines();
                continue;
            }

            FenceInfo fence = detectFenceOpen(line);
            if (fence != null) {
                inFence = true;
                openFence = fence;
            }

            buffer.add(line);
            i++;
        }

        addSectionIfPresent(
                sections,
                buffer,
                currentTitle,
                currentLevel,
                headerStack,
                currentStartLine,
                lines.size()
        );

        return sections;
    }

    private void addSectionIfPresent(
            List<ParsedSection> sections,
            List<String> rawLines,
            String currentTitle,
            int currentLevel,
            String[] currentHeaderStack,
            int startLine,
            int endLine
    ) {
        String content = trimOuterBlankLines(String.join("\n", rawLines));

        if (content.isBlank()) {
            return;
        }

        if (currentLevel == 0 && !this.emitPreamble) {
            return;
        }

        sections.add(new ParsedSection(
                content,
                currentTitle,
                currentLevel,
                currentHeaderStack.clone(),
                startLine,
                endLine
        ));
    }

    private HeadingMatch detectHeading(List<String> lines, int index) {
        String line = lines.get(index);

        Matcher atx = ATX_HEADING.matcher(line);
        if (atx.matches()) {
            int level = atx.group(1).length();
            String title = cleanupHeadingText(atx.group(2));
            return new HeadingMatch(level, title, 1, List.of(line));
        }

        if (index + 1 < lines.size() && !line.isBlank()) {
            String next = lines.get(index + 1);

            if (SETEXT_H1.matcher(next).matches()) {
                return new HeadingMatch(1, cleanupHeadingText(line), 2, List.of(line, next));
            }

            if (SETEXT_H2.matcher(next).matches()) {
                return new HeadingMatch(2, cleanupHeadingText(line), 2, List.of(line, next));
            }
        }

        return null;
    }

    private static void updateHeaderStack(String[] headerStack, int level, String title) {
        headerStack[level - 1] = title;
        for (int i = level; i < headerStack.length; i++) {
            headerStack[i] = null;
        }
    }

    private static String buildHeaderPath(String[] headers) {
        StringBuilder sb = new StringBuilder();
        for (String header : headers) {
            if (header == null || header.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(header);
        }
        return sb.toString();
    }

    private static FenceInfo detectFenceOpen(String line) {
        Matcher matcher = FENCE_MARKER.matcher(line);
        if (!matcher.matches()) {
            return null;
        }

        String fence = matcher.group(1);
        return new FenceInfo(fence.charAt(0), fence.length());
    }

    private static boolean isFenceClose(String line, FenceInfo openFence) {
        Matcher matcher = FENCE_CLOSE.matcher(line);
        if (!matcher.matches()) {
            return false;
        }

        String closing = matcher.group(1);
        return closing.charAt(0) == openFence.markerChar()
                && closing.length() >= openFence.markerLength();
    }

    private static int findYamlFrontMatterEnd(List<String> lines) {
        if (lines.isEmpty()) {
            return -1;
        }

        if (!"---".equals(lines.get(0).trim())) {
            return -1;
        }

        for (int i = 1; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if ("---".equals(trimmed) || "...".equals(trimmed)) {
                return i;
            }
        }

        return -1;
    }

    private static String cleanupHeadingText(String text) {
        return text == null ? "" : text.trim();
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }

        String normalized = input
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        if (!normalized.isEmpty() && normalized.charAt(0) == '\uFEFF') {
            normalized = normalized.substring(1);
        }

        return normalized;
    }

    private static String trimOuterBlankLines(String text) {
        List<String> lines = Arrays.asList(text.split("\n", -1));

        int start = 0;
        int end = lines.size() - 1;

        while (start <= end && lines.get(start).isBlank()) {
            start++;
        }

        while (end >= start && lines.get(end).isBlank()) {
            end--;
        }

        if (start > end) {
            return "";
        }

        return String.join("\n", lines.subList(start, end + 1));
    }

    private record ParsedSection(
            String content,
            String title,
            int level,
            String[] headers,
            int startLine,
            int endLine
            ) {

    }

    private record HeadingMatch(
            int level,
            String title,
            int consumedLines,
            List<String> rawLines
            ) {

    }

    private record FenceInfo(
            char markerChar,
            int markerLength
            ) {

    }
}
