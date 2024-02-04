package com.pehrs.spring.ai.util;

import com.codahale.metrics.Clock;
import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Counting;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metered;
import com.codahale.metrics.MetricAttribute;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import java.io.Closeable;
import java.io.PrintStream;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * FIXME: Supports only Meters and Histograms for now.
 */
public class ConsoleTableReporter extends ScheduledReporter implements Closeable {

  /**
   * Returns a new {@link ConsoleReporter.Builder} for {@link ConsoleReporter}.
   *
   * @param registry the registry to report
   * @return a {@link ConsoleReporter.Builder} instance for a {@link ConsoleReporter}
   */
  public static ConsoleTableReporter.Builder forRegistry(MetricRegistry registry) {
    return new ConsoleTableReporter.Builder(registry);
  }

  private final boolean showZeroMetrics;

  @Override
  public void close() {
    // One final report
    this.output.println("FINAL REPORT----");
    this.report();
    super.close();
  }

  /**
   * A builder for {@link ConsoleTableReporter} instances. Defaults to using the default locale and
   * time zone, writing to {@code System.out}, converting rates to events/second, converting
   * durations to milliseconds, and not filtering metrics.
   */
  public static class Builder {

    private final MetricRegistry registry;
    private PrintStream output;
    private Locale locale;
    private Clock clock;
    private TimeZone timeZone;
    private TimeUnit rateUnit;
    private TimeUnit durationUnit;
    private MetricFilter filter;
    private ScheduledExecutorService executor;
    private boolean shutdownExecutorOnStop;
    private Set<MetricAttribute> disabledMetricAttributes;

    private boolean showZeroMetrics;

    private final Map<String, Double> histogramScaleFactor;


    private Builder(MetricRegistry registry) {
      this.registry = registry;
      this.output = System.out;
      this.locale = Locale.getDefault();
      this.clock = Clock.defaultClock();
      this.timeZone = TimeZone.getDefault();
      this.rateUnit = TimeUnit.SECONDS;
      this.durationUnit = TimeUnit.MILLISECONDS;
      this.filter = MetricFilter.ALL;
      this.executor = null;
      this.shutdownExecutorOnStop = true;
      this.disabledMetricAttributes = Collections.emptySet();
      this.showZeroMetrics = false;
      this.histogramScaleFactor = new HashMap<>();
    }

    public ConsoleTableReporter.Builder histogramScaleFactor(String name, Double scale) {
      this.histogramScaleFactor.put(name, scale);
      return this;
    }

    public ConsoleTableReporter.Builder showZeroMetrics(boolean showZeroMetrics) {
      this.showZeroMetrics = showZeroMetrics;
      return this;
    }

    /**
     * Specifies whether or not, the executor (used for reporting) will be stopped with same time
     * with reporter. Default value is true. Setting this parameter to false, has the sense in
     * combining with providing external managed executor via
     * {@link #scheduleOn(ScheduledExecutorService)}.
     *
     * @param shutdownExecutorOnStop if true, then executor will be stopped in same time with this
     *                               reporter
     * @return {@code this}
     */
    public ConsoleTableReporter.Builder shutdownExecutorOnStop(boolean shutdownExecutorOnStop) {
      this.shutdownExecutorOnStop = shutdownExecutorOnStop;
      return this;
    }

    /**
     * Specifies the executor to use while scheduling reporting of metrics. Default value is null.
     * Null value leads to executor will be auto created on start.
     *
     * @param executor the executor to use while scheduling reporting of metrics.
     * @return {@code this}
     */
    public ConsoleTableReporter.Builder scheduleOn(ScheduledExecutorService executor) {
      this.executor = executor;
      return this;
    }

    /**
     * Write to the given {@link PrintStream}.
     *
     * @param output a {@link PrintStream} instance.
     * @return {@code this}
     */
    public ConsoleTableReporter.Builder outputTo(PrintStream output) {
      this.output = output;
      return this;
    }

    /**
     * Format numbers for the given {@link Locale}.
     *
     * @param locale a {@link Locale}
     * @return {@code this}
     */
    public ConsoleTableReporter.Builder formattedFor(Locale locale) {
      this.locale = locale;
      return this;
    }

    /**
     * Use the given {@link Clock} instance for the time.
     *
     * @param clock a {@link Clock} instance
     * @return {@code this}
     */
    public ConsoleTableReporter.Builder withClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Use the given {@link TimeZone} for the time.
     *
     * @param timeZone a {@link TimeZone}
     * @return {@code this}
     */
    public ConsoleTableReporter.Builder formattedFor(TimeZone timeZone) {
      this.timeZone = timeZone;
      return this;
    }

