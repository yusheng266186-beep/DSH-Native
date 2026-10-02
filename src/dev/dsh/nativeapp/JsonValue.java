package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JSON reader shared by JVM and Android. */
final class JsonValue {
    static Object parse(String source) {
        if (source == null || source.length() > 2 * 1024 * 1024)
            throw new IllegalArgumentException("JSON exceeds limit");
        return new JsonValue(source).parse();
    }
    final String source; int at;
    private JsonValue(String source) { this.source = source; }
    Object parse() {
        Object v = value(0); space();
        if (at != source.length()) throw new IllegalArgumentException("trailing JSON");
        return v;
    }
    void space() { while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++; }
    boolean take(char c) {
        space(); if (at < source.length() && source.charAt(at) == c) { at++; return true; } return false;
    }
    Object value(int depth) {
        space();
        if (depth > 32 || at >= source.length()) throw new IllegalArgumentException("invalid JSON");
        if (source.charAt(at) == '"') return string();
        if (take('{')) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            if (take('}')) return map;
            do {
                space(); String key = string();
                if (!take(':') || map.containsKey(key)) throw new IllegalArgumentException("invalid object");
                map.put(key, value(depth + 1));
            } while (take(','));
            if (!take('}')) throw new IllegalArgumentException("invalid object");
            return map;
        }
        if (take('[')) {
            List<Object> list = new ArrayList<Object>();
            if (take(']')) return list;
            do { list.add(value(depth + 1)); } while (take(','));
            if (!take(']')) throw new IllegalArgumentException("invalid array");
            return list;
        }
        for (String literal : new String[] {"true", "false", "null"}) {
            if (source.startsWith(literal, at)) {
                at += literal.length(); return "null".equals(literal) ? null : Boolean.valueOf(literal);
            }
        }
        int start = at;
        if (at < source.length() && source.charAt(at) == '-') at++;
        if (at >= source.length() || !Character.isDigit(source.charAt(at))) throw new IllegalArgumentException("invalid number");
        if (source.charAt(at) == '0') at++;
        else while (at < source.length() && Character.isDigit(source.charAt(at))) at++;
        if (at < source.length() && source.charAt(at) == '.') {
            at++; int digits = at;
            while (at < source.length() && Character.isDigit(source.charAt(at))) at++;
            if (digits == at) throw new IllegalArgumentException("invalid number");
        }
        if (at < source.length() && (source.charAt(at) == 'e' || source.charAt(at) == 'E')) {
            at++; if (at < source.length() && (source.charAt(at) == '+' || source.charAt(at) == '-')) at++;
            int digits = at;
            while (at < source.length() && Character.isDigit(source.charAt(at))) at++;
            if (digits == at) throw new IllegalArgumentException("invalid number");
        }
        try { return Double.valueOf(source.substring(start, at)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("invalid number"); }
    }
    String string() {
        if (at >= source.length() || source.charAt(at++) != '"') throw new IllegalArgumentException("invalid string");
        StringBuilder out = new StringBuilder();
        while (at < source.length()) {
            char c = source.charAt(at++);
            if (c == '"') return out.toString();
            if (c < 32) throw new IllegalArgumentException("invalid string");
            if (c != '\\') { out.append(c); continue; }
            if (at >= source.length()) break;
            char e = source.charAt(at++);
            if (e == '"' || e == '\\' || e == '/') out.append(e);
            else if (e == 'b') out.append('\b');
            else if (e == 'f') out.append('\f');
            else if (e == 'n') out.append('\n');
            else if (e == 'r') out.append('\r');
            else if (e == 't') out.append('\t');
            else if (e == 'u' && at + 4 <= source.length()) {
                try { out.append((char) Integer.parseInt(source.substring(at, at + 4), 16)); at += 4; }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("invalid escape"); }
            } else throw new IllegalArgumentException("invalid escape");
        }
        throw new IllegalArgumentException("unterminated string");
    }
}
