/*
 * Copyright (c) 2026 TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.internal.export;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the {@code {name}} placeholders of the HTML and SVG export templates.
 * This is an internal API and not part of the public contract.
 */
public final class ExportTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

    private ExportTemplate() {
    }

    /**
     * Replaces the placeholders of the template in a single pass, so the inserted values are
     * never searched for placeholders: exported text that reads {@code {background}} stays
     * as written. Placeholders without a value and other braces, such as CSS rules, are kept.
     *
     * @param template       the template with {@code {name}} placeholders
     * @param namesAndValues placeholder names, each followed by its value
     * @return the filled template
     * @throws IllegalArgumentException if a name has no value
     */
    public static String fill(String template, String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholder without a value: " + namesAndValues[namesAndValues.length - 1]);
        }
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        StringBuilder out = new StringBuilder(template.length());
        Matcher matcher = PLACEHOLDER.matcher(template);
        int copied = 0;
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value != null) {
                out.append(template, copied, matcher.start()).append(value);
                copied = matcher.end();
            }
        }
        return out.append(template, copied, template.length()).toString();
    }
}