    /**
     * Convert rates to the given time unit.
     *
     * @param rateUnit a unit of time
     * @return {@code this}
     */
    public ConsoleTableReporter.Builder convertRatesTo(TimeUnit rateUnit) {
      this.rateUnit = rateUnit;
      return this;
    }

    /**
     * Convert durations to the given time unit.
     *
     * @param durationUnit a unit of time
     * @return {@code this}
     */
    public ConsoleTableReporter.Builder convertDurationsTo(TimeUnit durationUnit) {
      this.durationUnit = durationUnit;
      return this;
    }

    /**
     * Only report metrics which match the given filter.
     *
     * @param filter a {@link MetricFilter}
     * @return {@code this}
     */
    public ConsoleTableReporter.Builder filter(MetricFilter filter) {
      this.filter = filter;
      return this;
    }

    /**
     * Don't report the passed metric attributes for all metrics (e.g. "p999", "stddev" or "m15").
     * See {@link MetricAttribute}.
     *
     * @param disabledMetricAttributes a {@link MetricFilter}
     * @return {@code this}
     */
    public ConsoleTableReporter.Builder disabledMetricAttributes(
        Set<MetricAttribute> disabledMetricAttributes) {
      this.disabledMetricAttributes = disabledMetricAttributes;
      return this;
    }

    /**
     * Builds a {@link ConsoleReporter} with the given properties.
     *
     * @return a {@link ConsoleReporter}
     */
    public ConsoleTableReporter build() {
      return new ConsoleTableReporter(
          registry,
          output,
          locale,
          clock,
          timeZone,
          rateUnit,
          durationUnit,
          filter,
          executor,
          shutdownExecutorOnStop,
          disabledMetricAttributes,
          showZeroMetrics,
          histogramScaleFactor);
    }
  }

  private static final int CONSOLE_WIDTH = 80;

  private final PrintStream output;
  private final Locale locale;
  private final Clock clock;
  private final DateFormat dateFormat;

  private final Map<String, Double> histogramScaleFactor;

