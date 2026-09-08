package com.schwab.common.json;

import com.schwab.testlib.Test;

import java.util.List;
import java.util.Map;

import static com.schwab.testlib.Assert.assertEquals;
import static com.schwab.testlib.Assert.assertTrue;

public class JsonTest {

    @Test
    public void roundTripsSimpleObject() {
        Map<String, Object> obj = new java.util.LinkedHashMap<>();
        obj.put("name", "schwab");
        obj.put("count", 42.0);
        obj.put("active", true);
        obj.put("nothing", null);

        String json = Json.write(obj);
        Map<String, Object> parsed = Json.parseObject(json);

        assertEquals("schwab", parsed.get("name"), "string round-trip");
        assertEquals(42.0, parsed.get("count"), "number round-trip");
        assertEquals(true, parsed.get("active"), "boolean round-trip");
        assertTrue(parsed.containsKey("nothing"), "null value key should be present");
        assertEquals(null, parsed.get("nothing"), "null round-trip");
    }

    @Test
    public void escapesSpecialCharactersInStrings() {
        String json = Json.write(Map.of("text", "line1\nline2\t\"quoted\""));
        Map<String, Object> parsed = Json.parseObject(json);
        assertEquals("line1\nline2\t\"quoted\"", parsed.get("text"), "escape sequences must round-trip");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void parsesNestedArraysAndObjects() {
        String json = "{\"items\":[{\"id\":1},{\"id\":2}],\"tags\":[\"a\",\"b\"]}";
        Map<String, Object> parsed = Json.parseObject(json);
        List<Object> items = (List<Object>) parsed.get("items");
        assertEquals(2, items.size(), "two items in nested array");
        Map<String, Object> first = (Map<String, Object>) items.get(0);
        assertEquals(1.0, first.get("id"), "nested object field");
    }

    @Test
    public void parsesUnicodeEscape() {
        Map<String, Object> parsed = Json.parseObject("{\"smiley\":\"\\u0041\"}");
        assertEquals("A", parsed.get("smiley"), "\\u0041 should decode to 'A'");
    }

    @Test
    public void rejectsMalformedJson() {
        com.schwab.testlib.Assert.assertThrows(Json.JsonParseException.class,
                () -> Json.parseObject("{not valid"), "malformed JSON should raise JsonParseException");
    }
}
