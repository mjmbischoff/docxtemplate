package com.controplex.docxgenerator;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class Row {
    private Map<String,String> columns = new HashMap<>();

    public Map<String, String> getColumns() {
        return columns;
    }

    public Map<String, String> getColumn() {
        return columns;
    }
    public void setColumns(Map<String, String> columns) {
        this.columns = new HashMap<>(columns);
    }

    @Override
    public String toString() {
        return "\"row\": {%s}".formatted(columns.entrySet().stream()
                .map(entry -> "{ \"column\": \"" + entry.getKey() + "\" , \"value\": \"" + entry.getValue() + "\" }")
                .collect(Collectors.joining(", ")));
    }

    public String column(String key) {
        return columns.get(key);
    }

    public void setColumn(String name, String value) {
        columns.put(name, value);
    }

    public boolean isBlank() {
        return columns.isEmpty();
    }
}
