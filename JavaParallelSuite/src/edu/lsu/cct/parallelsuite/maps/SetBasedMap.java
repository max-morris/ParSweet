package edu.lsu.cct.parallelsuite.maps;

import edu.lsu.cct.parallelsuite.sets.SlimSet;

import java.util.function.Supplier;

@SuppressWarnings("unchecked")
public class SetBasedMap<K, V> implements SlimMap<K, V> {
    private static final int N_BUCKETS = 16;

    private final Object/*SlimSet<Node>*/[] buckets;

    public SetBasedMap(Supplier<SlimSet<Object>> setSupplier) {
        buckets = new Object[N_BUCKETS];
        for (int i = 0; i < N_BUCKETS; ++i) {
            buckets[i] = setSupplier.get();
        }
    }

    @SuppressWarnings("unchecked")
    SlimSet<Node> getNodeHead(int index) {
        return ((SlimSet<Node>) buckets[index]);
    }

    @Override
    public V put(K key, V value) {
        var index = getBucketIndexOfKey(key);
        var node = new Node(key, value);

        getNodeHead(index).add(node);

        return null;
    }

    @Override
    public V get(Object key) {
        var index = getBucketIndexOfKey((K) key);
        var node = new Node((K) key, null);

        var n = getNodeHead(index).getEqual(node);

        return n == null ? null
                         : n.value;
    }

    @Override
    public V remove(Object key) {
        var index = getBucketIndexOfKey((K) key);
        var node = new Node((K) key, null);

        getNodeHead(index).remove(node);

        return null;
    }

    private class Node {
        private final K key;
        private final V value;

        private Node(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Node node = (Node) o;
            return key.equals(node.key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    private int getBucketIndexOfKey(K key) {
        return Math.floorMod(key.hashCode(), N_BUCKETS);
    }
}
