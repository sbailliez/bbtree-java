package io.github.sb.bbtree.bench;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** A sorted map behind one read-write lock: the simple way to share a {@link java.util.TreeMap}. */
final class ReadWriteLockedMap<K, V> extends AbstractMap<K, V> {

  private final Map<K, V> map;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  ReadWriteLockedMap(Map<K, V> map) {
    this.map = map;
  }

  @Override
  public V get(Object key) {
    lock.readLock().lock();
    try {
      return map.get(key);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public V put(K key, V value) {
    lock.writeLock().lock();
    try {
      return map.put(key, value);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public V remove(Object key) {
    lock.writeLock().lock();
    try {
      return map.remove(key);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public int size() {
    lock.readLock().lock();
    try {
      return map.size();
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    throw new UnsupportedOperationException("not shared safely");
  }
}
