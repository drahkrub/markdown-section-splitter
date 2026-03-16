package org.springframework.ai.reader.markdown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class MarkdownSectionTransformerTest implements WithAssertions {

    @Test
    void testBasicSplitting() {
        String text =
                """
                # Title
                ## Section 1
                section 1
                ## Section 2
                section 2
                ### Section 2.1
                section 2.1
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(4);

        // Section 1: "# Title" (heading-only section with heading text in content)
        checkSection(source, sections.get(0),
                "Title", 1, "markdown_section",
                "Title", null, null, null, null, null,
                "Title",
                "# Title");

        // Section 2: "## Section 1" + body
        checkSection(source, sections.get(1),
                "Section 1", 2, "markdown_section",
                "Title", "Section 1", null, null, null, null,
                "Title > Section 1",
                "## Section 1\nsection 1");

        // Section 3: "## Section 2" + body
        checkSection(source, sections.get(2),
                "Section 2", 2, "markdown_section",
                "Title", "Section 2", null, null, null, null,
                "Title > Section 2",
                "## Section 2\nsection 2");

        // Section 4: "### Section 2.1" + body
        checkSection(source, sections.get(3),
                "Section 2.1", 3, "markdown_section",
                "Title", "Section 2", "Section 2.1", null, null, null,
                "Title > Section 2 > Section 2.1",
                "### Section 2.1\nsection 2.1");
    }

    @Test
    void testPreambleEmitted() {
        String text =
                """
                Intro text
                ## Section 1
                section 1
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(2);

        // Preamble: level 0, kind = "markdown_preamble"
        assertThat(sections.get(0).getText().trim()).isEqualTo("Intro text");
        assertThat(sections.get(0).getMetadata().get("section_level")).isEqualTo(0);
        assertThat(sections.get(0).getMetadata().get("chunk_kind")).isEqualTo("markdown_preamble");
        assertThat(sections.get(0).getMetadata()).doesNotContainKey("section_title");
        assertThat(sections.get(0).getMetadata()).doesNotContainKey("header_path");

        // Section 1
        checkSection(source, sections.get(1),
                "Section 1", 2, "markdown_section",
                null, "Section 1", null, null, null, null,
                "Section 1",
                "## Section 1\nsection 1");
    }

    @Test
    void testPreambleSuppressed() {
        String text =
                """
                Intro text
                ## Section 1
                section 1
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer(null, true, false);
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        // With emitPreamble=false, the preamble should be dropped
        assertThat(sections).hasSize(1);
        checkSection(source, sections.get(1 - 1),
                "Section 1", 2, "markdown_section",
                null, "Section 1", null, null, null, null,
                "Section 1",
                "## Section 1\nsection 1");
    }

    @Test
    void testHeaderInFencedCodeBlock() {
        String text =
                """
                # Title
                ## Section 1
                section 1
                ```
                # In Code
                ## Also In Code
                ```
                ## Section 2
                section 2
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(3);

        checkSection(source, sections.get(0),
                "Title", 1, "markdown_section",
                "Title", null, null, null, null, null,
                "Title",
                "# Title");

        // Section 1 includes the code block with headings inside
        assertThat(sections.get(1).getText().trim())
                .isEqualTo("## Section 1\nsection 1\n```\n# In Code\n## Also In Code\n```");

        checkSection(source, sections.get(2),
                "Section 2", 2, "markdown_section",
                "Title", "Section 2", null, null, null, null,
                "Title > Section 2",
                "## Section 2\nsection 2");
    }

    @Test
    void testTildeFencedCodeBlock() {
        String text =
                """
                # Title
                ~~~
                # Not a heading
                ~~~
                After code
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getText().trim())
                .isEqualTo("# Title\n~~~\n# Not a heading\n~~~\nAfter code");
    }

    @Test
    void testFenceCloseRequiresMatchingMarker() {
        // Closing fence must use same character and at least same length as opening
        String text =
                """
                # Title
                ````
                # Not a heading
                ```
                # Still not a heading
                ````
                ## Section 2
                content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        // The ``` inside ```` should NOT close the fence
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getText()).contains("# Not a heading");
        assertThat(sections.get(0).getText()).contains("# Still not a heading");
    }

    @Test
    void testSetextHeaders() {
        String text =
                """
                Title
                =====
                intro

                Section 1
                ----
                section 1
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(2);

        // Setext H1
        assertThat(sections.get(0).getMetadata().get("section_title")).isEqualTo("Title");
        assertThat(sections.get(0).getMetadata().get("section_level")).isEqualTo(1);
        assertThat(sections.get(0).getText().trim()).isEqualTo("Title\n=====\nintro");

        // Setext H2
        assertThat(sections.get(1).getMetadata().get("section_title")).isEqualTo("Section 1");
        assertThat(sections.get(1).getMetadata().get("section_level")).isEqualTo(2);
        assertThat(sections.get(1).getText().trim()).isEqualTo("Section 1\n----\nsection 1");
    }

    @Test
    void testCodeSpanPreserved() {
        String text =
                """
                # Title
                section 1 is `the best` ever
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getText().trim())
                .isEqualTo("# Title\nsection 1 is `the best` ever");
    }

    @Test
    void testFencedCodeBlockPreserved() {
        String text =
                """
                # Title
                Some text
                ```
                    function(){
                       this.i++;
                \t}
                ```
                More text""";

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        // Unlike MarkdownSectionSplitter, the Transformer preserves content exactly as-is
        assertThat(sections.get(0).getText().trim()).isEqualTo(
                "# Title\nSome text\n```\n    function(){\n       this.i++;\n\t}\n```\nMore text");
    }

    @Test
    void testParagraphs() {
        String text =
                """
                # Title
                Paragraph 1

                Paragraph2


                Paragraph3""";

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        // Unlike MarkdownSectionSplitter which normalizes multiple blank lines,
        // the Transformer preserves the raw markdown including multiple blank lines
        assertThat(sections.get(0).getText().trim()).isEqualTo(
                "# Title\nParagraph 1\n\nParagraph2\n\n\nParagraph3");
    }

    @Test
    void testEmphasisPreserved() {
        // The Transformer preserves raw markdown, unlike MarkdownSectionSplitter which
        // normalizes '__' to '**'
        String text =
                """
                # Title
                The *quick* brown _fox_ jumped **over** the __lazy__ dog""";

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getText().trim()).isEqualTo(
                "# Title\nThe *quick* brown _fox_ jumped **over** the __lazy__ dog");
    }

    @Test
    void testBulletListPreserved() {
        String text =
                """
                # Title
                intro
                - One
                - Two `test` two

                After text

                * First
                * Second""";

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getText().trim()).isEqualTo(
                "# Title\nintro\n- One\n- Two `test` two\n\nAfter text\n\n* First\n* Second");
    }

    @Test
    void testOrderedListPreserved() {
        String text =
                """
                # Title
                intro
                1. One
                2. Two

                After text""";

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getText().trim()).isEqualTo(
                "# Title\nintro\n1. One\n2. Two\n\nAfter text");
    }

    @Test
    void testBlockQuotes() {
        String text =
                """
                # Title
                intro
                > line1
                >
                >line2
                line3

                Other text""";

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        // The Transformer preserves raw text exactly as-is (no rendering normalization)
        assertThat(sections.get(0).getText().trim()).isEqualTo(
                "# Title\nintro\n> line1\n>\n>line2\nline3\n\nOther text");
    }

    @Test
    void testHeadingInsideBlockquoteIgnored() {
        // Headings inside blockquotes start with '>' so the ATX regex doesn't match them.
        // This is correct behavior.
        String text =
                """
                # Title
                intro

                > ## Heading in blockquote

                outro
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        // The heading inside the blockquote should NOT create a section boundary
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getText()).contains("> ## Heading in blockquote");
    }

    @Test
    void testImagesPreserved() {
        // Unlike MarkdownSectionSplitter which removes images, the Transformer preserves them
        String text =
                """
                # Title

                intro
                ![link](/uri)
                outro
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getText()).contains("![link](/uri)");
    }

    @Test
    void testLinksPreserved() {
        String text =
                """
                # Title

                intro [A Link](https://a.com "testA").""";

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getText().trim())
                .isEqualTo("# Title\n\nintro [A Link](https://a.com \"testA\").");
    }

    @Test
    void testTablesPreserved() {
        String text =
                """
                # Title

                intro

                | H1 | H2 |
                |----|----|
                | 1  | 2  |
                | 3  | 4  |

                outro""";

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        // Tables are preserved exactly as-is (unlike MarkdownSectionSplitter which
        // normalizes them through CommonMark rendering)
        assertThat(sections.get(0).getText()).contains("| H1 | H2 |");
        assertThat(sections.get(0).getText()).contains("| 1  | 2  |");
    }

    @Test
    void testYamlFrontMatterIncludedInContent() {
        // Unlike MarkdownSectionSplitter which extracts YAML front matter via CommonMark extension,
        // the Transformer includes it as part of the preamble content.
        String text =
                """
                ---
                hello: world
                empty:
                ---
                # Title

                intro
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        // YAML front matter becomes a preamble section
        assertThat(sections).hasSize(2);

        // Preamble contains the YAML front matter
        assertThat(sections.get(0).getMetadata().get("section_level")).isEqualTo(0);
        assertThat(sections.get(0).getMetadata().get("chunk_kind")).isEqualTo("markdown_preamble");
        assertThat(sections.get(0).getText()).contains("hello: world");

        // Title section
        assertThat(sections.get(1).getMetadata().get("section_title")).isEqualTo("Title");
        assertThat(sections.get(1).getText().trim()).contains("intro");
    }

    @Test
    void testYamlFrontMatterWithNoPreambleEmit() {
        String text =
                """
                ---
                hello: world
                ---
                # Title

                intro
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer(null, true, false);
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        // With emitPreamble=false, the YAML front matter preamble is dropped
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getMetadata().get("section_title")).isEqualTo("Title");
    }

    @Test
    void testBOM() {
        String text =
                """
                \uFEFF# Title

                ## Section 1

                section 1
                ## Section 2

                section 2
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(3);

        // BOM should be stripped; first heading should parse correctly
        assertThat(sections.get(0).getMetadata().get("section_title")).isEqualTo("Title");
        assertThat(sections.get(0).getMetadata().get("section_level")).isEqualTo(1);
    }

    @Test
    void testHeaderHierarchyMetadata() {
        String text =
                """
                # H1
                ## H2
                ### H3
                #### H4
                content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(4);

        // H4 section should have full hierarchy
        Document h4Section = sections.get(3);
        assertThat(h4Section.getMetadata().get("h1")).isEqualTo("H1");
        assertThat(h4Section.getMetadata().get("h2")).isEqualTo("H2");
        assertThat(h4Section.getMetadata().get("h3")).isEqualTo("H3");
        assertThat(h4Section.getMetadata().get("h4")).isEqualTo("H4");
        assertThat(h4Section.getMetadata()).doesNotContainKey("h5");
        assertThat(h4Section.getMetadata()).doesNotContainKey("h6");
        assertThat(h4Section.getMetadata().get("header_path")).isEqualTo("H1 > H2 > H3 > H4");
    }

    @Test
    void testHeaderStackResetsOnHigherLevel() {
        // When a new H2 appears, H3+ from the previous H2 should be cleared
        String text =
                """
                # Title
                ## Section A
                ### Sub A
                ## Section B
                content B
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(4);

        // "Sub A" should have H1, H2, H3
        Document subA = sections.get(2);
        assertThat(subA.getMetadata().get("h1")).isEqualTo("Title");
        assertThat(subA.getMetadata().get("h2")).isEqualTo("Section A");
        assertThat(subA.getMetadata().get("h3")).isEqualTo("Sub A");

        // "Section B" should have H1, H2 only (H3 was cleared by the new H2)
        Document sectionB = sections.get(3);
        assertThat(sectionB.getMetadata().get("h1")).isEqualTo("Title");
        assertThat(sectionB.getMetadata().get("h2")).isEqualTo("Section B");
        assertThat(sectionB.getMetadata()).doesNotContainKey("h3");
        assertThat(sectionB.getMetadata().get("header_path")).isEqualTo("Title > Section B");
    }

    @Test
    void testSectionIndexMetadata() {
        String text =
                """
                # Title
                ## Section 1
                section 1
                ## Section 2
                section 2
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).getMetadata().get("section_index")).isEqualTo(1);
        assertThat(sections.get(1).getMetadata().get("section_index")).isEqualTo(2);
        assertThat(sections.get(2).getMetadata().get("section_index")).isEqualTo(3);
    }

    @Test
    void testParentDocumentIdMetadata() {
        String text =
                """
                # Title
                content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getMetadata().get("parent_document_id")).isEqualTo(source.getId());
    }

    @Test
    void testDocumentIdFormat() {
        String text =
                """
                # Title
                ## Section 1
                content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getId()).isEqualTo(source.getId() + "#sec-1");
        assertThat(sections.get(1).getId()).isEqualTo(source.getId() + "#sec-2");
    }

    @Test
    void testOriginalMetadataPreserved() {
        String text =
                """
                # Title
                content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        // All original metadata from the source document should be preserved
        assertThat(sections.get(0).getMetadata().get("doc-a")).isEqualTo("DOC-A");
        assertThat(sections.get(0).getMetadata().get("doc-b")).isEqualTo("DOC-B");
    }

    @Test
    void testNullDocumentSkipped() {
        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        List<Document> input = new ArrayList<>();
        input.add(null);
        List<Document> sections = transformer.apply(input);

        assertThat(sections).isEmpty();
    }

    @Test
    void testBlankDocumentPassedThrough() {
        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document blank = new Document("   ", Map.of());
        List<Document> sections = transformer.apply(List.of(blank));

        // Blank documents are passed through unchanged
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0)).isSameAs(blank);
    }

    @Test
    void testEmptyListInput() {
        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        List<Document> sections = transformer.apply(List.of());

        assertThat(sections).isEmpty();
    }

    @Test
    void testIncludeHeadingLineInChunkFalse() {
        String text =
                """
                # Title
                ## Section 1
                section 1 content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer(null, false, true);
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        // With includeHeadingLineInChunk=false, heading lines are not in the content
        // The "# Title" section has no body, so it would be blank and skipped
        // The "## Section 1" section has just "section 1 content"
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getText().trim()).isEqualTo("section 1 content");
        assertThat(sections.get(0).getMetadata().get("section_title")).isEqualTo("Section 1");
    }

    @Test
    void testEmptySectionsSkipped() {
        // Unlike MarkdownSectionSplitter which has a placeholder for empty sections,
        // the Transformer silently drops sections with blank content
        String text =
                """
                # Title
                ## Section 1
                ## Section 2
                section 2
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        // All sections should be present because includeHeadingLineInChunk=true (default)
        // means even "empty" sections contain their heading line
        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).getText().trim()).isEqualTo("# Title");
        assertThat(sections.get(1).getText().trim()).isEqualTo("## Section 1");
        assertThat(sections.get(2).getText().trim()).isEqualTo("## Section 2\nsection 2");
    }

    @Test
    void testEmptySectionsSkippedWhenHeadingExcluded() {
        // When heading line is excluded from chunk, truly empty sections are skipped
        String text =
                """
                # Title
                ## Section 1
                ## Section 2
                section 2
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer(null, false, true);
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        // "# Title" and "## Section 1" have no body content and are skipped
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getMetadata().get("section_title")).isEqualTo("Section 2");
    }

    @Test
    void testHtmlInHeadersPreservedAsRaw() {
        // The Transformer preserves raw text including HTML tags in headers.
        // Unlike MarkdownSectionSplitter which uses a visitor to extract clean header text,
        // the Transformer uses regex and keeps the raw header text.
        String text =
                """
                # Using <div> Elements

                intro
                ## Section 1: <span>

                span content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(2);

        // Header text includes HTML tags (same as MarkdownSectionSplitter)
        assertThat(sections.get(0).getMetadata().get("section_title")).isEqualTo("Using <div> Elements");
        assertThat(sections.get(1).getMetadata().get("section_title")).isEqualTo("Section 1: <span>");
    }

    @Test
    void testEmphasisInHeaderText() {
        // The Transformer does NOT strip markdown formatting from header text
        // unlike MarkdownSectionSplitter which uses a HeaderVisitor.
        // This means emphasis markers remain in the section_title metadata.
        String text =
                """
                # Title
                ## **Bold Header**
                content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(2);

        // The Transformer's regex captures the raw text including ** markers
        assertThat(sections.get(1).getMetadata().get("section_title")).isEqualTo("**Bold Header**");
    }

    @Test
    void testMultipleDocuments() {
        String text1 =
                """
                # Doc 1
                content 1
                """;
        String text2 =
                """
                # Doc 2
                content 2
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source1 = createDocument(text1);
        Document source2 = createDocument(text2);
        List<Document> sections = transformer.apply(List.of(source1, source2));

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getMetadata().get("section_title")).isEqualTo("Doc 1");
        assertThat(sections.get(1).getMetadata().get("section_title")).isEqualTo("Doc 2");
    }

    @Test
    void testNoHeadingsAtAll() {
        String text = "Just some plain text without any headings.";

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getMetadata().get("section_level")).isEqualTo(0);
        assertThat(sections.get(0).getMetadata().get("chunk_kind")).isEqualTo("markdown_preamble");
        assertThat(sections.get(0).getText().trim()).isEqualTo("Just some plain text without any headings.");
    }

    @Test
    void testTrailingHashesStripped() {
        // ATX headings can have trailing hashes: "# Title #"
        String text =
                """
                # Title #
                ## Section 1 ##
                content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getMetadata().get("section_title")).isEqualTo("Title");
        assertThat(sections.get(1).getMetadata().get("section_title")).isEqualTo("Section 1");
    }

    @Test
    void testLineNumberMetadata() {
        String text =
                """
                # Title
                ## Section 1
                section 1 content
                ## Section 2
                section 2 content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        assertThat(sections).hasSize(3);

        // Check that start_line and end_line metadata are set
        assertThat(sections.get(0).getMetadata()).containsKey("start_line");
        assertThat(sections.get(0).getMetadata()).containsKey("end_line");
        assertThat(sections.get(1).getMetadata()).containsKey("start_line");
        assertThat(sections.get(1).getMetadata()).containsKey("end_line");
    }

    // ===== Known limitation tests =====
    // These tests document behavioral differences from MarkdownSectionSplitter

    @Test
    void testHeadingInsideListItemWithIndentation() {
        // KNOWN LIMITATION: The Transformer's regex-based approach can incorrectly detect
        // headings that are indented 0-3 spaces inside list items, because it has no concept
        // of block nesting. The ATX regex allows 0-3 spaces of indentation.
        //
        // MarkdownSectionSplitter handles this correctly by checking AST parent nodes.
        String text =
                """
                # Section 1

                Introduction

                - List item 1

                  ## Heading in list item

                  Content under heading in list

                - List item 2

                ## Section 2

                Section 2 content
                """;

        MarkdownSectionTransformer transformer = new MarkdownSectionTransformer();
        Document source = createDocument(text);
        List<Document> sections = transformer.apply(List.of(source));

        // The Transformer INCORRECTLY creates a section for the heading inside the list item
        // because "  ## Heading in list item" matches the ATX regex (2 spaces is within 0-3 range).
        // MarkdownSectionSplitter would correctly produce only 2 sections.
        assertThat(sections.size()).isGreaterThan(2);
    }

    // ===== Helper methods =====

    private Document createDocument(String text) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc-a", "DOC-A");
        metadata.put("doc-b", "DOC-B");
        return new Document(text, metadata);
    }

    /**
     * Checks a section produced by the Transformer for expected metadata and content.
     */
    private void checkSection(
            Document source,
            Document section,
            String sectionTitle,
            int sectionLevel,
            String chunkKind,
            String h1, String h2, String h3, String h4, String h5, String h6,
            String headerPath,
            String expectedContent) {

        if (sectionTitle != null) {
            assertThat(section.getMetadata().get("section_title")).isEqualTo(sectionTitle);
        } else {
            assertThat(section.getMetadata()).doesNotContainKey("section_title");
        }

        assertThat(section.getMetadata().get("section_level")).isEqualTo(sectionLevel);
        assertThat(section.getMetadata().get("chunk_kind")).isEqualTo(chunkKind);

        if (headerPath != null && !headerPath.isBlank()) {
            assertThat(section.getMetadata().get("header_path")).isEqualTo(headerPath);
        }

        checkHeaderLevel(section, "h1", h1);
        checkHeaderLevel(section, "h2", h2);
        checkHeaderLevel(section, "h3", h3);
        checkHeaderLevel(section, "h4", h4);
        checkHeaderLevel(section, "h5", h5);
        checkHeaderLevel(section, "h6", h6);

        assertThat(section.getText().trim()).isEqualTo(expectedContent);

        // Verify source metadata is preserved
        for (String key : source.getMetadata().keySet()) {
            assertThat(section.getMetadata().get(key)).isEqualTo(source.getMetadata().get(key));
        }
    }

    private void checkHeaderLevel(Document section, String key, String expected) {
        if (expected != null) {
            assertThat(section.getMetadata().get(key)).isEqualTo(expected);
        } else {
            assertThat(section.getMetadata()).doesNotContainKey(key);
        }
    }
}
