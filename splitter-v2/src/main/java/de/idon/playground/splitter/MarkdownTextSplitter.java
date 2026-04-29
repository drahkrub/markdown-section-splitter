package de.idon.playground.splitter;

import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.ContentFormatter;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.yaml.snakeyaml.Yaml;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Markdown-aware {@link TextSplitter} for Spring AI 1.1.4.
 *
 * <p>This implementation uses a hybrid strategy:
 * <ul>
 *     <li>line-based scanning for exact source-preserving chunk extraction</li>
 *     <li>commonmark parsing for robust heading text normalization</li>
 *     <li>block-model-based sub-chunking to avoid splitting tables, lists, quotes and code fences unnecessarily</li>
 * </ul>
 */
public final class MarkdownTextSplitter extends TextSplitter {

    private static final Logger logger = LoggerFactory.getLogger(MarkdownTextSplitter.class);

    public static final String META_CONTENT_TYPE = "content_type";
    public static final String META_SECTION_TITLE = "section_title";
    public static final String META_HEADING_LEVEL = "heading_level";
    public static final String META_HEADING_PATH = "heading_path";
    public static final String META_SECTION_INDEX = "section_index";
    public static final String META_CHUNK_INDEX = "chunk_index";
    public static final String META_TOTAL_CHUNKS_IN_SECTION = "total_chunks_in_section";
    public static final String META_CHAR_COUNT = "char_count";
    public static final String META_WORD_COUNT = "word_count";
    public static final String META_HAS_CODE_BLOCK = "has_code_block";
    public static final String META_HAS_FRONTMATTER = "has_frontmatter";
    public static final String META_FRONTMATTER = "frontmatter";
    public static final String META_SOURCE_LINE_START = "source_line_start";
    public static final String META_SOURCE_LINE_END = "source_line_end";
    public static final String META_ANCHOR_SLUG = "anchor_slug";
    public static final String META_HEADER_PREFIX = "header_h";
    public static final String META_MERGED_SECTIONS = "merged_sections";
    public static final String META_MERGED_SECTION_TITLES = "merged_section_titles";
    public static final String META_BLOCK_TYPES = "block_types";

    private static final String META_PARENT_DOCUMENT_ID = "parent_document_id";
    private static final String META_TOTAL_CHUNKS = "total_chunks";

    private static final String DEFAULT_CONTENT_TYPE = "markdown";
    private static final String DEFAULT_SECTION_TITLE = "Introduction";

    private final Parser parser;
    private final Yaml yaml;

    private final boolean includeHeadingInChunk;
    private final boolean preservePreamble;
    private final boolean includeHierarchyMetadata;
    private final boolean splitLargeSections;
    private final boolean extractFrontmatter;
    private final boolean includeFrontmatterMetadata;
    private final boolean mergeSmallSections;

    private final int maxChunkChars;
    private final int minChunkChars;
    private final int minSectionCharsToKeepStandalone;

    public MarkdownTextSplitter() {
        this(builder());
    }

