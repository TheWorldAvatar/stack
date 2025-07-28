package com.cmclinnovations.stack;

import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.cmclinnovations.stack.clients.core.StackHost;
import com.cmclinnovations.stack.clients.utils.JsonHelper;
import com.fasterxml.jackson.databind.ObjectMapper;

public class StackHostTest {
    ObjectMapper objectMapper = JsonHelper.getMapper();

    @Test
    void testEmpty() {
        StackHost stackHost = new StackHost();
        Assertions.assertAll(
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getProto()),
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getName()),
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getPort()),
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getPath()));
    }

    @Test
    void testEmptyJson() {
        StackHost stackHost = Assertions
                .assertDoesNotThrow(() -> objectMapper.readValue("{}", StackHost.class));
        Assertions.assertAll(
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getProto()),
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getName()),
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getPort()),
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getPath()));
    }

    @Test
    void testNameJson() {
        StackHost stackHost = Assertions
                .assertDoesNotThrow(() -> objectMapper.readValue("{\"name\":\"host\"}", StackHost.class));
        Assertions.assertAll(
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getProto()),
                () -> Assertions.assertEquals(Optional.of("host"), stackHost.getName()),
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getPort()),
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getPath()),
                () -> Assertions.assertEquals("host", stackHost.getStringBuilder().withName().build()));
    }

    @Test
    void testWithNameConstructor() {
        StackHost stackHost = new StackHost("myhost");
        Assertions.assertAll(
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getProto()),
                () -> Assertions.assertEquals(Optional.of("myhost"), stackHost.getName()),
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getPort()),
                () -> Assertions.assertEquals(Optional.empty(), stackHost.getPath()));
    }

    @Test
    void testStringBuilderWithDefaults() {
        StackHost stackHost = new StackHost("host");
        String url = stackHost.getStringBuilder()
                .withProto("http")
                .withName()
                .withPort("8080")
                .withPath("/api")
                .withFinalSlash()
                .build();
        Assertions.assertEquals("http://host:8080/api/", url);
    }

    @Test
    void testStringBuilderWithExtraPath() {
        StackHost stackHost = new StackHost("host");
        String url = stackHost.getStringBuilder()
                .withName()
                .withExtraPath("/extra/path/")
                .withFinalSlash()
                .build();
        Assertions.assertEquals("host/extra/path/", url);
    }

    @Test
    void testStringBuilderWithOnlyDefaults() {
        StackHost stackHost = new StackHost();
        String url = stackHost.getStringBuilder()
                .withProto("https")
                .withName("defaultHost")
                .withPort("443")
                .withPath("/default/")
                .build();
        Assertions.assertEquals("https://defaultHost:443/default", url);
    }

    @Test
    void testStringBuilderWithNoValues() {
        StackHost stackHost = new StackHost();
        String url = stackHost.getStringBuilder().build();
        Assertions.assertEquals("", url);
    }

    @Test
    void testStringBuilderWithMultipleSlashes() {
        StackHost stackHost = new StackHost("host");
        String url = stackHost.getStringBuilder()
                .withName()
                .withExtraPath("foo//bar/")
                .build();
        Assertions.assertFalse(url.contains("///"));
        Assertions.assertEquals("host/foo/bar", url);
    }
}
