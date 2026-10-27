package io.github.sb.bbtree;

import com.google.common.collect.testing.ConcurrentMapTestSuiteBuilder;
import com.google.common.collect.testing.TestStringMapGenerator;
import com.google.common.collect.testing.features.CollectionFeature;
import com.google.common.collect.testing.features.CollectionSize;
import com.google.common.collect.testing.features.MapFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import junit.framework.Test;
import junit.framework.TestSuite;

/**
 * The {@link java.util.concurrent.ConcurrentMap} contract tests of guava-testlib, about a
 * thousand tests for each configuration.
 */
public class GuavaMapContractTest {

  public static Test suite() {
    TestSuite suite = new TestSuite("BBTreeMap");
    suite.addTest(suite("inline 4/4", () -> new BBTreeMap<>(InlineKey.STRING, 4, 4)));
    suite.addTest(suite("inline default", () -> new BBTreeMap<>(InlineKey.STRING)));
    suite.addTest(suite("natural 4/4", () -> new BBTreeMap<>(null, null, 4, 4)));
    return suite;
  }

  private static Test suite(String name, Supplier<BBTreeMap<String, String>> factory) {
    return ConcurrentMapTestSuiteBuilder.using(
            new TestStringMapGenerator() {
              @Override
              protected Map<String, String> create(Map.Entry<String, String>[] entries) {
                BBTreeMap<String, String> map = factory.get();
                for (Map.Entry<String, String> e : entries) {
                  map.put(e.getKey(), e.getValue());
                }
                return map;
              }

              @Override
              public Iterable<Map.Entry<String, String>> order(
                  List<Map.Entry<String, String>> insertionOrder) {
                List<Map.Entry<String, String>> sorted = new ArrayList<>(insertionOrder);
                sorted.sort(Map.Entry.comparingByKey());
                return sorted;
              }
            })
        .named("BBTreeMap " + name)
        .withFeatures(
            MapFeature.GENERAL_PURPOSE,
            CollectionFeature.KNOWN_ORDER,
            CollectionFeature.SUPPORTS_ITERATOR_REMOVE,
            CollectionSize.ANY)
        .createTestSuite();
  }
}
