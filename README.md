# Splitter Playground

Multi-module Maven project with two Markdown chunking strategies for [Spring AI](https://docs.spring.io/spring-ai/reference/) RAG pipelines.

**Requirements:** Java 17+, Spring AI 1.1.x  
**Package:** `de.idon.playground.splitter`

## Building

```sh
./mvnw clean verify
```

---

## splitter-v1 — `MarkdownSectionTransformer`

A `DocumentTransformer` that splits Markdown at heading boundaries (H1–H6). Each heading becomes one `Document`. Inspired by [LangChain4j's MarkdownDocumentSplitter](https://github.com/langchain4j/langchain4j).

```xml
<dependency>
    <groupId>de.idon.playground</groupId>
    <artifactId>splitter-v1</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

**Key features:**
- One `Document` per heading section, with structural metadata
- Headings inside code blocks, block quotes, or list items are not treated as boundaries
- Optional YAML front matter consumer, secondary splitter, and empty-section placeholder
- Strips images and UTF-8 BOM

**Metadata keys:**

| Key | Description |
|---|---|
| `md_section_level` | 0-based heading level (H1 = 0) |
| `md_section_header` | Heading text |
| `md_parent_header` | Parent heading text |
| `md_section_index_in_parent` | Zero-based sibling index |

**Usage:**

```java
var transformer = MarkdownSectionTransformer.builder()
        .setDocumentTitle("Preamble")       // title for pre-heading text
        .setSectionSplitter(myTokenSplitter) // optional secondary split
        .setEmptySectionPlaceholderText(".") // default
        .setYamlFrontMatterConsumer(fm -> {})
        .build();

List<Document> sections = transformer.apply(List.of(source));
```

---

## splitter-v2 — `MarkdownTextSplitter`

A `TextSplitter` subclass using a hybrid strategy: line-based scanning for source-preserving extraction, CommonMark AST for heading normalization, and a block model to avoid splitting tables, lists, or code fences mid-structure.

```xml
<dependency>
    <groupId>de.idon.playground</groupId>
    <artifactId>splitter-v2</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

**Key features:**
- Size-bounded chunks (`maxChunkChars`, default 4000; `minChunkChars`, default 250)
- Merges small adjacent sections to avoid undersized chunks
- Splits oversized sections at block boundaries (tables, fences, lists, block quotes)
- Extracts and optionally exposes YAML front matter as metadata
- GitHub-style anchor slugs with deduplication
- Full heading hierarchy metadata (`header_h1` … `header_h6`)

**Metadata keys (selection):**

| Key | Description |
|---|---|
| `section_title` | Heading text of the chunk's section |
| `heading_level` | ATX heading level (1–6; 0 = preamble) |
| `heading_path` | Full breadcrumb, e.g. `Intro > Setup` |
| `anchor_slug` | GitHub-style slug, deduplicated |
| `section_index` | 0-based section index in document |
| `chunk_index` | 0-based chunk index within the section |
| `char_count` / `word_count` | Chunk size metrics |
| `has_code_block` | `true` if chunk contains a fenced code block |
| `source_line_start/end` | Original line numbers in source |
| `merged_sections` | Number of merged sections (when merging) |

**Usage:**

```java
var splitter = MarkdownTextSplitter.builder()
        .maxChunkChars(4000)
        .minChunkChars(250)
        .minSectionCharsToKeepStandalone(200)
        .mergeSmallSections(true)
        .splitLargeSections(true)
        .includeHeadingInChunk(true)
        .includeHierarchyMetadata(true)
        .extractFrontmatter(true)
        .build();

List<Document> chunks = splitter.apply(List.of(source));
```

---

## License

See [LICENSE](LICENSE) for details.
