package org.astraea.partitioner;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.astraea.Utils;
import org.astraea.metrics.collector.BeanCollector;
import org.astraea.metrics.collector.Receiver;
import org.astraea.partitioner.cost.CostFunction;

/**
 * this dispatcher scores the nodes by multiples cost functions. Each function evaluate the target
 * node by different metrics. The default cost function ranks nodes by throughput. It means the node
 * having lower throughput get higher score.
 *
 * <p>The requisite config is JMX port. Most cost functions need the JMX metrics to score nodes.
 * Normally, all brokers use the same JMX port, so you can just define the `jmx.port=12345`. If one
 * of brokers uses different JMX client port, you can define `broker.1000.jmx.port=11111` (`1000` is
 * the broker id) to replace the value of `jmx.port`.
 */
public class StrictCostDispatcher implements Dispatcher {
  public static final String JMX_PORT = "jmx.port";

  private final BeanCollector beanCollector =
      BeanCollector.builder().interval(Duration.ofSeconds(4)).build();
  private final Collection<CostFunction> functions;
  private Optional<Integer> jmxPortDefault = Optional.empty();
  private final Map<NodeId, Integer> jmxPorts = new TreeMap<>();
  private final Map<NodeId, Receiver> receivers = new TreeMap<>();

  public StrictCostDispatcher() {
    this(List.of(CostFunction.throughput()));
  }

  // visible for testing
  StrictCostDispatcher(Collection<CostFunction> functions) {
    this.functions = functions;
  }

  @Override
  public int partition(String topic, byte[] key, byte[] value, ClusterInfo clusterInfo) {
    var partitions = clusterInfo.availablePartitions(topic);
    // just return first partition if there is no available partitions
    if (partitions.isEmpty()) return 0;

    // add new receivers for new brokers
    partitions.stream()
        .filter(p -> !receivers.containsKey(NodeId.of(p.leader().id())))
        .forEach(
            p ->
                receivers.put(
                    p.leader(),
                    beanCollector
                        .register()
                        .host(p.leader().host())
                        .port(jmxPort(p.leader().id()))
                        .metricsGetters(
                            functions.stream()
                                .flatMap(c -> c.metricsGetters().stream())
                                .collect(Collectors.toUnmodifiableList()))
                        .build()));

    // get latest beans for each node
    var beans =
        receivers.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().current()));

    // get scores from all cost functions
    var scores =
        functions.stream()
            .map(f -> f.cost(beans, clusterInfo))
            .collect(Collectors.toUnmodifiableList());

    // return the partition (node) having min score
    return partitions.stream()
        .map(
            p ->
                Map.entry(
                    p, scores.stream().mapToDouble(s -> s.getOrDefault(p.leader(), 0.0D)).sum()))
        .min(Map.Entry.comparingByValue())
        .map(e -> e.getKey().partition())
        .orElse(0);
  }

  @Override
  public void configure(Configuration config) {
    jmxPortDefault = config.integer(JMX_PORT);

    // seeks for custom jmx ports.
    config.entrySet().stream()
        .filter(e -> e.getKey().startsWith("broker."))
        .filter(e -> e.getKey().endsWith(JMX_PORT))
        .forEach(
            e ->
                jmxPorts.put(
                    NodeId.of(Integer.parseInt(e.getKey())), Integer.parseInt(e.getValue())));
  }

  // visible for testing
  int jmxPort(int id) {
    if (jmxPorts.containsKey(NodeId.of(id))) return jmxPorts.get(NodeId.of(id));
    return jmxPortDefault.orElseThrow(
        () -> new NoSuchElementException("broker: " + id + " does not have jmx port"));
  }

  @Override
  public void close() {
    receivers.values().forEach(Utils::close);
  }
}