    private MarkdownTextSplitter(Builder builder) {
        this.parser = builder.parser != null ? builder.parser : Parser.builder().build();
        this.yaml = builder.yaml != null ? builder.yaml : new Yaml();
        this.includeHeadingInChunk = builder.includeHeadingInChunk;
        this.preservePreamble = builder.preservePreamble;
        this.includeHierarchyMetadata = builder.includeHierarchyMetadata;
        this.splitLargeSections = builder.splitLargeSections;
        this.extractFrontmatter = builder.extractFrontmatter;
        this.includeFrontmatterMetadata = builder.includeFrontmatterMetadata;
        this.mergeSmallSections = builder.mergeSmallSections;
        this.maxChunkChars = builder.maxChunkChars;
        this.minChunkChars = builder.minChunkChars;
        this.minSectionCharsToKeepStandalone = builder.minSectionCharsToKeepStandalone;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> result = new ArrayList<>();

        for (Document source : documents) {
            if (source == null || source.getText() == null || source.getText().isBlank()) {
                continue;
            }

            List<Chunk> chunks = splitIntoChunksWithMetadata(source.getText());

            if (chunks.size() > 1) {
                logger.info("Splitting up markdown document into {} chunks.", chunks.size());
            }

            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);

                Map<String, Object> metadata = copyMetadata(source.getMetadata());
                metadata.put(META_PARENT_DOCUMENT_ID, source.getId());
                metadata.put(META_CHUNK_INDEX, i);
                metadata.put(META_TOTAL_CHUNKS, chunks.size());
                metadata.putAll(chunk.metadata());

                Document newDoc = Document.builder()
                        .text(chunk.text())
                        .metadata(metadata)
                        .score(source.getScore())
                        .build();

                if (isCopyContentFormatter()) {
                    ContentFormatter formatter = source.getContentFormatter();
                    newDoc.setContentFormatter(formatter);
                }

                result.add(newDoc);
            }
        }

        return result;
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        return splitIntoChunksWithMetadata(text).stream()
                .map(Chunk::text)
                .toList();
    }

    private List<Chunk> splitIntoChunksWithMetadata(String rawMarkdown) {
        String markdown = normalizeLineEndings(rawMarkdown);
        List<SourceLine> lines = SourceLine.from(markdown);

        Frontmatter frontmatter = extractFrontmatter ? parseFrontmatter(lines) : Frontmatter.none();

        int contentStartLine = frontmatter.contentStartLine();
        List<SourceLine> contentLines = lines.subList(
                Math.min(contentStartLine - 1, lines.size()),
                lines.size()
        );

        List<HeadingBoundary> boundaries = detectHeadingBoundaries(contentLines, contentStartLine);
        List<Section> sections = buildSections(contentLines, boundaries, contentStartLine);

        if (!preservePreamble && !sections.isEmpty() && sections.get(0).headingLevel() == 0) {
            sections = sections.subList(1, sections.size());
        }

        if (mergeSmallSections) {
            sections = mergeSmallSections(sections);
        }

        Map<String, Integer> slugCounts = new HashMap<>();
        List<Chunk> result = new ArrayList<>();

        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            Section section = sections.get(sectionIndex);

            List<ChunkPart> sectionChunks = splitLargeSections
                    ? splitSectionIntoChunkParts(section.content())
                    : List.of(new ChunkPart(section.content(), detectBlockTypes(section.content())));

            String baseSlug = githubSlugify(section.title());
            String deduplicatedSlug = deduplicateSlug(baseSlug, slugCounts);

            for (int sectionChunkIndex = 0; sectionChunkIndex < sectionChunks.size(); sectionChunkIndex++) {
                ChunkPart chunkPart = sectionChunks.get(sectionChunkIndex);
                String chunkText = chunkPart.text();

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put(META_CONTENT_TYPE, DEFAULT_CONTENT_TYPE);
                metadata.put(META_SECTION_TITLE, section.title());
                metadata.put(META_HEADING_LEVEL, section.headingLevel());
                metadata.put(META_HEADING_PATH, section.headingPath());
                metadata.put(META_SECTION_INDEX, sectionIndex);
                metadata.put(META_TOTAL_CHUNKS_IN_SECTION, sectionChunks.size());
                metadata.put(META_CHAR_COUNT, chunkText.length());
                metadata.put(META_WORD_COUNT, countWords(chunkText));
                metadata.put(META_HAS_CODE_BLOCK, containsBalancedFence(chunkText));
                metadata.put(META_HAS_FRONTMATTER, frontmatter.present());
                metadata.put(META_SOURCE_LINE_START, section.startLine());
                metadata.put(META_SOURCE_LINE_END, section.endLine());
                metadata.put(META_ANCHOR_SLUG, deduplicatedSlug);
                metadata.put(META_MERGED_SECTIONS, section.mergedSections());
                metadata.put(META_MERGED_SECTION_TITLES, section.mergedSectionTitles());
                metadata.put(META_BLOCK_TYPES, chunkPart.blockTypes());

                if (includeFrontmatterMetadata && frontmatter.present()) {
                    metadata.put(META_FRONTMATTER, frontmatter.attributes());
                }

                if (includeHierarchyMetadata) {
                    applyHierarchyMetadata(metadata, section.headingPath());
                }

                result.add(new Chunk(chunkText, metadata));
            }
        }

        return result;
    }

    private String deduplicateSlug(String baseSlug, Map<String, Integer> slugCounts) {
        String normalized = (baseSlug == null || baseSlug.isBlank()) ? "section" : baseSlug;
        int count = slugCounts.getOrDefault(normalized, 0);
        slugCounts.put(normalized, count + 1);
        return count == 0 ? normalized : normalized + "-" + count;
    }

    private List<Section> mergeSmallSections(List<Section> sections) {
        if (sections.isEmpty() || minSectionCharsToKeepStandalone <= 0) {
            return sections;
        }

        List<Section> merged = new ArrayList<>();
        Section carry = null;

        for (Section current : sections) {
            if (carry == null) {
                carry = current;
                continue;
            }

            if (carry.content().length() < minSectionCharsToKeepStandalone && shouldMerge(carry, current)) {
                carry = carry.mergeWith(current);
            }
            else {
                merged.add(carry);
                carry = current;
            }
        }

        if (carry != null) {
            if (!merged.isEmpty()
                    && carry.content().length() < minSectionCharsToKeepStandalone
                    && shouldMerge(merged.get(merged.size() - 1), carry)) {
                Section previous = merged.remove(merged.size() - 1);
                merged.add(previous.mergeWith(carry));
            }
            else {
                merged.add(carry);
            }
        }

        return merged;
    }

    private boolean shouldMerge(Section left, Section right) {
        return right.headingLevel() == 0
                || left.headingLevel() == 0
                || right.headingLevel() >= left.headingLevel();
    }

    private Map<String, Object> copyMetadata(Map<String, Object> sourceMetadata) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (sourceMetadata == null) {
            return target;
        }

        for (Map.Entry<String, Object> entry : sourceMetadata.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                target.put(entry.getKey(), entry.getValue());
            }
        }

        return target;
    }

    private Frontmatter parseFrontmatter(List<SourceLine> lines) {
        if (lines.isEmpty() || !"---".equals(lines.get(0).content().trim())) {
            return Frontmatter.none();
        }

        int end = -1;
        StringBuilder raw = new StringBuilder();

        for (int i = 1; i < lines.size(); i++) {
            String trimmed = lines.get(i).content().trim();
            if ("---".equals(trimmed) || "...".equals(trimmed)) {
                end = i;
                break;
            }
            raw.append(lines.get(i).content()).append('\n');
        }

        if (end == -1) {
            return Frontmatter.none();
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        try {
            Object loaded = yaml.load(raw.toString());
            if (loaded instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    Object value = entry.getValue();
                    if (key != null && value != null) {
                        attributes.put(String.valueOf(key), value);
                    }
                }
            }
        }
        catch (Exception ignored) {
            logger.debug("Failed to parse YAML frontmatter, falling back to empty frontmatter metadata.");
        }

        return new Frontmatter(true, attributes, end + 2);
    }

    private List<HeadingBoundary> detectHeadingBoundaries(List<SourceLine> lines, int lineNumberOffset) {
        List<HeadingBoundary> boundaries = new ArrayList<>();
        Deque<HeadingRef> stack = new ArrayDeque<>();

        boolean inFence = false;
        FenceState fenceState = null;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).content();
            String trimmed = raw.trim();

            FenceMarker marker = parseFenceMarker(trimmed);
            if (marker != null) {
                if (!inFence) {
                    FenceState candidateState = new FenceState(marker.markerChar(), marker.length());
                    if (hasFenceClose(lines, i, candidateState)) {
                        inFence = true;
                        fenceState = candidateState;
                    }
                    // Unmatched opening fence: skip this line without entering fence mode
                }
                else if (fenceState != null && fenceState.matches(marker)) {
                    inFence = false;
                    fenceState = null;
                }
                continue;
            }

            if (inFence) {
                continue;
            }

            AtxHeading atxHeading = parseAtxHeading(raw);
            if (atxHeading != null) {
                String normalizedTitle = normalizeHeadingText("#".repeat(atxHeading.level()) + " " + atxHeading.rawTitle());
                if (!normalizedTitle.isBlank()) {
                    while (!stack.isEmpty() && stack.peekLast().level() >= atxHeading.level()) {
                        stack.pollLast();
                    }
                    stack.addLast(new HeadingRef(atxHeading.level(), normalizedTitle));

                    int absoluteLine = lineNumberOffset + i;
                    boundaries.add(new HeadingBoundary(
                            absoluteLine,
                            absoluteLine,
                            atxHeading.level(),
                            normalizedTitle,
                            buildHeadingPath(stack)
                    ));
                }
                continue;
            }

            if (i + 1 < lines.size()) {
                SetextHeading setextHeading = parseSetextHeading(raw, lines.get(i + 1).content());
                if (setextHeading != null) {
                    String normalizedTitle = normalizeHeadingText(setextHeading.rawTitle() + "\n" + lines.get(i + 1).content());
                    if (!normalizedTitle.isBlank()) {
                        while (!stack.isEmpty() && stack.peekLast().level() >= setextHeading.level()) {
                            stack.pollLast();
                        }
                        stack.addLast(new HeadingRef(setextHeading.level(), normalizedTitle));

                        int absoluteStart = lineNumberOffset + i;
                        int absoluteUnderline = lineNumberOffset + i + 1;
                        boundaries.add(new HeadingBoundary(
                                absoluteStart,
                                absoluteUnderline,
                                setextHeading.level(),
                                normalizedTitle,
                                buildHeadingPath(stack)
                        ));
                    }
                    i++;
                }
            }
        }

        return boundaries;
    }

    private List<Section> buildSections(List<SourceLine> lines, List<HeadingBoundary> boundaries, int lineNumberOffset) {
        List<Section> sections = new ArrayList<>();

        if (boundaries.isEmpty()) {
            String content = trimOuterBlankLines(extractLines(lines, 1, lines.size()));
            if (!content.isBlank()) {
                sections.add(new Section(
                        DEFAULT_SECTION_TITLE,
                        0,
                        DEFAULT_SECTION_TITLE,
                        content,
                        lineNumberOffset,
                        lineNumberOffset + lines.size() - 1,
                        1,
                        List.of(DEFAULT_SECTION_TITLE)
                ));
            }
            return sections;
        }

        HeadingBoundary first = boundaries.get(0);
        int relativeFirstHeadingLine = first.headingStartLine() - lineNumberOffset + 1;

        if (relativeFirstHeadingLine > 1) {
            String preamble = trimOuterBlankLines(extractLines(lines, 1, relativeFirstHeadingLine - 1));
            if (!preamble.isBlank()) {
                sections.add(new Section(
                        DEFAULT_SECTION_TITLE,
                        0,
                        DEFAULT_SECTION_TITLE,
                        preamble,
                        lineNumberOffset,
                        first.headingStartLine() - 1,
                        1,
                        List.of(DEFAULT_SECTION_TITLE)
                ));
            }
        }

        for (int i = 0; i < boundaries.size(); i++) {
            HeadingBoundary current = boundaries.get(i);

            int absoluteStartLine = includeHeadingInChunk
                    ? current.headingStartLine()
                    : current.contentStartLine() + 1;

            int absoluteEndLine = (i + 1 < boundaries.size())
                    ? boundaries.get(i + 1).headingStartLine() - 1
                    : lineNumberOffset + lines.size() - 1;

            int relativeStart = absoluteStartLine - lineNumberOffset + 1;
            int relativeEnd = absoluteEndLine - lineNumberOffset + 1;

            String content = trimOuterBlankLines(extractLines(lines, relativeStart, relativeEnd));
            if (content.isBlank()) {
                continue;
            }

            sections.add(new Section(
                    current.title(),
                    current.level(),
                    current.path(),
                    content,
                    absoluteStartLine,
                    absoluteEndLine,
                    1,
                    List.of(current.title())
            ));
        }

        return sections;
    }

    private List<ChunkPart> splitSectionIntoChunkParts(String text) {
        if (text == null || text.isBlank()) {
            return List.of(new ChunkPart("", List.of()));
        }

        if (text.length() <= maxChunkChars) {
            return List.of(new ChunkPart(text, detectBlockTypes(text)));
        }

        List<Block> blocks = detectBlocks(SourceLine.from(text));
        List<ChunkPart> chunks = new ArrayList<>();

        List<Block> currentBlocks = new ArrayList<>();
        int currentLength = 0;

        for (Block block : blocks) {
            int blockLength = block.text().length();

            if (!currentBlocks.isEmpty() && currentLength + blockLength > maxChunkChars) {
                ChunkPart flushed = toChunkPart(currentBlocks);
                if (flushed.text().length() < minChunkChars && !chunks.isEmpty()) {
                    ChunkPart previous = chunks.remove(chunks.size() - 1);
                    chunks.add(mergeChunkParts(previous, flushed));
                }
                else {
                    chunks.add(flushed);
                }
                currentBlocks = new ArrayList<>();
                currentLength = 0;
            }

            currentBlocks.add(block);
            currentLength += blockLength;
        }

        if (!currentBlocks.isEmpty()) {
            ChunkPart flushed = toChunkPart(currentBlocks);
            if (flushed.text().length() < minChunkChars && !chunks.isEmpty()) {
                ChunkPart previous = chunks.remove(chunks.size() - 1);
                chunks.add(mergeChunkParts(previous, flushed));
            }
            else {
                chunks.add(flushed);
            }
        }

        return chunks.isEmpty()
                ? List.of(new ChunkPart(trimOuterBlankLines(text), detectBlockTypes(text)))
                : chunks;
    }

    private ChunkPart mergeChunkParts(ChunkPart left, ChunkPart right) {
        String mergedText = trimOuterBlankLines(left.text() + "\n\n" + right.text());
        List<String> types = new ArrayList<>(left.blockTypes());
        for (String type : right.blockTypes()) {
            if (!types.contains(type)) {
                types.add(type);
            }
        }
        return new ChunkPart(mergedText, types);
    }

    private ChunkPart toChunkPart(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        List<String> types = new ArrayList<>();

        for (Block block : blocks) {
            sb.append(block.text());
            if (!types.contains(block.type().name())) {
                types.add(block.type().name());
            }
        }

        return new ChunkPart(trimOuterBlankLines(sb.toString()), types);
    }

    private List<String> detectBlockTypes(String text) {
        List<Block> blocks = detectBlocks(SourceLine.from(text));
        List<String> types = new ArrayList<>();
        for (Block block : blocks) {
            String type = block.type().name();
            if (!types.contains(type)) {
                types.add(type);
            }
        }
        return types;
    }

    private List<Block> detectBlocks(List<SourceLine> lines) {
        List<Block> blocks = new ArrayList<>();
        int i = 0;

        while (i < lines.size()) {
            String raw = lines.get(i).content();
            String trimmed = raw.trim();

            if (trimmed.isEmpty()) {
                int start = i;
                while (i < lines.size() && lines.get(i).content().trim().isEmpty()) {
                    i++;
                }
                blocks.add(new Block(BlockType.BLANK, extractLines(lines, start + 1, i)));
                continue;
            }

            FenceMarker fenceMarker = parseFenceMarker(trimmed);
            if (fenceMarker != null) {
                int start = i;
                i++;
                boolean foundClose = false;
                while (i < lines.size()) {
                    String nextTrimmed = lines.get(i).content().trim();
                    FenceMarker nextMarker = parseFenceMarker(nextTrimmed);
                    if (nextMarker != null
                            && nextMarker.markerChar() == fenceMarker.markerChar()
                            && nextMarker.length() >= fenceMarker.length()) {
                        i++;
                        foundClose = true;
                        break;
                    }
                    i++;
                }
                if (foundClose) {
                    blocks.add(new Block(BlockType.FENCED_CODE, extractLines(lines, start + 1, i)));
                } else {
                    // Unclosed fence: emit the opening line as a paragraph and reprocess remaining lines
                    blocks.add(new Block(BlockType.PARAGRAPH, extractLines(lines, start + 1, start + 1)));
                    i = start + 1;
                }
                continue;
            }

            if (isHtmlBlockStart(trimmed)) {
                int start = i;
                i++;
                while (i < lines.size()) {
                    String currentTrimmed = lines.get(i).content().trim();
                    if (currentTrimmed.isEmpty()) {
                        i++;
                        break;
                    }
                    if (isHtmlBlockEnd(currentTrimmed) || isStandaloneHtmlLine(currentTrimmed)) {
                        i++;
                        break;
                    }
                    i++;
                }
                blocks.add(new Block(BlockType.HTML_BLOCK, extractLines(lines, start + 1, i)));
                continue;
            }

            if (isSetextHeading(lines, i)) {
                blocks.add(new Block(BlockType.HEADING, extractLines(lines, i + 1, i + 2)));
                i += 2;
                continue;
            }

            if (looksLikeAtxHeading(raw)) {
                blocks.add(new Block(BlockType.HEADING, extractLines(lines, i + 1, i + 1)));
                i++;
                continue;
            }

            if (isTableStart(lines, i)) {
                int start = i;
                i += 2;
                while (i < lines.size() && looksLikeTableRow(lines.get(i).content().trim())) {
                    i++;
                }
                blocks.add(new Block(BlockType.TABLE, extractLines(lines, start + 1, i)));
                continue;
            }

            if (looksLikeListItem(trimmed)) {
                int start = i;
                i++;
                while (i < lines.size()) {
                    String currentRaw = lines.get(i).content();
                    String currentTrimmed = currentRaw.trim();

                    if (currentTrimmed.isEmpty()) {
                        if (i + 1 < lines.size() && (looksLikeListItem(lines.get(i + 1).content().trim())
                                || isIndentedContinuation(lines.get(i + 1).content()))) {
                            i++;
                            continue;
                        }
                        break;
                    }

                    if (looksLikeListItem(currentTrimmed) || isIndentedContinuation(currentRaw)) {
                        i++;
                    }
                    else {
                        break;
                    }
                }
                blocks.add(new Block(BlockType.LIST, extractLines(lines, start + 1, i)));
                continue;
            }

            if (looksLikeBlockQuote(trimmed)) {
                int start = i;
                i++;
                while (i < lines.size()) {
                    String currentTrimmed = lines.get(i).content().trim();
                    if (currentTrimmed.isEmpty()) {
                        if (i + 1 < lines.size() && looksLikeBlockQuote(lines.get(i + 1).content().trim())) {
                            i++;
                            continue;
                        }
                        break;
                    }

                    if (looksLikeBlockQuote(currentTrimmed)) {
                        i++;
                    }
                    else {
                        break;
                    }
                }
                blocks.add(new Block(BlockType.BLOCK_QUOTE, extractLines(lines, start + 1, i)));
                continue;
            }

            int start = i;
            i++;
            while (i < lines.size()) {
                String currentRaw = lines.get(i).content();
                String currentTrimmed = currentRaw.trim();

                if (currentTrimmed.isEmpty()
                        || parseFenceMarker(currentTrimmed) != null
                        || isHtmlBlockStart(currentTrimmed)
                        || isSetextHeading(lines, i)
                        || looksLikeAtxHeading(currentRaw)
                        || isTableStart(lines, i)
                        || looksLikeListItem(currentTrimmed)
                        || looksLikeBlockQuote(currentTrimmed)) {
                    break;
                }
                i++;
            }
            blocks.add(new Block(BlockType.PARAGRAPH, extractLines(lines, start + 1, i)));
        }

        return blocks;
    }

    private boolean isSetextHeading(List<SourceLine> lines, int index) {
        if (index + 1 >= lines.size()) {
            return false;
        }

        String title = lines.get(index).content().trim();
        String underline = lines.get(index + 1).content().trim();

        return !title.isBlank() && (underline.matches("=+") || underline.matches("-+"));
    }

    private boolean isIndentedContinuation(String rawLine) {
        return rawLine.startsWith("    ") || rawLine.startsWith("\t");
    }

    private boolean isTableStart(List<SourceLine> lines, int index) {
        if (index + 1 >= lines.size()) {
            return false;
        }

        String first = lines.get(index).content().trim();
        String second = lines.get(index + 1).content().trim();

        return looksLikeTableRow(first) && looksLikeTableSeparator(second);
    }

    private boolean looksLikeTableSeparator(String trimmedLine) {
        return trimmedLine.matches("\\|?\\s*:?-{3,}:?(\\s*\\|\\s*:?-{3,}:?)*\\|?");
    }

    private boolean looksLikeAtxHeading(String rawLine) {
        return parseAtxHeading(rawLine) != null;
    }

    private boolean looksLikeTableRow(String trimmedLine) {
        return trimmedLine.startsWith("|") || trimmedLine.matches(".+\\|.+");
    }

    private boolean looksLikeListItem(String trimmedLine) {
        return trimmedLine.startsWith("- ")
                || trimmedLine.startsWith("* ")
                || trimmedLine.startsWith("+ ")
                || trimmedLine.matches("\\d+\\.\\s+.*");
    }

    private boolean looksLikeBlockQuote(String trimmedLine) {
        return trimmedLine.startsWith(">");
    }

    private boolean isHtmlBlockStart(String trimmedLine) {
        return trimmedLine.startsWith("<")
                && !trimmedLine.startsWith("<!--")
                && trimmedLine.matches("</?[A-Za-z][^>]*>|<([A-Za-z][A-Za-z0-9-]*)(\\s+[^>]*)?>.*");
    }

    private boolean isHtmlBlockEnd(String trimmedLine) {
        return trimmedLine.matches("</[A-Za-z][A-Za-z0-9-]*>");
    }

    private boolean isStandaloneHtmlLine(String trimmedLine) {
        return trimmedLine.matches("<[A-Za-z][A-Za-z0-9-]*[^>]*\\s*/?>")
                || trimmedLine.matches("</[A-Za-z][A-Za-z0-9-]*>");
    }

    private AtxHeading parseAtxHeading(String line) {
        if (line == null) {
            return null;
        }

        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }

        int start = i;
        while (i < line.length() && line.charAt(i) == '#') {
            i++;
        }

        int level = i - start;
        if (level < 1 || level > 6) {
            return null;
        }

        if (i >= line.length() || !Character.isWhitespace(line.charAt(i))) {
            return null;
        }

        String title = stripClosingHashes(line.substring(i).trim());
        if (title.isBlank()) {
            return null;
        }

        return new AtxHeading(level, title);
    }

    private SetextHeading parseSetextHeading(String line, String nextLine) {
        if (line == null || nextLine == null) {
            return null;
        }

        String title = line.trim();
        String underline = nextLine.trim();

        if (title.isBlank()) {
            return null;
        }

        if (underline.matches("=+")) {
            return new SetextHeading(1, title);
        }
        if (underline.matches("-+")) {
            return new SetextHeading(2, title);
        }

        return null;
    }

    private FenceMarker parseFenceMarker(String trimmedLine) {
        if (trimmedLine == null || trimmedLine.length() < 3) {
            return null;
        }

        if (trimmedLine.startsWith("```")) {
            return new FenceMarker('`', countLeading(trimmedLine, '`'));
        }
        if (trimmedLine.startsWith("~~~")) {
            return new FenceMarker('~', countLeading(trimmedLine, '~'));
        }
        return null;
    }

    private int countLeading(String value, char c) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == c) {
            count++;
        }
        return count;
    }

    private boolean containsBalancedFence(String text) {
        List<SourceLine> lines = SourceLine.from(normalizeLineEndings(text));
        boolean inFence = false;
        FenceState state = null;

        for (SourceLine line : lines) {
            FenceMarker marker = parseFenceMarker(line.content().trim());
            if (marker == null) {
                continue;
            }

            if (!inFence) {
                inFence = true;
                state = new FenceState(marker.markerChar(), marker.length());
            }
            else if (state != null && state.matches(marker)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasFenceClose(List<SourceLine> lines, int openIndex, FenceState openState) {
        for (int j = openIndex + 1; j < lines.size(); j++) {
            FenceMarker marker = parseFenceMarker(lines.get(j).content().trim());
            if (marker != null && openState.matches(marker)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeHeadingText(String markdownHeading) {
        if (markdownHeading == null || markdownHeading.isBlank()) {
            return "";
        }

        Node root = parser.parse(markdownHeading);
        Node first = root.getFirstChild();
        if (first == null) {
            return markdownHeading.trim();
        }

        StringBuilder sb = new StringBuilder();
        collectText(first, sb);

        String normalized = sb.toString()
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        return normalized.isBlank() ? markdownHeading.trim() : normalized;
    }

    private void collectText(Node node, StringBuilder sb) {
        if (node instanceof Text text) {
            sb.append(text.getLiteral());
        }
        else if (node instanceof Code code) {
            sb.append(code.getLiteral());
        }
        else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            sb.append(' ');
        }

        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            collectText(child, sb);
        }
    }

    private String buildHeadingPath(Deque<HeadingRef> stack) {
        if (stack.isEmpty()) {
            return DEFAULT_SECTION_TITLE;
        }

        StringBuilder sb = new StringBuilder();
        for (HeadingRef heading : stack) {
            if (!sb.isEmpty()) {
                sb.append(" > ");
            }
            sb.append(heading.title());
        }
        return sb.toString();
    }

    private void applyHierarchyMetadata(Map<String, Object> metadata, String headingPath) {
        String[] parts = headingPath.split("\\s*>\\s*");
        for (int i = 0; i < parts.length; i++) {
            metadata.put(META_HEADER_PREFIX + (i + 1), parts[i]);
        }
    }

    private String extractLines(List<SourceLine> lines, int startLine, int endLine) {
        if (lines.isEmpty() || startLine > endLine) {
            return "";
        }

        int safeStart = Math.max(1, startLine);
        int safeEnd = Math.min(lines.size(), endLine);

        StringBuilder sb = new StringBuilder();
        for (int i = safeStart; i <= safeEnd; i++) {
            SourceLine line = lines.get(i - 1);
            sb.append(line.content());
            if (i < safeEnd || line.hadNewline()) {
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    private String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String trimOuterBlankLines(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String result = text.replaceFirst("^\\s*\n", "");
        result = result.replaceFirst("\\s+$", "");
        return result;
    }

    private String stripClosingHashes(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        while (end > 0 && value.charAt(end - 1) == '#') {
            end--;
        }
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end).trim();
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private String githubSlugify(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();

        return normalized
                .replaceAll("[^\\p{Alnum}\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-");
    }

    public static final class Builder {
        private Parser parser;
        private Yaml yaml;
        private boolean includeHeadingInChunk = true;
        private boolean preservePreamble = true;
        private boolean includeHierarchyMetadata = true;
        private boolean splitLargeSections = true;
        private boolean extractFrontmatter = true;
        private boolean includeFrontmatterMetadata = true;
        private boolean mergeSmallSections = true;
        private int maxChunkChars = 4000;
        private int minChunkChars = 250;
        private int minSectionCharsToKeepStandalone = 200;

        private Builder() {
        }

        public Builder parser(Parser parser) {
            this.parser = parser;
            return this;
        }

        public Builder yaml(Yaml yaml) {
            this.yaml = yaml;
            return this;
        }

        public Builder includeHeadingInChunk(boolean includeHeadingInChunk) {
            this.includeHeadingInChunk = includeHeadingInChunk;
            return this;
        }

        public Builder preservePreamble(boolean preservePreamble) {
            this.preservePreamble = preservePreamble;
            return this;
        }

        public Builder includeHierarchyMetadata(boolean includeHierarchyMetadata) {
            this.includeHierarchyMetadata = includeHierarchyMetadata;
            return this;
        }

        public Builder splitLargeSections(boolean splitLargeSections) {
            this.splitLargeSections = splitLargeSections;
            return this;
        }

        public Builder extractFrontmatter(boolean extractFrontmatter) {
            this.extractFrontmatter = extractFrontmatter;
            return this;
        }

        public Builder includeFrontmatterMetadata(boolean includeFrontmatterMetadata) {
            this.includeFrontmatterMetadata = includeFrontmatterMetadata;
            return this;
        }

        public Builder mergeSmallSections(boolean mergeSmallSections) {
            this.mergeSmallSections = mergeSmallSections;
            return this;
        }

        public Builder maxChunkChars(int maxChunkChars) {
            this.maxChunkChars = Math.max(1, maxChunkChars);
            return this;
        }

        public Builder minChunkChars(int minChunkChars) {
            this.minChunkChars = Math.max(0, minChunkChars);
            return this;
        }

        public Builder minSectionCharsToKeepStandalone(int minSectionCharsToKeepStandalone) {
            this.minSectionCharsToKeepStandalone = Math.max(0, minSectionCharsToKeepStandalone);
            return this;
        }

        public MarkdownTextSplitter build() {
            if (minChunkChars > maxChunkChars) {
                throw new IllegalArgumentException("minChunkChars must not be greater than maxChunkChars");
            }
            return new MarkdownTextSplitter(this);
        }
    }

    private record Chunk(String text, Map<String, Object> metadata) {
    }

    private record ChunkPart(String text, List<String> blockTypes) {
    }

    private record SourceLine(String content, boolean hadNewline) {
        static List<SourceLine> from(String text) {
            List<SourceLine> result = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                return result;
            }

            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    result.add(new SourceLine(text.substring(start, i), true));
                    start = i + 1;
                }
            }

            if (start < text.length()) {
                result.add(new SourceLine(text.substring(start), false));
            }
            else if (text.endsWith("\n")) {
                result.add(new SourceLine("", false));
            }

            return result;
        }
    }

    private record HeadingBoundary(
            int headingStartLine,
            int contentStartLine,
            int level,
            String title,
            String path
    ) {
    }

    private record Section(
            String title,
            int headingLevel,
            String headingPath,
            String content,
            int startLine,
            int endLine,
            int mergedSections,
            List<String> mergedSectionTitles
    ) {
        private Section {
            Objects.requireNonNull(title);
            Objects.requireNonNull(headingPath);
            Objects.requireNonNull(content);
            Objects.requireNonNull(mergedSectionTitles);
        }

        Section mergeWith(Section other) {
            String mergedContent = this.content + "\n\n" + other.content;
            String mergedTitle = this.title.equals(other.title) ? this.title : this.title + " + " + other.title;
            String mergedPath = this.headingPath.equals(other.headingPath)
                    ? this.headingPath
                    : this.headingPath + " + " + other.headingPath;

            List<String> mergedTitles = new ArrayList<>(this.mergedSectionTitles);
            mergedTitles.addAll(other.mergedSectionTitles);

            int mergedLevel;
            if (this.headingLevel == 0) {
                mergedLevel = other.headingLevel;
            }
            else if (other.headingLevel == 0) {
                mergedLevel = this.headingLevel;
            }
            else {
                mergedLevel = Math.min(this.headingLevel, other.headingLevel);
            }

            return new Section(
                    mergedTitle,
                    mergedLevel,
                    mergedPath,
                    mergedContent,
                    Math.min(this.startLine, other.startLine),
                    Math.max(this.endLine, other.endLine),
                    this.mergedSections + other.mergedSections,
                    mergedTitles
            );
        }
    }

    private record HeadingRef(int level, String title) {
        private HeadingRef {
            Objects.requireNonNull(title);
        }
    }

    private record AtxHeading(int level, String rawTitle) {
    }

    private record SetextHeading(int level, String rawTitle) {
    }

    private record FenceMarker(char markerChar, int length) {
    }

    private record FenceState(char markerChar, int length) {
        boolean matches(FenceMarker marker) {
            return marker != null
                    && marker.markerChar == this.markerChar
                    && marker.length >= this.length;
        }
    }

    private record Frontmatter(boolean present, Map<String, Object> attributes, int contentStartLine) {
        static Frontmatter none() {
            return new Frontmatter(false, Collections.emptyMap(), 1);
        }
    }

    private record Block(BlockType type, String text) {
    }

    private enum BlockType {
        HEADING,
        PARAGRAPH,
        LIST,
        BLOCK_QUOTE,
        TABLE,
        FENCED_CODE,
        HTML_BLOCK,
        BLANK,
        OTHER
    }
}