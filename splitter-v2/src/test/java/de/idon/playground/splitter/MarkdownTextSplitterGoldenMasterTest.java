package de.idon.playground.splitter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownTextSplitterGoldenMasterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "basic",
            "duplicate-headings",
            "frontmatter",
            "block-mix"
    })
    void goldenMaster(String fixtureName) throws Exception {
        String markdown = readResource("/markdown-splitter/" + fixtureName + ".md");
        String expectedJson = readResource("/markdown-splitter/" + fixtureName + ".expected.json");

        MarkdownTextSplitter splitter = MarkdownTextSplitter.builder()
                .mergeSmallSections(false)
                .build();

        List<Document> actualDocuments = splitter.split(
                Document.builder()
                        .id("fixture-doc")
                        .text(markdown)
                        .metadata(Map.of("source", fixtureName + ".md"))
                        .build()
        );

        List<Map<String, Object>> actual = actualDocuments.stream()
                .map(this::normalizeDocument)
                .toList();

        List<Map<String, Object>> expected = objectMapper.readValue(
                expectedJson,
                new TypeReference<>() {
                }
        );

        assertEquals(expected, actual);
    }

    private Map<String, Object> normalizeDocument(Document doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", doc.getText());

        Map<String, Object> metadata = new LinkedHashMap<>();
        copyIfPresent(doc, metadata, "source");
        copyIfPresent(doc, metadata, "parent_document_id");
        copyIfPresent(doc, metadata, "chunk_index");
        copyIfPresent(doc, metadata, "total_chunks");
        copyIfPresent(doc, metadata, "section_title");
        copyIfPresent(doc, metadata, "heading_level");
        copyIfPresent(doc, metadata, "heading_path");
        copyIfPresent(doc, metadata, "section_index");
        copyIfPresent(doc, metadata, "total_chunks_in_section");
        copyIfPresent(doc, metadata, "char_count");
        copyIfPresent(doc, metadata, "word_count");
        copyIfPresent(doc, metadata, "has_code_block");
        copyIfPresent(doc, metadata, "has_frontmatter");
        copyIfPresent(doc, metadata, "frontmatter");
        copyIfPresent(doc, metadata, "source_line_start");
        copyIfPresent(doc, metadata, "source_line_end");
        copyIfPresent(doc, metadata, "anchor_slug");
        copyIfPresent(doc, metadata, "header_h1");
        copyIfPresent(doc, metadata, "header_h2");
        copyIfPresent(doc, metadata, "header_h3");
        copyIfPresent(doc, metadata, "merged_sections");
        copyIfPresent(doc, metadata, "merged_section_titles");
        copyIfPresent(doc, metadata, "block_types");

        out.put("metadata", metadata);
        return out;
    }

    private void copyIfPresent(Document doc, Map<String, Object> target, String key) {
        Object value = doc.getMetadata().get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private String readResource(String path) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}