  private ConsoleTableReporter(
      MetricRegistry registry,
      PrintStream output,
      Locale locale,
      Clock clock,
      TimeZone timeZone,
      TimeUnit rateUnit,
      TimeUnit durationUnit,
      MetricFilter filter,
      ScheduledExecutorService executor,
      boolean shutdownExecutorOnStop,
      Set<MetricAttribute> disabledMetricAttributes,
      boolean skipZeroMetrics,
      final Map<String, Double> histogramScaleFactor) {
    super(
        registry,
        "console-reporter",
        filter,
        rateUnit,
        durationUnit,
        executor,
        shutdownExecutorOnStop,
        disabledMetricAttributes);
    this.output = output;
    this.locale = locale;
    this.clock = clock;
    this.dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale);
    dateFormat.setTimeZone(timeZone);
    this.showZeroMetrics = skipZeroMetrics;
    this.histogramScaleFactor = histogramScaleFactor;
  }

  private static boolean hasAnyValues(Map<String, ? extends Counting> meterMap) {
    return meterMap.values().stream().filter(meter -> meter.getCount() > 0).count() > 0;
  }

  private static boolean hasAnyGaugeValues(Map<String, Gauge> meterMap) {
    return meterMap.values().stream().filter(meter -> meter.getValue() != null).count() > 0;
  }

  @Override
  @SuppressWarnings("rawtypes")
  public void report(
      SortedMap<String, Gauge> gauges,
      SortedMap<String, Counter> counters,
      SortedMap<String, Histogram> histograms,
      SortedMap<String, Meter> meters,
      SortedMap<String, Timer> timers) {
    final String dateTime = dateFormat.format(new Date(clock.getTime()));
    printWithBanner(dateTime, '=');
    output.println();

    if (this.showZeroMetrics || (!histograms.isEmpty() && hasAnyGaugeValues(gauges))) {
      printWithBanner("-- Gauges", '-');
      printGaugeTitleRow();
      for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
        printGauge(entry.getKey(), entry.getValue());
      }
      output.println();
    }

    if (this.showZeroMetrics || (!histograms.isEmpty() && hasAnyValues(counters))) {
      printWithBanner("-- Counters", '-');
      printCounterTitleRow();
      for (Map.Entry<String, Counter> entry : counters.entrySet()) {
        // output.println(entry.getKey());
        printCounter(entry.getKey(), entry);
      }
      output.println();
    }

    if (this.showZeroMetrics || (!histograms.isEmpty() && hasAnyValues(histograms))) {
      printWithBanner("-- Histograms", '-');
      printHistogramTitleRow();
      for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
        if (this.showZeroMetrics || entry.getValue().getCount() > 0) {
          printHistogram(entry.getKey(), entry.getValue());
        }
      }
      output.println();
    }

    if (this.showZeroMetrics || (!meters.isEmpty() && hasAnyValues(meters))) {
      printWithBanner("-- Meters", '-');
      printMeterTitleRow();
      for (Map.Entry<String, Meter> entry : meters.entrySet()) {
        if (this.showZeroMetrics || entry.getValue().getCount() > 0) {
          printMeter(entry.getKey(), entry.getValue());
        }
      }
      output.println();
    }

    if (!timers.isEmpty()) {
      printWithBanner("-- Timers", '-');
      for (Map.Entry<String, Timer> entry : timers.entrySet()) {
        output.println(entry.getKey());
        printTimer(entry.getValue());
      }
      output.println();
    }

    output.println();
    output.flush();
  }

  // count    mean_rate    m1_rate m5_rate M15_rate
  private static String meterFmt = "%-16s %-7s %-7s %-7s %-7s %-7s\n";

  private void printMeterTitleRow() {
    output.printf(
        meterFmt,
        "NAME",
        MetricAttribute.COUNT,
        "MEAN", // MetricAttribute.MEAN_RATE,
        "1m", // MetricAttribute.M1_RATE,
        "5m", // MetricAttribute.M5_RATE,
        "15m" // MetricAttribute.M15_RATE
    );
  }

  private String fmtIfEnabled(MetricAttribute type, String outputValue) {
    if (getDisabledMetricAttributes().contains(type)) {
      return "n/a";
    }
    return outputValue;
  }

  private void printMeter(String name, Meter meter) {
    output.printf(
        meterFmt,
        name,
        fmtIfEnabled(MetricAttribute.COUNT, String.format("%d", meter.getCount())),
        fmtIfEnabled(MetricAttribute.COUNT, String.format("%.2f", meter.getMeanRate())),
        fmtIfEnabled(MetricAttribute.COUNT, String.format("%.2f", meter.getOneMinuteRate())),
        fmtIfEnabled(MetricAttribute.COUNT, String.format("%.2f", meter.getFiveMinuteRate())),
        fmtIfEnabled(MetricAttribute.COUNT, String.format("%.2f", meter.getFifteenMinuteRate())));
  }

  private static String counterFmt =
      "%-24s %s\n";

  private void printCounterTitleRow() {
    output.printf(counterFmt, "name", "count");
  }

  private void printCounter(String name, Map.Entry<String, Counter> entry) {
    // output.printf(locale, "             count = %d%n", entry.getValue().getCount());
    output.printf(counterFmt, name, "" + entry.getValue().getCount());
  }

  private static String gaugeFmt =
      "%-24s %s\n";

  private void printGaugeTitleRow() {
    output.printf(gaugeFmt, "name", "value");
  }

  private void printGauge(String name, Gauge<?> entry) {
    // output.printf(locale, "             value = %s%n", gauge.getValue());
    output.printf(gaugeFmt, name, "" + entry.getValue());

  }

  // count min max mean stddev median 75% 95% 98% 99% 99.9%
  private static String histogramFmt =
      "%-16s %-7s %-7s %-7s %-7s %-7s %-7s %-7s %-7s %-7s %-7s %-7s\n";

  private void printHistogramTitleRow() {
    output.printf(
        histogramFmt,
        "name",
        MetricAttribute.COUNT,
        MetricAttribute.MIN,
        MetricAttribute.MAX,
        MetricAttribute.MEAN,
        MetricAttribute.STDDEV,
        MetricAttribute.P50,
        MetricAttribute.P75,
        MetricAttribute.P95,
        MetricAttribute.P98,
        MetricAttribute.P99,
        "P99.9" // MetricAttribute.P999
    );
  }

  private void printHistogram(String name, Histogram histogram) {
    Snapshot snapshot = histogram.getSnapshot();

    Double scaleFactor = this.histogramScaleFactor.getOrDefault(name, 1.0d);

    output.printf(
        histogramFmt,
        name,
        fmtIfEnabled(MetricAttribute.COUNT, String.format("%d", histogram.getCount())),
        fmtIfEnabled(MetricAttribute.MIN, String.format("%.2f", (snapshot.getMin() / scaleFactor))),
        fmtIfEnabled(MetricAttribute.MAX, String.format("%.2f", (snapshot.getMax() / scaleFactor))),
        fmtIfEnabled(MetricAttribute.MEAN, String.format("%.2f", (snapshot.getMean() / scaleFactor))),
        fmtIfEnabled(MetricAttribute.STDDEV, String.format("%.2f", (snapshot.getStdDev() / scaleFactor))),
        fmtIfEnabled(MetricAttribute.P50, String.format("%.2f", (snapshot.getMedian() / scaleFactor))),
        fmtIfEnabled(MetricAttribute.P75, String.format("%.2f", (snapshot.get75thPercentile() / scaleFactor))),
        fmtIfEnabled(MetricAttribute.P95, String.format("%.2f", (snapshot.get95thPercentile() / scaleFactor))),
        fmtIfEnabled(MetricAttribute.P98, String.format("%.2f", (snapshot.get98thPercentile() / scaleFactor))),
        fmtIfEnabled(MetricAttribute.P99, String.format("%.2f", (snapshot.get99thPercentile() / scaleFactor))),
        fmtIfEnabled(MetricAttribute.P999, String.format("%.2f", (snapshot.get999thPercentile() / scaleFactor))));
  }

  private void printTimer(Timer timer) {
    final Snapshot snapshot = timer.getSnapshot();
    printIfEnabled(
        MetricAttribute.COUNT, String.format(locale, "             count = %d", timer.getCount()));
    printIfEnabled(
        MetricAttribute.MEAN_RATE,
        String.format(
            locale,
            "         mean rate = %2.2f calls/%s",
            convertRate(timer.getMeanRate()),
            getRateUnit()));
    printIfEnabled(
        MetricAttribute.M1_RATE,
        String.format(
            locale,
            "     1-minute rate = %2.2f calls/%s",
            convertRate(timer.getOneMinuteRate()),
            getRateUnit()));
    printIfEnabled(
        MetricAttribute.M5_RATE,
        String.format(
            locale,
            "     5-minute rate = %2.2f calls/%s",
            convertRate(timer.getFiveMinuteRate()),
            getRateUnit()));
    printIfEnabled(
        MetricAttribute.M15_RATE,
        String.format(
            locale,
            "    15-minute rate = %2.2f calls/%s",
            convertRate(timer.getFifteenMinuteRate()),
            getRateUnit()));

    printIfEnabled(
        MetricAttribute.MIN,
        String.format(
            locale,
            "               min = %2.2f %s",
            convertDuration(snapshot.getMin()),
            getDurationUnit()));
    printIfEnabled(
        MetricAttribute.MAX,
        String.format(
            locale,
            "               max = %2.2f %s",
            convertDuration(snapshot.getMax()),
            getDurationUnit()));
    printIfEnabled(
        MetricAttribute.MEAN,
        String.format(
            locale,
            "              mean = %2.2f %s",
            convertDuration(snapshot.getMean()),
            getDurationUnit()));
    printIfEnabled(
        MetricAttribute.STDDEV,
        String.format(
            locale,
            "            stddev = %2.2f %s",
            convertDuration(snapshot.getStdDev()),
            getDurationUnit()));
    printIfEnabled(
        MetricAttribute.P50,
        String.format(
            locale,
            "            median = %2.2f %s",
            convertDuration(snapshot.getMedian()),
            getDurationUnit()));
    printIfEnabled(
        MetricAttribute.P75,
        String.format(
            locale,
            "              75%% <= %2.2f %s",
            convertDuration(snapshot.get75thPercentile()),
            getDurationUnit()));
    printIfEnabled(
        MetricAttribute.P95,
        String.format(
            locale,
            "              95%% <= %2.2f %s",
            convertDuration(snapshot.get95thPercentile()),
            getDurationUnit()));
    printIfEnabled(
        MetricAttribute.P98,
        String.format(
            locale,
            "              98%% <= %2.2f %s",
            convertDuration(snapshot.get98thPercentile()),
            getDurationUnit()));
    printIfEnabled(
        MetricAttribute.P99,
        String.format(
            locale,
            "              99%% <= %2.2f %s",
            convertDuration(snapshot.get99thPercentile()),
            getDurationUnit()));
    printIfEnabled(
        MetricAttribute.P999,
        String.format(
            locale,
            "            99.9%% <= %2.2f %s",
            convertDuration(snapshot.get999thPercentile()),
            getDurationUnit()));
  }

  private void printWithBanner(String s, char c) {
    output.print(s);
    output.print(' ');
    for (int i = 0; i < (CONSOLE_WIDTH - s.length() - 1); i++) {
      output.print(c);
    }
    output.println();
  }

  /**
   * Print only if the attribute is enabled
   *
   * @param type   Metric attribute
   * @param status Status to be logged
   */
  private void printIfEnabled(MetricAttribute type, String status) {
    if (getDisabledMetricAttributes().contains(type)) {
      return;
    }

    output.println(status);
  }
}
