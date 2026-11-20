package com.local.wififiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

import static com.local.wififiles.ManagedFiles.*;

/** One bounded I/O scan. Results are temporary, scoped to this listener, and contain no file bytes. */
final class FolderScans {
    private static final int MAX_FILES = 20_000, MAX_NODES = 100_000;
    private static final long MAX_BYTES = 128L << 30;
    private static final long HOLD_MS = 15 * 60_000L;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pocketbridge-folder-scan"); t.setDaemon(true); return t;
    });
    private final Path root;
    private final TrashStore trash;
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    private static final class Entry {
        final Path file;
        final String local;
        final BasicFileAttributes attrs;
        String sha;
        Entry(Path file, String local, BasicFileAttributes attrs) { this.file = file; this.local = local; this.attrs = attrs; }
        JSONObject row(String path) throws Exception { return new JSONObject().put("path", path).put("sha256", sha).put("size", attrs.size()); }
    }
    private static final class Job {
        final String id = UUID.randomUUID().toString(), mode;
        final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
        volatile String state = "running", phase = "walk", error;
        volatile long finished, bytes;
        volatile int checked, found, skipped;
        int visited;
        JSONObject result;
        Map<String, Entry> copies;
        boolean inWorker = true;
        volatile boolean cancelled;
        volatile Thread thread;
        Job(String mode) { this.mode = mode; }
    }

    FolderScans(File root, TrashStore trash) { this.root = root.toPath(); this.trash = trash; }

    synchronized JSONObject start(String mode, String path, String other) throws Exception {
        Path a = directory(scoped(root, path)), b = null;
        if ("compare".equals(mode)) b = directory(scoped(root, other));
        else if (!"manifest".equals(mode) && !"duplicates".equals(mode)) throw error(400, "Неизвестный режим проверки");
        if (a.equals(b)) throw error(400, "Выберите другую папку для сравнения");
        prune();
        for (Job job : jobs.values()) if (job.inWorker) throw error(429, "Дождитесь окончания текущей проверки");
        while (jobs.size() >= 2) jobs.remove(jobs.keySet().iterator().next());
        Job job = new Job(mode);
        jobs.put(job.id, job);
        Path dest = b;
        try { worker.execute(() -> run(job, a, dest)); }
        catch (RejectedExecutionException stopped) { jobs.remove(job.id); throw error(503, "Доступ остановлен"); }
        return status(job.id, false);
    }

    synchronized JSONObject status(String id, boolean withResult) throws Exception {
        Job j = require(id);
        JSONObject data = new JSONObject().put("id", id).put("mode", j.mode).put("state", j.state)
                .put("phase", j.phase).put("found", j.found).put("checked", j.checked).put("skipped", j.skipped).put("bytes", j.bytes);
        if (j.error != null) data.put("error", j.error);
        if (withResult && "done".equals(j.state)) data.put("result", j.result);
        return data;
    }

    synchronized void cancel(String id) throws Exception {
        Job j = require(id);
        if ("running".equals(j.state)) {
            j.cancelled = true;
            if (j.thread != null) j.thread.interrupt();
        }
    }

    synchronized void stop() {
        for (Job job : jobs.values()) job.cancelled = true;
        worker.shutdownNow();
        jobs.clear();
    }

    JSONObject trashDuplicate(String id, String path, String keep) throws Exception {
        Job job;
        synchronized (this) { job = require(id); }
        if (!"done".equals(job.state) || job.copies == null) throw error(409, "Сначала найдите дубликаты");
        Entry discard = job.copies.get(path), remaining = job.copies.get(keep);
        if (discard == null || remaining == null || discard == remaining || !discard.sha.equals(remaining.sha)) {
            throw error(400, "Выберите совпадающую копию, которую нужно оставить");
        }
        synchronized (NAMESPACE) {
            if (Thread.currentThread().isInterrupted()) throw error(503, "Доступ остановлен");
            checkEntry(discard);
            checkEntry(remaining);
            return trash.put(discard.file.toFile());
        }
    }

    private void prune() {
        long now = System.currentTimeMillis();
        jobs.entrySet().removeIf(e -> !e.getValue().inWorker && now - e.getValue().finished >= HOLD_MS);
    }

    private Job require(String id) throws IOException {
        prune();
        Job job = jobs.get(id);
        if (job == null) throw error(404, "Отчёт не найден или срок хранения истёк");
        return job;
    }

    private void run(Job job, Path a, Path b) {
        job.thread = Thread.currentThread();
        try {
            List<Entry> first = walk(job, a);
            List<Entry> second = b == null ? Collections.emptyList() : walk(job, b);
            job.phase = "hash";
            JSONObject result;
            if ("duplicates".equals(job.mode)) {
                result = duplicates(job, first);
            } else {
                for (Entry e : first) hash(job, e);
                for (Entry e : second) hash(job, e);
                if (b != null) result = compare(first, second);
                else {
                    JSONArray files = new JSONArray();
                    for (Entry e : first) files.put(e.row(e.local));
                    result = new JSONObject().put("format", "pocketbridge-sha256-v1").put("root", virtual(root, a)).put("files", files);
                }
            }
            alive(job);
            job.result = result;
            job.state = "done";
        } catch (Exception e) {
            job.error = job.cancelled ? null : e.getMessage() == null ? "Не удалось проверить папку" : e.getMessage();
            job.state = job.cancelled ? "cancelled" : "error";
        } finally {
            job.thread = null;
            synchronized (this) { job.finished = System.currentTimeMillis(); job.inWorker = false; }
        }
    }

    private List<Entry> walk(Job job, Path base) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Files.walkFileTree(directory(base), new SimpleFileVisitor<Path>() {
            private void visit() throws IOException {
                alive(job);
                if (++job.visited > MAX_NODES) throw error(413, "Слишком много объектов для проверки");
            }
            @Override public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) throws IOException {
                visit();
                if (!p.equals(base) && hiddenName(p.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                directory(p);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                visit();
                if (hiddenName(p.getFileName().toString())) return FileVisitResult.CONTINUE;
                if (!a.isRegularFile()) { job.skipped++; return FileVisitResult.CONTINUE; }
                if (++job.found > MAX_FILES) throw error(413, "Лимит проверки: 20 000 файлов");
                entries.add(new Entry(p, base.relativize(p).toString().replace(File.separatorChar, '/'), a));
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(e -> e.local));
        return entries;
    }

    private JSONObject duplicates(Job job, List<Entry> entries) throws Exception {
        Map<Long, List<Entry>> bySize = new TreeMap<>();
        for (Entry e : entries) bySize.computeIfAbsent(e.attrs.size(), ignored -> new ArrayList<>()).add(e);
        JSONArray groups = new JSONArray();
        Map<String, Entry> copies = new HashMap<>();
        for (Map.Entry<Long, List<Entry>> sameSize : bySize.entrySet()) {
            alive(job);
            if (sameSize.getValue().size() == 1) continue;
            Map<String, List<Entry>> hashed = new TreeMap<>();
            for (Entry e : sameSize.getValue()) {
                hash(job, e);
                hashed.computeIfAbsent(e.sha, ignored -> new ArrayList<>()).add(e);
            }
            for (List<Entry> group : hashed.values()) {
                if (group.size() < 2) continue;
                JSONArray paths = new JSONArray();
                for (Entry e : group) {
                    String p = virtual(root, e.file);
                    paths.put(p);
                    copies.put(p, e);
                }
                groups.put(new JSONObject().put("size", sameSize.getKey()).put("sha256", group.get(0).sha).put("paths", paths));
            }
        }
        job.copies = copies;
        return new JSONObject().put("groups", groups);
    }

    private static JSONObject compare(List<Entry> before, List<Entry> after) throws Exception {
        Map<String, Entry> remaining = new TreeMap<>();
        for (Entry e : after) remaining.put(e.local, e);
        JSONArray changes = new JSONArray();
        int same = 0;
        for (Entry a : before) {
            Entry b = remaining.remove(a.local);
            if (b == null) changes.put(change(a.local, "missing"));
            else if (!a.sha.equals(b.sha)) changes.put(change(a.local, "changed"));
            else same++;
        }
        for (String added : remaining.keySet()) changes.put(change(added, "added"));
        return new JSONObject().put("items", changes).put("same", same);
    }

    private static JSONObject change(String path, String state) throws Exception {
        return new JSONObject().put("path", path).put("state", state);
    }

    private static void alive(Job job) throws IOException {
        if (job.cancelled || Thread.currentThread().isInterrupted() || System.nanoTime() > job.deadline) {
            throw error(409, "Проверка отменена или превышен лимит времени");
        }
    }

    private static void checkEntry(Entry e) throws IOException {
        if (!e.file.equals(e.file.toRealPath()) || !sameEntry(e.attrs, attrs(e.file))) {
            throw error(409, "Файл изменился. Выполните проверку заново");
        }
    }

    private static void hash(Job job, Entry e) throws IOException {
        alive(job);
        if (e.attrs.size() > MAX_BYTES - job.bytes) throw error(413, "Лимит чтения: 128 ГиБ на проверку");
        checkEntry(e);
        long before = job.bytes;
        e.sha = sha256(e.file, job.deadline, bytes -> job.bytes = before + bytes);
        checkEntry(e);
        job.checked++;
    }
}
