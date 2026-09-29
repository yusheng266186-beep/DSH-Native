package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 模型、凭据与 settings.yaml 的纯逻辑；不依赖 Android。 */
final class ModelConfig {
    static final String COMMAND_CODE = "commandcode";
    static final String DEEPSEEK = "deepseek-official";

    private static final Set<String> EFFORTS = new LinkedHashSet<String>();
    static {
        Collections.addAll(EFFORTS, "off", "low", "medium", "high", "xhigh", "max");
    }

    private ModelConfig() { }

    static final class Selection {
        final String provider;
        final String model;
        final String effort;

        Selection(String provider, String model, String effort) {
            this.provider = normalizeProvider(provider);
            this.model = normalizeModel(model);
            this.effort = normalizeEffort(effort);
        }

        boolean valid() {
            return provider.length() > 0 && model.length() > 0 && effort.length() > 0;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Selection)) return false;
            Selection s = (Selection) other;
            return provider.equals(s.provider) && model.equals(s.model) && effort.equals(s.effort);
        }

        @Override public int hashCode() {
            return provider.hashCode() * 31 * 31 + model.hashCode() * 31 + effort.hashCode();
        }
    }

    static final class Model {
        final String id;
        final String name;
        final boolean image;
        final boolean reasoning;

        Model(String id, String name, boolean image, boolean reasoning) {
            this.id = id;
            this.name = name == null || name.length() == 0 ? id : name;
            this.image = image;
            this.reasoning = reasoning;
        }
    }

    static String normalizeProvider(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (COMMAND_CODE.equals(value) || DEEPSEEK.equals(value)) return value;
        return "";
    }

    static String normalizeModel(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.length() == 0 || value.length() > 200) return "";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127 || c == '\n' || c == '\r') return "";
        }
        return value;
    }

    static String normalizeEffort(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return EFFORTS.contains(value) ? value : "medium";
    }

    static String providerName(String provider, boolean english) {
        if (DEEPSEEK.equals(provider)) return english ? "DeepSeek direct" : "DeepSeek 官方";
        return "Command Code";
    }

    static Selection readSelection(String yaml) {
        String block = topLevelBlock(yaml, "agent-default-model");
        if (block == null) return new Selection(COMMAND_CODE, "", "medium");
        return new Selection(scalar(block, "provider"), scalar(block, "model"),
                scalar(block, "reasoningEffort"));
    }

    static String updateSelection(String yaml, Selection selection) {
        if (selection == null || !selection.valid()) {
            throw new IllegalArgumentException("invalid model selection");
        }
        String source = yaml == null ? "" : yaml;
        String block = "agent-default-model:\n"
                + "  provider: " + selection.provider + "\n"
                + "  model: " + quote(selection.model) + "\n"
                + "  reasoningEffort: " + selection.effort + "\n";
        return replaceTopLevelBlock(source, "agent-default-model", block);
    }

    static List<Model> modelsForProvider(String yaml, String provider) {
        if (DEEPSEEK.equals(normalizeProvider(provider))) {
            String direct = topLevelBlock(yaml, "llm-deepseek-api-key");
            if (direct == null) direct = topLevelBlock(yaml, "llm-deepseek");
            List<Model> configured = ModelCatalogSync.readModels(direct);
            if (!configured.isEmpty()) return configured;
            List<Model> out = new ArrayList<Model>();
            out.add(new Model("deepseek-v4-flash", "DeepSeek V4 Flash", true, true));
            out.add(new Model("deepseek-v4-pro", "DeepSeek V4 Pro", true, true));
            return out;
        }
        String llm = topLevelBlock(yaml, "llm-pi-ai");
        if (llm == null) return new ArrayList<Model>();
        String[] lines = llm.split("\n", -1);
        int providerStart = -1;
        int providerIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            int indent = indent(lines[i]);
            if (trimmed.equals(COMMAND_CODE + ":") && indent >= 4) {
                providerStart = i + 1;
                providerIndent = indent;
                break;
            }
        }
        List<Model> result = new ArrayList<Model>();
        if (providerStart < 0) return result;
        for (int i = providerStart; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int in = indent(line);
            if (trimmed.length() > 0 && in <= providerIndent) break;
            if (!trimmed.startsWith("- id:")) continue;
            String id = unquote(trimmed.substring(5).trim());
            if (normalizeModel(id).length() == 0) continue;
            int itemIndent = in;
            String name = id;
            boolean image = false;
            boolean reasoning = false;
            int j = i + 1;
            for (; j < lines.length; j++) {
                String next = lines[j];
                String nt = next.trim();
                int ni = indent(next);
                if (nt.length() > 0 && ni <= providerIndent) break;
                if (ni == itemIndent && nt.startsWith("- id:")) break;
                if (nt.startsWith("name:")) name = unquote(nt.substring(5).trim());
                if (nt.startsWith("input:") && nt.toLowerCase(Locale.ROOT).contains("image")) image = true;
                if (nt.startsWith("reasoningEfforts:")) reasoning = !nt.endsWith("false");
            }
            result.add(new Model(id, name, image, reasoning));
            i = j - 1;
        }
        return result;
    }

    static boolean containsModel(String yaml, String provider, String model) {
        for (Model item : modelsForProvider(yaml, provider)) {
            if (item.id.equals(model)) return true;
        }
        return false;
    }

    static String readCredentialRef(String yaml, String key) {
        if (!validCredentialKey(key) || yaml == null) return "";
        boolean refs = false;
        for (String line : yaml.split("\n", -1)) {
            if (line.matches("^refs\\s*:.*")) { refs = true; continue; }
            if (!refs) continue;
            if (line.length() > 0 && !Character.isWhitespace(line.charAt(0))) break;
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                return unquote(trimmed.substring(key.length() + 1).trim());
            }
        }
        return "";
    }

    static String updateCredentialRef(String yaml, String key, String value) {
        if (!validCredentialKey(key)) throw new IllegalArgumentException("invalid credential key");
        String safe = normalizeCredential(value);
        String source = yaml == null || yaml.length() == 0 ? "version: 1\n" : yaml;
        Pattern existing = Pattern.compile("(?m)^(\\s*" + Pattern.quote(key) + "\\s*:).*$");
        Matcher match = existing.matcher(source);
        String replacement = "$1 " + Matcher.quoteReplacement(quote(safe));
        if (match.find()) return match.replaceFirst(replacement);
        Matcher refs = Pattern.compile("(?m)^refs\\s*:.*$").matcher(source);
        if (refs.find()) {
            return source.substring(0, refs.end()) + "\n  " + key + ": " + quote(safe)
                    + source.substring(refs.end());
        }
        if (!source.endsWith("\n")) source += "\n";
        return source + "refs:\n  " + key + ": " + quote(safe) + "\n";
    }

    static String credentialKey(String provider) {
        return DEEPSEEK.equals(provider) ? "DEEPSEEK_API_KEY" : "COMMANDCODE_API_KEY";
    }

    private static boolean validCredentialKey(String key) {
        return "COMMANDCODE_API_KEY".equals(key) || "DEEPSEEK_API_KEY".equals(key);
    }

    private static String normalizeCredential(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > 4096) throw new IllegalArgumentException("credential too long");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127) throw new IllegalArgumentException("credential contains control characters");
        }
        return value;
    }

    private static String scalar(String yaml, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
                + "\\s*:\\s*(.+?)\\s*$").matcher(yaml == null ? "" : yaml);
        return m.find() ? unquote(m.group(1).trim()) : "";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String out = value.trim();
        if (out.length() >= 2 && ((out.charAt(0) == '"' && out.charAt(out.length() - 1) == '"')
                || (out.charAt(0) == '\'' && out.charAt(out.length() - 1) == '\''))) {
            out = out.substring(1, out.length() - 1);
        }
        return out.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    private static String topLevelBlock(String yaml, String key) {
        if (yaml == null) return null;
        String[] lines = yaml.split("\n", -1);
        int start = -1;
        int end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t'
                    || line.charAt(0) == '#') continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String found = line.substring(0, colon).trim();
            if (start < 0) {
                if (key.equals(found)) start = i;
            } else {
                end = i;
                break;
            }
        }
        if (start < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int i = start; i < end; i++) out.append(lines[i]).append('\n');
        return out.toString();
    }

    private static String replaceTopLevelBlock(String yaml, String key, String replacement) {
        String old = topLevelBlock(yaml, key);
        if (old != null) {
            String matched = old;
            int at = yaml.indexOf(matched);
            if (at < 0 && matched.endsWith("\n")) {
                matched = matched.substring(0, matched.length() - 1);
                at = yaml.indexOf(matched);
            }
            if (at >= 0) {
                return yaml.substring(0, at) + replacement + yaml.substring(at + matched.length());
            }
        }
        if (yaml.length() == 0) return replacement;
        return yaml + (yaml.endsWith("\n") ? "" : "\n") + replacement;
    }
}
