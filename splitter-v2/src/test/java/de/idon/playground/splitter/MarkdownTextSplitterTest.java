package de.idon.playground.splitter;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownTextSplitterTest {

    @Test
    void deduplicatesDuplicateHeadingSlugs() {
        String markdown = """
                # Intro
                
                A
                
                # Intro
                
                B
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        assertEquals("intro", docs.get(0).getMetadata().get("anchor_slug"));
        assertEquals("intro-1", docs.get(1).getMetadata().get("anchor_slug"));
    }

    @Test
    void keepsFencedCodeBlockTogetherWhenPossible() {
        String markdown = """
                # Code
                
                Intro
                
                ```java
                line1
                line2
                line3
                line4
                line5
                ```
                
                After code.
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .maxChunkChars(80)
                .minChunkChars(10)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        boolean codeChunkExists = docs.stream()
                .anyMatch(d -> d.getText().contains("```java") && d.getText().contains("line5"));
        assertTrue(codeChunkExists);
    }

    @Test
    void keepsTableTogetherWhenPossible() {
        String markdown = """
                # Table
                
                | A | B |
                |---|---|
                | 1 | 2 |
                | 3 | 4 |
                
                Following paragraph after table that should cause chunking.
                Following paragraph after table that should cause chunking.
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .maxChunkChars(100)
                .minChunkChars(10)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        boolean tableChunkExists = docs.stream()
                .anyMatch(d -> d.getText().contains("| A | B |") && d.getText().contains("| 3 | 4 |"));
        assertTrue(tableChunkExists);
    }

    @Test
    void keepsListTogetherWhenPossible() {
        String markdown = """
                # List
                
                - item 1
                - item 2
                  continuation
                - item 3
                
                Another paragraph that should force chunking.
                Another paragraph that should force chunking.
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .maxChunkChars(90)
                .minChunkChars(10)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        boolean listChunkExists = docs.stream()
                .anyMatch(d -> d.getText().contains("- item 1") && d.getText().contains("- item 3"));
        assertTrue(listChunkExists);
    }

    @Test
    void keepsBlockQuoteTogetherWhenPossible() {
        String markdown = """
                # Quote
                
                > line 1
                >
                > line 2
                > line 3
                
                Another paragraph that should force chunking.
                Another paragraph that should force chunking.
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .maxChunkChars(90)
                .minChunkChars(10)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        boolean quoteChunkExists = docs.stream()
                .anyMatch(d -> d.getText().contains("> line 1") && d.getText().contains("> line 3"));
        assertTrue(quoteChunkExists);
    }

    @Test
    void detectsHtmlBlocks() {
        String markdown = """
                # Html
                
                <div class="note">
                Hello
                </div>
                
                Paragraph
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        @SuppressWarnings("unchecked")
        List<String> blockTypes = (List<String>) docs.get(0).getMetadata().get("block_types");
        assertTrue(blockTypes.contains("HTML_BLOCK"));
    }

    @Test
    void supportsSetextHeadingsInBlocks() {
        String markdown = """
                Title
                =====
                
                Intro
                
                Subtitle
                --------
                
                Body
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        assertEquals(2, docs.size());
        assertEquals("Title", docs.get(0).getMetadata().get("section_title"));
        assertEquals("Subtitle", docs.get(1).getMetadata().get("section_title"));
    }

    @Test
    void stillParsesYamlFrontmatter() {
        String markdown = """
                ---
                title: Demo
                published: true
                tags:
                  - one
                  - two
                ---
                
                # Heading
                
                Text
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        assertEquals(Boolean.TRUE, docs.get(0).getMetadata().get("has_frontmatter"));
        assertTrue(docs.get(0).getMetadata().containsKey("frontmatter"));
    }

    @Test
    void canExcludeHeadingFromChunk() {
        String markdown = """
                # Heading
                
                Body
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .includeHeadingInChunk(false)
                .mergeSmallSections(false)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        assertFalse(docs.get(0).getText().contains("# Heading"));
        assertTrue(docs.get(0).getText().contains("Body"));
    }

    @Test
    void canMergeSmallSections() {
        String markdown = """
                # A
                
                short
                
                ## B
                
                tiny
                
                ## C
                
                this is a much longer section which should remain as useful chunk content
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(true)
                .minSectionCharsToKeepStandalone(40)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        assertTrue(docs.size() <= 2);
        assertTrue(docs.get(0).getMetadata().containsKey("merged_sections"));
    }

    @Test
    void keepsParentMetadata() {
        Document source = Document.builder()
                .id("doc-1")
                .text("""
                        # Heading
                        
                        Body
                        """)
                .metadata(Map.of("source", "README.md"))
                .build();

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .build();

        List<Document> docs = splitter.split(source);

        assertEquals("README.md", docs.get(0).getMetadata().get("source"));
        assertEquals("doc-1", docs.get(0).getMetadata().get("parent_document_id"));
    }

    @Test
    void unclosedFenceDoesNotPreventSubsequentHeadingDetection() {
        String markdown = """
                # Chapter 1
                
                Some text
                
                ```python
                unclosed code block
                
                # Chapter 2
                
                More text
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        assertTrue(docs.size() >= 2, "Expected at least 2 sections from two headings, got: " + docs.size());
        boolean hasChapter2 = docs.stream()
                .anyMatch(d -> "Chapter 2".equals(d.getMetadata().get("section_title")));
        assertTrue(hasChapter2, "Chapter 2 heading must be recognized even after an unclosed fence");
    }

    @Test
    void unclosedFenceDoesNotConsumeRestOfDocumentAsCodeBlock() {
        String markdown = """
                # Intro
                
                Paragraph before.
                
                ```
                unclosed
                
                # Later
                
                Paragraph after.
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        // "Paragraph after." must appear in some chunk and must NOT be inside a FENCED_CODE block
        boolean paragraphAfterExists = docs.stream()
                .anyMatch(d -> d.getText().contains("Paragraph after."));
        assertTrue(paragraphAfterExists, "Content after an unclosed fence must not be swallowed into a code block");

        // No single document should span the entire rest of the document as a code block
        boolean wholeTailIsCode = docs.stream()
                .anyMatch(d -> d.getText().contains("unclosed") && d.getText().contains("Paragraph after."));
        assertFalse(wholeTailIsCode, "The unclosed fence must not create a FENCED_CODE block spanning to EOF");
    }

    @Test
    void balancedFenceStillHandledAsCodeBlock() {
        String markdown = """
                # Section
                
                Before fence.
                
                ```java
                int x = 1;
                int y = 2;
                ```
                
                After fence.
                """;

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .build();

        List<Document> docs = splitter.split(new Document(markdown, Map.of()));

        // The balanced fence must still be recognized; code content appears in a chunk
        boolean hasCodeContent = docs.stream()
                .anyMatch(d -> d.getText().contains("```java") && d.getText().contains("int x = 1;"));
        assertTrue(hasCodeContent, "A properly balanced fenced code block must still be preserved in a chunk");
    }
}