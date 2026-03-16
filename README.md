# Spring AI Markdown Document Reader

A [Spring AI](https://docs.spring.io/spring-ai/reference/) `DocumentTransformer` that splits Markdown documents into sections based on headings. Each section becomes a separate `Document` enriched with structural metadata -- heading text, level, parent heading, and sibling index -- making it ideal for chunking Markdown content in RAG (Retrieval-Augmented Generation) pipelines.

Inspired by the `MarkdownDocumentSplitter` from [LangChain4j](https://github.com/langchain4j/langchain4j).

## Features

- Splits Markdown into sections at heading boundaries (H1 through H6)
- Preserves the full heading hierarchy as metadata on each section
- Supports GFM tables, fenced/indented code blocks, block quotes, nested lists, and emphasis
- Parses YAML front matter via an optional consumer callback
- Strips images from output
- Strips UTF-8 BOM characters
- Headings inside code blocks, block quotes, or list items are **not** treated as section boundaries
- Optionally chains a secondary `DocumentTransformer` to further split sections (e.g. by token count)
- Builder-based configuration

## Requirements

- Java 17+
- Spring AI 1.1.x (`spring-ai-commons`)

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-markdown-document-reader</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Usage

### Basic splitting

```java
var transformer = MarkdownSectionTransformer.builder().build();

var source = new Document("""
        # Introduction
        Some introductory text.

        ## Getting Started
        Follow these steps to get started.

        ## API Reference
        Details about the API.
        """);

List<Document> sections = transformer.apply(List.of(source));
// Returns 3 Documents, one per section
```

### Inspecting metadata

Each output `Document` carries the following metadata keys:

| Constant | Metadata key | Description |
|---|---|---|
| `SECTION_LEVEL` | `md_section_level` | 0-based heading level (H1 = 0, H2 = 1, ...) |
| `SECTION_HEADER` | `md_section_header` | The heading text of this section |
| `SECTION_PARENT_HEADER` | `md_parent_header` | The heading text of the parent section |
| `SECTION_INDEX_WITHIN_PARENT` | `md_section_index_in_parent` | Zero-based index among siblings under the same parent |

```java
Document section = sections.get(1); // "Getting Started"
section.getMetadata().get("md_section_header");          // "Getting Started"
section.getMetadata().get("md_section_level");           // 1
section.getMetadata().get("md_parent_header");           // "Introduction"
section.getMetadata().get("md_section_index_in_parent"); // 0
```

### Setting a document title

When a document starts with text before the first heading, that text becomes a section with no header. Use `setDocumentTitle` to give it a name:

```java
var transformer = MarkdownSectionTransformer.builder()
        .setDocumentTitle("My Document")
        .build();
```

### Chaining a secondary splitter

You can further split each section with another `DocumentTransformer` -- useful for enforcing a maximum chunk size:

```java
var transformer = MarkdownSectionTransformer.builder()
        .setSectionSplitter(myTokenSplitter)
        .build();
```

### Consuming YAML front matter

```java
var transformer = MarkdownSectionTransformer.builder()
        .setYamlFrontMatterConsumer(frontMatter -> {
            // frontMatter is Map<String, List<String>>
            System.out.println(frontMatter);
        })
        .build();
```

### Empty section placeholder

Sections that contain only a heading and no body text receive a placeholder. The default is `"."`. To customize:

```java
var transformer = MarkdownSectionTransformer.builder()
        .setEmptySectionPlaceholderText("[empty]")
        .build();
```

## How it works

1. The input Markdown is parsed into an AST using [CommonMark](https://commonmark.org/).
2. A custom `MarkdownRenderer` walks the AST. When it encounters a top-level heading, it finalizes the previous section and starts a new one.
3. Headings nested inside block quotes or list items do **not** create section boundaries.
4. Each section is emitted as a `Document` with the section text as content and heading hierarchy as metadata.
5. Original metadata from the input `Document` is preserved on every output section.

## Building

```sh
./mvnw clean verify
```

## License

See [LICENSE](LICENSE) for details.
