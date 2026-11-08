package pl.serverperformance;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

@Mod(ServerPerformance.MOD_ID)
public class ServerPerformance {
    public static final String MOD_ID = "serverperformance";
    private static final int DEFAULT_WINDOW_SECONDS = 300;
    private static final int MAX_SAMPLES = 20 * 60 * 30;

    private final Deque<Double> tickSamples = new ArrayDeque<>();
    private long tickStartedNanos;
    private volatile Thread serverThread;

    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ServerPerformance-ModSampler");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Path> modLocations = new HashMap<>();
    private final ConcurrentHashMap<String, LongAdder> modSamples = new ConcurrentHashMap<>();
    private final LongAdder totalStackSamples = new LongAdder();
    private final ConcurrentHashMap<String, String> classModCache = new ConcurrentHashMap<>();

    public ServerPerformance() {
        MinecraftForge.EVENT_BUS.register(this);
        discoverModLocations();
        sampler.scheduleAtFixedRate(this::sampleServerThread, 50, 50, TimeUnit.MILLISECONDS);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        serverThread = Thread.currentThread();
        if (event.phase == TickEvent.Phase.START) {
            tickStartedNanos = System.nanoTime();
        } else if (tickStartedNanos != 0L) {
            double mspt = (System.nanoTime() - tickStartedNanos) / 1_000_000.0;
            synchronized (tickSamples) {
                tickSamples.addLast(mspt);
                while (tickSamples.size() > MAX_SAMPLES) tickSamples.removeFirst();
            }
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("perfreport")
            .requires(source -> source.hasPermission(2))
            .executes(ctx -> saveReport(ctx.getSource().getServer(), DEFAULT_WINDOW_SECONDS, ctx.getSource()))
            .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 1800))
                .executes(ctx -> saveReport(ctx.getSource().getServer(),
                    IntegerArgumentType.getInteger(ctx, "seconds"), ctx.getSource()))));
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        sampler.shutdownNow();
    }

    /** Statistical sampling of the server thread; this is not exact per-mod CPU accounting. */
    private void sampleServerThread() {
        Thread thread = serverThread;
        if (thread == null || !thread.isAlive()) return;
        Set<String> seen = new HashSet<>();
        for (StackTraceElement frame : thread.getStackTrace()) {
            String modId = findModForClass(frame.getClassName());
            if (modId != null) seen.add(modId);
        }
        if (!seen.isEmpty()) {
            totalStackSamples.increment();
            for (String modId : seen) {
                modSamples.computeIfAbsent(modId, ignored -> new LongAdder()).increment();
            }
        }
    }

    private String findModForClass(String className) {
        String cached = classModCache.computeIfAbsent(className, name -> {
            try {
                Class<?> type = Class.forName(name, false, ServerPerformance.class.getClassLoader());
                CodeSource source = type.getProtectionDomain() == null ? null : type.getProtectionDomain().getCodeSource();
                if (source == null || source.getLocation() == null) return "";
                URI uri = source.getLocation().toURI();
                Path location = Path.of(uri).toAbsolutePath().normalize();
                synchronized (modLocations) {
                    for (Map.Entry<String, Path> entry : modLocations.entrySet()) {
                        if (location.equals(entry.getValue())) return entry.getKey();
                    }
                }
            } catch (Throwable ignored) {
                // Some transformed or isolated classes cannot be resolved through this classloader.
            }
            return "";
        });
        return cached.isEmpty() ? null : cached;
    }

    private void discoverModLocations() {
        try {
            ModList.get().getMods().forEach(modInfo -> {
                try {
                    String modId = modInfo.getModId();
                    Path path = modInfo.getOwningFile().getFile().getFilePath();
                    synchronized (modLocations) {
                        modLocations.put(modId, path.toAbsolutePath().normalize());
                    }
                } catch (RuntimeException ignored) {
                    // Ignore mod files whose location is not exposed by Forge.
                }
            });
        } catch (RuntimeException ignored) {
            // The main performance report remains available if mod discovery fails.
        }
    }

    private String buildModAnalysis() {
        long total = totalStackSamples.sum();
        StringBuilder out = new StringBuilder("\nANALIZA MODÓW (profilowanie próbkowane 20 Hz)\n");
        if (total == 0) {
            return out.append("Brak przypisanych próbek. ClassLoader/CodeSource może nie ujawniać lokalizacji klas modów.\n").toString();
        }
        out.append("Próbki stosu z rozpoznanym modem: ").append(total).append("\n")
            .append("Udział oznacza częstotliwość występowania na aktywnym stosie serwera, nie dokładny czas CPU.\n");

        modSamples.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
            .limit(15)
            .forEach(entry -> {
                long count = entry.getValue().sum();
                double share = count * 100.0 / total;
                String level = share >= 25 ? "WYSOKI KANDYDAT" : share >= 10 ? "MOŻLIWY KANDYDAT" : "niski udział";
                out.append(String.format(Locale.ROOT, "- %s: %.1f%% (%d próbek) — %s\n",
                    entry.getKey(), share, count, level));
            });
        out.append("Potwierdź wynik testem A/B na kopii serwera (z modem i bez niego).\n");
        return out.toString();
    }

    private int saveReport(MinecraftServer server, int seconds, net.minecraft.commands.CommandSourceStack source) {
        double[] values;
        synchronized (tickSamples) {
            int wanted = seconds * 20;
            int skip = Math.max(0, tickSamples.size() - wanted);
            values = tickSamples.stream().skip(skip).mapToDouble(Double::doubleValue).toArray();
        }
        if (values.length == 0) {
            source.sendFailure(Component.literal("Brak próbek wydajności. Poczekaj kilka sekund i spróbuj ponownie."));
            return 0;
        }

        double avg = average(values);
        double min = min(values);
        double max = max(values);
        double p95 = percentile(values, 0.95);
        double estimatedTps = Math.min(20.0, 1000.0 / Math.max(0.001, avg));
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double load = os.getSystemLoadAverage();
        String loadText = load < 0 ? "niedostępne" : String.format(Locale.ROOT, "%.2f", load);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = server.getServerDirectory().resolve("performance-reports");
        Path txt = dir.resolve("performance-" + timestamp + ".txt");
        Path csv = dir.resolve("performance-" + timestamp + ".csv");
        try {
            Files.createDirectories(dir);
            String report = buildTextReport(server, seconds, values.length, avg, min, max, p95,
                estimatedTps, usedMb, maxMb, loadText);
            Files.writeString(txt, report, StandardCharsets.UTF_8);
            StringBuilder csvText = new StringBuilder("sample,tick_time_ms\n");
            for (int i = 0; i < values.length; i++) {
                csvText.append(i + 1).append(',').append(String.format(Locale.ROOT, "%.3f", values[i])).append('\n');
            }
            Files.writeString(csv, csvText, StandardCharsets.UTF_8);
            source.sendSuccess(() -> Component.literal("Raport zapisany: " + txt.toAbsolutePath()
                + " (" + values.length + " próbek)"), true);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Nie udało się zapisać raportu: " + e.getMessage()));
            return 0;
        }
    }

    private String buildTextReport(MinecraftServer server, int seconds, int samples, double avg, double min,
                                   double max, double p95, double tps, long usedMb, long maxMb, String load) {
        String status = avg <= 50 ? "DOBRA" : avg <= 100 ? "OBNIŻONA" : "KRYTYCZNA";
        StringBuilder out = new StringBuilder();
        out.append("SERVER PERFORMANCE REPORT\n=========================\n")
            .append("Data: ").append(LocalDateTime.now()).append('\n')
            .append("Serwer: ").append(server.getServerModName()).append('\n')
            .append("Wersja Minecraft: 1.20.1\n")
            .append("Okno pomiaru: ").append(seconds).append(" s\n")
            .append("Liczba próbek: ").append(samples).append('\n')
            .append("\nWYNIKI\n")
            .append(String.format(Locale.ROOT, "Średni czas ticka: %.2f ms\n", avg))
            .append(String.format(Locale.ROOT, "95. percentyl MSPT: %.2f ms\n", p95))
            .append(String.format(Locale.ROOT, "Maksymalny czas ticka: %.2f ms\n", max))
            .append(String.format(Locale.ROOT, "Minimalny czas ticka: %.2f ms\n", min))
            .append(String.format(Locale.ROOT, "Szacowane TPS: %.2f\n", tps))
            .append("Ocena: ").append(status).append("\n")
            .append("\nPAMIĘĆ JVM\nUżywana sterta: ").append(usedMb).append(" MB / ").append(maxMb).append(" MB\n")
            .append("\nSYSTEM\nDostępne procesory: ").append(Runtime.getRuntime().availableProcessors()).append('\n')
            .append("Średnie obciążenie systemu: ").append(load).append('\n')
            .append("\nINTERPRETACJA\n");
        if (avg > 50) out.append("- Średni tick przekracza budżet 50 ms. Serwer może nie utrzymywać 20 TPS.\n");
        if (p95 > 50) out.append("- Co najmniej 5% pomiarów jest wolniejszych niż 50 ms.\n");
        if (usedMb > maxMb * 0.85) out.append("- Zużycie sterty JVM przekracza 85% maksymalnej pamięci.\n");
        if (avg <= 50 && p95 <= 50 && usedMb <= maxMb * 0.85) {
            out.append("- Nie wykryto podstawowych sygnałów przeciążenia w zebranych metrykach.\n");
        }
        out.append(buildModAnalysis());
        out.append("\nOgraniczenia: profilowanie stosu jest statystyczne, nie mierzy bezpośrednio czasu CPU każdego moda. ")
            .append("Klasy transformowane lub ładowane niestandardowo mogą pozostać nierozpoznane. Wynik nie jest dowodem winy moda.\n");
        return out.toString();
    }

    private static double average(double[] a) {
        double sum = 0;
        for (double value : a) sum += value;
        return sum / a.length;
    }

    private static double min(double[] a) {
        double result = Double.MAX_VALUE;
        for (double value : a) result = Math.min(result, value);
        return result;
    }

    private static double max(double[] a) {
        double result = 0;
        for (double value : a) result = Math.max(result, value);
        return result;
    }

    private static double percentile(double[] values, double p) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
    }
}
