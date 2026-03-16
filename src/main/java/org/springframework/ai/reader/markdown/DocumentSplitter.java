package org.springframework.ai.reader.markdown;

import java.util.List;

import org.springframework.ai.document.Document;

/**
 * A functional interface for splitting a {@link Document} into a list of {@link Document} segments.
 */
@FunctionalInterface
public interface DocumentSplitter {

    List<Document> split(Document document);

}
