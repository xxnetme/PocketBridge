package com.local.wififiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.local.wififiles.ManagedFiles.*;

/** Preview plans are single-use and never authorize replacing existing entries. */
final class BatchRenames {
    private static final int MAX = 200;
    private static final long TTL = 5 * 60_000L;
    private static final Pattern TOKENS = Pattern.compile("\\{(name|ext|n(?::[1-6])?)\\}|[{}]");
    private final Path root;
    private final Map<String, Plan> plans = new LinkedHashMap<>();

    private static final class Row {
        String path, name, newName, error;
        Path source, target;
        BasicFileAttributes attrs;
        JSONObject json() throws Exception {
            return new JSONObject().put("path", path).put("name", name).put("newName", newName)
                    .put("error", error == null ? JSONObject.NULL : error);
        }
    }
    private static final class Plan {
        final List<Row> rows;
        final long expires = System.currentTimeMillis() + TTL;
        Plan(List<Row> rows) { this.rows = rows; }
    }
    BatchRenames(File root) { this.root = root.toPath(); }
    void clear() { synchronized (plans) { plans.clear(); } }

    JSONObject preview(JSONObject request) throws Exception {
        String template = request.getString("template");
        JSONArray paths = request.getJSONArray("paths");
        int start = request.has("start") ? request.getInt("start") : 1;
        if (paths.length() < 2 || paths.length() > MAX || start < 0 || start > 999_999 || template.isEmpty() || template.length() > 200) {
            throw error(400, "Выберите 2–200 объектов и задайте корректный шаблон");
        }
        replace(template, "a", ".txt", start);
        List<Row> rows = new ArrayList<>();
        Set<Path> sources = new HashSet<>();
        Map<String, Row> targets = new HashMap<>();
        Map<Path, Set<String>> occupied = new HashMap<>();
        for (int i = 0; i < paths.length(); i++) {
            Row r = new Row();
            r.path = paths.getString(i);
            r.name = r.path.substring(r.path.lastIndexOf('/') + 1);
            rows.add(r);
            try {
                r.source = scoped(root, r.path);
                if (r.source.equals(root) || !sources.add(r.source)) throw error(400, "Повтор или недопустимый объект");
                r.attrs = attrs(r.source);
                if (!r.attrs.isRegularFile() && !r.attrs.isDirectory()) throw error(400, "Тип объекта не поддерживается");
                int split = r.attrs.isDirectory() ? -1 : r.name.lastIndexOf('.');
                String stem = split > 0 ? r.name.substring(0, split) : r.name;
                String ext = split > 0 ? r.name.substring(split) : "";
                r.newName = replace(template, stem, ext, start + i);
                if (!TrashStore.validName(r.newName)) throw error(400, "Недопустимое имя");
                Path parent = r.source.getParent();
                r.target = parent.resolve(r.newName);
                if (r.target.equals(r.source)) continue;
                if (!occupied.containsKey(parent)) occupied.put(parent, names(parent));
                if (occupied.get(parent).contains(FileServer.normalize(r.newName))) r.error = "Имя уже занято";
                String key = parent.toString() + '\0' + FileServer.normalize(r.newName);
                Row other = targets.putIfAbsent(key, r);
                if (other != null) { other.error = "Повтор нового имени"; r.error = other.error; }
            } catch (IOException e) { r.error = e.getMessage(); }
        }
        // Nested selections cannot be safely renamed from the same immutable preview.
        for (Row r : rows) {
            if (r.source == null || r.source.equals(root)) continue;
            for (Path p = r.source.getParent(); p != null && !p.equals(root); p = p.getParent()) {
                if (sources.contains(p)) { r.error = "Одновременно выбраны папка и её содержимое"; break; }
            }
        }
        boolean valid = rows.stream().noneMatch(r -> r.error != null);
        String id = null;
        if (valid) {
            id = UUID.randomUUID().toString();
            synchronized (plans) {
                plans.entrySet().removeIf(e -> e.getValue().expires <= System.currentTimeMillis());
                if (plans.size() >= 16) plans.remove(plans.keySet().iterator().next());
                plans.put(id, new Plan(rows));
            }
        }
        JSONArray entries = new JSONArray();
        for (Row r : rows) entries.put(r.json());
        return new JSONObject().put("items", entries).put("previewId", id == null ? JSONObject.NULL : id);
    }

    JSONObject apply(String id) throws Exception {
        Plan plan;
        synchronized (plans) { plan = plans.remove(id); }
        if (plan == null || plan.expires <= System.currentTimeMillis()) throw error(409, "Предпросмотр устарел. Создайте новый");
        JSONArray results = new JSONArray();
        int renamed = 0, failed = 0;
        synchronized (NAMESPACE) {
            Map<Path, Set<String>> occupied = new HashMap<>();
            for (Row r : plan.rows) {
                JSONObject result = r.json();
                try {
                    if (Thread.currentThread().isInterrupted()) throw error(503, "Доступ остановлен");
                    Path source = scoped(root, r.path);
                    if (!sameEntry(r.attrs, attrs(source))) throw error(409, "Исходный объект изменился");
                    if (r.target.equals(source)) {
                        result.put("status", "unchanged");
                    } else {
                        Path parent = source.getParent();
                        if (!occupied.containsKey(parent)) occupied.put(parent, names(parent));
                        String folded = FileServer.normalize(r.newName);
                        if (Files.exists(r.target, NOFOLLOW) || occupied.get(parent).contains(folded)) {
                            throw error(409, "Имя занято после предпросмотра");
                        }
                        movableTree(source);
                        Files.move(source, r.target);
                        occupied.get(parent).add(folded);
                        renamed++;
                        result.put("status", "renamed").put("newPath", virtual(root, r.target));
                    }
                } catch (IOException e) { failed++; result.put("status", "error").put("error", e.getMessage()); }
                results.put(result);
            }
        }
        return new JSONObject().put("items", results).put("renamed", renamed).put("failed", failed);
    }

    private static Set<String> names(Path dir) throws IOException {
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory(dir))) {
            int visited = 0;
            for (Path child : stream) {
                if (++visited > 100_000 || Thread.currentThread().isInterrupted()) throw error(413, "Слишком много имён в папке");
                names.add(FileServer.normalize(child.getFileName().toString()));
            }
        }
        return names;
    }

    private static String replace(String template, String stem, String ext, int index) throws IOException {
        Matcher match = TOKENS.matcher(template);
        StringBuffer out = new StringBuffer();
        while (match.find()) {
            String token = match.group(1);
            if (token == null) throw error(400, "Допустимы только {name}, {ext}, {n}, {n:1}…{n:6}");
            String value = token.equals("name") ? stem : token.equals("ext") ? ext
                    : token.equals("n") ? String.valueOf(index) : String.format(Locale.ROOT, "%0" + token.charAt(2) + "d", index);
            match.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        match.appendTail(out);
        return out.toString();
    }
}
