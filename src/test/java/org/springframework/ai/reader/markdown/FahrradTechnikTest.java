package org.springframework.ai.reader.markdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class FahrradTechnikTest implements WithAssertions {

    private static final ObjectWriter OW = (new ObjectMapper()).writerWithDefaultPrettyPrinter();

    @Disabled
    @Test
    void splitFahrradTechnikOhneSubSplitter() throws IOException {

        String inputFile = "/home/ego/Downloads/22917_8.pdf.simple_merge.md";

        Path trash = Path.of("./trash");
        Files.createDirectories(trash);

        Document document = new Document(
                Files.readString(Path.of(inputFile))
        );
        DocumentTransformer splitter = MarkdownSectionTransformer.builder().build();
        List<Document> chunks = splitter.apply(List.of(document));
        for (int i = chunks.size(); --i >= 0;) {
            final Document chunk = chunks.get(i);
            Path file = trash.resolve("chunk_%03d.txt".formatted(i));
            Files.writeString(file, chunk.getText());
            file = trash.resolve("meta_%03d.txt".formatted(i));
            Files.writeString(file, OW.writeValueAsString(chunk.getMetadata()));
        }
    }
}
